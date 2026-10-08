package baby.freedom.mobile.browser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a 2 MB paste is shortened to the bound with an ellipsis, cursor at its end`() {
        val pasted = TextFieldValue(huge, TextRange(huge.length), TextRange(0, huge.length))
        val capped = AddressFieldText.capped(pasted)
        assertEquals(max, capped.text.length)
        assertEquals(AddressFieldText.shown(huge), capped.text)
        assertTrue(capped.text.endsWith("…"))
        assertEquals(TextRange(max), capped.selection)
        assertNull(capped.composition)
    }

    @Test
    fun `Go on a shortened paste or fill submits the whole pasted text`() {
        // The caller keeps the edit's whole text as what the field stands for.
        val capped = AddressFieldText.capped(TextFieldValue(huge, TextRange(huge.length)))
        assertEquals(huge, AddressFieldText.submitted(capped.text, huge))
        // Edited after the paste, Go submits the edit.
        val edited = capped.text.dropLast(1)
        assertEquals(edited, AddressFieldText.submitted(edited, huge))
    }

    @Test
    fun `a keystroke at or after a shortened field's ellipsis is dropped, not submitted`() {
        val field = AddressFieldText.shown(huge)
        // Typed at the end, after the ellipsis: cut straight back, so dropped.
        assertTrue(AddressFieldText.swallowed(field, field + "x"))
        // Typed just before the ellipsis: cut back the same way.
        val beforeEllipsis = field.dropLast(1) + "x…"
        assertTrue(AddressFieldText.swallowed(field, beforeEllipsis))
        // A paste after the ellipsis too.
        assertTrue(AddressFieldText.swallowed(field, field + "https://other.example/"))
        // Dropped, the field still stands for the whole address, so Go still
        // submits it rather than the cut, a literal ellipsis and the "x".
        assertEquals(huge, AddressFieldText.submitted(field, huge))
    }

    @Test
    fun `edits that change what the field shows are not dropped`() {
        val field = AddressFieldText.shown(huge)
        // Typing earlier in the shortened text changes what it shows.
        val mid = field.substring(0, 10) + "x" + field.substring(10)
        assertTrue(!AddressFieldText.swallowed(field, mid))
        // Deleting the ellipsis, or any shrink, stays within the bound.
        assertTrue(!AddressFieldText.swallowed(field, field.dropLast(1)))
        // A whole-field paste with the same head but no ellipsis of its own
        // stands for itself, even though the field looks the same.
        val samehead = field.dropLast(1) + "b".repeat(100)
        assertEquals(field, AddressFieldText.shown(samehead))
        assertTrue(!AddressFieldText.swallowed(field, samehead))
        // A field that isn't shortened never drops an edit: typing at the
        // bound of a full-length, unshortened field is cut like a paste.
        val full = "a".repeat(max)
        assertTrue(!AddressFieldText.swallowed(full, full + "…x"))
        // Nor does an ordinary field.
        assertTrue(!AddressFieldText.swallowed("example.com", "example.com…"))
    }

    @Test
    fun `the Go to address row for a shortened field submits the whole text`() {
        val goRow = addressActions(AddressFieldText.shown(huge), "https://s.example/?q=%s")
            .filterIsInstance<AddressAction.Go>().single().submitText
        assertEquals(huge, AddressFieldText.picked(goRow, huge))
        // Leading spaces the row trims are trimmed from the whole text too.
        assertEquals(huge, AddressFieldText.picked(AddressFieldText.shown("  $huge").trim(), "  $huge"))
        // Any other row is submitted as it is.
        assertEquals("https://h.example/", AddressFieldText.picked("https://h.example/", huge))
        // A short query is never swapped.
        assertEquals("example.com", AddressFieldText.picked("example.com", "example.com "))
    }

    @Test
    fun `a row is laid out with at most its head, ellipsis marking the cut`() {
        val row = AddressFieldText.row(huge)
        assertEquals(AddressFieldText.MAX_ROW_CHARS, row.length)
        assertEquals(huge.take(AddressFieldText.MAX_ROW_CHARS - 1) + "…", row)
        // Text within the bound is left as it is.
        val atBound = "a".repeat(AddressFieldText.MAX_ROW_CHARS)
        assertEquals(atBound, AddressFieldText.row(atBound))
        assertEquals("", AddressFieldText.row(""))
        // The cut never splits a surrogate pair.
        val emoji = "a".repeat(AddressFieldText.MAX_ROW_CHARS - 2) + "😀".repeat(10)
        val cut = AddressFieldText.row(emoji)
        assertEquals(false, Character.isHighSurrogate(cut[cut.length - 2]))
    }
}
