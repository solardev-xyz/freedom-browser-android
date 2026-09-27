package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            mimes("Image/*", " .PDF", ".png ", "image/*"),
        )
    }

    @Test fun `an unmappable extension widens to anything instead of narrowing`() {
        assertEquals(emptyList<String>(), mimes(".pdf", ".gpx"))
        assertEquals(emptyList<String>(), mimes(".gpx, image/*"))
    }

    @Test fun `an unsplit comma list is split too`() {
        assertEquals(listOf("image/jpeg", "video/*"), mimes(".jpg, video/*"))
    }

    @Test fun `a wildcard anywhere accepts everything`() {
        assertEquals(emptyList<String>(), mimes("image/*", "*/*"))
    }

    private fun camera(capture: Boolean, vararg accept: String) =
        captureKindFor(capture, parseAccept(arrayOf(*accept)) { ext[it] })

    @Test fun `camera only for capture inputs the camera can satisfy`() {
        assertNull(camera(false, "image/*"))
        assertEquals(CaptureKind.IMAGE, captureKindFor(true, parseAccept(null) { null }))
        assertEquals(CaptureKind.IMAGE, camera(true, ""))
        assertEquals(CaptureKind.IMAGE, camera(true, "image/jpeg"))
        assertEquals(CaptureKind.IMAGE, camera(true, ".jpg"))
        assertEquals(CaptureKind.IMAGE, camera(true, "video/*", "image/*"))
        assertEquals(CaptureKind.IMAGE, camera(true, "application/pdf", "*/*"))
        assertEquals(CaptureKind.VIDEO, camera(true, "video/mp4"))
        assertNull(camera(true, "application/pdf"))
        assertNull(camera(true, "audio/*"))
    }

    @Test fun `an unmappable extension widens the picker but never opens the camera by itself`() {
        // Picker is widened to anything...
        assertEquals(emptyList<String>(), mimes("application/pdf", ".xyz"))
        // ...but the camera still only opens for types the page names.
        assertNull(camera(true, "application/pdf", ".xyz"))
        assertNull(camera(true, ".xyz"))
        assertEquals(CaptureKind.IMAGE, camera(true, ".xyz", "image/*"))
        assertEquals(CaptureKind.VIDEO, camera(true, ".xyz", "video/*"))
    }

    private val own = setOf("baby.freedom.mobile.files", "baby.freedom.mobile.androidx-startup")
    private fun uploadable(s: String) = isUploadableUri(
        s.substringBefore(':', ""),
        s.substringAfter("://", "").substringBefore('/'),
    ) { it in own }

    @Test fun `single pick comes from data`() {
        assertEquals(listOf("content://a/1"), pickedUris(emptyList(), "content://a/1", false, ::uploadable))
    }

    @Test fun `multi pick comes from clip data`() {
        val clip = listOf("content://a/1", "content://a/2", "content://a/2")
        assertEquals(listOf("content://a/1", "content://a/2"), pickedUris(clip, "content://a/1", true, ::uploadable))
    }

    @Test fun `single-file input keeps only the first of several picks`() {
        val clip = listOf("content://a/1", "content://a/2")
        assertEquals(listOf("content://a/1"), pickedUris(clip, null, false, ::uploadable))
    }

    @Test fun `non-content uris are never uploaded`() {
        val clip = listOf("file:///data/data/baby.freedom.mobile/databases/x.db", "content://a/2")
        assertEquals(listOf("content://a/2"), pickedUris(clip, null, true, ::uploadable))
        assertEquals(emptyList<String>(), pickedUris(emptyList(), "file:///sdcard/x", false, ::uploadable))
    }

    @Test fun `uris from the browser's own providers are never uploaded`() {
        val clip = listOf(
            "content://baby.freedom.mobile.files/uploads/IMG_20260101_120000.jpg",
            "content://0@baby.freedom.mobile.files/uploads/IMG_20260101_120000.jpg",
            "content://baby.freedom.mobile.androidx-startup/x",
            "content://com.android.providers.media.documents/document/image%3A1",
        )
        assertEquals(
            listOf("content://com.android.providers.media.documents/document/image%3A1"),
            pickedUris(clip, null, true, ::uploadable),
        )
        assertFalse(isUploadableUri("content", null) { false })
        assertFalse(isUploadableUri("content", "") { false })
        assertTrue(isUploadableUri("CONTENT", "10@com.other.app") { it in own })
    }

    @Test fun `nothing picked is empty`() {
        assertEquals(emptyList<String>(), pickedUris(emptyList<String>(), null, true, ::uploadable))
    }
}
