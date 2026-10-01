package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsTrust
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayUrlTest {

    @After
    fun tearDown() {
        KnownEnsNames.clear()
    }

    @Test
    fun `override wins over known names`() {
        // The override is set for swarm.eth pointing at a gateway base.
        // Known-names registry *also* has that hash — override should still
        // win (in-manifest clicks match the base prefix directly, cheaper
        // than the regex path).
        KnownEnsNames.record("bzz://deadbeef", "swarm.eth", EnsTrust.ASSUMED)
        val o = BrowserState.Override(
            baseUrl = "http://127.0.0.1:1633/bzz/deadbeef",
            prefix = "swarm.eth",
        )
        assertEquals(
            "bzz://swarm.eth/page",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/deadbeef/page",
                override = o,
            ),
        )
    }

    @Test
    fun `scheme-constrained override keeps the typed scheme form`() {
        val o = BrowserState.Override(
            baseUrl = "http://127.0.0.1:1633/bzz/deadbeef",
            prefix = "bzz://swarm.eth",
        )
        assertEquals(
            "bzz://swarm.eth/page",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/deadbeef/page",
                override = o,
            ),
        )
    }

    @Test
    fun `gateway url with known hash rewrites to the name under its transport`() {
        KnownEnsNames.record("bzz://abcdef", "swarm.eth", EnsTrust.ASSUMED)
        assertEquals(
            "bzz://swarm.eth/docs",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/abcdef/docs",
                override = null,
            ),
        )
    }

    @Test
    fun `gateway url with unknown hash falls back to bzz`() {
        assertEquals(
            "bzz://abcdef/docs",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/abcdef/docs",
                override = null,
            ),
        )
    }

    @Test
    fun `hash lookup is case insensitive`() {
        KnownEnsNames.record("bzz://ABCDEF", "caseful.eth", EnsTrust.ASSUMED)
        // Gateway URL uses lowercase hex; registry recorded it uppercase.
        assertEquals(
            "bzz://caseful.eth/p",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/abcdef/p",
                override = null,
            ),
        )
    }

    @Test
    fun `bzz scheme input with known hash rewrites`() {
        KnownEnsNames.record("bzz://abc", "s.eth", EnsTrust.ASSUMED)
        assertEquals(
            "bzz://s.eth/p",
            DisplayUrl.forActualUrl("bzz://abc/p", override = null),
        )
    }

    @Test
    fun `ipfs scheme input with known cid rewrites`() {
        KnownEnsNames.record("ipfs://bafy", "v.eth", EnsTrust.ASSUMED)
        assertEquals(
            "ipfs://v.eth/x",
            DisplayUrl.forActualUrl("ipfs://bafy/x", override = null),
        )
    }

    @Test
    fun `virtual bzz origin displays as bzz scheme`() {
        val ref = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        val virtual = VirtualOrigin.toVirtualUrl("bzz://$ref/docs")!!
        assertEquals(
            "bzz://$ref/docs",
            DisplayUrl.forActualUrl(virtual, override = null),
        )
    }

    @Test
    fun `virtual bzz origin with known hash rewrites to the name under its transport`() {
        val ref = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        KnownEnsNames.record("bzz://$ref", "swarm.eth", EnsTrust.ASSUMED)
        val virtual = VirtualOrigin.toVirtualUrl("bzz://$ref/docs")!!
        assertEquals(
            "bzz://swarm.eth/docs",
            DisplayUrl.forActualUrl(virtual, override = null),
        )
    }

    @Test
    fun `virtual ens origin of an unresolved name displays as the bare name`() {
        val virtual = VirtualOrigin.toVirtualUrl("ens://vitalik.eth/about")!!
        assertEquals(
            "vitalik.eth/about",
            DisplayUrl.forActualUrl(virtual, override = null),
        )
    }

    @Test
    fun `virtual-origin override keeps the typed scheme form`() {
        val virtual = VirtualOrigin.toVirtualUrl("ens://swarm.eth")!!
        val o = BrowserState.Override(
            baseUrl = virtual.removeSuffix("/"),
            prefix = "bzz://swarm.eth",
        )
        assertEquals(
            "bzz://swarm.eth/page",
            DisplayUrl.forActualUrl(virtual.removeSuffix("/") + "/page", override = o),
        )
    }

    // #97: a resolved name is shown under its transport, desktop's
    // transport-aware address bar.

    @Test
    fun `virtual ens origin displays the name under its resolved transport`() {
        KnownEnsNames.record("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", "vitalik.eth", EnsTrust.ASSUMED)
        val virtual = VirtualOrigin.toVirtualUrl("ens://vitalik.eth/about")!!
        assertEquals(
            "ipfs://vitalik.eth/about",
            DisplayUrl.forActualUrl(virtual, override = null),
        )
    }

    @Test
    fun `a bare-name override follows the name's current transport`() {
        val virtual = VirtualOrigin.toVirtualUrl("ens://mysite.eth")!!.removeSuffix("/")
        val o = BrowserState.Override(baseUrl = virtual, prefix = "mysite.eth")
        KnownEnsNames.record("bzz://$REF", "mysite.eth", EnsTrust.ASSUMED)
        assertEquals("bzz://mysite.eth/p", DisplayUrl.forActualUrl("$virtual/p", o))
        // The contenthash moved to IPFS; a re-check recorded it.
        KnownEnsNames.record("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", "mysite.eth", EnsTrust.ASSUMED)
        assertEquals("ipfs://mysite.eth/p", DisplayUrl.forActualUrl("$virtual/p", o))
        assertNull(o.assertedProtocol)
    }

    @Test
    fun `a typed-scheme override is shown as typed and asserts its transport`() {
        val virtual = VirtualOrigin.toVirtualUrl("ens://mysite.eth")!!.removeSuffix("/")
        val o = BrowserState.Override(baseUrl = virtual, prefix = "bzz://mysite.eth")
        KnownEnsNames.record("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", "mysite.eth", EnsTrust.ASSUMED)
        assertEquals("bzz://mysite.eth/p", DisplayUrl.forActualUrl("$virtual/p", o))
        assertEquals("bzz", o.assertedProtocol)
        assertEquals("ipns", o.copy(prefix = "IPNS://mysite.eth").assertedProtocol)
    }

    @Test
    fun `the override covers its own origin only, not a host that starts with it`() {
        val virtual = VirtualOrigin.toVirtualUrl("ens://mysite.eth")!!.removeSuffix("/")
        val o = BrowserState.Override(baseUrl = virtual, prefix = "mysite.eth")
        KnownEnsNames.record("bzz://$REF", "mysite.eth", EnsTrust.ASSUMED)
        // A DNS site whose host merely begins with the name's virtual
        // host: shown as what it is, not as `bzz://mysite.eth.evil.com/`
        // under the Swarm badge.
        assertEquals(
            "$virtual.evil.com/",
            DisplayUrl.forActualUrl("$virtual.evil.com/", o),
        )
        assertEquals(
            "${virtual}x.evil.com/login",
            DisplayUrl.forActualUrl("${virtual}x.evil.com/login", o),
        )
        assertNull(protocolBadgeFor(BrowserState(1).apply { url = DisplayUrl.forActualUrl("$virtual.evil.com/", o) }))
        // The origin itself, with a path, query or fragment, still is.
        assertEquals("bzz://mysite.eth", DisplayUrl.forActualUrl(virtual, o))
        assertEquals("bzz://mysite.eth/p", DisplayUrl.forActualUrl("$virtual/p", o))
        assertEquals("bzz://mysite.eth?q=1", DisplayUrl.forActualUrl("$virtual?q=1", o))
        assertEquals("bzz://mysite.eth#top", DisplayUrl.forActualUrl("$virtual#top", o))
    }

    @Test
    fun `withTransport leaves urls and unresolved names alone`() {
        KnownEnsNames.record("bzz://$REF", "known.eth", EnsTrust.ASSUMED)
        assertEquals("bzz://known.eth", DisplayUrl.withTransport("known.eth"))
        assertEquals("unknown.eth/x", DisplayUrl.withTransport("unknown.eth/x"))
        assertEquals("https://known.eth/", DisplayUrl.withTransport("https://known.eth/"))
        assertEquals("ipfs://known.eth", DisplayUrl.withTransport("ipfs://known.eth"))
        assertEquals("about:blank", DisplayUrl.withTransport("about:blank"))
    }

    @Test
    fun `external url passes through`() {
        assertEquals(
            "https://example.com/path",
            DisplayUrl.forActualUrl("https://example.com/path", override = null),
        )
    }

    private companion object {
        const val REF = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    }
}
