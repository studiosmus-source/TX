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
        assertEquals("application/octet-stream", RemoteNames.mimeType("mystery"))
    }
}
