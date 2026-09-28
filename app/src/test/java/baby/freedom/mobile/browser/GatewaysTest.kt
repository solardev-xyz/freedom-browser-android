package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsRpcConfig
import kotlinx.coroutines.runBlocking
import baby.freedom.mobile.ens.EnsTrust
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
        Gateways.setExternalEndpoints("", "")
    }

    @Test
    fun `external endpoints replace the embedded gateways and switch back`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        Gateways.setExternalEndpoints("http://192.168.1.10:1633", "https://gw.example/sub")
        assertEquals(
            "http://192.168.1.10:1633/bzz/$ref64/x?q=1",
            Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/x?q=1"),
        )
        assertEquals(
            "https://gw.example/sub/ipfs/bafy/",
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        assertEquals(
            "https://gw.example/sub/ipns/ipfs.tech/",
            Gateways.gatewayUrlFor(ContentRoot.IpnsName("ipfs.tech"), "/"),
        )
        assertEquals("http://192.168.1.10:1633/bzz/$ref64/", Gateways.toGatewayUrl("bzz://$ref64"))
        assertEquals("https://gw.example/sub/ipfs/bafy/p", Gateways.toGatewayUrl("ipfs://bafy/p"))
        assertEquals("bzz://abc/p", Gateways.toDisplay("http://192.168.1.10:1633/bzz/abc/p"))
        assertEquals("ipfs://bafy/p", Gateways.toDisplay("https://gw.example/sub/ipfs/bafy/p"))
        assertTrue(Gateways.isLocalGateway("http://192.168.1.10:1633/bzz/abc"))
        assertFalse(Gateways.isLocalGateway("http://127.0.0.1:1633/bzz/abc"))

        // Back to the embedded nodes.
        Gateways.setExternalEndpoints("", "")
        assertEquals(
            "http://127.0.0.1:1633/bzz/$ref64/x?q=1",
            Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/x?q=1"),
        )
        assertEquals(
            "http://127.0.0.1:58312/ipfs/bafy/",
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
        assertFalse(Gateways.isLocalGateway("http://192.168.1.10:1633/bzz/abc"))
    }

    @Test
    fun `an external ipfs gateway serves while the embedded node is down`() {
        Gateways.setExternalEndpoints("", "http://10.0.0.2:8080")
        assertEquals(
            "http://10.0.0.2:8080/ipfs/bafy/",
            Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/"),
        )
    }

    @Test
    fun `local cors preflights are answered only for the embedded nodes`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        Gateways.setExternalEndpoints("http://10.0.2.2:8701", "http://10.0.2.2:8702")
        // The external endpoints are the ones in use...
        assertTrue(Gateways.isLocalGateway("http://10.0.2.2:8701/stamps/topup/x/1"))
        // ...but their preflights are theirs to answer.
        assertFalse(Gateways.isEmbeddedGateway("http://10.0.2.2:8701/stamps/topup/x/1"))
        assertFalse(Gateways.isEmbeddedGateway("http://10.0.2.2:8702/api/v0/pin/rm"))
        assertTrue(Gateways.isEmbeddedGateway("http://127.0.0.1:1633/bzz"))
        assertTrue(Gateways.isEmbeddedGateway("http://127.0.0.1:58312/ipfs/x"))
    }

    @Test
    fun `externalIpfsGatewayOf names the unverified gateway only`() {
        Gateways.setIpfsBase("http://127.0.0.1:58312")
        assertNull(Gateways.externalIpfsGatewayOf("http://127.0.0.1:58312/ipfs/bafy/"))
        Gateways.setExternalEndpoints("http://10.0.2.2:8701", "https://gw.example/sub")
        assertEquals(
            "https://gw.example/sub",
            Gateways.externalIpfsGatewayOf(Gateways.gatewayUrlFor(ContentRoot.Ipfs("bafy"), "/")!!),
        )
        assertNull(Gateways.externalIpfsGatewayOf(Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/")!!))
        assertEquals("https://gw.example/sub", Gateways.externalIpfsBaseFlow.value)
    }

    @Test
    fun `requests wait for the endpoint settings once they are expected`() {
        Gateways.expectExternalEndpoints()
        val waited = java.util.concurrent.atomic.AtomicReference<String>()
        val t = Thread {
            Gateways.awaitExternalEndpointsBlocking()
            waited.set(Gateways.gatewayUrlFor(ContentRoot.Bzz(ref64), "/"))
        }.apply { start() }
        Thread.sleep(200)
        assertNull("must not route before the settings are known", waited.get())
        Gateways.setExternalEndpoints("http://10.0.2.2:8701", "")
        t.join(2_000)
        assertEquals("http://10.0.2.2:8701/bzz/$ref64/", waited.get())
        // Known now: no more waiting.
        Gateways.awaitExternalEndpointsBlocking()
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
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
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
        KnownEnsNames.record("bzz://$ref64", "stalled.eth", EnsTrust.ASSUMED)
        val release = java.util.concurrent.CountDownLatch(1)
        val lookups = java.util.concurrent.atomic.AtomicInteger(0)
        val deadline = Gateways.reverifyDeadlineMs
        Gateways.reverifyDeadlineMs = 200
        try {
            withLookup({ name ->
                lookups.incrementAndGet()
                release.await() // the RPC black-holes until released
                EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef, EnsTrust.ASSUMED)
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
        KnownEnsNames.record("bzz://$ref64", "gone.eth", EnsTrust.ASSUMED)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val deadline = Gateways.reverifyDeadlineMs
        Gateways.reverifyDeadlineMs = 100
        try {
            withLookup({ name ->
                if (first.getAndSet(false)) Thread.sleep(300) // the one slow answer
                EnsResult.NotFound(name, "NO_CONTENTHASH", EnsTrust.ASSUMED)
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
                EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef, EnsTrust.ASSUMED)
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
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `reverifyEnsDocument replaces the first visit's answer with the current one`() {
        // First visit recorded ref64; the name has since moved on.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        val lookups = mutableListOf<String>()
        withLookup({ name ->
            lookups += name
            EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef, EnsTrust.ASSUMED)
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
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        withLookup({ EnsResult.NotFound(it, "NO_CONTENTHASH", EnsTrust.ASSUMED) }) {
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
        trust = EnsTrust(verified = false, agreed = listOf("rpc.test")),
    )

    @Test
    fun `one server's word for a new answer is refused, the answer already served is not`() {
        // #96: the page was served ref64 (cross-checked, or let through
        // by the user); only one server now answers, with another root.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        withLookup({ unverified(it, otherRef) }) {
            assertEquals("ens_unverified", Gateways.reverifyEnsDocument("swarm.eth", pins))
            // Not an answer about the name: nothing is forgotten.
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
        }
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
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
        KnownEnsNames.record("bzz://$otherRef", "swarm.eth", EnsTrust.ASSUMED)
        withLookup({ unverified(it, otherRef) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$otherRef", pins.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `a typed scheme is held on a re-check - another transport is refused, not switched to`() {
        // #97: the tab is on `bzz://swarm.eth`; the name has moved to IPFS.
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        withLookup({ EnsResult.Ok(it, "ipfs", "ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", EnsTrust.ASSUMED) }) {
            assertEquals(
                "ens_wrong_protocol",
                Gateways.reverifyEnsDocument("swarm.eth", pins, assertedProtocol = "bzz"),
            )
            // The page on screen, the tab's and the session's answers
            // are left as they were — nothing was switched.
            assertEquals("bzz://$ref64", pins.uriFor("swarm.eth"))
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
            // With no assertion (a bare `swarm.eth`) the answer is taken.
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("ipfs", KnownEnsNames.protocolFor("swarm.eth"))
        }
    }

    @Test
    fun `a typed scheme on a tez name is held too - a web record under it is refused, not followed`() {
        // #97 across name systems: `ipfs://alice.tez` whose website record
        // moved to the ordinary web is the same broken assertion.
        KnownEnsNames.record("ipfs://bafyold", "alice.tez", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("alice.tez", "ipfs://bafyold")
        withLookup({ EnsResult.Ok(it, "https", "https://alice.example/", "https://alice.example/", EnsTrust.ASSUMED) }) {
            var web: EnsResult.Ok? = null
            assertEquals(
                "ens_wrong_protocol",
                Gateways.reverifyEnsDocument("alice.tez", pins, assertedProtocol = "ipfs", onWebRecord = { web = it }),
            )
            assertNull(web)
            assertEquals("ipfs://bafyold", pins.uriFor("alice.tez"))
            assertEquals("ipfs://bafyold", KnownEnsNames.uriFor("alice.tez"))
        }
    }

    @Test
    fun `a typed scheme the answer matches is served, with the answer's trust recorded`() {
        val trust = EnsTrust(verified = true, agreed = listOf("a.test", "b.test"), block = 7L)
        withLookup({ EnsResult.Ok(it, "bzz", "bzz://$ref64", ref64, trust) }) {
            assertNull(Gateways.reverifyEnsDocument("swarm.eth", assertedProtocol = "bzz"))
            assertEquals(trust, KnownEnsNames.trustFor("swarm.eth"))
        }
    }

    @Test
    fun `servers that disagree refuse the document without forgetting the name`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
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
    fun `one server's word that the name is gone doesn't forget its answer`() {
        // #96: only one server answered, with "no resolver" / an
        // unloadable codec. With an earlier answer that's refused as
        // unverified and the answer kept, not acted on as not-found.
        val lone = EnsTrust(verified = false, agreed = listOf("rpc.test"))
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("swarm.eth", "bzz://$ref64")
        withLookup({ EnsResult.NotFound(it, "NO_RESOLVER", lone) }) {
            assertEquals("ens_unverified", Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
            assertEquals("bzz://$ref64", pins.lastAnswerFor("swarm.eth"))
        }
        withLookup({ EnsResult.Unsupported(it, "0xe5", "", lone) }) {
            // (withLookup cleared the registry; the tab's pins remain.)
            KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
            assertEquals("ens_unverified", Gateways.reverifyEnsDocument("swarm.eth", pins))
            assertEquals("bzz://$ref64", pins.lastAnswerFor("swarm.eth"))
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("swarm.eth"))
        }
        // With nothing earlier to lose it's the plain not-found.
        withLookup({ EnsResult.NotFound(it, "NO_RESOLVER", lone) }) {
            assertEquals("ens_not_found", Gateways.reverifyEnsDocument("fresh.eth", EnsDocumentPins()))
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name ENSIP-15 rejects, even with an earlier answer`() {
        val pins = EnsDocumentPins()
        pins.pin("ab--c.eth", "bzz://$ref64")
        withLookup({ EnsResult.Error(it, "INVALID_NAME", "invalid label extension") }) {
            // Not a transport failure: serving the last answer would keep
            // a name no client can resolve alive.
            assertEquals("ens_invalid_name", Gateways.reverifyEnsDocument("ab--c.eth", pins))
            assertNull(pins.lastAnswerFor("ab--c.eth"))
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name too long to look up`() {
        val name = "a".repeat(256) + ".eth"
        val pins = EnsDocumentPins()
        pins.pin(name, "bzz://$ref64")
        withLookup({ EnsResult.Error(it, "NAME_TOO_LONG", "a label is longer than 255 bytes") }) {
            assertEquals("ens_name_too_long", Gateways.reverifyEnsDocument(name, pins))
            assertNull(pins.lastAnswerFor(name))
        }
    }

    @Test
    fun `reverifyEnsDocument says CCIP-Read is off rather than lookup failed`() {
        withLookup({ EnsResult.Error(it, "CCIP_DISABLED", "off") }) {
            val code = Gateways.reverifyEnsDocument("offchain.eth", EnsDocumentPins())
            assertEquals("ens_ccip_disabled", code)
            assertEquals(502, statusForNameResolutionError(code!!))
        }
    }

    @Test
    fun `with CCIP-Read off, an earlier answer doesn't bring the name back`() {
        // Loaded before CCIP-Read was switched off; Back / reload now.
        KnownEnsNames.record("bzz://$ref64", "offchain.eth", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        withLookup({ EnsResult.Error(it, "CCIP_DISABLED", "off") }) {
            assertEquals("ens_ccip_disabled", Gateways.reverifyEnsDocument("offchain.eth", pins))
            // Every later document too — the refusal doesn't open the
            // failure window that would let one skip the wait and fall back.
            assertEquals("ens_ccip_disabled", Gateways.reverifyEnsDocument("offchain.eth", pins))
            assertNull(pins.uriFor("offchain.eth"))
            // The answer is kept for when CCIP-Read is back on.
            assertEquals("bzz://$ref64", KnownEnsNames.uriFor("offchain.eth"))
        }
    }

    @Test
    fun `a lookup started under old settings isn't joined after a settings change`() {
        val realConfig = Gateways.ensRpcConfig
        var config = EnsRpcConfig(customEndpoints = listOf("https://old.example"))
        Gateways.ensRpcConfig = { config }
        val release = java.util.concurrent.CountDownLatch(1)
        val lookups = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            withLookup({ name ->
                lookups.incrementAndGet()
                // Which endpoints this lookup asks is fixed when it starts.
                val old = runBlocking { Gateways.ensRpcConfig() }.customEndpoints.isNotEmpty()
                if (old) {
                    release.await() // the old endpoint is slow to answer
                    EnsResult.Ok(name, "bzz", "bzz://$ref64", ref64, EnsTrust.ASSUMED)
                } else {
                    EnsResult.Ok(name, "bzz", "bzz://$otherRef", otherRef, EnsTrust.ASSUMED)
                }
            }) {
                val oldPins = EnsDocumentPins()
                val first = Thread { Gateways.reverifyEnsDocument("switch.eth", oldPins) }
                first.start()
                val until = System.currentTimeMillis() + 2_000
                while (lookups.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(5)
                assertEquals(1, lookups.get())

                // The user removes the endpoint; a page for the name arrives.
                config = EnsRpcConfig()
                val pins = EnsDocumentPins()
                assertNull(Gateways.reverifyEnsDocument("switch.eth", pins))
                assertEquals(2, lookups.get())
                assertEquals("bzz://$otherRef", pins.uriFor("switch.eth"))
                assertEquals("bzz://$otherRef", KnownEnsNames.uriFor("switch.eth"))

                // The first document's lookup answers late, from the old
                // endpoint: it asks again rather than taking that answer.
                release.countDown()
                first.join(2_000)
                assertFalse(first.isAlive)
                assertEquals("bzz://$otherRef", oldPins.uriFor("switch.eth"))
                assertEquals("bzz://$otherRef", KnownEnsNames.uriFor("switch.eth"))
            }
        } finally {
            release.countDown()
            Gateways.ensRpcConfig = realConfig
        }
    }

    @Test
    fun `failures recorded under superseded settings or past their window are dropped`() {
        val realConfig = Gateways.ensRpcConfig
        var config = EnsRpcConfig(customEndpoints = listOf("https://a.example"))
        Gateways.ensRpcConfig = { config }
        val window = Gateways.reverifyFailureWindowMs
        try {
            withLookup({ EnsResult.Error(it, "PROVIDER_ERROR", "down", retryable = true) }) {
                fun fail(name: String) {
                    KnownEnsNames.record("bzz://$ref64", name, EnsTrust.ASSUMED)
                    assertNull(Gateways.reverifyEnsDocument(name, EnsDocumentPins()))
                    // The failure is recorded when the lookup completes.
                    val until = System.currentTimeMillis() + 2_000
                    while (Gateways.ensLookupFailureCount() == 0 &&
                        System.currentTimeMillis() < until
                    ) Thread.sleep(5)
                }
                fail("a.eth")
                fail("b.eth")
                assertEquals(2, Gateways.ensLookupFailureCount())

                // Each settings change leaves nothing behind from the old ones.
                repeat(5) { i ->
                    config = EnsRpcConfig(customEndpoints = listOf("https://e$i.example"))
                    fail("a.eth")
                    assertEquals(1, Gateways.ensLookupFailureCount())
                }

                // Under the same settings, failures whose window has closed go too.
                fail("b.eth")
                assertEquals(2, Gateways.ensLookupFailureCount())
                Gateways.reverifyFailureWindowMs = 1
                Thread.sleep(5)
                fail("c.eth")
                assertEquals(1, Gateways.ensLookupFailureCount())
            }
        } finally {
            Gateways.reverifyFailureWindowMs = window
            Gateways.ensRpcConfig = realConfig
        }
    }

    @Test
    fun `a lookup failing under superseded settings keeps the current settings' failures`() {
        val realConfig = Gateways.ensRpcConfig
        var config = EnsRpcConfig(customEndpoints = listOf("https://old.example"))
        Gateways.ensRpcConfig = { config }
        val deadline = Gateways.reverifyDeadlineMs
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            withLookup({ name ->
                if (name == "old.eth") release.await()
                EnsResult.Error(name, "PROVIDER_ERROR", "down", retryable = true)
            }) {
                KnownEnsNames.record("bzz://$ref64", "old.eth", EnsTrust.ASSUMED)
                KnownEnsNames.record("bzz://$ref64", "new.eth", EnsTrust.ASSUMED)
                Gateways.reverifyDeadlineMs = 50
                // A lookup under the old settings is stuck on a removed endpoint.
                assertNull(Gateways.reverifyEnsDocument("old.eth", EnsDocumentPins()))

                // The user switches settings; a lookup under them fails.
                config = EnsRpcConfig(customEndpoints = listOf("https://new.example"))
                assertNull(Gateways.reverifyEnsDocument("new.eth", EnsDocumentPins()))
                var until = System.currentTimeMillis() + 2_000
                while (Gateways.ensLookupFailureCount() == 0 &&
                    System.currentTimeMillis() < until
                ) Thread.sleep(5)
                assertEquals(1, Gateways.ensLookupFailureCount())

                // The old lookup now fails too: it neither drops the
                // current failure nor records one of its own.
                release.countDown()
                Thread.sleep(200)
                assertEquals(1, Gateways.ensLookupFailureCount())

                // So the next document for new.eth still skips the wait.
                val stall = java.util.concurrent.CountDownLatch(1)
                Gateways.ensLookup = { name ->
                    stall.await()
                    EnsResult.Error(name, "PROVIDER_ERROR", "down", retryable = true)
                }
                Gateways.reverifyDeadlineMs = 5_000
                val started = System.currentTimeMillis()
                assertNull(Gateways.reverifyEnsDocument("new.eth", EnsDocumentPins()))
                assertTrue(System.currentTimeMillis() - started < 2_000)
                stall.countDown()
            }
        } finally {
            release.countDown()
            Gateways.reverifyDeadlineMs = deadline
            Gateways.ensRpcConfig = realConfig
        }
    }

    @Test
    fun `reverifyEnsDocument refuses a name whose content is no longer loadable`() {
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
        withLookup({ EnsResult.Unsupported(it, "0xe5", "", EnsTrust.ASSUMED) }) {
            assertEquals("ens_unsupported_codec", Gateways.reverifyEnsDocument("swarm.eth"))
            assertNull(KnownEnsNames.uriFor("swarm.eth"))
        }
    }

    @Test
    fun `a navigation's pins reach the page on screen only when it commits`() {
        val pins = EnsDocumentPins()
        var current = ref64
        withLookup({ EnsResult.Ok(it, "bzz", "bzz://$current", current, EnsTrust.ASSUMED) }) {
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
        KnownEnsNames.record("bzz://$ref64", "swarm.eth", EnsTrust.ASSUMED)
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
    fun `a Tezos provider conflict is refused like an ENS one, the last answer kept`() {
        KnownEnsNames.record("ipfs://bafyold", "alice.tez", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("alice.tez", "ipfs://bafyold")
        val conflict = { name: String ->
            EnsResult.Conflict(
                name,
                EnsResult.Conflict.Subject.RECORD,
                listOf(
                    EnsResult.Conflict.Group("ipfs://bafyA", listOf("rpc.tzkt.io")),
                    EnsResult.Conflict.Group("ipfs://bafyB", listOf("mainnet.tezos.ecadinfra.com")),
                ),
                15_133_172,
            )
        }
        withLookup(conflict) {
            assertEquals("ens_conflict", Gateways.reverifyEnsDocument("alice.tez", pins))
            assertEquals("ipfs://bafyold", pins.lastAnswerFor("alice.tez"))
            assertEquals("ipfs://bafyold", KnownEnsNames.uriFor("alice.tez"))
        }
    }

    @Test
    fun `an unverified tez answer on re-check goes through the not-cross-checked gate`() {
        KnownEnsNames.record("ipfs://bafyold", "alice.tez", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("alice.tez", "ipfs://bafyold")
        val lone = EnsTrust(verified = false, agreed = listOf("rpc.tzkt.io"))
        withLookup({ EnsResult.Ok(it, "ipfs", "ipfs://bafynew", "bafynew", lone) }) {
            val page = pins.beginNavigation("https://alice.tez.ens.freedom.baby/")
            assertEquals("ens_unverified", Gateways.reverifyEnsDocument("alice.tez", pins, page))
            assertEquals("ipfs://bafyold", pins.lastAnswerFor("alice.tez"))
        }
        // A lone web record isn't followed unasked either.
        withLookup({ EnsResult.Ok(it, "https", "https://evil.example/", "https://evil.example/", lone) }) {
            var web: EnsResult.Ok? = null
            assertEquals(
                "ens_unverified",
                Gateways.reverifyEnsDocument("alice.tez", pins, onWebRecord = { web = it }),
            )
            assertNull(web)
        }
        // A verified one is served and remembered.
        withLookup({ EnsResult.Ok(it, "ipfs", "ipfs://bafynew", "bafynew", EnsTrust.ASSUMED) }) {
            assertNull(Gateways.reverifyEnsDocument("alice.tez", pins))
            assertEquals("ipfs://bafynew", pins.lastAnswerFor("alice.tez"))
        }
    }

    @Test
    fun `a tez name whose record moved to the web sends the document there`() {
        KnownEnsNames.record("ipfs://bafyold", "alice.tez", EnsTrust.ASSUMED)
        val pins = EnsDocumentPins()
        pins.pin("alice.tez", "ipfs://bafyold")
        withLookup({ EnsResult.Ok(it, "https", "https://alice.example/", "https://alice.example/", EnsTrust.ASSUMED) }) {
            var web: EnsResult.Ok? = null
            assertEquals(
                Gateways.ENS_WEB_RECORD,
                Gateways.reverifyEnsDocument("alice.tez", pins, onWebRecord = { web = it }),
            )
            assertEquals("https://alice.example/", web?.uri)
            // The old IPFS root no longer describes the name.
            assertNull(KnownEnsNames.uriFor("alice.tez"))
            assertNull(pins.lastAnswerFor("alice.tez"))
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
        withLookup({ EnsResult.Ok(it, "bzz", "bzz://$current", current, EnsTrust.ASSUMED) }) {
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
