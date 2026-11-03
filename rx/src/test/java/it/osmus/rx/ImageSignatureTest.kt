package it.osmus.rx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSignatureTest {
    @Test fun pngRetainsCorrectExtension() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertEquals("png", ImageSignature.detect(png)?.extension)
        assertEquals("pic.png", ImageSignature.saveName("pic.png", png))
    }
    @Test fun misleadingPngNameGetsCorrectedToJpeg() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        assertEquals("pic.jpg", ImageSignature.saveName("pic.png", jpeg))
        assertEquals("pic.jpeg", ImageSignature.saveName("pic.jpeg", jpeg))
    }
    @Test fun webpIsIdentifiedFromRiffSignature() {
        val webp = byteArrayOf(0x52,0x49,0x46,0x46,0,0,0,0,0x57,0x45,0x42,0x50)
        assertEquals("webp", ImageSignature.detect(webp)?.extension)
    }
    @Test fun unknownBinaryIsNotGuessed() {
        assertNull(ImageSignature.detect(byteArrayOf(1,2,3,4)))
        assertEquals("unknown.bin", ImageSignature.saveName("unknown.bin", byteArrayOf(1,2,3,4)))
    }
    @Test fun avifBrandIsDetected() {
        val avif = byteArrayOf(0,0,0,24,0x66,0x74,0x79,0x70,0x61,0x76,0x69,0x66)
        assertEquals("avif", ImageSignature.detect(avif)?.extension)
    }
}
