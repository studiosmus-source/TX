package it.osmus.tx.remote

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Outbound HTTPS enrollment. No remote file access until user taps Save and Start. */
class EnrollmentClient(private val deviceName: String) {
    private val running = AtomicBoolean(true)
    @Volatile private var current: HttpURLConnection? = null

    fun cancel() {
        running.set(false)
        current?.disconnect()
    }

    fun run(onCode: (String) -> Unit, onReady: (String, String) -> Unit, onError: (String) -> Unit) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val created = post("/v1/pair/request", JSONObject().put("name", deviceName))
                val id = created.getString("id")
                val claim = created.getString("claim")
                val code = created.getString("code")
                onCode(code)
                val expiry = System.currentTimeMillis() + 600_000L
                while (running.get() && System.currentTimeMillis() < expiry) {
                    Thread.sleep(3500)
                    if (!running.get()) break
                    val response = post("/v1/pair/poll", JSONObject().put("id", id).put("claim", claim))
                    if (response.optBoolean("approved", false)) {
                        if (!running.get()) break
                        val deviceId = response.getString("deviceId")
                        val token = response.getString("deviceToken")
                        RelayConfig(SecureRelaySettings.RELAY_URL, token, deviceId)
                        onReady(deviceId, token)
                        return
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (!running.get()) break
                // Do not expose network headers or any sensitive response body to the UI.
                onError(if (e.message?.contains("HTTP 429") == true) "Troppe richieste: nuovo tentativo automatico"
                    else "Collegamento non disponibile: nuovo tentativo automatico")
                try { Thread.sleep(12_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun post(path: String, payload: JSONObject): JSONObject {
        val url = URL(SecureRelaySettings.RELAY_URL + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        current = conn
        try {
            val data = payload.toString().toByteArray(StandardCharsets.UTF_8)
            require(data.size <= 1024)
            conn.outputStream.use { it.write(data) }
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buf = CharArray(1024)
                val result = StringBuilder()
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (result.length + n > 8192) throw IOException("Risposta eccessiva")
                    result.append(buf, 0, n)
                }
                result.toString()
            }
            return JSONObject(text)
        } finally {
            current = null
            conn.disconnect()
        }
    }
}
