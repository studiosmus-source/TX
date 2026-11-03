package it.osmus.rx

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class TransferIntegrityTest {
    private fun sourceDigest(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    @Test fun binaryContentAndMultipleBlocksVerifyExactly() {
        val source = ByteArray(150_317) { (it * 37 % 256).toByte() }
        TransferIntegrity.verifySaved(ByteArrayInputStream(source), source.size.toLong(), sourceDigest(source))
        assertEquals(64, TransferIntegrity.sha256(source).length)
    }

    @Test fun emptyFileVerifies() {
        val empty = byteArrayOf()
        TransferIntegrity.verifySaved(ByteArrayInputStream(empty), 0, sourceDigest(empty))
    }

    @Test(expected = IOException::class)
    fun equalLengthCorruptionIsDetected() {
        val original = ByteArray(140_000) { (it % 251).toByte() }
        val damaged = original.copyOf()
        damaged[70_000] = (damaged[70_000].toInt() xor 0xff).toByte()
        TransferIntegrity.verifySaved(ByteArrayInputStream(damaged), original.size.toLong(), sourceDigest(original))
    }

    @Test(expected = IOException::class)
    fun truncatedFileIsDetected() {
        val original = ByteArray(100_000) { it.toByte() }
        TransferIntegrity.verifySaved(ByteArrayInputStream(original.copyOf(20_000)), original.size.toLong(), sourceDigest(original))
    }

    @Test(expected = IOException::class)
    fun extraBytesAreDetected() {
        val original = byteArrayOf(1, 2, 3)
        TransferIntegrity.verifySaved(ByteArrayInputStream(byteArrayOf(1,2,3,4)), 3, sourceDigest(original))
    }
}
