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

    /**
     * Keep ONE enrollment request alive through transient network errors.
     * Only create a new request when the old one has expired or is gone.
     */
    fun run(onCode: (String) -> Unit, onReady: (String) -> Unit, onError: (String) -> Unit) {
        var current: EnrollmentAttempt? = null
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                if (current == null) {
                    val created = post("/v1/pair/request", JSONObject().put("name", deviceName))
                    current = EnrollmentAttempt(
                        id = created.getString("id"),
                        claim = created.getString("claim"),
                        code = created.getString("code"),
                        expiresAtMillis = System.currentTimeMillis() + 600_000L
                    )
                    onCode(current.code)
                }

                val attempt = current
                if (System.currentTimeMillis() >= attempt.expiresAtMillis) {
                    current = null
                    continue
                }
                Thread.sleep(3_500)
                if (!running.get()) break
                val response = post("/v1/pair/poll", JSONObject()
                    .put("id", attempt.id).put("claim", attempt.claim))
                if (response.optBoolean("approved", false)) {
                    if (!running.get()) break
                    val token = response.getString("txToken")
                    RelayConfig(SecureRelaySettings.RELAY_URL, token)
                    onReady(token)
                    return
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                if (!running.get()) break
                if (EnrollmentRetryPolicy.mustRestartRequest(error.message)) {
                    current = null
                    onError("Richiesta scaduta: ne invio una nuova")
                } else {
                    // An intermittent network failure is not a reason to ask RX to pair again.
                    onError(if (current == null) "Invio richiesta in corso"
                        else "Richiesta attiva: riconnessione in corso")
                }
                try { Thread.sleep(6_000) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
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
