package it.osmus.rx

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/**
 * Saves into Android's shared media collections, not a picker-owned placeholder.
 * Newly created entries stay pending until content is written and verified.
 */
class RxMediaStore(private val resolver: ContentResolver) {
    data class Destination(val uri: Uri, val relativePath: String)

    @Throws(IOException::class)
    fun create(fileName: String): Destination {
        val category = SaveLocation.category(fileName)
        val collection = when (category) {
            SaveLocation.Category.PHOTOS ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveLocation.Category.VIDEOS ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveLocation.Category.AUDIO ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SaveLocation.Category.DOWNLOADS ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = SaveLocation.relativePath(category)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, RemoteNames.safeName(fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, RemoteNames.mimeType(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Android non riesce a creare il file nella raccolta $relativePath")
        return Destination(uri, relativePath)
    }

    @Throws(IOException::class)
    fun publish(destination: Destination) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(destination.uri, values, null, null) != 1) {
            throw IOException("Il file è stato scritto, ma Android non riesce a renderlo disponibile")
        }
    }

    fun discard(destination: Destination) {
        resolver.delete(destination.uri, null, null)
    }
}
