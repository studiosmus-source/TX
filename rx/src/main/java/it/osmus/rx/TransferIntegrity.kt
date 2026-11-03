package it.osmus.rx

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Integrity checks for binary transfers. No file contents are converted to text.
 */
object TransferIntegrity {
    fun sha256(bytes: ByteArray): String = toHex(
        MessageDigest.getInstance("SHA-256").digest(bytes)
    )

    fun toHex(digest: ByteArray): String = digest.joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    /** Read the newly saved SAF document back and compare bytes and SHA-256. */
    @Throws(IOException::class)
    fun verifySaved(input: InputStream, expectedBytes: Long, expectedHash: ByteArray) {
        require(expectedBytes >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(32 * 1024)
        var seen = 0L
        input.use { source ->
            while (true) {
                val read = source.read(block)
                if (read < 0) break
                if (read == 0) continue
                seen += read
                if (seen > expectedBytes) throw IOException("Il file salvato è più grande del previsto")
                digest.update(block, 0, read)
            }
        }
        if (seen != expectedBytes || !MessageDigest.isEqual(expectedHash, digest.digest())) {
            throw IOException("Il file salvato non corrisponde all'originale: download non valido")
        }
    }
}
