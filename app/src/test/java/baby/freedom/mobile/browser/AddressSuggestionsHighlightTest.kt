package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSuggestionsHighlightTest {

    @Test
    fun `matches are found case-insensitively`() {
        assertEquals(listOf(0 to 5, 12 to 17), highlightRanges("Login again login", "LOGIN"))
        assertEquals(listOf(4 to 11), highlightRanges("www.example.com", "example"))
        assertEquals(emptyList<Pair<Int, Int>>(), highlightRanges("abc", ""))
        assertEquals(emptyList<Pair<Int, Int>>(), highlightRanges("ab", "abc"))
    }

    @Test
    fun `a title that grows when lower-cased is highlighted inside itself`() {
        // `İ`.lowercase() is two chars: offsets found in the lower-cased
        // title ran past the end of the real one and crashed the app.
        val title = "İİİİİİ Loginpage"
        val ranges = highlightRanges(title, "loginpage")
        assertEquals(listOf(7 to 16), ranges)
        assertTrue(ranges.all { (s, e) -> s >= 0 && e <= title.length })
        assertEquals("Loginpage", title.substring(7, 16))
    }

    @Test
    fun `action rows show the typed text with its bidi controls marked`() {
        // The field's buffer keeps U+202E (Go submits it as it is); the
        // rows drawn from it must not let it reorder what they show.
        val edited = "ens://\u202Emoc.lapyap.et"
        val go = AddressAction.Go(edited, AddressInput.Kind.Url)
        val search = AddressAction.Search(edited, "DuckDuckGo", "https://duckduckgo.com/?q=x")
        assertEquals("ens://\uFFFDmoc.lapyap.et", go.shownTitle)
        assertEquals("ens://\uFFFDmoc.lapyap.et", search.shownTitle)
        assertEquals(edited, go.submitText)
        assertEquals("example.com", AddressAction.Go("example.com", AddressInput.Kind.Url).shownTitle)
    }
}
