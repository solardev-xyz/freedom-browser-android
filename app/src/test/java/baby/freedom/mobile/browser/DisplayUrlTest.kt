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
            prefix = "ens://swarm.eth",
        )
        assertEquals(
            "ens://swarm.eth/page",
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
            "ens://swarm.eth/docs",
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
            "ens://caseful.eth/p",
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
            "ens://s.eth/p",
            DisplayUrl.forActualUrl("bzz://abc/p", override = null),
        )
    }

    @Test
    fun `ipfs scheme input with known cid rewrites`() {
        KnownEnsNames.record("ipfs://bafy", "v.eth")
        assertEquals(
            "ens://v.eth/x",
            DisplayUrl.forActualUrl("ipfs://bafy/x", override = null),
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
