package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VirtualOriginTest {

    private val ref64 = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    private val ref128 = ref64 + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    // ------------------------------------------------------------------
    // Swarm refs
    // ------------------------------------------------------------------

    @Test
    fun `bzz 64-hex ref round-trips through host`() {
        val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref64))!!
        assertTrue(host.endsWith(".bzz.freedom.baby"))
        assertEquals(ContentRoot.Bzz(ref64), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `bzz label is pinned base36`() {
        // Cross-implementation vector — mirrored in
        // infra/redirector/test-vectors.json.
        val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref64))!!
        assertEquals(
            "3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71.bzz.freedom.baby",
            host,
        )
    }

    @Test
    fun `bzz 128-hex encrypted ref uses two labels and round-trips`() {
        val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref128))!!
        val labels = host.removeSuffix(".bzz.freedom.baby").split('.')
        assertEquals(2, labels.size)
        assertTrue(labels.all { it.length <= 63 })
        assertEquals(ContentRoot.Bzz(ref128), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `bzz refs are case-insensitive on input`() {
        val upper = ref64.uppercase()
        assertEquals(
            VirtualOrigin.hostFor(ContentRoot.Bzz(ref64)),
            VirtualOrigin.hostFor(ContentRoot.Bzz(upper)),
        )
    }

    @Test
    fun `bzz refs with invalid length or non-hex are rejected`() {
        assertNull(VirtualOrigin.hostFor(ContentRoot.Bzz("abc123")))
        assertNull(VirtualOrigin.hostFor(ContentRoot.Bzz(ref64.dropLast(1))))
        assertNull(VirtualOrigin.hostFor(ContentRoot.Bzz(ref64.dropLast(1) + "g")))
    }

    @Test
    fun `bzz round-trip property - random refs`() {
        val rng = Random(42)
        repeat(200) {
            val bytes = ByteArray(32).also(rng::nextBytes)
            val ref = bytes.joinToString("") { "%02x".format(it) }
            val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref))!!
            val label = host.removeSuffix(".bzz.freedom.baby")
            assertTrue("label too long: $label", label.length <= 63)
            assertEquals(ContentRoot.Bzz(ref), VirtualOrigin.parseHost(host))
        }
    }

    @Test
    fun `bzz round-trip property - random encrypted refs`() {
        val rng = Random(1337)
        repeat(200) {
            val bytes = ByteArray(64).also(rng::nextBytes)
            val ref = bytes.joinToString("") { "%02x".format(it) }
            val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref))!!
            assertEquals(ContentRoot.Bzz(ref), VirtualOrigin.parseHost(host))
        }
    }

    @Test
    fun `bzz refs with leading zero bytes round-trip`() {
        val ref = "00000000" + ref64.substring(8)
        val host = VirtualOrigin.hostFor(ContentRoot.Bzz(ref))!!
        assertEquals(ContentRoot.Bzz(ref), VirtualOrigin.parseHost(host))
        val allZero = "0".repeat(64)
        val zeroHost = VirtualOrigin.hostFor(ContentRoot.Bzz(allZero))!!
        assertEquals(ContentRoot.Bzz(allZero), VirtualOrigin.parseHost(zeroHost))
    }

    // ------------------------------------------------------------------
    // IPFS CIDs
    // ------------------------------------------------------------------

    @Test
    fun `CIDv0 converts to pinned base36 CIDv1`() {
        // Vectors verified against the IPFS subdomain-gateway conversion
        // (the same multihash renders as
        // bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi in
        // base32). Mirrored in infra/redirector/test-vectors.json.
        assertEquals(
            "k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m",
            VirtualOrigin.normalizeCid("QmbWqxBEKC3P8tqsKc98xmWNzrzDtRLMiMPL8wBuTGsMnR"),
        )
        assertEquals(
            "k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr",
            VirtualOrigin.normalizeCid("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"),
        )
    }

    @Test
    fun `CIDv0 host round-trips to the base36 form`() {
        val host = VirtualOrigin.hostFor(
            ContentRoot.Ipfs("QmbWqxBEKC3P8tqsKc98xmWNzrzDtRLMiMPL8wBuTGsMnR"),
        )!!
        assertEquals(
            "k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m.ipfs.freedom.baby",
            host,
        )
        assertEquals(
            ContentRoot.Ipfs("k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m"),
            VirtualOrigin.parseHost(host),
        )
    }

    @Test
    fun `lowercase CIDv1 base32 passes through verbatim`() {
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        val host = VirtualOrigin.hostFor(ContentRoot.Ipfs(cid))!!
        assertEquals("$cid.ipfs.freedom.baby", host)
        assertEquals(ContentRoot.Ipfs(cid), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `lowercased CIDv0 is rejected, not corrupted`() {
        assertNull(VirtualOrigin.normalizeCid("qmbwqxbekc3p8tqskc98xmwnzrzdtrlmimpl8wbutgsmnr"))
    }

    // ------------------------------------------------------------------
    // IPNS
    // ------------------------------------------------------------------

    @Test
    fun `base58 PeerID converts to base36 libp2p-key CIDv1`() {
        // Synthetic ed25519 PeerID (identity multihash over
        // 08011220 || 0x01..0x20) — pinned both sides.
        val host = VirtualOrigin.hostFor(
            ContentRoot.IpnsKey("12D3KooW9tJMax94Lrqw7Y5Qw36viGQAS2gTEPQ5Wg1vTk7xPfQs"),
        )!!
        assertEquals(
            "k51qzi5uqu5dg7hrs1jyr49oygapxsw71v7pv43rk8lemejo9h2m3hkzvww8io.ipns.freedom.baby",
            host,
        )
        assertEquals(
            ContentRoot.IpnsKey("k51qzi5uqu5dg7hrs1jyr49oygapxsw71v7pv43rk8lemejo9h2m3hkzvww8io"),
            VirtualOrigin.parseHost(host),
        )
    }

    @Test
    fun `DNSLink name dot-escapes and round-trips`() {
        val host = VirtualOrigin.hostFor(ContentRoot.IpnsName("en.wikipedia-on-ipfs.org"))!!
        assertEquals("en-wikipedia--on--ipfs-org.ipns.freedom.baby", host)
        assertEquals(
            ContentRoot.IpnsName("en.wikipedia-on-ipfs.org"),
            VirtualOrigin.parseHost(host),
        )
    }

    // ------------------------------------------------------------------
    // ENS
    // ------------------------------------------------------------------

    @Test
    fun `ens name escapes and round-trips`() {
        val host = VirtualOrigin.hostFor(ContentRoot.Ens("vitalik.eth"))!!
        assertEquals("vitalik-eth.ens.freedom.baby", host)
        assertEquals(ContentRoot.Ens("vitalik.eth"), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `ens name with hyphens round-trips`() {
        val host = VirtualOrigin.hostFor(ContentRoot.Ens("foo-bar.swarm.eth"))!!
        assertEquals("foo--bar-swarm-eth.ens.freedom.baby", host)
        assertEquals(ContentRoot.Ens("foo-bar.swarm.eth"), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `escape and unescape are inverse for tricky names`() {
        for (name in listOf("a.b", "a-b", "a--b", "a-b.c-d", "a---b.c", "x")) {
            assertEquals(name, VirtualOrigin.unescapeName(VirtualOrigin.escapeName(name)))
        }
    }

    // ------------------------------------------------------------------
    // URL mapping
    // ------------------------------------------------------------------

    @Test
    fun `toVirtualUrl maps bzz with path and query`() {
        val virtual = VirtualOrigin.toVirtualUrl("bzz://$ref64/gallery/index.html?q=1")!!
        assertEquals(
            "https://3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71.bzz.freedom.baby" +
                "/gallery/index.html?q=1",
            virtual,
        )
    }

    @Test
    fun `toVirtualUrl gives bare roots a trailing slash`() {
        assertEquals(
            "https://3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71.bzz.freedom.baby/",
            VirtualOrigin.toVirtualUrl("bzz://$ref64"),
        )
    }

    @Test
    fun `toVirtualUrl maps ens and ipns names`() {
        assertEquals(
            "https://vitalik-eth.ens.freedom.baby/about",
            VirtualOrigin.toVirtualUrl("ens://vitalik.eth/about"),
        )
        assertEquals(
            "https://ipfs-tech.ipns.freedom.baby/",
            VirtualOrigin.toVirtualUrl("ipns://ipfs.tech"),
        )
    }

    @Test
    fun `toVirtualUrl rejects unknown schemes and malformed ids`() {
        assertNull(VirtualOrigin.toVirtualUrl("https://example.com/"))
        assertNull(VirtualOrigin.toVirtualUrl("bzz://nothex"))
        assertNull(VirtualOrigin.toVirtualUrl("ipfs://Not_A_CID!"))
    }

    @Test
    fun `displayUrlFor is the inverse of toVirtualUrl`() {
        val cases = listOf(
            "bzz://$ref64/gallery/index.html?q=1",
            "bzz://$ref128/x",
            "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi/app.js",
            "ipns://en.wikipedia-on-ipfs.org/wiki",
        )
        for (url in cases) {
            val virtual = VirtualOrigin.toVirtualUrl(url)!!
            assertEquals(url, VirtualOrigin.displayUrlFor(virtual))
        }
        // ENS displays in the bare address-bar form.
        assertEquals(
            "vitalik.eth/about",
            VirtualOrigin.displayUrlFor(VirtualOrigin.toVirtualUrl("ens://vitalik.eth/about")!!),
        )
    }

    @Test
    fun `displayUrlFor drops the bare-root slash`() {
        assertEquals(
            "bzz://$ref64",
            VirtualOrigin.displayUrlFor(VirtualOrigin.toVirtualUrl("bzz://$ref64")!!),
        )
    }

    @Test
    fun `isVirtualUrl and isVirtualHost gate on the suffix list`() {
        assertTrue(VirtualOrigin.isVirtualUrl("https://vitalik-eth.ens.freedom.baby/"))
        assertTrue(VirtualOrigin.isVirtualHost("vitalik-eth.ens.freedom.baby"))
        assertEquals(false, VirtualOrigin.isVirtualUrl("https://freedom.baby/"))
        assertEquals(false, VirtualOrigin.isVirtualUrl("https://example.com/"))
        assertEquals(false, VirtualOrigin.isVirtualUrl("http://127.0.0.1:1633/bzz/$ref64/"))
    }

    @Test
    fun `suffix layout matches the pinned base domains`() {
        assertEquals(
            listOf(
                "bzz.freedom.baby", "ipfs.freedom.baby",
                "ipns.freedom.baby", "ens.freedom.baby",
            ),
            VirtualOrigin.SUFFIXES,
        )
    }

    @Test
    fun `pathAndQueryOf keeps query, drops fragment`() {
        assertEquals("/a/b?q=1", VirtualOrigin.pathAndQueryOf("https://h.bzz.freedom.baby/a/b?q=1#frag"))
        assertEquals("/", VirtualOrigin.pathAndQueryOf("https://h.bzz.freedom.baby"))
    }
}
