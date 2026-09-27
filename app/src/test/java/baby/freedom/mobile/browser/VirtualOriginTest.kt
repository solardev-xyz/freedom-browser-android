package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ------------------------------------------------------------------
    // Unicode ENS names (ENSIP-15) — Punycode host labels
    // ------------------------------------------------------------------

    @Test
    fun `punycode matches RFC 3492 and IDNA vectors`() {
        assertEquals("bcher-kva", Punycode.encode("bücher"))
        assertEquals("bücher", Punycode.decode("bcher-kva"))
        // Chromium / WHATWG: new URL("https://🦊.eth").host == "xn--9s9h.eth"
        assertEquals("9s9h", Punycode.encode("🦊"))
        assertEquals("🦊", Punycode.decode("9s9h"))
        assertNull(Punycode.decode("a!b"))
    }

    @Test
    fun `unicode ENS names get an ASCII punycode host that round-trips`() {
        for (name in listOf("🦊.eth", "café.eth", "⌐◨-◨.eth", "🏴‍☠.eth", "日本.wei")) {
            val host = VirtualOrigin.hostFor(ContentRoot.Ens(name))!!
            assertTrue(host, host.all { it.code < 0x80 })
            assertTrue(host, host.startsWith("xn--"))
            assertEquals(name, ContentRoot.Ens(name), VirtualOrigin.parseHost(host))
            // Hosts come back case-folded, possibly upper-cased by a caller.
            assertEquals(name, ContentRoot.Ens(name), VirtualOrigin.parseHost(host.uppercase()))
        }
        assertEquals("xn---eth-9y14c.ens.freedom.baby", VirtualOrigin.hostFor(ContentRoot.Ens("🦊.eth")))
    }

    @Test
    fun `chromium's FE0F-less punycode still maps back to the normalized name`() {
        // Chromium's IDNA mapping drops U+FE0F; ENSIP-15 re-normalization
        // of the decoded label must land on the same name.
        val host = "xn--" + Punycode.encode("\u263A\uFE0F-eth".replace("\uFE0F", "")) + ".ens.freedom.baby"
        assertEquals(
            ContentRoot.Ens(baby.freedom.mobile.ens.EnsNormalize.normalize("\u263A\uFE0F.eth")),
            VirtualOrigin.parseHost(host),
        )
    }

    @Test
    fun `ASCII names starting with xn-- keep the plain escape`() {
        for (name in listOf("xn--2i8h.eth", "xn--a.b.eth", "xn--bcher-kva.eth")) {
            val host = VirtualOrigin.hostFor(ContentRoot.Ens(name))!!
            assertEquals(name, ContentRoot.Ens(name), VirtualOrigin.parseHost(host))
        }
    }

    @Test
    fun `ASCII xn-- names whose escape also decodes to a refused unicode name keep the ASCII reading`() {
        // `xn----abc-eth-eth` is also valid Punycode: `-abмc.eth`, which
        // ENSIP-15 refuses — the resolvable ASCII name wins the tie.
        for (name in listOf("xn--abc.eth.eth", "xn--foo.box.eth", "xn--abc.eth.box")) {
            val host = VirtualOrigin.hostFor(ContentRoot.Ens(name))!!
            assertEquals(name, ContentRoot.Ens(name), VirtualOrigin.parseHost(host))
            assertEquals(name + "/p", VirtualOrigin.displayUrlFor("https://$host/p"))
        }
        assertEquals("-ab\u041Cc.eth", Punycode.decode("--abc-eth-eth")!!.let(VirtualOrigin::unescapeName))
        assertNull(baby.freedom.mobile.ens.EnsNormalize.normalizeOrNull("-ab\u043Cc.eth"))
    }

    @Test
    fun `needsEnsTables flags only parses that reach ENSIP-15`() {
        val fox = VirtualOrigin.toVirtualUrl("ens://🦊.eth/x")!!
        assertTrue(VirtualOrigin.needsEnsTables(fox))
        assertTrue(VirtualOrigin.needsEnsTables(fox.uppercase().replace("HTTPS", "https")))
        assertTrue(VirtualOrigin.needsEnsTables("https://xn----2i8h-eth.ens.freedom.baby/"))
        assertTrue(VirtualOrigin.needsEnsTables("ens://🦊.eth/x"))
        assertTrue(VirtualOrigin.needsEnsTables("ENS://%F0%9F%A6%8A.eth"))
        assertTrue(VirtualOrigin.needsEnsTables("ens://Ⓜ️.eth"))
        assertFalse(VirtualOrigin.needsEnsTables("https://vitalik-eth.ens.freedom.baby/"))
        assertFalse(VirtualOrigin.needsEnsTables("ens://VITALIK.eth/a?b"))
        assertFalse(VirtualOrigin.needsEnsTables("https://xn--abc.bzz.freedom.baby/"))
        assertFalse(VirtualOrigin.needsEnsTables("https://example.com/"))
        assertFalse(VirtualOrigin.needsEnsTables("bzz://" + "a".repeat(64)))
        assertFalse(VirtualOrigin.needsEnsTables(null))
    }

    @Test
    fun `unicode ENS virtual url maps back to the display name`() {
        val url = VirtualOrigin.toVirtualUrl("ens://🦊.eth/docs?q=1")!!
        assertEquals("https://xn---eth-9y14c.ens.freedom.baby/docs?q=1", url)
        assertEquals("🦊.eth/docs?q=1", VirtualOrigin.displayUrlFor(url))
    }

    @Test
    fun `overflowing punycode is rejected, never a wrapped code point`() {
        // R6-F1: `i` ran past Int.MAX_VALUE, `n` wrapped negative and
        // appendCodePoint threw — a remote crash from any link.
        for (bad in listOf("0s23082r", "pz50266x", "dx49084u2bh")) {
            assertNull(bad, Punycode.decode(bad))
            val host = "xn--$bad.ens.freedom.baby"
            // Fails closed: no navigable ASCII reading either → invalid host.
            assertNull(host, VirtualOrigin.parseHost(host))
            assertNull(VirtualOrigin.parseHostOfUrl("https://$host/"))
            assertNull(VirtualOrigin.displayUrlFor("https://$host/"))
            assertFalse(VirtualOrigin.isVirtualUrl("https://$host/"))
        }
        // A lone surrogate is not a code point; U+10FFFF is the last one.
        assertNull(Punycode.decode(Punycode.encode("\uD800")))
        assertNull(Punycode.decode(Punycode.encode("a\uDFFFb")))
        assertEquals("\uDBFF\uDFFF", Punycode.decode(Punycode.encode("\uDBFF\uDFFF")))
    }

    @Test
    fun `random xn-- labels never throw through the decode path`() {
        val rnd = Random(0x5EED)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-"
        repeat(5000) {
            val len = 1 + rnd.nextInt(24)
            val label = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val decoded = Punycode.decode(label)
            decoded?.codePoints()?.forEach { cp ->
                assertTrue(label, cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF)
            }
            for (host in listOf("xn--$label.ens.freedom.baby", "xn--$label-eth.ens.freedom.baby")) {
                VirtualOrigin.parseHost(host)
                VirtualOrigin.needsEnsTables("https://$host/")
                VirtualOrigin.displayUrlFor("https://$host/x")
            }
        }
    }

    @Test
    fun `a punycode host of a name ENSIP-15 refuses stays that name, not its xn-- spelling`() {
        val name = "a\u0661b.eth"
        assertNull(baby.freedom.mobile.ens.EnsNormalize.normalizeOrNull(name))
        val host = VirtualOrigin.hostFor(ContentRoot.Ens(name))!!
        assertTrue(host, host.startsWith("xn--"))
        // So the resolver answers INVALID_NAME instead of looking up "xn--…".
        assertEquals(ContentRoot.Ens(name), VirtualOrigin.parseHost(host))
    }

    @Test
    fun `ens content urls are percent-decoded and ENSIP-15 normalized`() {
        // WebView hands over an ens:// iframe src percent-encoded.
        assertEquals(
            ContentRoot.Ens("🦊.eth") to "/x",
            VirtualOrigin.parseContentUrl("ens://%F0%9F%A6%8A.eth/x"),
        )
        assertEquals(ContentRoot.Ens("m.eth") to "", VirtualOrigin.parseContentUrl("ens://Ⓜ️.eth"))
        assertEquals(ContentRoot.Ens("vitalik.eth") to "", VirtualOrigin.parseContentUrl("ens://VITALIK.eth"))
        // ASCII pre-ENSIP-15 names keep desktop's fast path.
        assertEquals(ContentRoot.Ens("ab--c.eth") to "", VirtualOrigin.parseContentUrl("ens://AB--c.eth"))
        // A refused name is kept (decoded) for the resolver to refuse.
        assertEquals(
            ContentRoot.Ens("a\u0661b.eth") to "",
            VirtualOrigin.parseContentUrl("ens://a%D9%A1b.eth"),
        )
    }
}
