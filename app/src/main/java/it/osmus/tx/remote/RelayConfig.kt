package it.osmus.tx.remote

import android.content.Context
import java.net.URI

/** Credenziali configurate sul singolo telefono e mai incluse nei sorgenti GitHub. */
data class RelayConfig(val baseUrl: String, val txToken: String) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Il server deve usare HTTPS" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Inserire solo l'indirizzo HTTPS del server"
        }
        require(txToken.length >= 32) { "La chiave deve contenere almeno 32 caratteri" }
    }
    fun endpoint(path: String): String = baseUrl.trimEnd('/') + path
}

object RelaySettings {
    private const val PREF = "tx_relay_v1"
    fun save(context: Context, config: RelayConfig) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("url", config.baseUrl).putString("token", config.txToken).apply()
    }
    fun load(context: Context): RelayConfig? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return runCatching {
            RelayConfig(prefs.getString("url", "") ?: "", prefs.getString("token", "") ?: "")
        }.getOrNull()
    }
}
