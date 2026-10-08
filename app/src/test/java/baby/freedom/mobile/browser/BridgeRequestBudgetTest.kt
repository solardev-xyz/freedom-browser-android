package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRequestBudgetTest {
    private fun budget() = BridgeRequestBudget(maxRequests = 4, smallChars = 1000, largeAbove = 100)

    @Test
    fun `a tab gets at most maxRequests in flight, and one back for each answered`() {
        val b = budget()
        val tickets = (1..4).map { b.reserve(1, 10)!! }
        assertNull("the fifth is refused before it's parsed", b.reserve(1, 10))
        assertNotNull("another tab has its own share", b.reserve(2, 10))
        tickets[0].release()
        assertNotNull(b.reserve(1, 10))
    }

    @Test
    fun `small requests share the tab's characters`() {
        val b = BridgeRequestBudget(maxRequests = 100, smallChars = 1000, largeAbove = 100)
        val held = (1..10).map { b.reserve(1, 100)!! }
        assertNull("1100 characters is over 1000", b.reserve(1, 100))
        assertNull("even one more character is", b.reserve(1, 1))
        held[3].release()
        assertNotNull(b.reserve(1, 100))
    }

    @Test
    fun `one large request at a time per tab, beside the small ones`() {
        val b = budget()
        val small = b.reserve(1, 100)!!
        val large = b.reserve(1, 72_000_000)!!
        assertNull("a second upload waits for the first", b.reserve(1, 101))
        assertNotNull("small requests still go", b.reserve(1, 50))
        assertNotNull("another tab may upload", b.reserve(2, 72_000_000))
        large.release()
        assertNotNull(b.reserve(1, 101))
        small.release()
    }

    @Test
    fun `releasing twice gives back one share`() {
        val b = budget()
        val t = b.reserve(1, 10)!!
        b.reserve(1, 10)!!
        t.release()
        t.release()
        assertEquals(1, b.inFlight(1))
    }

    @Test
    fun `a tab with nothing in flight is forgotten`() {
        val b = budget()
        val t = b.reserve(7, 500)!!
        t.release()
        assertEquals(0, b.inFlight(7))
        assertTrue((1..4).all { b.reserve(7, 10) != null })
    }

    @Test
    fun `a loop of requests is held to the cap however long it runs`() {
        // #459's scenario: a page that isn't connected loops requests that wait on its prompt.
        val b = BridgeRequestBudget(maxRequests = 64, smallChars = 1024 * 1024, largeAbove = 64 * 1024)
        val admitted = (1..10_000).count { b.reserve(1, 1024 * 1024) != null }
        assertEquals("only one megabyte-sized request is ever held", 1, admitted)
        val small = (1..10_000).count { b.reserve(1, 100) != null }
        assertEquals(63, small)
    }

    @Test
    fun `tooComplexMessage names the caps only for strict JSON over them`() {
        assertNull(tooComplexMessage("""{"id":1,"params":[1,2]}""", 10, 10))
        assertNull("lenient syntax is just malformed", tooComplexMessage("""{'id':1}""", 10, 10))
        assertEquals(
            "Request has more than 2 values or 10 arrays and objects",
            tooComplexMessage("""{"id":1,"params":[1,2,3]}""", 2, 10),
        )
    }
}
