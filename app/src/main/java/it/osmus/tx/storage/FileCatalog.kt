package it.osmus.tx.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/** Accesso in sola lettura alla memoria condivisa. I percorsi sono sempre relativi alla radice. */
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
    data class Chunk(val bytes: ByteArray, val totalBytes: Long, val nextOffset: Long, val complete: Boolean)

    @Throws(IOException::class, IllegalArgumentException::class)
    fun listDirectory(relativeDirectory: String = "", offset: Int = 0, limit: Int = 100): Page {
        require(offset >= 0) { "Offset non valido" }
        require(limit in 1..200) { "Limit deve essere tra 1 e 200" }
        val directory = resolve(relativeDirectory)
        if (!directory.isDirectory) throw IOException("Cartella assente o non accessibile")
        val files = directory.listFiles() ?: throw IOException("Impossibile leggere la cartella")
        val sorted = files.asSequence()
            .filter { file -> runCatching { file.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
        val selected = sorted.drop(offset).take(limit)
        return Page(selected.map { file ->
            Entry(
                relativePath = canonicalRoot.relativize(file.canonicalFile.toPath()).toString()
                    .replace(File.separatorChar, '/'),
                name = file.name,
                directory = file.isDirectory,
                hidden = file.name.startsWith(".") || file.isHidden,
                sizeBytes = if (file.isFile) file.length() else 0L,
                lastModifiedMillis = file.lastModified()
            )
        }, sorted.size, offset.toLong() + selected.size < sorted.size)
    }

    /** Legge solo il segmento richiesto: nessuna copia preventiva di foto/video su un server. */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun readChunk(relativeFile: String, offset: Long, length: Int): Chunk {
        require(relativeFile.isNotBlank()) { "File mancante" }
        require(offset >= 0) { "Offset non valido" }
        require(length in 1..MAX_CHUNK) { "Dimensione blocco non valida" }
        val file = resolve(relativeFile)
        if (!file.isFile) throw IOException("File assente o non accessibile")
        return RandomAccessFile(file, "r").use { reader ->
            val size = reader.length()
            if (offset > size) throw IllegalArgumentException("Offset oltre la fine del file")
            val bytes = ByteArray(minOf(length.toLong(), size - offset).toInt())
            reader.seek(offset)
            reader.readFully(bytes)
            val nextOffset = offset + bytes.size
            Chunk(bytes, size, nextOffset, nextOffset >= size)
        }
    }

    private fun resolve(relative: String): File {
        require(!File(relative).isAbsolute) { "Percorsi assoluti non consentiti" }
        val resolved = canonicalRoot.resolve(relative).toFile().canonicalFile
        require(resolved.toPath().startsWith(canonicalRoot)) { "Percorso fuori dalla memoria condivisa" }
        return resolved
    }

    companion object { const val MAX_CHUNK = 512 * 1024 }
}
