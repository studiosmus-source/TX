package it.osmus.tx.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileCatalogTest {
    @Test fun includesHiddenFilesAndDirectories() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try {
            File(root, ".nomedia").writeText("")
            File(root, ".hidden").mkdir()
            File(root, "camera.jpg").writeText("photo")
            val page = FileCatalog(root).listDirectory()
            assertEquals(3, page.total)
            assertTrue(page.entries.any { it.name == ".nomedia" && it.hidden })
            assertTrue(page.entries.any { it.name == ".hidden" && it.hidden && it.directory })
            assertEquals(".hidden", page.entries.first().name)
        } finally { root.deleteRecursively() }
    }
    @Test fun paginates() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try {
            repeat(5) { File(root, "item$it").writeText("x") }
            val catalog = FileCatalog(root)
            val a = catalog.listDirectory(limit = 2)
            val b = catalog.listDirectory(offset = 2, limit = 2)
            val c = catalog.listDirectory(offset = 4, limit = 2)
            assertTrue(a.hasMore)
            assertTrue(b.hasMore)
            assertFalse(c.hasMore)
            assertEquals(5, (a.entries + b.entries + c.entries).map { it.name }.distinct().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun readsLargeFileInBlocks() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try {
            val input = ByteArray(140_000) { (it % 251).toByte() }
            File(root, ".video.mp4").writeBytes(input)
            val catalog = FileCatalog(root)
            val first = catalog.readChunk(".video.mp4", 0, 65_536)
            val middle = catalog.readChunk(".video.mp4", first.nextOffset, 65_536)
            val last = catalog.readChunk(".video.mp4", middle.nextOffset, 65_536)
            assertFalse(first.complete)
            assertTrue(last.complete)
            assertArrayEquals(input, first.bytes + middle.bytes + last.bytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun emptyFileAndLastChunkAtEnd() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try {
            File(root, ".empty").writeBytes(byteArrayOf())
            val catalog = FileCatalog(root)
            val chunk = catalog.readChunk(".empty", 0, 128)
            assertTrue(chunk.complete)
            assertEquals(0, chunk.bytes.size)
            assertEquals(0L, chunk.nextOffset)
        } finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsSymlinkOutsideRoot() {
        val root = Files.createTempDirectory("tx-test").toFile()
        val outside = Files.createTempDirectory("tx-outside").toFile()
        try {
            File(outside, "private.txt").writeText("secret")
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            FileCatalog(root).readChunk("escape/private.txt", 0, 10)
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try { FileCatalog(root).listDirectory("../") }
        finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsFileOutsideRoot() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try { FileCatalog(root).readChunk("/etc/passwd", 0, 10) }
        finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedRead() {
        val root = Files.createTempDirectory("tx-test").toFile()
        try { FileCatalog(root).readChunk("example", 0, 1_000_000) }
        finally { root.deleteRecursively() }
    }
}
