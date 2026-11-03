package it.osmus.tx.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NomediaScannerTest {
    @Test fun listsPhotosAndVideosBelowNomediaButNotOrdinaryMedia() {
        val root = Files.createTempDirectory("nomedia-index").toFile()
        try {
            File(root, "DCIM").mkdir()
            File(root, "DCIM/normal.jpg").writeBytes(byteArrayOf(1, 2, 3))
            val hidden = File(root, "Pictures/Private").apply { mkdirs() }
            File(hidden, ".nomedia").writeText("")
            File(hidden, "pic.jpg").writeBytes(byteArrayOf(4, 5))
            File(hidden, "nested").mkdir()
            File(hidden, "nested/movie.mp4").writeBytes(byteArrayOf(1))
            File(hidden, "notes.txt").writeText("not a photo")
            val scanner = NomediaScanner(root)
            val all = mutableListOf<NomediaScanner.Item>()
            var index = 0
            var calls = 0
            do {
                val page = scanner.page(index, 1)
                all.addAll(page.entries)
                index = page.nextOffset
                calls++
                if (!page.hasMore) break
            } while (calls < 50)
            assertTrue(calls < 50)
            val names = all.map { it.name }.toSet()
            assertEquals(setOf("pic.jpg", "movie.mp4"), names)
            assertTrue(all.all { it.path.startsWith("Pictures/Private/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nomediaMarkerAloneDoesNotAppearAsMedia() {
        val root = Files.createTempDirectory("nomedia-empty").toFile()
        try {
            File(root, ".nomedia").writeText("")
            val page = NomediaScanner(root).page(0, 20)
            assertTrue(page.entries.isEmpty())
            assertFalse(page.hasMore)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun mediaInUnmarkedHiddenDirectoryIsNotNomediaMedia() {
        val root = Files.createTempDirectory("nomedia-not-dot").toFile()
        try {
            val dot = File(root, ".hidden").apply { mkdir() }
            File(dot, "photo.jpg").writeText("fake")
            val page = NomediaScanner(root).page(0, 20)
            assertTrue(page.entries.isEmpty())
            assertFalse(page.hasMore)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun outsideRootSymlinkIsNeverIndexed() {
        val root = Files.createTempDirectory("nomedia-safe").toFile()
        val elsewhere = Files.createTempDirectory("nomedia-outside").toFile()
        try {
            File(root, ".nomedia").writeText("")
            File(elsewhere, "secret.jpg").writeBytes(byteArrayOf(7))
            Files.createSymbolicLink(File(root, "alias").toPath(), elsewhere.toPath())
            val page = NomediaScanner(root).page(0, 30)
            assertTrue(page.entries.isEmpty())
            assertFalse(page.hasMore)
        } finally {
            root.deleteRecursively()
            elsewhere.deleteRecursively()
        }
    }
}
