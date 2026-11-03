package it.osmus.tx.remote

import android.os.Environment
import android.util.Base64
import it.osmus.tx.storage.FileCatalog
import it.osmus.tx.storage.MediaCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Long-poll OUTBOUND HTTPS: funziona anche dietro NAT e su reti differenti. */
class RelayWorker(
    private val context: android.content.Context,
    private val config: RelayConfig,
    private val onState: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(true)
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val catalog by lazy { FileCatalog(Environment.getExternalStorageDirectory()) }
    private val media by lazy { MediaCatalog(context, Environment.getExternalStorageDirectory()) }

    fun stop() {
        running.set(false)
        activeConnection?.disconnect()
    }

    fun run() {
        var failures = 0
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val command = request("GET", "/v1/tx/next")
                failures = 0
                onState("Relay HTTPS raggiungibile")
                if (!running.get()) break
                if (command.optBoolean("pending", false)) continue
                val id = command.optString("id")
                if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) continue
                val answer = try { handle(command) } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", error.message ?: "Accesso negato")
                }
                request("POST", "/v1/tx/result/$id", answer)
            } catch (error: Exception) {
                if (!running.get()) break
                onState("Connessione non disponibile: ${error.message ?: "errore sconosciuto"}")
                failures = (failures + 1).coerceAtMost(5)
                try { Thread.sleep((1000L shl failures).coerceAtMost(20_000L)) }
                catch (_: InterruptedException) { break }
            }
        }
    }

    private fun handle(command: JSONObject): JSONObject {
        val path = command.getString("path")
        return when (command.getString("op")) {
            "list" -> {
                val page = catalog.listDirectory(path, command.getInt("offset"), command.getInt("limit"))
                val entries = JSONArray()
                page.entries.forEach { item ->
                    entries.put(JSONObject()
                        .put("path", item.relativePath).put("name", item.name)
                        .put("directory", item.directory).put("hidden", item.hidden)
                        .put("size", item.sizeBytes).put("modified", item.lastModifiedMillis))
                }
                JSONObject().put("ok", true).put("entries", entries)
                    .put("total", page.total).put("hasMore", page.hasMore)
            }
            "gallery" -> {
                val page = media.gallery(command.getInt("offset"), command.getInt("limit"))
                val entries = JSONArray()
                page.entries.forEach { item ->
                    entries.put(JSONObject().put("path", item.path)
                        .put("name", item.name).put("type", item.type)
                        .put("size", item.size).put("modified", item.modified))
                }
                JSONObject().put("ok", true).put("entries", entries)
                    .put("hasMore", page.hasMore)
                    .put("nextOffset", page.nextOffset)
            }
            "thumb", "preview" -> {
                val bytes = media.thumbnail(path, command.getString("op") == "preview")
                JSONObject().put("ok", true)
                    .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            "read" -> {
                val chunk = catalog.readChunk(path, command.getLong("offset"), command.getInt("length"))
                JSONObject().put("ok", true)
                    .put("base64", Base64.encodeToString(chunk.bytes, Base64.NO_WRAP))
                    .put("sha256", java.security.MessageDigest.getInstance("SHA-256")
                        .digest(chunk.bytes).joinToString("") {
                            "%02x".format(it.toInt() and 0xff)
                        })
                    .put("totalBytes", chunk.totalBytes)
                    .put("nextOffset", chunk.nextOffset)
                    .put("complete", chunk.complete)
            }
            else -> JSONObject().put("ok", false).put("error", "Comando non supportato")
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val url = URL(config.endpoint(path))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 32_000
            setRequestProperty("Authorization", "Bearer ${config.txToken}")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = false
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        activeConnection = conn
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 800_000) { "Risposta troppo grande" }
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Server HTTP $code")
            val json = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                // Risposta limitata, anche se il server viene compromesso.
                val text = StringBuilder()
                val chars = CharArray(4096)
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    if (text.length + count > 256_000) throw IOException("Risposta eccessiva")
                    text.append(chars, 0, count)
                }
                JSONObject(text.toString())
            }
            return json
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }
}
