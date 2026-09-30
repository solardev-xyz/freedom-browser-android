package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An edited bookmark's address, read like the address bar reads it (#264). */
class BookmarkAddressTest {

    @Before
    fun realIcu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private fun ok(input: String) = (bookmarkAddress(input) as BookmarkAddress.Ok).url

    @Test
    fun `dweb forms stay dweb addresses, in the spelling the page reports`() {
        val hash = "ab".repeat(32)
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        val rid = "z3gqcJUoA1n9HaHKufZs5FCSGazv5"
        for ((typed, saved) in listOf(
            "vitalik.eth" to "vitalik.eth",
            "vitalik.eth/docs" to "vitalik.eth/docs",
            "vitalik.eth/" to "vitalik.eth",
            "ens://vitalik.eth" to "vitalik.eth",
            "ENS://Vitalik.ETH/" to "vitalik.eth",
            "bzz://vitalik.eth" to "bzz://vitalik.eth",
            "bzz://" + hash.uppercase() + "/index.html" to "bzz://$hash/index.html",
            "ipfs://$cid" to "ipfs://$cid",
            "ipns://ipfs.tech" to "ipns://ipfs.tech",
            "rad://$rid" to "rad://$rid",
            "rad:$rid" to "rad://$rid",
        )) {
            assertEquals(typed, saved, ok(" $typed "))
        }
    }

    @Test
    fun `ordinary addresses get what Enter adds`() {
        assertEquals("https://example.com/", ok("example.com"))
        assertEquals("https://example.com/a?b=1", ok("  example.com/a?b=1 "))
        assertEquals("http://localhost:8080/x", ok("localhost:8080/x"))
        assertEquals("http://localhost:8730/", ok("localhost:8730"))
        assertEquals("http://example.onion/", ok("example.onion"))
        assertEquals("https://10.0.0.1:8080/", ok("10.0.0.1:8080"))
        assertEquals("http://example.com/", ok("http://example.com/"))
        assertEquals("https://example.com/?q=1", ok("HTTPS://Example.COM:443?q=1"))
        assertEquals("https://xn--bcher-kva.de/", ok("bücher.de"))
    }

    @Test
    fun `search terms, blanks and internal pages are refused`() {
        for (s in listOf(
            "", "   ", "swarm storage", "localhost",
            "about:blank", "javascript:alert(1)", "JavaScript:alert(1)",
            "data:text/html,hi", "blob:https://example.com/x",
        )) {
            assertTrue(s, bookmarkAddress(s) is BookmarkAddress.Invalid)
        }
    }

    @Test
    fun `names are one trimmed line`() {
        assertEquals("My site", bookmarkTitle("  My site "))
        assertEquals("a b c", bookmarkTitle("a\nb \tc"))
        assertEquals("", bookmarkTitle(" \n "))
        assertEquals("👨‍👩‍👧 family", bookmarkTitle("👨‍👩‍👧 family"))
    }

    @Test
    fun `move actions only go somewhere`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf("Move down" to 2L, "Move to bottom" to 4L), bookmarkMoves(ids, 0))
        assertEquals(
            listOf("Move up" to null, "Move down" to 3L, "Move to bottom" to 4L),
            bookmarkMoves(ids, 1),
        )
        assertEquals(
            listOf("Move up" to 1L, "Move to top" to null, "Move down" to 4L),
            bookmarkMoves(ids, 2),
        )
        assertEquals(listOf("Move up" to 2L, "Move to top" to null), bookmarkMoves(ids, 3))
        assertEquals(emptyList<Pair<String, Long?>>(), bookmarkMoves(listOf(1L), 0))
        assertEquals(emptyList<Pair<String, Long?>>(), bookmarkMoves(ids, 7))
    }
}
