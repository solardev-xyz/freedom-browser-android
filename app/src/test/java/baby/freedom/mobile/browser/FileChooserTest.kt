package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileChooserTest {

    private val ext = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg", "png" to "image/png")
    private fun mimes(vararg accept: String) = mimeTypesForAccept(arrayOf(*accept)) { ext[it] }

    @Test fun `no accept means anything`() {
        assertEquals(emptyList<String>(), mimeTypesForAccept(null) { null })
        assertEquals(emptyList<String>(), mimes(""))
    }

    @Test fun `accept entries are trimmed, lowercased, deduped and extensions mapped`() {
        assertEquals(
            listOf("image/*", "application/pdf", "image/png"),
            mimes("Image/*", " .PDF", ".png ", "image/*", ".unknownext"),
        )
    }

    @Test fun `an unsplit comma list is split too`() {
        assertEquals(listOf("image/jpeg", "video/*"), mimes(".jpg, video/*"))
    }

    @Test fun `a wildcard anywhere accepts everything`() {
        assertEquals(emptyList<String>(), mimes("image/*", "*/*"))
    }

    @Test fun `camera only for capture inputs the camera can satisfy`() {
        assertNull(captureKindFor(false, listOf("image/*")))
        assertEquals(CaptureKind.IMAGE, captureKindFor(true, emptyList()))
        assertEquals(CaptureKind.IMAGE, captureKindFor(true, listOf("image/jpeg")))
        assertEquals(CaptureKind.IMAGE, captureKindFor(true, listOf("video/*", "image/*")))
        assertEquals(CaptureKind.VIDEO, captureKindFor(true, listOf("video/mp4")))
        assertNull(captureKindFor(true, listOf("application/pdf")))
        assertNull(captureKindFor(true, listOf("audio/*")))
    }

    private fun scheme(s: String) = s.substringBefore(':', "")

    @Test fun `single pick comes from data`() {
        assertEquals(listOf("content://a/1"), pickedUris(emptyList(), "content://a/1", false, ::scheme))
    }

    @Test fun `multi pick comes from clip data`() {
        val clip = listOf("content://a/1", "content://a/2", "content://a/2")
        assertEquals(listOf("content://a/1", "content://a/2"), pickedUris(clip, "content://a/1", true, ::scheme))
    }

    @Test fun `single-file input keeps only the first of several picks`() {
        val clip = listOf("content://a/1", "content://a/2")
        assertEquals(listOf("content://a/1"), pickedUris(clip, null, false, ::scheme))
    }

    @Test fun `non-content uris are never uploaded`() {
        val clip = listOf("file:///data/data/baby.freedom.mobile/databases/x.db", "content://a/2")
        assertEquals(listOf("content://a/2"), pickedUris(clip, null, true, ::scheme))
        assertEquals(emptyList<String>(), pickedUris(emptyList(), "file:///sdcard/x", false, ::scheme))
    }

    @Test fun `nothing picked is empty`() {
        assertEquals(emptyList<String>(), pickedUris(emptyList<String>(), null, true, ::scheme))
    }
}
