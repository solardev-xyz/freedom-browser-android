package baby.freedom.mobile.data

import baby.freedom.mobile.data.BrowsingRepository.Companion.MAX_TITLE_CHARS
import baby.freedom.mobile.data.BrowsingRepository.Companion.MAX_URL_CHARS
import baby.freedom.mobile.data.BrowsingRepository.Companion.isRecordable
import baby.freedom.mobile.data.BrowsingRepository.Companion.storedTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What history and bookmarks refuse or cut so no row grows past what
 * Android can read back (#461): one ~2 MB address made Home and History
 * crash on every open.
 */
class RecordLimitsTest {

    @Test
    fun `an address up to 8 KiB is kept, a longer one is not`() {
        val base = "https://example.com/x#"
        assertTrue(isRecordable(base + "a".repeat(MAX_URL_CHARS - base.length)))
        assertFalse(isRecordable(base + "a".repeat(MAX_URL_CHARS - base.length + 1)))
        // The page from the issue: a fragment just under Chromium's 2 MiB.
        assertFalse(isRecordable("http://127.0.0.1:8710/x.html#" + "a".repeat(2_097_100)))
        assertFalse(isRecordable("bzz://" + "a".repeat(MAX_URL_CHARS)))
    }

    @Test
    fun `ordinary addresses are still kept and internal ones still refused`() {
        assertTrue(isRecordable("https://example.com/"))
        assertTrue(isRecordable("vitalik.eth"))
        assertFalse(isRecordable("about:blank"))
        assertFalse(isRecordable("   "))
    }

    @Test
    fun `a title is cut to its limit`() {
        val short = "Example Domain"
        assertSame(short, storedTitle(short))
        val exact = "t".repeat(MAX_TITLE_CHARS)
        assertSame(exact, storedTitle(exact))
        assertEquals(exact, storedTitle(exact + "more"))
        assertEquals(MAX_TITLE_CHARS, storedTitle("x".repeat(4_000_000)).length)
    }

    @Test
    fun `a title isn't cut inside a surrogate pair`() {
        // "😀" is two chars; the pair would straddle the limit.
        val title = "t".repeat(MAX_TITLE_CHARS - 1) + "😀" + "tail"
        val stored = storedTitle(title)
        assertEquals("t".repeat(MAX_TITLE_CHARS - 1), stored)
        assertFalse(Character.isHighSurrogate(stored.last()))
        val whole = "t".repeat(MAX_TITLE_CHARS - 2) + "😀" + "tail"
        assertEquals("t".repeat(MAX_TITLE_CHARS - 2) + "😀", storedTitle(whole))
    }
}
