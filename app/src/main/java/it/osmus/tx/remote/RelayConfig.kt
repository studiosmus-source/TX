package it.osmus.tx.remote

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The public relay address is built in. No token is embedded in the APK. */
data class RelayConfig(val baseUrl: String, val txToken: String) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "È richiesto HTTPS" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "Indirizzo server non valido" }
        require(txToken.length in 32..256) { "Chiave TX non valida" }
    }
    fun endpoint(path: String) = baseUrl.trimEnd('/') + path
}

/** Stores TX token encrypted with a nonexportable AES key in Android Keystore. */
object SecureRelaySettings {
    const val RELAY_URL = "https://tx-3dhs.onrender.com"
    private const val STORAGE = "tx_secure_v1"
    private const val KEY_ALIAS = "tx_relay_aes_v1"

    fun save(context: Context, txToken: String) {
        RelayConfig(RELAY_URL, txToken)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(txToken.toByteArray(StandardCharsets.UTF_8))
        val prefs = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        check(prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) {
            "Impossibile salvare la chiave"
        }
    }

    fun load(context: Context): RelayConfig? {
        val prefs = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val iv = prefs.getString("iv", null) ?: return null
        val ciphertext = prefs.getString("ciphertext", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val token = String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
            RelayConfig(RELAY_URL, token)
        }.getOrNull()
    }

    /** Migrate 0.2's unencrypted preferences once, then clear the old copy. */
    fun migrateLegacy(context: Context) {
        val old = context.getSharedPreferences("tx_relay_v1", Context.MODE_PRIVATE)
        val token = old.getString("token", null)
        val url = old.getString("url", null)
        if (load(context) == null && url?.trimEnd('/') == RELAY_URL && token != null) {
            runCatching { save(context, token) }
        }
        old.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
