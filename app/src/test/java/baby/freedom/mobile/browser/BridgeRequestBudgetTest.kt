package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRequestBudgetTest {
    private fun budget() = BridgeRequestBudget(maxRequests = 4, smallChars = 1000, largeAbove = 100, largeChars = 1000)

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
        val b = BridgeRequestBudget(maxRequests = 100, smallChars = 1000, largeAbove = 100, largeChars = 1000)
        val held = (1..10).map { b.reserve(1, 100)!! }
        assertNull("1100 characters is over 1000", b.reserve(1, 100))
        assertNull("even one more character is", b.reserve(1, 1))
        held[3].release()
        assertNotNull(b.reserve(1, 100))
    }

    @Test
    fun `a lone large request is let in whatever its length, beside the small ones`() {
        val b = budget()
        val small = b.reserve(1, 100)!!
        val large = b.reserve(1, 72_000_000)!!
        assertNull("nothing more fits beside an upload over the large budget: refused, not queued", b.reserve(1, 101))
        assertNotNull("small requests still go", b.reserve(1, 50))
        assertNotNull("another tab may upload", b.reserve(2, 72_000_000))
        large.release()
        assertNotNull(b.reserve(1, 101))
        small.release()
    }

    @Test
    fun `parallel uploads share the large budget (R1-F1)`() {
        // Promise.all([publishData(photoA), publishData(photoB)]) with two ~1 MB images.
        val b = BridgeRequestBudget(maxRequests = 128, smallChars = 4L * 1024 * 1024, largeAbove = 1024 * 1024, largeChars = 24L * 1024 * 1024)
        val photo = 1_400_000
        val both = (1..2).map { b.reserve(1, photo) }
        assertTrue("both run side by side", both.all { it != null })
        val dozen = (1..15).count { b.reserve(1, photo) != null }
        assertEquals("17 fit in 24M characters", 15, dozen)
        assertNull("the 18th is over the budget", b.reserve(1, photo))
        both[0]!!.release()
        assertNotNull("and goes once one is answered", b.reserve(1, photo))
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
        val b = BridgeRequestBudget(maxRequests = 64, smallChars = 1024 * 1024, largeAbove = 64 * 1024, largeChars = 4L * 1024 * 1024)
        val admitted = (1..10_000).count { b.reserve(1, 1024 * 1024) != null }
        assertEquals("only four megabyte-sized requests are ever held", 4, admitted)
        val small = (1..10_000).count { b.reserve(1, 100) != null }
        assertEquals(60, small)
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
