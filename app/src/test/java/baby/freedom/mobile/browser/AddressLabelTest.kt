package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressLabelTest {

    @Test
    fun `https url collapses to the registrable host`() {
        assertEquals("example.com", AddressLabel.resting("https://example.com/"))
        assertEquals("example.com", AddressLabel.resting("https://example.com/a/b?c=d#e"))
        assertEquals("example.com", AddressLabel.resting("http://example.com"))
    }

    @Test
    fun `www and deeper subdomains are dropped`() {
        assertEquals("example.com", AddressLabel.resting("https://www.example.com/x"))
        assertEquals("wikipedia.org", AddressLabel.resting("https://en.wikipedia.org/wiki/Swarm"))
        assertEquals("example.com", AddressLabel.resting("https://a.b.c.example.com/"))
    }

    @Test
    fun `compound public suffixes keep their registrable label`() {
        assertEquals("bbc.co.uk", AddressLabel.resting("https://www.bbc.co.uk/news"))
        assertEquals("bbc.co.uk", AddressLabel.resting("https://news.bbc.co.uk/"))
        assertEquals("example.com.au", AddressLabel.resting("https://shop.example.com.au/x"))
    }

    @Test
    fun `tenants of a user-content platform keep their own label`() {
        // The PSL's PRIVATE section: everything under these suffixes is
        // handed out per-user, so two tenants are two owners and must
        // not rest on one bold platform name.
        assertEquals("google.github.io", AddressLabel.resting("https://google.github.io/styleguide/"))
        assertEquals("myapp.web.app", AddressLabel.resting("https://myapp.web.app/"))
        assertEquals(
            "myapp-support.web.app",
            AddressLabel.resting("https://myapp-support.web.app/seed"),
        )
        assertEquals("evil.netlify.app", AddressLabel.resting("https://evil.netlify.app/x"))
        assertEquals("someone.blogspot.com", AddressLabel.resting("https://someone.blogspot.com/p"))
        assertEquals("vitalik.eth.limo", AddressLabel.resting("https://vitalik.eth.limo/"))
        // Deeper labels below the tenant still collapse onto it — the
        // tenant owns everything under its own name.
        assertEquals("google.github.io", AddressLabel.resting("https://a.b.google.github.io/x"))
    }

    @Test
    fun `virtual dweb origins are one label per content root`() {
        // `*.{bzz,ipfs,ipns,ens}.freedom.baby` are ours and pending
        // upstream (issue #6); two roots are two owners.
        assertEquals(
            "vitalik-eth.ens.freedom.baby",
            AddressLabel.resting("https://vitalik-eth.ens.freedom.baby/"),
        )
        assertEquals(
            "deadbeef.bzz.freedom.baby",
            AddressLabel.resting("https://deadbeef.bzz.freedom.baby/index.html"),
        )
    }

    @Test
    fun `a host that is itself a public suffix is shown whole`() {
        assertEquals("github.io", AddressLabel.resting("https://github.io/"))
        assertEquals("www.github.io", AddressLabel.resting("https://www.github.io/"))
    }

    @Test
    fun `ports and userinfo are stripped`() {
        assertEquals("example.com", AddressLabel.resting("https://example.com:8443/x"))
        assertEquals("example.com", AddressLabel.resting("https://user:pw@www.example.com/x"))
    }

    @Test
    fun `ip literals and single-label hosts are left intact`() {
        assertEquals("127.0.0.1", AddressLabel.resting("http://127.0.0.1:1633/bzz/abc"))
        assertEquals("localhost", AddressLabel.resting("http://localhost:8080/"))
    }

    @Test
    fun `a backslash ends the authority on special schemes`() {
        // WHATWG / Chromium treat `\` as a path separator for special
        // schemes, so these all load `host` with `/@bank.com/x` as the
        // path — the label must not read `bank.com` out of them.
        assertEquals("10.0.2.2", AddressLabel.resting("http://10.0.2.2:8922\\@bank.com/x"))
        assertEquals("example.com", AddressLabel.resting("https://example.com\\@bank.co.uk/x"))
        assertEquals("example.com", AddressLabel.resting("https://example.com\\"))
        // Same for the bare-name form, which UrlParser loads as https.
        assertEquals("example.com", AddressLabel.resting("example.com\\@bank.com/x"))
    }

    @Test
    fun `backslashes are kept inside non-special authorities`() {
        // `bzz:` is not a special scheme, so its authority is not split
        // on `\` — but a backslash means it is not a host either, so it
        // is never re-read as `bank.com`; and as it carries an `@`, it
        // is shown as typed rather than elided into `swarm.….com`.
        assertEquals("bzz://swarm.eth\\@bank.com/x", AddressLabel.resting("bzz://swarm.eth\\@bank.com/x"))
        assertEquals("bzz://a\\@b.co/x", AddressLabel.resting("bzz://a\\@b.co/x"))
        assertEquals("bzz://paypal\\@aaaaaaaaaaaa.com", AddressLabel.resting("bzz://paypal\\@aaaaaaaaaaaa.com"))
        // Without an `@`, a backslashed id is still elided.
        assertEquals("bzz://swarm.….com", AddressLabel.resting("bzz://swarm.eth\\bank.com/x"))
    }

    @Test
    fun `bare ens display form keeps the name`() {
        assertEquals("swarm.eth", AddressLabel.resting("swarm.eth"))
        assertEquals("swarm.eth", AddressLabel.resting("swarm.eth/docs/index.html"))
        assertEquals("swarm.eth", AddressLabel.resting("bzz://swarm.eth/docs"))
        assertEquals("swarm.eth", AddressLabel.resting("ens://swarm.eth"))
        assertEquals("vitalik.eth", AddressLabel.resting("ipfs://vitalik.eth/posts"))
    }

    @Test
    fun `userinfo in a name is shown as typed, never stripped`() {
        // `ens://evil@vitalik.eth` resolves the name `evil@vitalik.eth`,
        // which is refused — the capsule must not rest on `vitalik.eth`
        // over that error page (#478).
        assertEquals("evil@vitalik.eth", AddressLabel.resting("evil@vitalik.eth"))
        assertEquals("evil@vitalik.eth/x", AddressLabel.resting("evil@vitalik.eth/x"))
        assertEquals("ens://evil@vitalik.eth", AddressLabel.resting("ens://evil@vitalik.eth"))
        assertEquals("bzz://a:b@swarm.eth/x", AddressLabel.resting("bzz://a:b@swarm.eth/x"))
        assertEquals("ipfs://evilevil@vitalik.eth", AddressLabel.resting("ipfs://evilevil@vitalik.eth"))
        // A percent-encoded `@` is not elided into a name either.
        assertEquals("ipfs://evil%40vitalik.eth", AddressLabel.resting("ipfs://evil%40vitalik.eth"))
        assertEquals("ens://evil%40vitalik.eth", AddressLabel.resting("ens://evil%40vitalik.eth"))
        // Web URLs keep the userinfo strip: that is the host that loads.
        assertEquals("example.com", AddressLabel.resting("https://evil@www.example.com/x"))
    }

    @Test
    fun `a label too long for the capsule is shortened around its at sign`() {
        // Width stand-in: a label fits in [n] characters.
        fun within(n: Int): (String) -> Boolean = { it.length <= n }
        val a64 = "a".repeat(64)
        // Fits: unchanged, and a label with no `@` is never touched here.
        assertEquals("evil@vitalik.eth", AddressLabel.keepingAt("evil@vitalik.eth", within(18)))
        assertEquals("x".repeat(40), AddressLabel.keepingAt("x".repeat(40), within(18)))
        // The userinfo gives way first, keeping the name after the `@`.
        assertEquals("aaaaaa…@vitalik.eth", AddressLabel.keepingAt("$a64@vitalik.eth", within(19)))
        assertEquals("ens://a…@vitalik.eth", AddressLabel.keepingAt("ens://$a64@vitalik.eth", within(20)))
        // Then the name keeps its tail — the `@` is never dropped.
        assertEquals("e…@…lik.eth", AddressLabel.keepingAt("ens://$a64@vitalik.eth", within(11)))
        // A `%40` is kept whole the same way.
        assertEquals("ens://aa…%40vitalik.eth", AddressLabel.keepingAt("ens://$a64%40vitalik.eth", within(23)))
        // The last `@` is the one kept — where a userinfo strip would cut.
        assertEquals("a@b…@vitalik.eth", AddressLabel.keepingAt("a@b$a64@vitalik.eth", within(16)))
        // A surrogate pair is never split by the cut.
        val emoji = "\uD83D\uDE00".repeat(20)
        val cut = AddressLabel.keepingAt("$emoji@vitalik.eth", within(16))
        assertEquals("\uD83D\uDE00…@vitalik.eth", cut)
        // Nor at one character: a leading pair is kept whole, not halved.
        val tight = AddressLabel.keepingAt("${emoji}@" + "b".repeat(60) + ".eth", within(10))
        assertEquals("\uD83D\uDE00…@…b.eth", tight)
        // A trailing pair after the `@` is kept whole too.
        val tail = AddressLabel.keepingAt("x@" + "b".repeat(60) + "\uD83D\uDE00", within(4))
        assertEquals("x@…\uD83D\uDE00", tail)
        // Every result is well-formed UTF-16 at every width.
        for (n in 1..30) {
            for (label in listOf("$emoji@vitalik.eth", "$emoji@" + emoji, "a@" + emoji)) {
                val s = AddressLabel.keepingAt(label, within(n))
                s.forEachIndexed { i, c ->
                    if (c.isHighSurrogate()) assertTrue(s, i + 1 < s.length && s[i + 1].isLowSurrogate())
                    if (c.isLowSurrogate()) assertTrue(s, i > 0 && s[i - 1].isHighSurrogate())
                }
            }
        }
        // A one-character part that lost nothing gets no ellipsis.
        assertEquals("x@y", AddressLabel.keepingAt("x@y", within(2)))
        assertEquals("e@…vvvvvv", AddressLabel.keepingAt("e@" + "v".repeat(50), within(9)))
    }

    @Test
    fun `the at sign kept is the authority's, never one in the path`() {
        fun within(n: Int): (String) -> Boolean = { it.length <= n }
        val a64 = "a".repeat(64)
        val label = "ens://$a64@vitalik.eth/@paypal.com"
        // The userinfo gives way first, the name and path kept whole.
        assertEquals("ens://a…@vitalik.eth/@paypal.com", AddressLabel.keepingAt(label, within(32)))
        // Then the path keeps its head, down to a bare `…`.
        assertEquals("e…@vitalik.eth/@pa…", AddressLabel.keepingAt(label, within(19)))
        assertEquals("e…@vitalik.eth…", AddressLabel.keepingAt(label, within(15)))
        // Only then does the name give up its middle.
        assertEquals("e…@…lik.eth…", AddressLabel.keepingAt(label, within(12)))
        // At no width does it rest on the path's `@paypal.com`.
        for (n in 1..label.length) {
            val s = AddressLabel.keepingAt(label, within(n))
            assertTrue(s, '@' in s)
            assertTrue(s, "@paypal.com" !in s || "@vitalik.eth/" in s)
        }
        // The bare-name form and `%40` split the authority the same way.
        assertEquals("a…@vitalik.eth…", AddressLabel.keepingAt("$a64@vitalik.eth/@paypal.com", within(15)))
        assertEquals("e…%40vitalik.eth…", AddressLabel.keepingAt("ens://$a64%40vitalik.eth/%40paypal.com", within(17)))
        // An `@` only in the path is no userinfo: left to the capsule.
        val pathOnly = "ens://vitalik.eth/" + a64 + "@paypal.com"
        assertEquals(pathOnly, AddressLabel.keepingAt(pathOnly, within(20)))
        // A special scheme's authority ends at a backslash; a content one's doesn't.
        val bs = "https://$a64\\@paypal.com"
        assertEquals(bs, AddressLabel.keepingAt(bs, within(20)))
        assertEquals("bzz://swa…@bank.com", AddressLabel.keepingAt("bzz://swarm.eth\\@bank.com", within(19)))
    }

    @Test
    fun `ens subnames are never collapsed into their parent`() {
        // Each ENS label is its own name with its own owner and
        // resolver, so `pay.vitalik.eth` must not rest on the name
        // `vitalik.eth` — the subname's contenthash can point anywhere.
        assertEquals("pay.vitalik.eth", AddressLabel.resting("pay.vitalik.eth"))
        assertEquals("pay.vitalik.eth", AddressLabel.resting("pay.vitalik.eth/donate"))
        assertEquals("pay.vitalik.eth", AddressLabel.resting("ens://PAY.vitalik.eth/donate"))
        assertEquals("a.b.swarm.eth", AddressLabel.resting("bzz://a.b.swarm.eth/docs"))
        assertEquals("shop.foo.box", AddressLabel.resting("shop.foo.box/x"))
        // `www` is a subname like any other under ENS, not an alias for
        // the parent the way DNS convention has it.
        assertEquals("www.vitalik.eth", AddressLabel.resting("https://www.vitalik.eth/x"))
    }

    @Test
    fun `wns and gns names are shown whole like ens names`() {
        assertEquals("meinhard.wei", AddressLabel.resting("meinhard.wei/docs"))
        assertEquals("apoorv.gwei", AddressLabel.resting("ipfs://apoorv.gwei/x"))
        assertEquals("sub.meinhard.wei", AddressLabel.resting("sub.meinhard.wei"))
        assertEquals("sub.apoorv.gwei", AddressLabel.resting("ens://sub.apoorv.gwei/p"))
    }

    @Test
    fun `content hashes show the scheme and an elided id`() {
        val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertEquals("bzz://a1b2c3…8f90", AddressLabel.resting("bzz://$hash/index.html"))
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        assertEquals("ipfs://bafybe…bzdi", AddressLabel.resting("ipfs://$cid"))
    }

    @Test
    fun `short ids are shown whole`() {
        assertEquals("bzz://deadbeef", AddressLabel.resting("bzz://deadbeef/page"))
    }

    @Test
    fun `blank and unparseable inputs are passed through`() {
        assertEquals("", AddressLabel.resting(""))
        assertEquals("", AddressLabel.resting("   "))
        assertEquals("about:blank", AddressLabel.resting("about:blank"))
        assertEquals("data:text/html,hi", AddressLabel.resting("data:text/html,hi"))
    }

    @Test
    fun `bidi controls can't reorder the label`() {
        // A name ENSIP-15 refuses is put in the bar as given; an RLO in
        // it made the capsule read `hte.paypal.com`.
        assertEquals("\uFFFDmoc.lapyap.eth", AddressLabel.resting("\u202Emoc.lapyap.eth"))
        assertEquals("ens://\uFFFDmoc.l….eth", AddressLabel.resting("ens://\u202Emoc.lapyap.eth"))
        assertEquals("a\uFFFDb\uFFFDc\uFFFD.eth/x", AddressLabel.resting("a\u2067b\u200Fc\u061C.eth/x"))
        // Joiners aren't bidi controls: an emoji name keeps them.
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDCBB.eth", AddressLabel.resting("\uD83D\uDC68\u200D\uD83D\uDCBB.eth"))
    }
}
