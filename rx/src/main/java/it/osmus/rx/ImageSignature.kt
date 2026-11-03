package it.osmus.rx

/**
 * Detect common still-image formats by the actual first bytes rather than
 * trusting an extension or an old MediaStore DISPLAY_NAME.
 */
object ImageSignature {
    data class Format(val extension: String, val mime: String)

    fun detect(bytes: ByteArray): Format? {
        fun has(vararg values: Int): Boolean =
            bytes.size >= values.size &&
                values.indices.all { i -> (bytes[i].toInt() and 0xff) == values[i] }
        return when {
            has(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
                Format("png", "image/png")
            has(0xff, 0xd8, 0xff) -> Format("jpg", "image/jpeg")
            has(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) ||
                has(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) ->
                Format("gif", "image/gif")
            has(0x42, 0x4d) -> Format("bmp", "image/bmp")
            bytes.size >= 12 && has(0x52, 0x49, 0x46, 0x46) &&
                bytes.copyOfRange(8, 12).contentEquals(
                    byteArrayOf(0x57, 0x45, 0x42, 0x50)) ->
                Format("webp", "image/webp")
            has(0x49, 0x49, 0x2a, 0x00) || has(0x4d, 0x4d, 0x00, 0x2a) ->
                Format("tiff", "image/tiff")
            bytes.size >= 12 && hasFtyp(bytes) -> when {
                brand(bytes, "heic", "heix", "hevc", "hevx", "mif1", "msf1") ->
                    Format("heic", "image/heic")
                brand(bytes, "avif", "avis") -> Format("avif", "image/avif")
                else -> null
            }
            else -> null
        }
    }

    private fun hasFtyp(bytes: ByteArray): Boolean =
        bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()

    private fun brand(bytes: ByteArray, vararg candidates: String): Boolean {
        val text = String(bytes, 8, 4, Charsets.US_ASCII)
        return text in candidates
    }

    /** Keep the original filename if format matches; fix mislabeled photos otherwise. */
    fun saveName(originalName: String, start: ByteArray): String {
        val format = detect(start) ?: return originalName
        val extension = originalName.substringAfterLast('.', "").lowercase()
        val sameType = when (format.extension) {
            "jpg" -> extension in setOf("jpg", "jpeg", "jpe")
            "heic" -> extension in setOf("heic", "heif")
            "tiff" -> extension in setOf("tif", "tiff")
            else -> extension == format.extension
        }
        if (sameType) return originalName
        return originalName.substringBeforeLast('.', originalName) + "." + format.extension
    }
}
