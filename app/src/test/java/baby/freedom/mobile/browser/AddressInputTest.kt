package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.AddressInput.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The address bar's search / go-to rows and action key (#171). */
class AddressInputTest {

    @Before
    fun realIcu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private val ddg = SearchEngines.DEFAULT.template
    private val custom = "https://search.example.org/find?q={searchTerms}"

    @Test
    fun `classifies search terms, addresses and dweb names`() {
        for (s in listOf("swarm storage", "swarm", "what is ens?", "localhost", " hello ")) {
            assertEquals(s, Kind.Search, AddressInput.classify(s))
        }
        for (s in listOf(
            "example.com", "https://example.com/a b", "http://x", "10.0.0.1:8080",
            "example.com/path?q=1", "about:blank", "localhost:8080",
        )) {
            assertEquals(s, Kind.Url, AddressInput.classify(s))
        }
        for (s in listOf(
            "vitalik.eth", "VITALIK.ETH/docs", "ens://vitalik.eth", "foo.box",
            "alice.wei", "bzz://vitalik.eth", "ipfs://name.eth/x",
            "bzz://" + "ab".repeat(32), "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            "ipns://ipfs.tech",
        )) {
            assertEquals(s, Kind.Dweb, AddressInput.classify(s))
        }
        assertNull(AddressInput.classify(""))
        assertNull(AddressInput.classify("   "))
    }

    @Test
    fun `the classification is what Enter does`() {
        // Search ⇔ UrlParser turns it into the engine's results URL.
        for (s in listOf("swarm storage", "example.com", "a b.c", "ftp://x", "1.2.3.4")) {
            val searched = UrlParser.toUrl(s, ddg) == UrlParser.searchUrl(s, ddg)
            assertEquals(s, searched, AddressInput.classify(s) == Kind.Search)
        }
    }

    @Test
    fun `a search term gets only the search row, naming the engine`() {
        val rows = addressActions("  swarm storage ", ddg)
        assertEquals(
            listOf(
                AddressAction.Search(
                    query = "swarm storage",
                    engine = "DuckDuckGo",
                    url = "https://duckduckgo.com/?q=swarm%20storage",
                ),
            ),
            rows,
        )
    }

    @Test
    fun `an address gets go-to first, then search`() {
        val rows = addressActions("example.com ", ddg)
        assertEquals(2, rows.size)
        assertEquals(AddressAction.Go("example.com", Kind.Url), rows[0])
        assertEquals("example.com", rows[0].submitText)
        assertEquals("Go to address", (rows[0] as AddressAction.Go).subtitle)
        assertEquals("https://duckduckgo.com/?q=example.com", rows[1].submitText)
    }

    @Test
    fun `a dweb name gets go-to first, submitted as typed`() {
        val go = addressActions("vitalik.eth", ddg)[0] as AddressAction.Go
        assertEquals(Kind.Dweb, go.kind)
        // Verbatim, so submit's ENS resolution runs on it.
        assertEquals("vitalik.eth", go.submitText)
        assertEquals("Open ENS name", go.subtitle)
        assertEquals("Open ENS name", (addressActions("bzz://name.eth", ddg)[0] as AddressAction.Go).subtitle)
        assertEquals("Open on Swarm", (addressActions("bzz://" + "ab".repeat(32), ddg)[0] as AddressAction.Go).subtitle)
        assertEquals("Open on IPFS", (addressActions("ipfs://bafy", ddg)[0] as AddressAction.Go).subtitle)
        assertEquals("Open on IPFS", (addressActions("IPNS://name", ddg)[0] as AddressAction.Go).subtitle)
    }

    @Test
    fun `an ens address EnsInput rejects is not labelled IPFS`() {
        val go = addressActions("ens://example.com", ddg)[0] as AddressAction.Go
        assertEquals(Kind.Dweb, go.kind)
        assertEquals("Go to address", go.subtitle)
    }

    @Test
    fun `blank input gets no rows`() {
        assertTrue(addressActions("  ", ddg).isEmpty())
    }

    @Test
    fun `search row and Enter load the same URL on every engine`() {
        val templates = SearchEngines.BUILT_IN.map { it.template } + custom
        for (t in templates) {
            for (q in listOf("swarm storage", "c++ & rust", "grüße", "100%")) {
                val row = addressActions(q, t).single() as AddressAction.Search
                assertEquals("$t / $q", UrlParser.toUrl(q, t), row.url)
            }
        }
    }

    @Test
    fun `engine names - built-in label, custom template's host`() {
        for (e in SearchEngines.BUILT_IN) {
            assertEquals(e.label, SearchEngines.nameForTemplate(e.template))
        }
        assertEquals("search.example.org", SearchEngines.nameForTemplate(custom))
        assertEquals("example.net", SearchEngines.nameForTemplate("https://www.example.net/s/{searchTerms}"))
        // Terms in the host: no fixed host, never the probe substitution.
        assertEquals("Custom", SearchEngines.nameForTemplate("https://{searchTerms}.example.org/"))
        assertEquals(
            "search.example.org",
            (addressActions("x y", custom).single() as AddressAction.Search).engine,
        )
    }
}
