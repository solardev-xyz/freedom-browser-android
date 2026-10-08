package baby.freedom.mobile.browser

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #482: an icon is decoded once, however many fresh arrays it arrives in. */
class FaviconDecodeCacheTest {
    @Test
    fun `the same bytes in a new array decode once`() {
        var decodes = 0
        val cache = DecodeCache(maxSize = 4) { data -> decodes++; data.toList() }
        val first = cache.get(byteArrayOf(1, 2))
        val second = cache.get(byteArrayOf(1, 2))
        assertSame(first, second)
        assertEquals(1, decodes)
    }

    @Test
    fun `the least recently shown icon goes first`() {
        var decodes = 0
        val cache = DecodeCache(maxSize = 2) { data -> decodes++; data.toList() }
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
        val cache = DecodeCache<Any>(maxSize = 4) { decodes++; null }
        assertNull(cache.get(byteArrayOf(9)))
        assertNull(cache.get(byteArrayOf(9)))
        assertEquals(2, decodes)
    }

    @Test
    fun `the cache is budgeted by size, not count (#516 R1-F1)`() {
        var decodes = 0
        // Each "image" weighs as many units as the bytes it came from.
        val cache = DecodeCache(maxSize = 10, sizeOf = { it: List<Byte> -> it.size }) { data ->
            decodes++; data.toList()
        }
        cache.get(ByteArray(4) { 1 })
        cache.get(ByteArray(4) { 2 })
        cache.get(ByteArray(4) { 3 }) // 12 > 10: the first goes
        assertEquals(3, decodes)
        cache.get(ByteArray(4) { 2 })
        cache.get(ByteArray(4) { 3 })
        assertEquals(3, decodes)
        cache.get(ByteArray(4) { 1 })
        assertEquals(4, decodes)
    }

    @Test
    fun `an image bigger than the whole budget is shown but not kept`() {
        var decodes = 0
        val cache = DecodeCache(maxSize = 10, sizeOf = { it: List<Byte> -> it.size }) { data ->
            decodes++; data.toList()
        }
        cache.get(ByteArray(2) { 7 })
        assertEquals(11, cache.get(ByteArray(11))?.size)
        assertEquals(11, cache.get(ByteArray(11))?.size)
        assertEquals(3, decodes)
        cache.get(ByteArray(2) { 7 }) // the small one was not pushed out
        assertEquals(3, decodes)
    }

    @Test
    fun `clear drops everything`() {
        var decodes = 0
        val cache = DecodeCache(maxSize = 4) { data -> decodes++; data.toList() }
        cache.get(byteArrayOf(1))
        cache.clear()
        cache.get(byteArrayOf(1))
        assertEquals(2, decodes)
    }

    @Test
    fun `decoded icons go once the UI is hidden or memory runs low`() {
        @Suppress("DEPRECATION")
        val clears = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        for (level in clears) assertTrue(level.toString(), FaviconImages.clearsOn(level))
        @Suppress("DEPRECATION")
        assertFalse(FaviconImages.clearsOn(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }
}
