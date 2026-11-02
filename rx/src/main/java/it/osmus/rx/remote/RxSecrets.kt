package it.osmus.rx.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val rxToken: String, val txToken: String) {
    init {
        require(rxToken.length in 32..256 && txToken.length in 32..256 && rxToken != txToken)
    }
}

/** Secrets never enter the APK or GitHub. Encrypted on the device. */
object RxSecrets {
    const val URL = "https://tx-3dhs.onrender.com"
    private const val PREF = "rx_credential_v1"
    private const val ALIAS = "rx_credentials_aes_v1"

    fun save(context: Context, credentials: Credentials) {
        val data = JSONObject()
            .put("rx", credentials.rxToken).put("tx", credentials.txToken)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data)
        check(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit())
    }

    fun load(context: Context): Credentials? = runCatching {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null) ?: return null
        val cipherData = p.getString("cipher", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(
            128, Base64.decode(iv, Base64.NO_WRAP)))
        val plain = cipher.doFinal(Base64.decode(cipherData, Base64.NO_WRAP))
        val j = JSONObject(String(plain, StandardCharsets.UTF_8))
        Credentials(j.getString("rx"), j.getString("tx"))
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
