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
        // Not a key the page can tell apart: both open ipns://k51abc.
        same("ipns://k51Abc", "ipns://k51abc")
        differ("ipfs://bafyabc", "ipfs://bafyabd")
    }

    /** The page's own address for each typed dweb address (#296 R4-F1). */
    private val dwebPages = listOf(
        "ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG/" to
            "ipfs://k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr",
        "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi/a#x" to
            "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi/a",
        "ipns://Docs.IPFS.tech/x?q=1#frag" to "ipns://docs.ipfs.tech/x?q=1",
        "bzz://" + "AB".repeat(32) + "/p#f" to "bzz://" + "ab".repeat(32) + "/p",
        // A name's page keeps its fragment (#296 R5-M1): it is shown
        // through the name's override, not the virtual origin.
        "vitalik.eth/#section" to "vitalik.eth/#section",
        "vitalik.eth#section" to "vitalik.eth/#section",
        "ens://Vitalik.eth/a#b c" to "vitalik.eth/a#b%20c",
        "ipfs://vitalik.eth/p#b" to "ipfs://vitalik.eth/p#b",
    )

    @Test
    fun `an edited dweb address is saved the way its page reports it`() {
        for ((typed, page) in dwebPages) {
            assertEquals(typed, page, BookmarkUrls.canonical(typed))
            same(typed, page)
        }
        val qm = "ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        same(qm, VirtualOrigin.displayUrlFor(VirtualOrigin.toVirtualUrl(qm)!!)!!)
        val peer = "ipns://12D3KooWD3eckifWpRn9wQpMG9R9hX3sD158z7EqHWmweQAJU5SA/"
        val shown = VirtualOrigin.displayUrlFor(VirtualOrigin.toVirtualUrl(peer)!!)!!
        assertEquals(true, shown.startsWith("ipns://k51"))
        same(peer, shown)
        assertEquals(shown, BookmarkUrls.canonical(peer))
    }

    @Test
    fun `the key of the saved form is the key of what was typed`() {
        for (s in listOf(
            "http://localhost:8730", "Example.com", "ENS://X.eth/p", "ipfs://x.eth", "bzz://" + "CD".repeat(32),
            "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5?x", "ftp://Host/a", "mailto:A@b.c",
            "http://h/x/../Straße/?q=ß#ß", "vitalik.eth/a b/../c", "http://h/a/%2e%2E/b",
        )) {
            assertEquals(s, BookmarkUrls.key(s), BookmarkUrls.key(BookmarkUrls.canonical(s)))
        }
    }

    /** Chromium 153's `new URL(x).href` for each, run in headless Chromium (#296 R3-F1). */
    private val chromium = listOf(
        "http://h/Straße/" to "http://h/Stra%C3%9Fe/",
        "http://h/x/../Stra%C3%9Fe/" to "http://h/Stra%C3%9Fe/",
        "http://h/a b\"<>`{}|^\\c" to "http://h/a%20b%22%3C%3E%60%7B%7D%7C%5E/c",
        "http://h/%41%2e%7e%c3%9f/%2E%2e/x" to "http://h/x",
        "http://h/a/./b/.%2E/c/%2e" to "http://h/a/c/",
        "http://h/?q=a b\"'<>`{}|^ß#f a\"<>`{}|^ß" to
            "http://h/?q=a%20b%22%27%3C%3E`{}|^%C3%9F#f%20a%22%3C%3E%60{}|^%C3%9F",
        "http://h/a%2fb%zz%" to "http://h/a%2fb%zz%",
        "http://h/a\tb\nc" to "http://h/abc",
        "http://h/\u0001\u007f" to "http://h/%01%7F",
        "http://h/.." to "http://h/",
        "http://h/a/.." to "http://h/",
        "http://h/a/." to "http://h/a/",
        "http://h/p?%41%c3" to "http://h/p?%41%c3",
        "http://h/p#%41" to "http://h/p#%41",
        "http://h/[]@!$&()*+,;=:~" to "http://h/[]@!$&()*+,;=:~",
        "http://h/p?[]@!$&()*+,;=:~/?#[]@!$&()*+,;=:~/?#" to "http://h/p?[]@!$&()*+,;=:~/?#[]@!$&()*+,;=:~/?#",
        "http://h/😀" to "http://h/%F0%9F%98%80",
        "http://h/p?\ud800" to "http://h/p?%EF%BF%BD",
        "http://h//a//b" to "http://h//a//b",
        "http://h/%2E" to "http://h/",
        "http://h/a/%2e%2E/b" to "http://h/b",
        "http://h/.%2e" to "http://h/",
        "http://h/?" to "http://h/?",
        "http://h/#" to "http://h/#",
        "http://h/p%" to "http://h/p%",
        "http://h/%5B%3a%40" to "http://h/%5B%3a%40",
        "http://h/p%41%7e%2D" to "http://h/p%41%7e%2D",
        "http://h/p?a\u007fb\\c" to "http://h/p?a%7Fb\\c",
        "http://h/p#a\u007fb\\c" to "http://h/p#a%7Fb\\c",
        "http://h/a%2eb/x/%2e%2e%2f" to "http://h/a%2eb/x/%2e%2e%2f",
        "http://h/a/..%2f" to "http://h/a/..%2f",
        "http://h\\a\\b" to "http://h/a/b",
        "http://h/a/b/../../../c" to "http://h/c",
        "http://h/a/ ./b" to "http://h/a/%20./b",
        "http://h/a/.. /b" to "http://h/a/..%20/b",
        "http://h/p?a#b#c" to "http://h/p?a#b#c",
        "http://h/p#a?b" to "http://h/p#a?b",
        "http://h/%zz/../x" to "http://h/x",
    )

    @Test
    fun `paths, queries and fragments are saved the way Chromium serialises them`() {
        for ((typed, page) in chromium) {
            assertEquals(typed, page, BookmarkUrls.canonical(typed))
            same(typed, page)
        }
    }

    @Test
    fun `an edited path matches the page it opens`() {
        same("http://localhost:8731/Stra%C3%9Fe/", "localhost:8731/x/../Stra%C3%9Fe/".let { "http://$it" })
        same("https://de.wikipedia.org/wiki/Stra%C3%9Fe", "https://de.wikipedia.org/wiki/Straße")
        same("vitalik.eth/Stra%C3%9Fe", "vitalik.eth/a/../Straße")
        same("ipfs://vitalik.eth/a%20b", "ens://vitalik.eth/a b")
        same("vitalik.eth", "vitalik.eth/x/..")
        differ("https://example.com/%41", "https://example.com/A")
    }

    /**
     * A rad page reports `RadUrl.displayUrlFor` of the WebView's own
     * serialisation of its virtual URL (#296 R5-F1).
     */
    @Test
    fun `an edited rad address is saved the way its page reports it`() {
        val rid = "z3gqcJUoA1n9HaHKufZs5FCSGazv5"
        fun page(typed: String): String {
            val loaded = BookmarkUrls.pathQueryFragment(RadUrl.pathOf(RadUrl.toVirtualUrl(typed)!!)!!)
            return RadUrl.displayUrlFor(RadUrl.ORIGIN + loaded)!!
        }
        for ((typed, shown) in listOf(
            "rad://$rid/tree/a b" to "rad://$rid/tree/a%20b",
            "rad://$rid/tree/Straße" to "rad://$rid/tree/Stra%C3%9Fe",
            "rad://$rid/tree/x/../y" to "rad://$rid/tree/y",
            "rad://$rid/tree/x/%2e%2E/y" to "rad://$rid/tree/y",
            "rad:$rid?q=ä" to "rad://$rid/?q=%C3%A4",
            "rad://$rid/tree#x y" to "rad://$rid/tree#x%20y",
            "rad://$rid/" to "rad://$rid",
        )) {
            assertEquals(typed, shown, BookmarkUrls.canonical(typed))
            assertEquals(typed, shown, page(typed))
            same(typed, shown)
        }
        differ("rad://$rid/tree/a b", "rad://$rid/tree/a")
    }

    @Test
    fun `canonical is a fixed point, and keeps the key, for any input`() {
        for (s in listOf("ipfs:// /", "ens:// ..eth", "bzz:// ?x", "ipns://\u00a0/", " x.eth /")) {
            val c = BookmarkUrls.canonical(s)
            assertEquals(s, c, BookmarkUrls.canonical(c))
            assertEquals(s, BookmarkUrls.key(s), BookmarkUrls.key(c))
        }
        val parts = listOf(
            "ipfs://", "ipns://", "bzz://", "ens://", "rad:", "rad://", "http://", "HTTPS://", "z3gqcJUoA1n9HaHKufZs5FCSGazv5",
            "x.eth", ".eth", "..", ".", "/", "?", "#", " ", "\t", "%2e", "%", "a", "ß", "ä", "A", "-", ":", "@", "[", "]",
            "\\", "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", "ab".repeat(32), "\u00a0", "😀",
        )
        val rnd = java.util.Random(296)
        repeat(30_000) {
            val s = buildString { repeat(1 + rnd.nextInt(7)) { append(parts[rnd.nextInt(parts.size)]) } }
            val c = BookmarkUrls.canonical(s)
            assertEquals(s, c, BookmarkUrls.canonical(c))
            assertEquals(s, BookmarkUrls.key(s), BookmarkUrls.key(c))
        }
    }
}
