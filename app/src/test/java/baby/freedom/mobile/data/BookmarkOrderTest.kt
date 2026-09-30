package baby.freedom.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moving a bookmark in the user's order (#264). */
class BookmarkOrderTest {
    private val ids = listOf(1L, 2L, 3L, 4L)

    @Test
    fun `moves after a neighbour, or first`() {
        assertEquals(listOf(2L, 3L, 1L, 4L), movedAfter(ids, 1, 3))
        assertEquals(listOf(4L, 1L, 2L, 3L), movedAfter(ids, 4, null))
        assertEquals(listOf(1L, 2L, 4L, 3L), movedAfter(ids, 3, 4))
        assertEquals(listOf(2L, 3L, 4L, 1L), movedAfter(ids, 1, 4))
    }

    @Test
    fun `nothing to do is null`() {
        // Already there.
        assertNull(movedAfter(ids, 1, null))
        assertNull(movedAfter(ids, 3, 2))
        // After itself.
        assertNull(movedAfter(ids, 2, 2))
        // Removed meanwhile: the moved one, or the one it goes after.
        assertNull(movedAfter(ids, 9, null))
        assertNull(movedAfter(ids, 1, 9))
    }

    @Test
    fun `a bookmark added meanwhile doesn't shift where a move lands`() {
        // The list was 1,2,3,4 when the user dropped 4 after 1; 5 was
        // added at the top in the meantime.
        assertEquals(listOf(5L, 1L, 4L, 2L, 3L), movedAfter(listOf(5L) + ids, 4, 1))
    }
}
