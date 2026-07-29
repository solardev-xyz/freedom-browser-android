package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
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
        KnownEnsNames.record("bzz://deadbeef", "swarm.eth")
        val o = BrowserState.Override(
            baseUrl = "http://127.0.0.1:1633/bzz/deadbeef",
            prefix = "swarm.eth",
        )
        assertEquals(
            "swarm.eth/page",
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
    fun `gateway url with known hash rewrites to ens`() {
        KnownEnsNames.record("bzz://abcdef", "swarm.eth")
        assertEquals(
            "swarm.eth/docs",
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
        KnownEnsNames.record("bzz://ABCDEF", "caseful.eth")
        // Gateway URL uses lowercase hex; registry recorded it uppercase.
        assertEquals(
            "caseful.eth/p",
            DisplayUrl.forActualUrl(
                "http://127.0.0.1:1633/bzz/abcdef/p",
                override = null,
            ),
        )
    }

    @Test
    fun `bzz scheme input with known hash rewrites`() {
        KnownEnsNames.record("bzz://abc", "s.eth")
        assertEquals(
            "s.eth/p",
            DisplayUrl.forActualUrl("bzz://abc/p", override = null),
        )
    }

    @Test
    fun `ipfs scheme input with known cid rewrites`() {
        KnownEnsNames.record("ipfs://bafy", "v.eth")
        assertEquals(
            "v.eth/x",
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
    fun `virtual bzz origin with known hash rewrites to the ens name`() {
        val ref = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
        KnownEnsNames.record("bzz://$ref", "swarm.eth")
        val virtual = VirtualOrigin.toVirtualUrl("bzz://$ref/docs")!!
        assertEquals(
            "swarm.eth/docs",
            DisplayUrl.forActualUrl(virtual, override = null),
        )
    }

    @Test
    fun `virtual ens origin displays as the bare name`() {
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

    @Test
    fun `external url passes through`() {
        assertEquals(
            "https://example.com/path",
            DisplayUrl.forActualUrl("https://example.com/path", override = null),
        )
    }
}
