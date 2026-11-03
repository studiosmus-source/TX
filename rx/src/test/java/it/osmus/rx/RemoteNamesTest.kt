package it.osmus.rx

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteNamesTest {
    @Test fun namesSanitized() {
        assertEquals("a_b_c.jpg", RemoteNames.safeName("a/b\\c.jpg"))
        assertEquals("file", RemoteNames.safeName("  "))
    }
    @Test fun mimeTypes() {
        assertEquals("image/jpeg", RemoteNames.mimeType("Camera.JPG"))
        assertEquals("video/mp4", RemoteNames.mimeType("clip.mp4"))
        assertEquals("video/x-matroska", RemoteNames.mimeType("clip.mkv"))
        assertEquals("audio/mp4", RemoteNames.mimeType("voice.m4a"))
        assertEquals("audio/wav", RemoteNames.mimeType("voice.wav"))
        assertEquals("audio/flac", RemoteNames.mimeType("music.flac"))
        assertEquals("image/heic", RemoteNames.mimeType("photo.heif"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", RemoteNames.mimeType("report.xlsx"))
        assertEquals("application/octet-stream", RemoteNames.mimeType("mystery"))
    }
}
