package baby.freedom.mobile.browser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFieldTextTest {

    private val max = AddressFieldText.MAX_CHARS

    /** The #488 address: a page's own navigation to just under 2 MiB. */
    private val huge = "http://127.0.0.1:8700/x.html#" + "a".repeat(2_097_100)

    @Test
    fun `an ordinary address is shown as it is`() {
        val url = "https://example.com/a/b?c=d#e"
        assertSame(url, AddressFieldText.shown(url))
        val atLimit = "https://example.com/#" + "a".repeat(max - 21)
        assertEquals(max, atLimit.length)
        assertEquals(atLimit, AddressFieldText.shown(atLimit))
    }

    @Test
    fun `a 2 MB address is shown shortened to the bound, with an ellipsis`() {
        val shown = AddressFieldText.shown(huge)
        assertEquals(max, shown.length)
        assertTrue(shown.endsWith("…"))
        assertEquals(huge.substring(0, max - 1), shown.dropLast(1))
        // One past the limit is shortened too.
        val over = "a".repeat(max + 1)
        assertEquals("a".repeat(max - 1) + "…", AddressFieldText.shown(over))
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        // An emoji straddling the cut point is dropped whole.
        val url = "a".repeat(max - 2) + "😀" + "tail"
        val shown = AddressFieldText.shown(url)
        assertEquals("a".repeat(max - 2) + "…", shown)
        assertTrue(shown.none { Character.isHighSurrogate(it) })
    }

    @Test
    fun `Go on the unedited field submits the whole address`() {
        assertEquals(huge, AddressFieldText.submitted(AddressFieldText.shown(huge), huge))
    }

    @Test
    fun `Go after an edit submits the edit`() {
        val edited = AddressFieldText.shown(huge).dropLast(1) + "b"
        assertEquals(edited, AddressFieldText.submitted(edited, huge))
        assertEquals("swarm.eth", AddressFieldText.submitted("swarm.eth", huge))
    }

    @Test
    fun `an address ending in an ellipsis of its own is not swapped`() {
        val url = "https://example.com/…"
        assertEquals(url, AddressFieldText.submitted(url, url))
        assertEquals("typed", AddressFieldText.submitted("typed", url))
    }

    @Test
    fun `an edit within the bound passes through untouched`() {
        val v = TextFieldValue("example.com", TextRange(3))
        assertSame(v, AddressFieldText.capped(v))
    }

    @Test
    fun `a 2 MB paste is cut to the bound with its selection clamped`() {
        val pasted = TextFieldValue(huge, TextRange(huge.length), TextRange(0, huge.length))
        val capped = AddressFieldText.capped(pasted)
        assertEquals(max, capped.text.length)
        assertEquals(huge.substring(0, max), capped.text)
        assertEquals(TextRange(max), capped.selection)
        assertEquals(TextRange(0, max), capped.composition)
    }
}
