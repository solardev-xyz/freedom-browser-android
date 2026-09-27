package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [Gateways] object carries mutable process-wide state — the IPFS
 * base URL the UI mirrors from the `:node` process. Each test clears
 * it in [tearDown] so runs stay independent.
 */
class GatewaysTest {

    @After
    fun tearDown() {
        Gateways.setIpfsBase("")
    }

    private val ref64 = "8f1d385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432abcd"
    private val bzzLabel = "3kescpgjpg23w0jk9ccszdtmgq3mcqnthwg1oxbfcnb6w68t71"

    @Test
    fun `toLoadable maps bzz to its virtual origin`() {
        assertEquals(
            "https://$bzzLabel.bzz.freedom.baby/p",
            Gateways.toLoadable("bzz://$ref64/p"),
        )
    }

    @Test
    fun `toLoadable maps ipfs and ens to virtual origins`() {
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        assertEquals(
            "https://$cid.ipfs.freedom.baby/p",
            Gateways.toLoadable("ipfs://$cid/p"),
        )
        assertEquals(
            "https://vitalik-eth.ens.freedom.baby/",
            Gateways.toLoadable("ens://vitalik.eth"),
        )
    }

    @Test
    fun `toLoadable falls back to the gateway for malformed refs`() {
        // "abc" can't be label-encoded (not a 64/128-hex ref) — fall
        // back to the direct gateway URL so its error surfaces instead
        // of ERR_UNKNOWN_URL_SCHEME.
        assertEquals(
            "http://127.0.0.1:1633/bzz/abc/p",
            Gateways.toLoadable("bzz://abc/p"),
        )
    }

