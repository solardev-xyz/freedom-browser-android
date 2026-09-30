package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * One key per page for bookmarks (#296 R1-F1): the star's saved address
 * (the page's own spelling) and an edited one (typed) must match.
 */
class BookmarkUrlsTest {

    @Before
    fun realIcu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private fun same(a: String, b: String) = assertEquals("$a ~ $b", BookmarkUrls.key(a), BookmarkUrls.key(b))
    private fun differ(a: String, b: String) =
        assertNotEquals("$a !~ $b", BookmarkUrls.key(a), BookmarkUrls.key(b))

    @Test
    fun `web addresses match the way the page reports them`() {
        same("http://localhost:8730/", "http://localhost:8730")
        same("https://example.com/", "HTTPS://EXAMPLE.com")
        same("https://example.com/", "https://example.com:443/")
        same("http://example.com/?q=1", "http://example.com?q=1")
        same("https://xn--bcher-kva.de/", "https://bücher.de")
        differ("https://example.com/", "http://example.com/")
        differ("https://example.com/A", "https://example.com/a")
        differ("https://example.com:8443/", "https://example.com/")
        differ("https://example.com/#x", "https://example.com/")
    }

    @Test
    fun `a name is one bookmark whatever transport it's shown under`() {
        same("vitalik.eth", "ens://vitalik.eth")
        same("vitalik.eth", "ipfs://vitalik.eth/")
        same("bzz://swarm.eth/docs", "swarm.eth/docs")
        same("vitalik.eth/?a=1", "vitalik.eth?a=1")
        differ("vitalik.eth", "vitalik.eth/docs")
        differ("vitalik.eth", "nick.eth")
    }

    @Test
    fun `content addresses ignore case only where it means nothing`() {
        val hash = "ab".repeat(32)
        same("bzz://$hash/", "BZZ://${hash.uppercase()}")
        same("rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5", "rad://z3gqcJUoA1n9HaHKufZs5FCSGazv5/")
        differ("ipns://k51Abc", "ipns://k51abc")
    }

    @Test
    fun `the key of the saved form is the key of what was typed`() {
        for (s in listOf(
            "http://localhost:8730", "Example.com", "ENS://X.eth/p", "ipfs://x.eth", "bzz://" + "CD".repeat(32),
            "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5?x", "ftp://Host/a", "mailto:A@b.c",
        )) {
            assertEquals(s, BookmarkUrls.key(s), BookmarkUrls.key(BookmarkUrls.canonical(s)))
        }
    }
}
