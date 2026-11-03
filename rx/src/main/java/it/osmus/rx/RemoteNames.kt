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
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "3gp", "3gpp" -> "video/3gpp"
        "mpeg", "mpg" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "amr" -> "audio/amr"
        "aif", "aiff" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}
