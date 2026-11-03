package it.osmus.rx.remote

import android.util.Base64
import it.osmus.rx.TransferIntegrity
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RemoteFile(
    val path: String,
    val name: String,
    val directory: Boolean,
    val hidden: Boolean,
    val size: Long,
    val modified: Long
)
data class DirectoryPage(val entries: List<RemoteFile>, val hasMore: Boolean)

class RxApi(private val credentials: Credentials) {
    fun pending(): List<PendingPair> {
        val response = request("GET", "/v1/pair/pending")
        val items = response.getJSONArray("requests")
        return (0 until items.length()).map { index ->
            val j = items.getJSONObject(index)
            PendingPair(j.getString("id"), j.getString("name"), j.getString("code"))
        }
    }

    fun approve(id: String) {
        require(ID.matches(id)) { "ID non valido" }
        val result = request("POST", "/v1/pair/approve/" + id,
            JSONObject().put("txToken", credentials.txToken))
        check(result.optBoolean("approved")) { "Attivazione non confermata" }
    }

    data class GalleryItem(
        val path: String, val name: String, val type: String,
        val size: Long, val modified: Long
    )
    data class GalleryPage(val entries: List<GalleryItem>, val hasMore: Boolean, val nextOffset: Int)

    fun gallery(offset: Int, limit: Int = 36): GalleryPage {
        val result = call(JSONObject().put("op", "gallery").put("path", "")
            .put("offset", offset).put("limit", limit))
        val entries = result.getJSONArray("entries")
        return GalleryPage((0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            GalleryItem(item.getString("path"), item.getString("name"),
                item.getString("type"), item.getLong("size"), item.getLong("modified"))
        }, result.getBoolean("hasMore"), result.getInt("nextOffset"))
    }

    fun thumbnail(path: String): ByteArray {
        val result = call(JSONObject().put("op", "thumb").put("path", path)
            .put("offset", 0))
        val decoded = Base64.decode(result.getString("base64"), Base64.DEFAULT)
        if (decoded.size > 48_000 || decoded.isEmpty()) throw IOException("Anteprima non valida")
        return decoded
    }

    fun preview(path: String): ByteArray {
        val result = call(JSONObject().put("op", "preview").put("path", path)
            .put("offset", 0))
        val decoded = Base64.decode(result.getString("base64"), Base64.DEFAULT)
        if (decoded.size > 110_000 || decoded.isEmpty()) throw IOException("Immagine non valida")
        return decoded
    }

    fun list(path: String, offset: Int, limit: Int = 100): DirectoryPage {
        require(offset >= 0 && limit in 1..200)
        val result = call(JSONObject().put("op", "list").put("path", path)
            .put("offset", offset).put("limit", limit))
        val array = result.getJSONArray("entries")
        val entries = (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            RemoteFile(j.getString("path"), j.getString("name"),
                j.getBoolean("directory"), j.getBoolean("hidden"),
                j.optLong("size", 0), j.optLong("modified", 0))
        }
        if (entries.isEmpty() && result.optBoolean("hasMore", false)) {
            throw IOException("Risposta elenco non valida")
        }
        return DirectoryPage(entries, result.optBoolean("hasMore", false))
    }

    data class RemoteChunk(val bytes: ByteArray, val nextOffset: Long,
                           val total: Long, val complete: Boolean)

    fun read(path: String, offset: Long): RemoteChunk {
        val result = call(JSONObject().put("op", "read").put("path", path)
            .put("offset", offset).put("length", 65_536))
        val bytes = Base64.decode(result.getString("base64"), Base64.DEFAULT)
        val next = result.getLong("nextOffset")
        val total = result.getLong("totalBytes")
        val complete = result.getBoolean("complete")
        if (bytes.size > 65_536 || next != offset + bytes.size || total < next ||
            (!complete && bytes.isEmpty()) || (complete && next != total)) {
            throw IOException("Blocco file non valido")
        }
        // Supports previously installed TX versions; new TX always supplies the hash.
        if (result.has("sha256")) {
            val expected = result.getString("sha256")
            if (!expected.matches(Regex("[0-9a-f]{64}")) ||
                expected != TransferIntegrity.sha256(bytes)) {
                throw IOException("Dati ricevuti danneggiati: controllo integrità fallito")
            }
        }
        return RemoteChunk(bytes, next, total, complete)
    }

    private fun call(operation: JSONObject): JSONObject =
        RemoteRequestGate.run(priority = operation.optString("op") != "thumb") {
            // Busy means the finite relay queue is full (often older thumbnail jobs).
            // Do not create new jobs during backoff, and never bypass the priority gate.
            var pauseMs = 1_000L
            var lastBusy: IOException? = null
            repeat(8) {
                try {
                    return@run callOnce(operation)
                } catch (error: ServerBusyException) {
                    lastBusy = error
                    Thread.sleep(pauseMs)
                    pauseMs = (pauseMs * 2).coerceAtMost(12_000L)
                }
            }
            throw IOException("Server ancora occupato. Riprova quando TX è raggiungibile", lastBusy)
        }

    private fun callOnce(operation: JSONObject): JSONObject {
        val job = request("POST", "/v1/rx/request", operation)
        val id = job.getString("id")
        require(ID.matches(id)) { "ID richiesta non valido" }
        repeat(6) {
            val answer = request("GET", "/v1/rx/result/" + id)
            if (answer.optBoolean("pending", false)) return@repeat
            if (!answer.optBoolean("ok", false)) {
                throw IOException(answer.optString("error", "TX non disponibile"))
            }
            return answer
        }
        throw IOException("TX non risponde: controlla che sia avviato")
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(RxSecrets.URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 32_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + credentials.rxToken)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 4_096)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code == 429) throw ServerBusyException()
            if (code !in 200..299) {
                throw IOException(when (code) {
                    401 -> "Chiave RX non valida"
                    403 -> "Autorizzazione negata"
                    404, 410 -> "Richiesta scaduta, riprova"
                    429 -> "Server occupato, riprova"
                    else -> "Errore server HTTP " + code
                })
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val chars = CharArray(4_096)
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    if (output.length + count > 256_000) throw IOException("Risposta troppo grande")
                    output.append(chars, 0, count)
                }
                output.toString()
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private class ServerBusyException : IOException("Server occupato")

    companion object {
        private val ID = Regex("[0-9a-f-]{36}")
    }
}

data class PendingPair(val id: String, val name: String, val code: String)
