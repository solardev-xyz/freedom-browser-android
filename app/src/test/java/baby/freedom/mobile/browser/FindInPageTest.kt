package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Find in page (#83): the count the bar shows and the per-tab session. */
class FindInPageTest {

    @Test
    fun `listener ordinal is 0-based and becomes a 1-based count`() {
        val r = findResultFrom(activeMatchOrdinal = 2, numberOfMatches = 12, isDoneCounting = true)
        assertEquals(FindResult(active = 3, matches = 12, final = true), r)
        assertEquals("3/12", findCountLabel(r))
        assertTrue(findNavigationEnabled(r))
    }

    @Test
    fun `no matches reads 0 of 0 and disables stepping`() {
        // The listener reports a garbage ordinal (-1 or 0) with no matches.
        val r = findResultFrom(activeMatchOrdinal = -1, numberOfMatches = 0, isDoneCounting = true)
        assertEquals("0/0", findCountLabel(r))
        assertFalse(findNavigationEnabled(r))
    }

    @Test
    fun `no result shows no count`() {
        assertEquals("", findCountLabel(null))
        assertFalse(findNavigationEnabled(null))
    }

    @Test
    fun `interim report with no current match reads 0 of N, not 1 of N`() {
        val r = findResultFrom(activeMatchOrdinal = -1, numberOfMatches = 5, isDoneCounting = false)
        assertEquals(0, r.active)
        assertEquals("0/5", findCountLabel(r))
        // There are matches to walk to, so stepping stays available.
        assertTrue(findNavigationEnabled(r))
        // An ordinal past the end marks no known match either.
        assertEquals(0, findResultFrom(9, 5, false).active)
        // Boundary: the last valid 0-based ordinal is the last match.
        assertEquals(5, findResultFrom(4, 5, true).active)
    }

    @Test
    fun `results land only while a session is live`() {
        val find = FindInPageState()
        val r = FindResult(1, 4, true)

        find.onResult(r)
        assertNull("bar closed", find.result)

        find.show()
        find.onResult(r)
        assertNull("nothing typed", find.result)

        find.startSearch("needle")
        find.onResult(r)
        assertEquals(r, find.result)
    }

    @Test
    fun `a new query blanks the previous count until the webview reports`() {
        val find = FindInPageState()
        find.show()
        find.startSearch("needle")
        find.onResult(FindResult(2, 7, true))
        find.startSearch("needles")
        assertNull(find.result)
    }

    @Test
    fun `clearing the query drops a late report for the old one`() {
        val find = FindInPageState()
        find.show()
        find.startSearch("needle")
        find.startSearch("")
        find.onResult(FindResult(1, 3, true))
        assertNull(find.result)
    }

    @Test
    fun `navigation closes the bar, clears the count and keeps the query for reopening`() {
        val find = FindInPageState()
        find.show()
        find.startSearch("needle")
        find.onResult(FindResult(1, 3, true))

        find.onDocumentCommitted()

        assertFalse(find.open)
        assertNull(find.result)
        assertEquals("needle", find.query)
        // A report from the old document arriving after the commit is dropped.
        find.onResult(FindResult(1, 3, true))
        assertNull(find.result)
    }

    @Test
    fun `sessions are per tab`() {
        val a = BrowserState(id = 1)
        val b = BrowserState(id = 2)
        a.find.show()
        a.find.startSearch("needle")
        assertTrue(a.find.open)
        assertFalse(b.find.open)
        assertEquals("", b.find.query)
    }

    @Test
    fun `search key runs a prefilled query once, then steps - even before the first report`() {
        val find = FindInPageState()
        find.show()
        find.query = "needle" // prefilled from last session
        assertFalse(find.searching)
        assertEquals(FindSubmit.Search, findSubmitAction(find.searching, "needle", find.result))

        find.startSearch("needle")
        // Sent, Chromium hasn't reported: result is null but the search is
        // live, so Enter must step rather than restart at match 1 (R2-F1).
        assertTrue(find.searching)
        assertNull(find.result)
        assertEquals(FindSubmit.Step, findSubmitAction(find.searching, "needle", find.result))

        find.onResult(FindResult(1, 4, true))
        assertEquals(FindSubmit.Step, findSubmitAction(find.searching, "needle", find.result))
        // A finished search with nothing found has nowhere to go.
        assertEquals(FindSubmit.None, findSubmitAction(true, "needle", FindResult(0, 0, true)))
        assertEquals(FindSubmit.None, findSubmitAction(false, "", null))

        find.close()
        assertFalse(find.searching)
        assertEquals(FindSubmit.Search, findSubmitAction(find.searching, "needle", find.result))
    }

    @Test
    fun `clearing the field ends the live search`() {
        val find = FindInPageState()
        find.show()
        find.startSearch("needle")
        find.startSearch("")
        assertFalse(find.searching)
    }

    @Test
    fun `count is announced in words and only once counting is final`() {
        assertEquals("", findCountSpoken(null))
        assertEquals("No matches", findCountSpoken(FindResult(0, 0, true)))
        assertEquals("5 matches", findCountSpoken(FindResult(0, 5, false)))
        assertEquals("Match 3 of 12", findCountSpoken(FindResult(3, 12, true)))

        assertFalse(findCountLiveRegion(null))
        assertFalse("interim reports stay quiet", findCountLiveRegion(FindResult(0, 40, false)))
        assertTrue(findCountLiveRegion(FindResult(3, 120, true)))
    }
}
