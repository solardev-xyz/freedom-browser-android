package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Base32
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Open without gateway" (#537): the gateway URL parsing desktop's
 * `src/main/gateway-offer.test.js` covers (the same cases, the same
 * CIDs — bee-js's `Reference.toCid` output), and one tab's offer and
 * "always" bookkeeping ([GatewayOfferTab]).
 */
class GatewayOfferTest {
    private val hex = "ab".repeat(32)

    // `new Reference('ab'.repeat(32)).toCid('manifest' | 'feed')` in bee-js.
    private val manifestCid = "bah5acgzavov2xk5lvov2xk5lvov2xk5lvov2xk5lvov2xk5lvov2xk5lvovq"
    private val feedCid = "bah5qcgzavov2xk5lvov2xk5lvov2xk5lvov2xk5lvov2xk5lvov2xk5lvovq"

    private val ipfsOk = EnsResult.Ok("vitalik.eth", "ipfs", "ipfs://bafy", "bafy", EnsTrust(verified = true))
    private val bzzOk = EnsResult.Ok("vitalik.eth", "bzz", "bzz://$hex", hex, EnsTrust(verified = true))

    private fun cidWithByte(index: Int, byte: Int): String {
        val bytes = byteArrayOf(0x01, 0xfa.toByte(), 0x01, 0x1b, 0x20) + ByteArray(32) { 0xab.toByte() }
        bytes[index] = byte.toByte()
        return "b" + Base32.encodeLower(bytes)
    }

    // --- decodeSwarmCid ---

    @Test
    fun `a manifest and a feed CID decode to the hex reference`() {
        assertEquals(hex, GatewayOffer.decodeSwarmCid(manifestCid))
        assertEquals(hex, GatewayOffer.decodeSwarmCid(feedCid))
        assertEquals(
            "c2a1c1a3b2ffa1d5d5ff2d2bc2b71ecb5ff4fa22d0dc7a3fd46546c3bc3c1b8b",
            GatewayOffer.decodeSwarmCid("bah5acgzaykq4di5s76q5lvp7fuv4fny6znp7j6rc2dohup6umvdmhpb4dofq"),
        )
    }

    @Test
    fun `a CID one character short or long is refused`() {
        assertNull(GatewayOffer.decodeSwarmCid(manifestCid.dropLast(1)))
        assertNull(GatewayOffer.decodeSwarmCid(manifestCid + "a"))
        assertNull(GatewayOffer.decodeSwarmCid(manifestCid + "aaaaaaaa"))
    }

    @Test
    fun `a CID with non-canonical trailing bits is refused`() {
        // The last character carries 2 padding bits; `q` has them clear.
        assertNull(GatewayOffer.decodeSwarmCid(manifestCid.dropLast(1) + "r"))
    }

    @Test
    fun `a CID with another multihash header is refused`() {
        assertEquals(manifestCid, cidWithByte(0, 0x01)) // the helper builds the real one
        assertNull(GatewayOffer.decodeSwarmCid(cidWithByte(2, 0x12)))
        assertNull(GatewayOffer.decodeSwarmCid(cidWithByte(3, 0x12)))
        assertNull(GatewayOffer.decodeSwarmCid(cidWithByte(4, 0x21)))
    }

    @Test
    fun `anything not a bah5 base32 label is refused`() {
        for (label in listOf(
            "", hex, manifestCid.uppercase(), "bah5", "bah5zzzz", manifestCid.dropLast(1) + "1",
            "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
        )) {
            assertNull(label, GatewayOffer.decodeSwarmCid(label))
        }
    }

    // --- parse ---

    @Test
    fun `eth limo and eth link hosts are the ENS name`() {
        val cases = mapOf(
            "https://vitalik.eth.limo/" to ("eth.limo" to "vitalik.eth"),
            "https://vitalik.eth.link/" to ("eth.link" to "vitalik.eth"),
            "http://vitalik.eth.limo/" to ("eth.limo" to "vitalik.eth"),
            "https://app.uniswap.eth.limo/swap" to ("eth.limo" to "app.uniswap.eth"),
            "https://VITALIK.ETH.LIMO/" to ("eth.limo" to "vitalik.eth"),
            "https://vitalik.eth.limo./" to ("eth.limo" to "vitalik.eth"),
            "https://vitalik.eth.limo" to ("eth.limo" to "vitalik.eth"),
            "https://vitalik.eth.limo?x#y" to ("eth.limo" to "vitalik.eth"),
        )
        for ((url, expected) in cases) {
            val shape = GatewayOffer.parse(url)
            assertEquals(url, expected.first, shape?.gateway)
            assertEquals(url, expected.second, shape?.ensName)
            assertNull(url, shape?.requireProtocol)
            assertNull(url, shape?.reference)
        }
    }

    @Test
    fun `a punycode host is looked up under its Unicode name`() {
        assertEquals("münchen.eth", GatewayOffer.parse("https://xn--mnchen-3ya.eth.limo/")?.ensName)
    }

    @Test
    fun `name bzz link is name eth, and only as Swarm content`() {
        assertEquals(
            GatewayOffer.Shape("bzz.link", "vitalik.bzz.link", ensName = "vitalik.eth", requireProtocol = "bzz"),
            GatewayOffer.parse("https://vitalik.bzz.link/"),
        )
        assertEquals("app.swarm.eth", GatewayOffer.parse("https://app.swarm.bzz.link/")?.ensName)
        // The gateway appends `.eth` itself.
        assertEquals("vitalik.eth.eth", GatewayOffer.parse("https://vitalik.eth.bzz.link/")?.ensName)
    }

    @Test
    fun `cid bzz link carries the reference`() {
        assertEquals(
            GatewayOffer.Shape("bzz.link", "$manifestCid.bzz.link", reference = hex),
            GatewayOffer.parse("https://$manifestCid.bzz.link/"),
        )
        assertEquals(hex, GatewayOffer.parse("https://$feedCid.bzz.link/")?.reference)
    }

    @Test
    fun `an invalid bah5 label is no offer, never a name`() {
        assertNull(GatewayOffer.parse("https://${manifestCid.dropLast(1)}.bzz.link/"))
        assertNull(GatewayOffer.parse("https://bah5abc.bzz.link/"))
        // 64 hex characters can't be a DNS label.
        assertNull(GatewayOffer.parse("https://$hex.bzz.link/"))
    }

    @Test
    fun `lookalikes and other URLs are not gateway URLs`() {
        for (url in listOf(
            "https://eth.limo/", "https://eth.link/", "https://bzz.link/", "https://limo/",
            "https://example.com/", "https://vitalik.eth/", "https://vitaliketh.limo/",
            "https://vitalik-eth.limo/", "https://vitalik.eth.limo.evil.example/",
            "https://vitalik.eth.limo.example/", "https://vitalik.eth.lime/",
            "https://vitalik.eth.limo:8443/", "https://vitalik.eth.limo:443/",
            "https://user:pw@vitalik.eth.limo/", "https://evil.example@vitalik.eth.limo/",
            "https://evil.example\\@vitalik.eth.limo/", "ftp://vitalik.eth.limo/",
            "ipfs://vitalik.eth/", "bzz://vitalik.eth/", "vitalik.eth.limo", "not a url", "",
            "https://.eth.limo/", "https://a..b.eth.limo/", "https://${"a".repeat(64)}.eth.limo/",
            "https://bzz.link/forbidden?hash=$manifestCid",
        )) {
            assertNull(url, GatewayOffer.parse(url))
        }
        assertNull(GatewayOffer.parse(null))
    }

    // --- offers and destinations ---

    @Test
    fun `the destination keeps path, query and fragment`() {
        val shape = GatewayOffer.parse("https://vitalik.eth.limo/a/b.html?x=1&y=2#top")!!
        val offer = GatewayOffer.offerFor(shape, ipfsOk)!!
        assertEquals("ipfs://vitalik.eth", offer.target)
        assertEquals("ipfs://vitalik.eth/a/b.html?x=1&y=2#top", offer.destinationFor("https://vitalik.eth.limo/a/b.html?x=1&y=2#top"))
        assertEquals("ipfs://vitalik.eth/", offer.destinationFor("https://vitalik.eth.limo"))
        assertEquals("ipfs://vitalik.eth/?q#f", offer.destinationFor("https://vitalik.eth.limo?q#f"))
        // Another host: the tab moved on.
        assertNull(offer.destinationFor("https://other.eth.limo/"))
    }

    @Test
    fun `destinations are what the address bar parses as that name on that network`() {
        for ((url, result) in listOf(
            "https://vitalik.eth.limo/blog/?p=1#c" to ipfsOk,
            "https://vitalik.eth.link/" to ipfsOk.copy(protocol = "ipns"),
            "https://vitalik.bzz.link/x" to bzzOk,
            "https://xn--mnchen-3ya.eth.limo/a" to ipfsOk,
        )) {
            val shape = GatewayOffer.parse(url)!!
            val offer = GatewayOffer.offerFor(shape, result)!!
            val destination = offer.destinationFor(url)!!
            val parsed = EnsInput.parseConstrained(destination)!!
            assertEquals(url, shape.ensName, parsed.name)
            assertEquals(url, offer.protocol, parsed.protocol)
        }
    }

    @Test
    fun `a CID is offered without a lookup`() {
        val url = "https://$manifestCid.bzz.link/index.html?q#f"
        val offer = GatewayOffer.offerWithoutLookup(GatewayOffer.parse(url)!!)!!
        assertEquals("bzz", offer.protocol)
        assertEquals("bzz://$hex/index.html?q#f", offer.destinationFor(url))
        assertNull(GatewayOffer.offerWithoutLookup(GatewayOffer.parse("https://vitalik.eth.limo/")!!))
    }

    @Test
    fun `only a settled answer on a native network is offered`() {
        val limo = GatewayOffer.parse("https://vitalik.eth.limo/")!!
        val bzzLink = GatewayOffer.parse("https://vitalik.bzz.link/")!!
        assertEquals("bzz://vitalik.eth", GatewayOffer.offerFor(limo, bzzOk)?.target)
        assertEquals("bzz://vitalik.eth", GatewayOffer.offerFor(bzzLink, bzzOk)?.target)
        // `<name>.bzz.link` is Swarm content or nothing.
        assertNull(GatewayOffer.offerFor(bzzLink, ipfsOk))
        assertNull(GatewayOffer.offerFor(limo, ipfsOk.copy(protocol = "https", redirect = true)))
        assertNull(GatewayOffer.offerFor(limo, ipfsOk.copy(protocol = "http")))
        assertNull(GatewayOffer.offerFor(limo, EnsResult.NotFound("vitalik.eth", "no contenthash", trust = EnsTrust(verified = true))))
        assertNull(GatewayOffer.offerFor(limo, null))
    }

    // --- one tab ---

    private val limoUrl = "https://vitalik.eth.limo/blog"
    private val limoOffer = GatewayOffer.offerFor(GatewayOffer.parse(limoUrl)!!, ipfsOk)!!

    @Test
    fun `a gateway page gets its offer, which follows same-document moves`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        assertTrue(tab.take(lookup))
        assertEquals(GatewayOfferTab.Verdict.Show, tab.answered(lookup, limoOffer, verified = true, alwaysOpen = false))
        assertEquals("ipfs://vitalik.eth/blog", tab.destination())
        tab.located("https://vitalik.eth.limo/about#me")
        assertEquals("ipfs://vitalik.eth/about#me", tab.destination())
    }

    @Test
    fun `a page that is no gateway's asks for nothing and clears the offer`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        tab.answered(lookup, limoOffer, verified = true, alwaysOpen = false)
        assertNull(tab.committed("https://example.com/", traversal = false))
        assertNull(tab.offer)
        assertNull(tab.pending)
    }

    @Test
    fun `a late answer for an earlier page, or over a new load, does nothing`() {
        val tab = GatewayOfferTab()
        val first = tab.committed(limoUrl, traversal = false)!!
        tab.committed("https://example.com/", traversal = false)
        assertEquals(GatewayOfferTab.Verdict.Nothing, tab.answered(first, limoOffer, verified = true, alwaysOpen = true))
        assertNull(tab.offer)

        val second = tab.committed(limoUrl, traversal = false)!!
        tab.loadStarted() // the user typed an address meanwhile
        assertEquals(GatewayOfferTab.Verdict.Nothing, tab.answered(second, limoOffer, verified = true, alwaysOpen = true))
    }

    @Test
    fun `no offer, no pill`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        assertEquals(GatewayOfferTab.Verdict.Nothing, tab.answered(lookup, null, verified = false, alwaysOpen = true))
        assertNull(tab.offer)
    }

    @Test
    fun `a lookup is taken once`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        assertEquals(lookup, tab.pending)
        assertTrue(tab.take(lookup))
        assertEquals(false, tab.take(lookup))
    }

    @Test
    fun `always opens a fresh gateway navigation natively`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        assertEquals(
            GatewayOfferTab.Verdict.AutoOpen("ipfs://vitalik.eth/blog"),
            tab.answered(lookup, limoOffer, verified = true, alwaysOpen = true),
        )
    }

    @Test
    fun `always never opens an unverified answer by itself, only offers it`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        assertEquals(GatewayOfferTab.Verdict.Show, tab.answered(lookup, limoOffer, verified = false, alwaysOpen = true))
    }

    @Test
    fun `always leaves a Back or Forward onto a gateway page alone`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = true)!!
        assertEquals(GatewayOfferTab.Verdict.Show, tab.answered(lookup, limoOffer, verified = true, alwaysOpen = true))
    }

    /** Commit [url] and answer its lookup with [offer] as "always" would. */
    private fun GatewayOfferTab.visit(url: String, traversal: Boolean = false, offer: GatewayOffer.Offer? = limoOffer) =
        committed(url, traversal)?.let { answered(it, offer, verified = true, alwaysOpen = true) }

    @Test
    fun `a page's own Back onto the page always left stays there, once`() {
        val tab = GatewayOfferTab()
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
        assertNull(tab.visit("https://bafy.ipfs.example/")) // the native page
        // `history.back()`, which the chrome doesn't see as a step.
        assertEquals(GatewayOfferTab.Verdict.Show, tab.visit(limoUrl))
        // The next visit is redirected again.
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
    }

    @Test
    fun `once the tab moves on, the same gateway page is redirected again`() {
        val tab = GatewayOfferTab()
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
        tab.visit("https://bafy.ipfs.example/")
        tab.visit("https://example.com/")
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
    }

    @Test
    fun `the user asking for the gateway page again is redirected again`() {
        val tab = GatewayOfferTab()
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
        tab.visit("https://bafy.ipfs.example/")
        tab.userNavigated()
        tab.loadStarted()
        assertTrue(tab.visit(limoUrl) is GatewayOfferTab.Verdict.AutoOpen)
    }

    @Test
    fun `a link the page follows while always looks the name up is not overridden`() {
        val tab = GatewayOfferTab()
        val lookup = tab.committed(limoUrl, traversal = false)!!
        tab.loadStarted() // `shouldOverrideUrlLoading`: the user tapped a link
        assertEquals(GatewayOfferTab.Verdict.Nothing, tab.answered(lookup, limoOffer, verified = true, alwaysOpen = true))
    }

    @Test
    fun `a superseded lookup is cancelled`() {
        val tab = GatewayOfferTab()
        val first = kotlinx.coroutines.Job()
        tab.running = first
        tab.committed("https://other.eth.limo/", traversal = false)
        assertTrue(first.isCancelled)
        val second = kotlinx.coroutines.Job()
        tab.running = second
        tab.loadStarted()
        assertTrue(second.isCancelled)
        val third = kotlinx.coroutines.Job()
        tab.running = third
        tab.running = kotlinx.coroutines.Job()
        assertTrue(third.isCancelled)
    }
}
