package it.osmus.rx

/** Pure selection logic for storing each received file in a discoverable collection. */
object SaveLocation {
    enum class Category { PHOTOS, VIDEOS, AUDIO, DOWNLOADS }

    fun category(name: String): Category {
        val mime = RemoteNames.mimeType(name)
        return when {
            mime.startsWith("image/") -> Category.PHOTOS
            mime.startsWith("video/") -> Category.VIDEOS
            mime.startsWith("audio/") -> Category.AUDIO
            else -> Category.DOWNLOADS
        }
    }

    fun relativePath(category: Category): String = when (category) {
        Category.PHOTOS -> "Pictures/GALLERY/"
        Category.VIDEOS -> "Movies/GALLERY/"
        Category.AUDIO -> "Music/GALLERY/"
        Category.DOWNLOADS -> "Download/GALLERY/"
    }
}
