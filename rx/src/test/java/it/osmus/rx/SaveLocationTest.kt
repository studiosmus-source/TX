package it.osmus.rx

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLocationTest {
    @Test fun savesPhotosToPictures() {
        assertEquals(SaveLocation.Category.PHOTOS, SaveLocation.category("selfie.HEIC"))
        assertEquals("Pictures/GALLERY/", SaveLocation.relativePath(SaveLocation.Category.PHOTOS))
    }

    @Test fun savesVideosToMovies() {
        assertEquals(SaveLocation.Category.VIDEOS, SaveLocation.category("film.mkv"))
        assertEquals("Movies/GALLERY/", SaveLocation.relativePath(SaveLocation.Category.VIDEOS))
    }

    @Test fun savesAudioToMusic() {
        assertEquals(SaveLocation.Category.AUDIO, SaveLocation.category("memo.m4a"))
        assertEquals(SaveLocation.Category.AUDIO, SaveLocation.category("audio.opus"))
        assertEquals("Music/GALLERY/", SaveLocation.relativePath(SaveLocation.Category.AUDIO))
    }

    @Test fun savesDocumentsAndUnknownFilesToDownloads() {
        assertEquals(SaveLocation.Category.DOWNLOADS, SaveLocation.category("notes.pdf"))
        assertEquals(SaveLocation.Category.DOWNLOADS, SaveLocation.category("data.bin"))
        assertEquals("Download/GALLERY/", SaveLocation.relativePath(SaveLocation.Category.DOWNLOADS))
    }
}
