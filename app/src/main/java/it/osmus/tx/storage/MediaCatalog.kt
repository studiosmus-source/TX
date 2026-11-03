package it.osmus.tx.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Paged media index, no full-device scan before the first gallery page. */
class MediaCatalog(private val context: Context, root: File) {
    private val rootPath = root.canonicalFile.toPath()
    private val nomedia = NomediaCatalog(root)
    data class Item(val path: String, val name: String, val type: String,
                    val size: Long, val modified: Long)
    data class Page(val entries: List<Item>, val hasMore: Boolean, val nextOffset: Int)

    fun gallery(offset: Int, limit: Int): Page {
        require(offset >= 0 && limit in 1..80)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC, " +
                    MediaStore.MediaColumns._ID + " DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val entries = mutableListOf<Item>()
        var hasMore = false
        var rowsRead = 0
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED)
        context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            projection, args, null)?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                if (rowsRead++ >= limit) { hasMore = true; break }
                val rawPath = cursor.getString(pathIndex) ?: continue
                val path = safePath(rawPath) ?: continue
                val type = when (cursor.getInt(typeIndex)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
                    else -> continue
                }
                val file = File(rawPath)
                if (!file.isFile || !file.canRead()) continue
                entries.add(Item(path, cursor.getString(nameIndex) ?: file.name, type,
                    cursor.getLong(sizeIndex).coerceAtLeast(0),
                    cursor.getLong(modifiedIndex).coerceAtLeast(0) * 1000L))
            }
        } ?: throw IOException("Indice multimediale non disponibile")
        return Page(entries, hasMore, offset + minOf(rowsRead, limit))
    }

    @Throws(IOException::class)
    fun thumbnail(relativePath: String, large: Boolean = false): ByteArray {
        val file = rootPath.resolve(relativePath).normalize().toFile().canonicalFile
        require(file.toPath().startsWith(rootPath) && file.isFile && file.canRead()) {
            "File non accessibile"
        }
        val extension = file.extension.lowercase()
        val video = extension in setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi")
        val image = extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")
        require(video || image) { "Anteprima non supportata" }
        val side = if (large) 1440 else 240
        // 110 KB JPEG -> ~147 KB base64, still below the relay's 160 KB result limit.
        val maxBytes = if (large) 110_000 else MAX_THUMB
        val bitmap = try {
            if (video) {
                ThumbnailUtils.createVideoThumbnail(file, Size(side, side), null)
            } else {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val width = info.size.width
                        val height = info.size.height
                        val longest = maxOf(width, height)
                        if (longest > side) {
                            val ratio = side.toFloat() / longest
                            decoder.setTargetSize(maxOf(1, (width * ratio).toInt()),
                                maxOf(1, (height * ratio).toInt()))
                        }
                    }
                }.getOrElse {
                    ThumbnailUtils.createImageThumbnail(file, Size(side, side), null)
                }
            }
        } catch (error: Exception) {
            throw IOException("Anteprima non disponibile: " + (error.message ?: "decodifica"), error)
        }
        var working = bitmap
        try {
            val bytes = ByteArrayOutputStream()
            repeat(5) {
                for (quality in intArrayOf(85, 72, 60, 48, 36)) {
                    bytes.reset()
                    if (!working.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                        throw IOException("Impossibile generare il JPEG")
                    if (bytes.size() <= maxBytes) return bytes.toByteArray()
                }
                if (working.width <= 144 || working.height <= 144) {
                    throw IOException("Anteprima troppo grande per il collegamento")
                }
                val reduced = Bitmap.createScaledBitmap(working,
                    maxOf(1, working.width * 3 / 4),
                    maxOf(1, working.height * 3 / 4), true)
                if (working !== bitmap) working.recycle()
                working = reduced
            }
            throw IOException("Anteprima troppo grande per il collegamento")
        } finally {
            if (working !== bitmap) working.recycle()
            bitmap.recycle()
        }
    }

    private fun safePath(path: String): String? = runCatching {
        val normalized = File(path).canonicalFile.toPath()
        if (normalized.startsWith(rootPath)) rootPath.relativize(normalized).toString()
            .replace(File.separatorChar, '/') else null
    }.getOrNull()

    companion object { const val MAX_THUMB = 48_000 }
}
