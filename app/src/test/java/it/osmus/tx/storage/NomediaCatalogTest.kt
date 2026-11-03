package it.osmus.tx.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NomediaCatalogTest {
    @Test fun findsOnlyMediaSuppressedByNomediaIncludingDescendants() {
        val root = Files.createTempDirectory("nomedia-test").toFile()
        try {
            val normal = File(root, "DCIM").apply { mkdir() }
            File(normal, "visible.jpg").writeText("photo")
            val privateFolder = File(root, "Pictures/cache").apply { mkdirs() }
            File(privateFolder, ".nomedia").writeText("")
            File(privateFolder, "photo.jpg").writeText("photo")
            File(privateFolder, "clip.mp4").writeText("video")
            File(privateFolder, "notes.txt").writeText("not media")
            val descendant = File(privateFolder, "nested").apply { mkdir() }
            File(descendant, "another.png").writeText("image")
            val folder = File(root, "Videos").apply { mkdirs() }
            File(folder, "normal.mp4").writeText("video")

            val page = NomediaCatalog(root).page(0, 36)
            val names = page.entries.map { it.name }.toSet()
            assertEquals(setOf("photo.jpg", "clip.mp4", "another.png"), names)
            assertTrue(page.entries.all { it.path.startsWith("Pictures/cache/") })
            assertFalse(page.hasMore)
            assertFalse(page.truncated)
        } finally { root.deleteRecursively() }
    }

    @Test fun paginatesAndDoesNotReturnNomediaMarkerAsMedia() {
        val root = Files.createTempDirectory("nomedia-pages").toFile()
        try {
            val folder = File(root, "hidden").apply { mkdir() }
            File(folder, ".nomedia").writeText("")
            repeat(5) { File(folder, "image$it.jpg").writeText("image") }
            val scanner = NomediaCatalog(root)
            val a = scanner.page(0, 2)
            val b = scanner.page(2, 2)
            val c = scanner.page(4, 2)
            assertTrue(a.hasMore)
            assertTrue(b.hasMore)
            assertFalse(c.hasMore)
            assertEquals(5, (a.entries + b.entries + c.entries).map { it.path }.distinct().size)
            assertTrue((a.entries + b.entries + c.entries).none { it.name == ".nomedia" })
        } finally { root.deleteRecursively() }
    }

    @Test fun onlyNestedSuppressedDirectoryCounts() {
        val root = Files.createTempDirectory("nomedia-nested").toFile()
        try {
            val parent = File(root, "outer").apply { mkdir() }
            File(parent, "outside.jpg").writeText("visible")
            val child = File(parent, "inside").apply { mkdir() }
            File(child, ".nomedia").writeText("")
            File(child, "inside.jpg").writeText("hidden")
            val page = NomediaCatalog(root).page(0, 100)
            assertEquals(listOf("inside.jpg"), page.entries.map { it.name })
        } finally { root.deleteRecursively() }
    }
}
