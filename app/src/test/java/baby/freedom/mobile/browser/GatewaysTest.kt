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
    fun `after a timeout, a background answer is taken even while documents keep coming`() {
        // One timeout opens the failure window; the network then recovers
        // and the name's contenthash is gone. Documents loading every few
        // ms (well inside the window) must not stay on the stale root.
        KnownEnsNames.record("bzz://$ref64", "gone.eth")
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val deadline = Gateways.reverifyDeadlineMs
        Gateways.reverifyDeadlineMs = 100
        try {
            withLookup({ name ->
                if (first.getAndSet(false)) Thread.sleep(300) // the one slow answer
                EnsResult.NotFound(name, "NO_CONTENTHASH")
            }) {
                val pins = EnsDocumentPins()
                assertNull(Gateways.reverifyEnsDocument("gone.eth", pins))
                assertEquals("bzz://$ref64", pins.uriFor("gone.eth"))
                var refused: String? = null
                val until = System.currentTimeMillis() + 3_000
                while (refused == null && System.currentTimeMillis() < until) {
                    Thread.sleep(20)
                    refused = Gateways.reverifyEnsDocument("gone.eth", pins)
                }
                assertEquals("ens_not_found", refused)
            }
        } finally {
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
        pins.beginNavigation("https://other.example/")
        pins.documentStarted("https://other.example/")
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
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        withLookup({ EnsResult.NotFound(it, "NO_CONTENTHASH") }) {
            assertEquals("ens_not_found", Gateways.reverifyEnsDocument("swarm.eth", pins))
            // The name's old root is forgotten — the address bar's badge
            // and hash-to-name mapping no longer describe it, and a later
            // failed lookup doesn't bring it back…
            assertNull(KnownEnsNames.uriFor("swarm.eth"))
            assertNull(KnownEnsNames.protocolFor("swarm.eth"))
            assertNull(KnownEnsNames.nameFor(ref64))
            assertNull(pins.lastAnswerFor("swarm.eth"))
            // …but the page on screen keeps serving from its own pin.
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
        }
        withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
            assertEquals("ens_lookup_failed", Gateways.reverifyEnsDocument("swarm.eth", pins))
        }
    }

    private fun unverified(name: String, ref: String) = EnsResult.Ok(
        name, "bzz", "bzz://$ref", ref,
        trust = baby.freedom.mobile.ens.EnsTrust(verified = false, agreed = listOf("rpc.test")),
    )

    @Test
    fun `one server's word for a new answer is refused, the answer already served is not`() {
        // #96: the page was served ref64 (cross-checked, or let through
        // by the user); only one server now answers, with another root.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        val pins = EnsDocumentPins()
        withLookup({ unverified(it, otherRef) }) {
            assertEquals("ens_unverified", Gateways.reverifyEnsDocument("swarm.eth", pins))
            // Not an answer about the name: nothing is forgotten.
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
        }
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        withLookup({ unverified(it, ref64) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `an answer the user let through is served even if the tab had another`() {
        // The tab was on a cross-checked ref64; the user then chose
        // "Continue once" for one server's otherRef, which the submit
        // flow records in the session registry.
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        KnownEnsNames.record("bzz://$otherRef", "swarm.eth")
        withLookup({ unverified(it, otherRef) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `servers that disagree refuse the document without forgetting the name`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        val conflict = { name: String ->
            EnsResult.Conflict(
                name, EnsResult.Conflict.Subject.RECORD,
                listOf(EnsResult.Conflict.Group("bzz://$ref64", listOf("a")), EnsResult.Conflict.Group("bzz://$otherRef", listOf("b"))),
                block = 1L,
            )
        }
        withLookup(conflict) {
            assertEquals("ens_conflict", Gateways.reverifyEnsDocument("swarm.eth"))
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `subresources after a restart aren't served from one server's word`() {
        withLookup({ unverified(it, otherRef) }) {
            assertNull(Gateways.gatewayUrlFor(ContentRoot.Ens("fresh.eth"), "/p"))
            assertNull(KnownEnsNames.uriFor("fresh.eth"))
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name whose content is no longer loadable`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth")
        withLookup({ EnsResult.Unsupported(it, "0xe5", "") }) {
            assertEquals("ens_unsupported_codec", Gateways.reverifyEnsDocument("swarm.eth"))
            assertNull(KnownEnsNames.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `a navigation's pins reach the page on screen only when it commits`() {
        val pins = EnsDocumentPins()
        var current = ref64
        withLookup({ EnsResult.Ok(it, "bzz", "bzz://$current", current) }) {
            val root = ContentRoot.Ens("swarm.eth")
            val first = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/")
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins, first))
            pins.delivered(first)
            pins.documentStarted("https://swarm.eth.ens.freedom.baby/")
            // The name moves; the page follows a link that never commits
            // (a download): its re-check pins the incoming page only.
            current = otherRef
            val download = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/file.bin")
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins, download))
            assertEquals(
                "http://127.0.0.1:1633/bzz/$otherRef/file.bin",
                Gateways.gatewayUrlFor(root, "/file.bin", pins, download),
            )
            assertEquals(
                "http://127.0.0.1:1633/bzz/$ref64/chunk.js",
                Gateways.gatewayUrlFor(root, "/chunk.js", pins),
            )
            // A stale commit signal for another URL doesn't promote it…
            pins.documentStarted("https://swarm.eth.ens.freedom.baby/")
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
            // …and a later navigation that does commit replaces it.
            val next = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/b.html")
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins, next))
            pins.delivered(download) // superseded: ignored
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
            pins.documentStarted("https://swarm.eth.ens.freedom.baby/b.html#top")
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
            // A document that never went through the interceptor starts empty.
            pins.documentStarted("data:text/html,x")
            assertNull(pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `a delivered navigation that never commits leaves subresources on the page on screen`() {
        val pins = EnsDocumentPins()
        val wait = EnsDocumentPins.commitWaitMs
        EnsDocumentPins.commitWaitMs = 300
        try {
            pins.pin("swarm.eth", "bzz://$ref64")
            // The name moved; the navigation's response went to WebView,
            // then Stop / window.stop() cancelled it: no onPageStarted.
            val next = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/?next=1")
            pins.pin("swarm.eth", "bzz://$otherRef", next)
            pins.delivered(next)
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
            val t = System.currentTimeMillis()
            val page = pins.pageFor("swarm.eth")
            assertTrue(System.currentTimeMillis() - t >= 250)
            assertEquals(
                "http://127.0.0.1:1633/bzz/$ref64/chunk.js",
                Gateways.gatewayUrlFor(ContentRoot.Ens("swarm.eth"), "/chunk.js", page = page),
            )
            // Once it is known not to have committed, nothing waits again.
            val t2 = System.currentTimeMillis()
            assertEquals("bzz://$ref64", pins.pageFor("swarm.eth").uriFor("swarm.eth"))
            assertTrue(System.currentTimeMillis() - t2 < 100)
        } finally {
            EnsDocumentPins.commitWaitMs = wait
        }
    }

    @Test
    fun `a subresource racing the commit waits for it and gets the new page`() {
        val pins = EnsDocumentPins()
        val wait = EnsDocumentPins.commitWaitMs
        EnsDocumentPins.commitWaitMs = 10_000
        try {
            pins.pin("swarm.eth", "bzz://$ref64")
            // A name the pages agree on never waits.
            pins.pin("other.eth", "bzz://$ref64")
            val next = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/b.html")
            pins.pin("swarm.eth", "bzz://$otherRef", next)
            pins.pin("other.eth", "bzz://$ref64", next)
            pins.delivered(next)
            val t0 = System.currentTimeMillis()
            assertEquals("bzz://$ref64", pins.pageFor("other.eth").uriFor("other.eth"))
            assertTrue(System.currentTimeMillis() - t0 < 100)

            val got = java.util.concurrent.atomic.AtomicReference<String?>()
            val io = Thread { got.set(pins.pageFor("swarm.eth").uriFor("swarm.eth")) }
            val t = System.currentTimeMillis()
            io.start()
            Thread.sleep(150)
            pins.documentStarted("https://swarm.eth.ens.freedom.baby/b.html")
            io.join(5_000)
            assertEquals("bzz://$otherRef", got.get())
            assertTrue(System.currentTimeMillis() - t < 5_000)
        } finally {
            EnsDocumentPins.commitWaitMs = wait
        }
    }

    @Test
    fun `an iframe on a name neither page pins waits for the commit and pins the new page`() {
        val pins = EnsDocumentPins()
        val wait = EnsDocumentPins.commitWaitMs
        EnsDocumentPins.commitWaitMs = 10_000
        try {
            pins.pin("swarm.eth", "bzz://$ref64")
            val next = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/p.html")
            pins.pin("swarm.eth", "bzz://$ref64", next)
            pins.delivered(next)
            // The new page's iframe on iframe.eth reaches the interceptor
            // before onPageStarted: neither page pins the name yet.
            val got = java.util.concurrent.atomic.AtomicReference<EnsDocumentPins.Page?>()
            val io = Thread { got.set(pins.pageFor("iframe.eth")) }
            io.start()
            Thread.sleep(150)
            assertNull(got.get())
            pins.documentStarted("https://swarm.eth.ens.freedom.baby/p.html")
            io.join(5_000)
            assertTrue(got.get() === next)
            // The re-check's answer lands on the page that asked for it.
            pins.pin("iframe.eth", "bzz://$otherRef", got.get())
            assertEquals("bzz://$otherRef", pins.uriFor("iframe.eth"))
        } finally {
            EnsDocumentPins.commitWaitMs = wait
        }
    }

    @Test
    fun `an undelivered navigation never holds a subresource`() {
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        val next = pins.beginNavigation("https://swarm.eth.ens.freedom.baby/file.bin")
        pins.pin("swarm.eth", "bzz://$otherRef", next)
        val t = System.currentTimeMillis()
        assertEquals("bzz://$ref64", pins.pageFor("swarm.eth").uriFor("swarm.eth"))
        assertTrue(System.currentTimeMillis() - t < 100)
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
