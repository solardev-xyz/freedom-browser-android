package baby.freedom.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Delete browsing data reaches the favicon cache (#480). */
class FaviconForgetTest {
    private val hour = 60L * 60L * 1000L
    private val now = 1_000L * hour

    private val stamps = listOf(
        FaviconStamp("https://news.example", now - 10 * 60_000L),
        FaviconStamp("https://old.example", now - 48 * hour),
        FaviconStamp("ens://meinhard.eth", now - 5 * 60_000L),
        FaviconStamp("https://bookmarked-old.example", now - 72 * hour),
        FaviconStamp("https://unbookmarked.example", 0L),
    )
    private val kept = setOf("ens://meinhard.eth", "https://bookmarked-old.example")

    @Test
    fun `all time deletes every icon but bookmarked sites'`() {
        val forget = faviconsToForget(stamps, 0L, kept)
        assertEquals(
            listOf("https://news.example", "https://old.example", "https://unbookmarked.example"),
            forget.delete,
        )
        assertEquals(listOf("ens://meinhard.eth", "https://bookmarked-old.example"), forget.undate)
    }

    @Test
    fun `a range deletes only icons stored in it`() {
        val forget = faviconsToForget(stamps, now - hour, kept)
        assertEquals(listOf("https://news.example"), forget.delete)
        // The bookmarked one keeps its icon but no longer says it was seen in the last hour.
        assertEquals(listOf("ens://meinhard.eth"), forget.undate)
    }

    @Test
    fun `the range's start is inclusive, like history's`() {
        val at = now - hour
        val forget = faviconsToForget(listOf(FaviconStamp("https://edge.example", at)), at, emptySet())
        assertEquals(listOf("https://edge.example"), forget.delete)
    }

    @Test
    fun `a bookmarked icon already without a time is left alone`() {
        val forget = faviconsToForget(listOf(FaviconStamp("https://b.example", 0L)), 0L, setOf("https://b.example"))
        assertEquals(emptyList<String>(), forget.delete)
        assertEquals(emptyList<String>(), forget.undate)
    }

    @Test
    fun `nothing in the cache, nothing to do`() {
        val forget = faviconsToForget(emptyList(), 0L, kept)
        assertEquals(FaviconForget(emptyList(), emptyList()), forget)
    }
}
