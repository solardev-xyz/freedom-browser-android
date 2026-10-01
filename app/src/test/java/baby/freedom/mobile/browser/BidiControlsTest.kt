package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BidiControlsTest {

    /** Unicode's whole Bidi_Control property, ALM included. */
    private val all = listOf(
        '؜', '‎', '‏',
        '‪', '‫', '‬', '‭', '‮',
        '⁦', '⁧', '⁨', '⁩',
    )

    @Test
    fun `every bidi control is marked and stripped, and nothing else`() {
        for (c in all) {
            assertEquals("a�b", BidiControls.marked("a${c}b"))
            assertEquals("ab", BidiControls.stripped("a${c}b"))
        }
        // ZWJ / ZWNJ stay: emoji names need them.
        val emoji = "👨‍👩.eth a‌b"
        assertSame(emoji, BidiControls.marked(emoji))
        assertSame(emoji, BidiControls.stripped(emoji))
        // Marking keeps the length, so an edit field's offsets map 1:1.
        assertEquals(5, BidiControls.marked("‮abc⁩").length)
    }

    @Test
    fun `the refusal text strips the same list, ALM included`() {
        val text = all.joinToString("x")
        assertEquals(
            "x".repeat(all.size - 1),
            baby.freedom.mobile.ens.EnsNormalize.cleanMessage(text),
        )
    }

    @Test
    fun `the address field copy is marked, a clean one goes as it is`() {
        assertEquals("ens://�moc.lapyap.eth", markedClipText("ens://\u202Emoc.lapyap.eth"))
        assertEquals("a�b", markedClipText(StringBuilder("a\u200Fb")))
        assertEquals(null, markedClipText("https://example.com/"))
        assertEquals(null, markedClipText(null))
    }
}
