package it.osmus.tx.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileCatalogTest {
    @Test fun includesHiddenFilesAndDirectories() {
        val temp = Files.createTempDirectory("tx-test-").toFile()
        try {
            File(temp, ".nomedia").writeText("")
            File(temp, ".hidden").mkdir()
            File(temp, "camera.jpg").writeText("photo")
            val page = FileCatalog(temp).listDirectory()
            assertEquals(3, page.total)
            assertTrue(page.entries.any { it.name == ".nomedia" && it.hidden })
            assertTrue(page.entries.any { it.name == ".hidden" && it.hidden && it.directory })
            assertEquals(".hidden", page.entries.first().name)
        } finally { temp.deleteRecursively() }
    }

    @Test fun paginatesWithoutLosingEntries() {
        val temp = Files.createTempDirectory("tx-test-").toFile()
        try {
            repeat(5) { File(temp, "item$it").writeText("x") }
            val catalog = FileCatalog(temp)
            val first = catalog.listDirectory(limit = 2)
            val second = catalog.listDirectory(offset = 2, limit = 2)
            val third = catalog.listDirectory(offset = 4, limit = 2)
            assertTrue(first.hasMore)
            assertTrue(second.hasMore)
            assertFalse(third.hasMore)
            assertEquals(5, (first.entries + second.entries + third.entries).map { it.name }.distinct().size)
        } finally { temp.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        val temp = Files.createTempDirectory("tx-test-").toFile()
        try { FileCatalog(temp).listDirectory("../") }
        finally { temp.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsolutePath() {
        FileCatalog(File("/tmp")).listDirectory("/etc")
    }
}
