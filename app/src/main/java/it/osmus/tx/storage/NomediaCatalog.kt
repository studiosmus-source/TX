package it.osmus.tx.storage

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale

/**
 * Finds media in directories suppressed by .nomedia, bypassing MediaStore.
 * Traverses only readable shared storage; never follows symlinks outside root.
 * Cached to make paging cheap and bounded to protect phones with huge trees.
 */
class NomediaCatalog(root: File) {
    private val rootDir = root.canonicalFile
    private val rootPath = rootDir.toPath()
    data class Entry(val path: String, val name: String, val type: String,
                     val size: Long, val modified: Long)
    data class Page(val entries: List<Entry>, val hasMore: Boolean,
                    val nextOffset: Int, val truncated: Boolean)
    private data class Scan(val files: List<Entry>, val truncated: Boolean,
                            val timestamp: Long)
    @Volatile private var cached: Scan? = null

    @Synchronized
    fun page(offset: Int, limit: Int): Page {
        require(offset >= 0 && limit in 1..80)
        val current = cached
        val now = System.currentTimeMillis()
        val scan = if (current != null && now - current.timestamp < CACHE_MS) current
            else scan().also { cached = it }
        val selected = scan.files.drop(offset).take(limit)
        val next = offset + selected.size
        return Page(selected, next < scan.files.size, next, scan.truncated)
    }

    private fun scan(): Scan {
        data class Directory(val file: File, val suppressed: Boolean)
        val queue = ArrayDeque<Directory>()
        val visited = HashSet<java.nio.file.Path>()
        val found = ArrayList<Entry>()
        queue.add(Directory(rootDir, false))
        var examined = 0
        var folders = 0
        var truncated = false
        val startNanos = System.nanoTime()
        while (queue.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) {
                truncated = true
                break
            }
            if (folders >= MAX_DIRECTORIES || examined >= MAX_ENTRIES ||
                found.size >= MAX_RESULTS ||
                System.nanoTime() - startNanos >= MAX_SCAN_NANOS) {
                truncated = true
                break
            }
            val task = queue.removeFirst()
            val folder = task.file
            val canonical = runCatching { folder.canonicalFile.toPath() }.getOrNull()
                ?: continue
            if (!canonical.startsWith(rootPath) || !visited.add(canonical) ||
                Files.isSymbolicLink(folder.toPath())) continue
            val files = folder.listFiles() ?: continue
            folders++
            val hidden = task.suppressed || files.any { it.isFile && it.name == ".nomedia" }
            for (entry in files) {
                examined++
                if (examined > MAX_ENTRIES || found.size >= MAX_RESULTS ||
                    System.nanoTime() - startNanos >= MAX_SCAN_NANOS) {
                    truncated = true
                    break
                }
                if (Files.isSymbolicLink(entry.toPath())) continue
                if (entry.isDirectory) {
                    queue.add(Directory(entry, hidden))
                } else if (hidden && entry.isFile && entry.canRead()) {
                    val ext = entry.extension.lowercase(Locale.ROOT)
                    val type = when (ext) {
                        in IMAGES -> "image"
                        in VIDEOS -> "video"
                        else -> null
                    } ?: continue
                    val canonicalFile = runCatching { entry.canonicalFile.toPath() }
                        .getOrNull() ?: continue
                    if (!canonicalFile.startsWith(rootPath)) continue
                    val path = rootPath.relativize(canonicalFile).toString()
                        .replace(File.separatorChar, '/')
                    found.add(Entry(path, entry.name, type,
                        entry.length(), entry.lastModified()))
                }
            }
            if (truncated) break
        }
        found.sortWith(compareByDescending<Entry> { it.modified }.thenBy { it.path })
        return Scan(found, truncated, System.currentTimeMillis())
    }

    companion object {
        private const val CACHE_MS = 600_000L
        private const val MAX_DIRECTORIES = 20_000
        private const val MAX_ENTRIES = 150_000
        private const val MAX_RESULTS = 20_000
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "heic",
            "heif", "bmp", "avif")
        private val VIDEOS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi")
    }
}
