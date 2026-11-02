package it.osmus.tx.storage

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Elenca i file nella memoria condivisa; non distingue tra file visibili e nascosti.
 * Non espone percorsi fuori dalla directory radice, nemmeno tramite symlink.
 * Nessuna rete, trasferimento o operazione di scrittura.
 */
class FileCatalog(root: File) {
    private val canonicalRoot = root.canonicalFile.toPath()

    data class Entry(
        val relativePath: String,
        val name: String,
        val directory: Boolean,
        val hidden: Boolean,
        val sizeBytes: Long,
        val lastModifiedMillis: Long
    )

    data class Page(val entries: List<Entry>, val total: Int, val hasMore: Boolean)

    @Throws(IOException::class, IllegalArgumentException::class)
    fun listDirectory(relativeDirectory: String = "", offset: Int = 0, limit: Int = 100): Page {
        require(offset >= 0) { "Offset non valido" }
        require(limit in 1..500) { "Limit deve essere tra 1 e 500" }
        val directory = resolve(relativeDirectory)
        if (!directory.isDirectory) throw IOException("Cartella assente o non accessibile")
        val all = directory.listFiles() ?: throw IOException("Impossibile leggere la cartella")
        val sorted = all.asSequence()
            .filter { file -> runCatching { file.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
        val pageFiles = sorted.drop(offset).take(limit)
        return Page(
            entries = pageFiles.map { file ->
                Entry(
                    relativePath = canonicalRoot.relativize(file.canonicalFile.toPath())
                        .toString().replace(File.separatorChar, '/'),
                    name = file.name,
                    directory = file.isDirectory,
                    hidden = file.name.startsWith(".") || file.isHidden,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    lastModifiedMillis = file.lastModified()
                )
            },
            total = sorted.size,
            hasMore = offset.toLong() + pageFiles.size < sorted.size
        )
    }

    private fun resolve(relative: String): File {
        require(!File(relative).isAbsolute) { "Percorsi assoluti non consentiti" }
        val resolved = canonicalRoot.resolve(relative).toFile().canonicalFile
        require(resolved.toPath().startsWith(canonicalRoot)) { "Percorso esterno alla memoria condivisa" }
        return resolved
    }
}
