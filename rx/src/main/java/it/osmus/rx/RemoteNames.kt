package it.osmus.rx

import java.util.Locale

object RemoteNames {
    /** Suggested SAF display name; never a local path. */
    fun safeName(name: String): String {
        val clean = name.map { c ->
            if (c == '/' || c == '\\' || c.code < 32) '_' else c
        }.joinToString("").trim().take(160)
        return clean.ifBlank { "file" }
    }

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
