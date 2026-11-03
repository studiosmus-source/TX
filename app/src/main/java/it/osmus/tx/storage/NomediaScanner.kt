package it.osmus.tx.storage

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.ArrayDeque

/**
 * On-demand, read-only index of media inside folders with a .nomedia marker.
 *
 * Never scans during the ordinary MediaStore gallery request. Work is bounded
 * per remote page so large storage trees cannot block the relay indefinitely.
 * This cannot access Android's protected app data or the OPPO private safe.
 */
class NomediaScanner(root: File) {
    data class Item(val path: String, val name: String, val type: String,
                    val size: Long, val modified: Long)
    data class Page(val entries: List<Item>, val hasMore: Boolean, val nextOffset: Int)

    private data class Directory(val file: File, val hiddenByNomedia: Boolean)
    private data class ActiveDirectory(
        val files: Array<File>,
        val hiddenByNomedia: Boolean,
        var index: Int = 0
    )

    private val base: Path = root.canonicalFile.toPath()
    private val directories = ArrayDeque<Directory>().apply {
        add(Directory(root.canonicalFile, false))
    }
    private val visited = HashSet<Path>()
    private val found = ArrayList<Item>()
    private var activeDirectory: ActiveDirectory? = null

    /** A page may be empty with hasMore=true when scanning has not finished. */
    @Synchronized
    fun page(offset: Int, limit: Int): Page {
        require(offset >= 0 && limit in 1..80)
        val limitNeeded = offset.toLong() + limit
        val deadline = System.nanoTime() + 2_000_000_000L
        var visitedEntries = 0
        while (found.size.toLong() < limitNeeded &&
            (activeDirectory != null || directories.isNotEmpty()) &&
            visitedEntries < 8_000 && System.nanoTime() < deadline) {
            val current = activeDirectory ?: openNextDirectory() ?: break
            if (current.index >= current.files.size) {
                activeDirectory = null
                continue
            }
            val file = current.files[current.index++]
            visitedEntries++
            // Canonicalization also rejects links leading outside shared storage.
            val canonical = try { file.canonicalFile } catch (_: IOException) { continue }
            val path = canonical.toPath()
            if (!path.startsWith(base) || canonical == base.toFile()) continue
            if (canonical.isDirectory) {
                if (visited.size < 100_000 && !visited.contains(path)) {
                    directories.add(Directory(canonical, current.hiddenByNomedia))
                }
            } else if (current.hiddenByNomedia && canonical.isFile && canonical.canRead()) {
                val type = mediaType(canonical.extension.lowercase()) ?: continue
                found.add(Item(base.relativize(path).toString()
                    .replace(File.separatorChar, '/'), canonical.name, type,
                    canonical.length(), canonical.lastModified()))
            }
        }
        val entries = if (offset >= found.size) emptyList()
            else found.subList(offset, minOf(found.size, offset + limit)).toList()
        return Page(entries, activeDirectory != null || directories.isNotEmpty() ||
            offset + entries.size < found.size, offset + entries.size)
    }

    private fun openNextDirectory(): ActiveDirectory? {
        while (directories.isNotEmpty()) {
            val dir = directories.removeFirst()
            val canonical = try { dir.file.canonicalFile } catch (_: IOException) { continue }
            val path = canonical.toPath()
            if (!path.startsWith(base) || !canonical.isDirectory ||
                !canonical.canRead() || !visited.add(path)) continue
            val children = canonical.listFiles() ?: continue
            val hidden = dir.hiddenByNomedia ||
                children.any { it.name == ".nomedia" && it.isFile }
            return ActiveDirectory(children, hidden).also { activeDirectory = it }
        }
        return null
    }

    private fun mediaType(extension: String): String? = when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif",
        "tif", "tiff" -> "image"
        "mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi" -> "video"
        else -> null
    }
}
