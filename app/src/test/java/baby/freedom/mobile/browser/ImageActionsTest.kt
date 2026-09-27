package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** File naming and `data:` decoding for the image context-menu actions (#84). */
class ImageActionsTest {

    private val ext: (String) -> String? = {
        when (it) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }
    }

    @Test
    fun `the URL's own file name is kept, with an extension matching the bytes`() {
        assertEquals("cat.jpg", imageFileName("https://a.com/pics/cat.jpeg?w=200#x", "image/jpeg", ext))
        assertEquals("cat.webp", imageFileName("https://a.com/pics/cat.png", "image/webp", ext))
    }

    @Test
    fun `a percent-encoded name is decoded and made filesystem-safe`() {
        assertEquals("my cat_.png", imageFileName("https://a.com/my%20cat%3F.png", "image/png", ext))
    }

    @Test
    fun `a hash or an empty path saves as image`() {
        val hash = "a".repeat(64)
        assertEquals("image.png", imageFileName("http://127.0.0.1:1633/bzz/$hash", "image/png", ext))
        assertEquals("image.png", imageFileName("https://a.com/", "image/png", ext))
        assertEquals("image.png", imageFileName("data:image/png;base64,AAAA", "image/png", ext))
    }

    @Test
    fun `an unknown mime keeps the URL's extension, or falls back to png`() {
        assertEquals("logo.svg", imageFileName("https://a.com/logo.svg", "image/x-unknown", ext))
        assertEquals("logo.png", imageFileName("https://a.com/logo", "image/x-unknown", ext))
    }

    @Test
    fun `malformed data URLs decode to nothing`() {
        assertNull(decodeDataUrl("https://a.com/x.png"))
        assertNull(decodeDataUrl("data:image/png;base64"))
        assertNull(decodeDataUrl("data:image/png;base64,"))
    }
}
