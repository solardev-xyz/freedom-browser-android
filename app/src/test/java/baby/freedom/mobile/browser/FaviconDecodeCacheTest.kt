package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #482: an icon is decoded once, however many fresh arrays it arrives in. */
class FaviconDecodeCacheTest {
    @Test
    fun `the same bytes in a new array decode once`() {
        var decodes = 0
        val cache = DecodeCache(maxEntries = 4) { data -> decodes++; data.toList() }
        val first = cache.get(byteArrayOf(1, 2))
        val second = cache.get(byteArrayOf(1, 2))
        assertSame(first, second)
        assertEquals(1, decodes)
    }

    @Test
    fun `the least recently shown icon goes first`() {
        var decodes = 0
        val cache = DecodeCache(maxEntries = 2) { data -> decodes++; data.toList() }
        cache.get(byteArrayOf(1))
        cache.get(byteArrayOf(2))
        cache.get(byteArrayOf(1)) // 1 is now the most recent
        cache.get(byteArrayOf(3)) // evicts 2
        assertEquals(3, decodes)
        cache.get(byteArrayOf(1))
        assertEquals(3, decodes)
        cache.get(byteArrayOf(2))
        assertEquals(4, decodes)
    }

    @Test
    fun `a failed decode is not kept`() {
        var decodes = 0
        val cache = DecodeCache<Any>(maxEntries = 4) { decodes++; null }
        assertNull(cache.get(byteArrayOf(9)))
        assertNull(cache.get(byteArrayOf(9)))
        assertEquals(2, decodes)
    }
}
