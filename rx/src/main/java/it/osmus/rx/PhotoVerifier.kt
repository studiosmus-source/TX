package it.osmus.rx

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.IOException

/** Decode image bytes from the final MediaStore file, not from the TX preview. */
object PhotoVerifier {
    @Throws(IOException::class)
    fun verifySavedImage(resolver: ContentResolver, uri: Uri) {
        try {
            val source = ImageDecoder.createSource(resolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) throw IOException("Dimensioni foto non valide")
                val longest = maxOf(w, h)
                if (longest > 512) {
                    val factor = 512.0 / longest
                    decoder.setTargetSize(maxOf(1, (w * factor).toInt()),
                        maxOf(1, (h * factor).toInt()))
                }
            }
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0)
                    throw IOException("Foto non decodificabile")
            } finally {
                bitmap.recycle()
            }
        } catch (error: Exception) {
            throw IOException("Il contenuto non è una foto valida: " +
                (error.message ?: "formato non riconosciuto"), error)
        }
    }
}
