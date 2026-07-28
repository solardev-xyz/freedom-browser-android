package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Kotlin half of the cross-implementation vectors in
 * `infra/redirector/test-vectors.json`.
 *
 * The redirector service decodes the same hostnames this module
 * encodes, so the two must never drift. `redirector.test.js` reads the
 * JSON directly; there's no JSON parser on the unit-test classpath, so
 * this file mirrors it **literally**. Every value below appears
 * verbatim in the JSON — change one and you must change both.
 */
class VirtualOriginVectorsTest {

    private fun check(root: ContentRoot, host: String, path: String, display: String) {
        assertEquals("hostFor($root)", host, VirtualOrigin.hostFor(root))
        assertEquals("parseHost($host)", root, VirtualOrigin.parseHost(host))
        assertEquals(display, VirtualOrigin.displayUrlFor("https://$host$path"))
    }

    // ------------------------------------------------------------------
    // vectors.bzz
    // ------------------------------------------------------------------

    private val ref = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    private val refLabel = "3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71"

    @Test
    fun `bzz - plain 64-hex ref, path and query`() = check(
        ContentRoot.Bzz(ref),
        "$refLabel.bzz.freedom.baby",
        "/gallery/index.html?q=1",
        "bzz://$ref/gallery/index.html?q=1",
    )

    @Test
    fun `bzz - same ref, bare root`() = check(
        ContentRoot.Bzz(ref),
        "$refLabel.bzz.freedom.baby",
        "/",
        "bzz://$ref",
    )

    @Test
    fun `bzz - 128-hex encrypted ref uses two labels`() = check(
        ContentRoot.Bzz(ref + "0123456789abcdef".repeat(4)),
        "$refLabel.10r2curot7aoi80l0gyf25bl7y111lpgrb8bzoi8f0c1uhmgf.bzz.freedom.baby",
        "/x",
        "bzz://" + ref + "0123456789abcdef".repeat(4) + "/x",
    )

    @Test
    fun `bzz - leading zero bytes encode as leading zero chars`() = check(
        ContentRoot.Bzz("000000002493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"),
        "0000gmt7pg0m47sf3innkz4sz73gbzf2xzswf47xjt5q025.bzz.freedom.baby",
        "/",
        "bzz://000000002493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd",
    )

    @Test
    fun `bzz - all-zero ref is 32 zero chars, not the empty label`() = check(
        ContentRoot.Bzz("0".repeat(64)),
        "${"0".repeat(32)}.bzz.freedom.baby",
        "/",
        "bzz://${"0".repeat(64)}",
    )

    // ------------------------------------------------------------------
    // vectors.ipfs
    // ------------------------------------------------------------------

    @Test
    fun `ipfs - CIDv0 normalizes to base36 CIDv1`() {
        assertEquals(
            "k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m",
            VirtualOrigin.normalizeCid("QmbWqxBEKC3P8tqsKc98xmWNzrzDtRLMiMPL8wBuTGsMnR"),
        )
        check(
            ContentRoot.Ipfs("k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m"),
            "k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m.ipfs.freedom.baby",
            "/",
            "ipfs://k2jmtxw8rjh1z69c6not3wtdxb0u3urbzhyll1t9jg6ox26dhi5sfi1m",
        )
    }

    @Test
    fun `ipfs - second CIDv0 vector`() {
        assertEquals(
            "k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr",
            VirtualOrigin.normalizeCid("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"),
        )
        check(
            ContentRoot.Ipfs("k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr"),
            "k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr.ipfs.freedom.baby",
            "/index.html",
            "ipfs://k2jmtxvacy5p64u708sn9oawhfsizpcwgk1g59ckse0h1r7a2j7d0tlr/index.html",
        )
    }

    @Test
    fun `ipfs - lowercase base32 CIDv1 passes through verbatim`() = check(
        ContentRoot.Ipfs("bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"),
        "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi.ipfs.freedom.baby",
        "/app.js",
        "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi/app.js",
    )

    // ------------------------------------------------------------------
    // vectors.ipnsKeys / vectors.ipnsNames
    // ------------------------------------------------------------------

    @Test
    fun `ipns - base58 PeerID becomes a base36 libp2p-key CIDv1`() {
        val key = "k51qzi5uqu5dg7hrs1jyr49oygapxsw71v7pv43rk8lemejo9h2m3hkzvww8io"
        assertEquals(
            "$key.ipns.freedom.baby",
            VirtualOrigin.hostFor(
                ContentRoot.IpnsKey("12D3KooW9tJMax94Lrqw7Y5Qw36viGQAS2gTEPQ5Wg1vTk7xPfQs"),
            ),
        )
        check(ContentRoot.IpnsKey(key), "$key.ipns.freedom.baby", "/", "ipns://$key")
    }

    @Test
    fun `ipns - DNSLink name, dot-escaped`() = check(
        ContentRoot.IpnsName("en.wikipedia-on-ipfs.org"),
        "en-wikipedia--on--ipfs-org.ipns.freedom.baby",
        "/wiki",
        "ipns://en.wikipedia-on-ipfs.org/wiki",
    )

    @Test
    fun `ipns - short DNSLink name`() = check(
        ContentRoot.IpnsName("ipfs.tech"),
        "ipfs-tech.ipns.freedom.baby",
        "/",
        "ipns://ipfs.tech",
    )

    // ------------------------------------------------------------------
    // vectors.ens
    // ------------------------------------------------------------------

    @Test
    fun `ens - plain name`() = check(
        ContentRoot.Ens("vitalik.eth"),
        "vitalik-eth.ens.freedom.baby",
        "/about",
        // ENS displays in the bare address-bar form, not `ens://`.
        "vitalik.eth/about",
    )

    @Test
    fun `ens - name containing a hyphen`() = check(
        ContentRoot.Ens("foo-bar.swarm.eth"),
        "foo--bar-swarm-eth.ens.freedom.baby",
        "/",
        "foo-bar.swarm.eth",
    )

    // ------------------------------------------------------------------
    // vectors.escaping
    // ------------------------------------------------------------------

    @Test
    fun `escaping edge cases`() {
        val vectors = listOf(
            "a.b" to "a-b",
            "a-b" to "a--b",
            "a--b" to "a----b",
            "a-b.c-d" to "a--b-c--d",
            "a---b.c" to "a------b-c",
            "x" to "x",
            "foo-bar.swarm.eth" to "foo--bar-swarm-eth",
            "en.wikipedia-on-ipfs.org" to "en-wikipedia--on--ipfs-org",
        )
        for ((name, label) in vectors) {
            assertEquals(label, VirtualOrigin.escapeName(name))
            assertEquals(name, VirtualOrigin.unescapeName(label))
        }
    }

    // ------------------------------------------------------------------
    // vectors.rejects
    // ------------------------------------------------------------------

    @Test
    fun `hosts the mapping must refuse`() {
        val hosts = listOf(
            "example.com",
            "freedom.baby",
            "bzz.freedom.baby",
            "$refLabel.zzz.freedom.baby",
            "a.b.c.bzz.freedom.baby",
            "NOT-hex!.bzz.freedom.baby",
            "z".repeat(56) + ".bzz.freedom.baby",
        )
        for (host in hosts) assertNull(host, VirtualOrigin.parseHost(host))
    }
}
