package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDownloadSessionsTest {
    @Test
    fun `ids are negative and each session's span is its own`() {
        val s = PrivateDownloadSessions()
        val first = s.current()
        val a = s.allocate(first)!!
        val b = s.allocate(first)!!
        assertTrue(a < 0 && b < a)
        assertTrue(s.isLive(a) && s.isLive(b))

        val ended = s.end()
        assertTrue(a in ended && b in ended)
        assertFalse(s.isLive(a) || s.isLive(b))

        val c = s.allocate(s.current())!!
        assertTrue(s.isLive(c))
        assertFalse(c in ended)
        assertEquals(listOf(c), s.end().toList())
    }

    @Test
    fun `an offer from an ended session gets no id`() {
        val s = PrivateDownloadSessions()
        val offered = s.current()
        s.end()
        assertNull(s.allocate(offered))
        // …even once the next session is under way.
        val next = s.allocate(s.current())!!
        assertNull(s.allocate(offered))
        assertTrue(s.isLive(next))
    }

    @Test
    fun `ending a session that handed out nothing covers no ids`() {
        val s = PrivateDownloadSessions()
        assertTrue(s.end().isEmpty())
        val a = s.allocate(s.current())!!
        assertTrue(s.end().contains(a))
    }
}
