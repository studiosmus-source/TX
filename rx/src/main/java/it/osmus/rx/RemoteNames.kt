package it.osmus.rx

import java.util.Locale

object RemoteNames {
    /** Suggested SAF name; never a filesystem destination path. */
    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\\\x00-\\x1f]"), "_").trim().take(160)
        return cleaned.ifBlank { "file" }
    }
    fun mimeType(name: String): String {
        return when(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "pdf" -> "application/pdf"
            "txt", "log", "csv" -> "text/plain"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }
}