    @Test
    fun `toGatewayUrl keeps the direct mapping for the probe`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/p",
            Gateways.toGatewayUrl("bzz://$ref64/p"),
        )
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/",
            Gateways.toGatewayUrl("bzz://$ref64"),
        )
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/p",
            Gateways.toGatewayUrl("ipfs://bafy/p"),
        )
    }

    @Test
    fun `toGatewayUrl leaves ipfs unchanged when base empty`() {
        assertEquals(
            "ipfs://bafy/p",
            Gateways.toGatewayUrl("ipfs://bafy/p"),
        )
    }

    @Test
    fun `gatewayUrlFor maps roots onto the gateways`() {
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/x?q=1",
            Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/x?q=1"),
        )
        // No IPFS gateway yet → null, so the interceptor synthesizes a
        // clean error instead of fetching nowhere.
        assertEquals(
            null,
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/",
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        assertEquals(
            "http://127.0.0.1:58312/ipns/ipfs.tech/",
            Gateways.gatewayUrlFor(ContentRoot.IpnsName("ipfs.tech"), "/"),
        )
    }

    @Test
    fun `gatewayUrlFor resolves ens roots from the session registry`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        try {
            assertEquals(
                "http://127.0.0.1:1633/bzz/$ref64/p",
                Gateways.gatewayUrlFor(ContentRoot.Ens("swarm.eth"), "/p"),
            )
        } finally {
            KnownEnsNames.clear()
        }
    }

    private val otherRef = "1111111111111111111111111111111111111111111111111111111111111111"

    private fun withLookup(answer: (String) -> EnsResult, block: () -> Unit) {
        val real = Gateways.ensLookup
        Gateways.ensLookup = answer
        Gateways.resetEnsLookupState()
        try {
            block()
        } finally {
            Gateways.ensLookup = real
            Gateways.resetEnsLookupState()
            KnownEnsNames.clear()
        }
    }

    @Test
    fun `a stalled lookup serves the last answer after the deadline, and only waits once`() {
        KnownEnsNames.record("bzz://$ref64", "stalled.eth")
        val release = java.util.concurrent.CountDownLatch(1)
        val lookups = java.util.concurrent.atomic.AtomicInteger(0)
        val deadline = Gateways.reverifyDeadlineMs
        Gateways.reverifyDeadlineMs = 200
        try {
            withLookup({ name ->
                lookups.incrementAndGet()
                release.await() // the RPC black-holes until released
                EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef)
            }) {
                val pins = EnsDocumentPins()
                var t = System.nanoTime()
                assertNull(Gateways.reverifyEnsDocument("stalled.eth", pins))
                val firstMs = (System.nanoTime() - t) / 1_000_000
                assertTrue("waited ${firstMs}ms", firstMs in 150..2_000)
                assertEquals("bzz://$ref64", pins.uriFor("stalled.eth"))

                // The next document (an iframe, another Back) doesn't wait
                // again, and doesn't start a second lookup either.
                t = System.nanoTime()
                assertNull(Gateways.reverifyEnsDocument("stalled.eth", pins))
                assertTrue((System.nanoTime() - t) / 1_000_000 < 150)
                assertEquals(1, lookups.get())

                // Once the lookup answers, the next load takes the answer.
                release.countDown()
                val until = System.currentTimeMillis() + 2_000
                while (System.currentTimeMillis() < until &&
                    pins.uriFor("stalled.eth") != "bzz://$otherRef"
                ) {
                    assertNull(Gateways.reverifyEnsDocument("stalled.eth", pins))
                    Thread.sleep(20)
                }
                assertEquals("bzz://$otherRef", pins.uriFor("stalled.eth"))
            }
        } finally {
            release.countDown()
            Gateways.reverifyDeadlineMs = deadline
        }
    }

    @Test
    fun `with no earlier answer a slow lookup is waited for`() {
        val deadline = Gateways.reverifyDeadlineMs
        Gateways.reverifyDeadlineMs = 50
        try {
            withLookup({ name ->
                Thread.sleep(300)
                EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef)
            }) {
                val pins = EnsDocumentPins()
                assertNull(Gateways.reverifyEnsDocument("slow.eth", pins))
                assertEquals("bzz://$otherRef", pins.uriFor("slow.eth"))
            }
        } finally {
            Gateways.reverifyDeadlineMs = deadline
        }
    }

    @Test
    fun `a new page drops the page's pins but keeps the tab's last answer`() {
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$otherRef")
        pins.newPage()
        assertNull(pins.uriFor("swarm.eth"))
        assertEquals("bzz://$otherRef", pins.lastAnswerFor("swarm.eth"))
        // …which a failed lookup on the next page still falls back on.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `reverifyEnsDocument replaces the first visit's answer with the current one`() {
        // First visit recorded ref64; the name has since moved on.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        val lookups = mutableListOf<String>()
        withLookup({ name ->
            lookups += name
            EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef)
        }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth"))
            assertEquals(listOf("swarm.eth"), lookups)
            // The registry — what the document and its subresources are
            // served from — now follows the fresh answer.
            assertEquals("bzz://$otherRef", KnownEnsNames.uriFor("swarm.eth"))
            assertEquals(
                "http://127.0.0.1:1633/bzz/$otherRef/p",
                Gateways.gatewayUrlFor(ContentRoot.Ens("swarm.eth"), "/p"),
            )
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name that no longer resolves`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        withLookup({ EnsResult.NotFound(it, "NO_CONTENTHASH") }) {
            assertEquals("ens_not_found", Gateways.reverifyEnsDocument("swarm.eth"))
            // Left alone: the refusal is the document's, not other tabs'.
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name whose content is no longer loadable`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        withLookup({ EnsResult.Unsupported(it, "0xe5", "") }) {
            assertEquals("ens_unsupported_codec", Gateways.reverifyEnsDocument("swarm.eth"))
        }
    }

    @Test
    fun `a failed lookup serves the last answer instead of refusing`() {
        // RPC unreachable: Back keeps working off what this tab (or, for
        // a fresh tab, the session) last had for the name.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        val pins = EnsDocumentPins()
        withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
            pins.pin("swarm.eth", "bzz://$otherRef")
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            // The tab's own answer beats the session's.
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `a failed lookup with no earlier answer is refused`() {
        withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
            assertEquals("ens_lookup_failed", Gateways.reverifyEnsDocument("swarm.eth"))
            assertEquals(
                "ens_lookup_failed",
                Gateways.reverifyEnsDocument("swarm.eth", EnsDocumentPins()),
            )
        }
    }

    @Test
    fun `one tab's re-check does not move another tab's subresources`() {
        val tab1 = EnsDocumentPins()
        val tab2 = EnsDocumentPins()
        var current = ref64
        withLookup({ EnsResult.Ok(it, "bzz", "bzz://$current", current) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", tab1))
            // The name moves; tab 2 loads it (or goes Back onto it).
            current = otherRef
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", tab2))
            val root = ContentRoot.Ens("swarm.eth")
            assertEquals(
                "http://127.0.0.1:1633/bzz/$ref64/chunk.js",
                Gateways.gatewayUrlFor(root, "/chunk.js", tab1),
            )
            assertEquals(
                "http://127.0.0.1:1633/bzz/$otherRef/chunk.js",
                Gateways.gatewayUrlFor(root, "/chunk.js", tab2),
            )
            // No tab (a service worker): the session's latest answer.
            assertEquals(
                "http://127.0.0.1:1633/bzz/$otherRef/chunk.js",
                Gateways.gatewayUrlFor(root, "/chunk.js"),
            )
        }
    }

    @Test
    fun `toLoadable passes external urls through`() {
        assertEquals(
            "https://example.com/",
            Gateways.toLoadable("https://example.com/"),
        )
    }

    @Test
    fun `toDisplay round-trips bzz`() {
        assertEquals(
            "bzz://abc/p",
            Gateways.toDisplay("http://127.0.0.1:1633/bzz/abc/p"),
        )
    }

    @Test
    fun `toDisplay round-trips ipfs when base set`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "ipfs://bafy/p",
            Gateways.toDisplay("http://127.0.0.1:58312/ipfs/bafy/p"),
        )
    }

    @Test
    fun `toDisplay round-trips ipns when base set`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertEquals(
            "ipns://docs.eth",
            Gateways.toDisplay("http://127.0.0.1:58312/ipns/docs.eth"),
        )
    }

    @Test
    fun `isLocalGateway recognizes swarm origin`() {
        assertTrue(Gateways.isLocalGateway("http://127.0.0.1:1633/health"))
        assertFalse(Gateways.isLocalGateway("https://example.com/health"))
    }

    @Test
    fun `isLocalGateway recognizes ipfs origin only when base set`() {
        assertFalse(Gateways.isLocalGateway("http://127.0.0.1:58312/ipfs/bafy"))
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertTrue(Gateways.isLocalGateway("http://127.0.0.1:58312/ipfs/bafy"))
    }
}
