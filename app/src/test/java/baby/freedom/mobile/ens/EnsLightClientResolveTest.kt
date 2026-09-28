package baby.freedom.mobile.ens

import java.util.Collections
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Myotis light-client tier of [EnsResolver] (#101): asked first when
 * ready, its answers verified on their own; the RPC servers take over
 * whenever it isn't ready or has no usable answer. Scripted through a
 * fake [EnsLightClient] and the [EnsHttp] seam — no network, no engine.
 */
private val IPFS = "e30101701220" + "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"

class EnsLightClientResolveTest {

    private val rpc = "https://rpc.test/"
    private val ur = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"
    private val ipfsContenthash = IPFS

    /**
     * Answers every call with [answer]; records the calls. The resolver's
     * name-independent health probe is recorded apart, in [probes] — and
     * given [answer] too, bounded by its `timeoutMs` as `MyotisLink` is.
     */
    private class FakeLightClient(
        @Volatile var generation: Long? = 1L,
        val answer: (to: String, data: String) -> EnsLightClient.Call,
    ) : EnsLightClient {
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val probes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        @Volatile var lastTimeoutMs = 0L
        override fun readyGeneration(): Long? = generation
        override fun ethCall(
            to: String,
            data: String,
            timeoutMs: Long,
            probe: Boolean,
            released: (() -> Unit)?,
        ): EnsLightClient.Call {
            lastTimeoutMs = timeoutMs
            if (probe) {
                assertTrue(to == EnsResolver.ENS_REGISTRY && data == EnsResolver.PROBE_CALL_DATA)
                probes += timeoutMs
                val task = FutureTask {
                    try {
                        answer(to, data)
                    } finally {
                        released?.invoke()
                    }
                }
                Thread(task).apply { isDaemon = true }.start()
                return try {
                    task.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms", timedOut = true)
                }
            }
            calls += to to data
            return try {
                answer(to, data)
            } finally {
                released?.invoke()
            }
        }
    }

    /** One RPC server answering [result] to every `eth_call`; records the requests. */
    private class OneServer(private val result: () -> EnsHttp.Reply) : EnsHttp {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            urls += url
            return if (url == "https://rpc.test/") result() else error("unexpected request $method $url")
        }
    }

    private fun rpcResult(hex: String) =
        EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"$hex"}""")

    private fun resolver(
        client: EnsLightClient,
        http: EnsHttp,
        settings: () -> EnsResolver.Settings = { EnsResolver.Settings(listOf(rpc)) },
        deadlineMs: Long = EnsResolver.LIGHT_CLIENT_DEADLINE_MS,
        backoffMs: Long = EnsResolver.LIGHT_CLIENT_BACKOFF_MS,
        establishedMs: Long = 0,
    ) = EnsResolver(
        { settings() }, http, TezosDomainsResolver(), client, deadlineMs, backoffMs,
        lightClientEstablishedMs = establishedMs,
    )

    private val lightClientOk = EnsLightClient.Call.Ok(wrapAsOuterInner(ipfsContenthash), block = 21_000_000L)

    @Test
    fun `a ready light client answers alone and its answer is verified`() {
        val client = FakeLightClient { _, _ -> lightClientOk }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking { resolver(client, http).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("ipfs", result.protocol)
        assertTrue(result.trust.verified)
        assertTrue(result.trust.lightClient)
        assertEquals(listOf(EnsResolver.LIGHT_CLIENT_SOURCE), result.trust.agreed)
        assertEquals(21_000_000L, result.trust.block)
        assertEquals(ur.lowercase(), client.calls.single().first.lowercase())
        assertTrue(client.calls.single().second.startsWith("0x9061b923"))
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun `a light client that isn't ready is never asked, and RPC resolves as before`() {
        val client = FakeLightClient(generation = null) { _, _ -> error("not ready: must not be called") }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }

        val result = runBlocking { resolver(client, http).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        assertFalse(result.trust.verified) // one server's word, as without Myotis
        assertEquals(listOf("rpc.test"), result.trust.agreed)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `no light client wired at all resolves over RPC`() {
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val result = runBlocking {
            EnsResolver(listOf(rpc), http).resolveContenthash("vitalik.eth")
        }
        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
    }

    @Test
    fun `an unavailable answer falls back to RPC`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("engine error") }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }

        val result = runBlocking { resolver(client, http).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        assertEquals(1, client.calls.size)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `a light client past its deadline falls back to RPC in time`() {
        val client = FakeLightClient { _, _ ->
            Thread.sleep(5_000)
            lightClientOk
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }

        val started = System.currentTimeMillis()
        val result = runBlocking { resolver(client, http, deadlineMs = 200).resolveContenthash("vitalik.eth") }
        val took = System.currentTimeMillis() - started

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        assertTrue("took ${took}ms", took < 2_000)
    }

    @Test
    fun `a verified no-resolver revert is a verified not-found`() {
        // ResolverNotFound(bytes) from the Universal Resolver.
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Revert("0x77209fe8" + "00".repeat(64), 21_000_001L) }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking { resolver(client, http).resolveContenthash("nothing-here-at-all.eth") }

        require(result is EnsResult.NotFound) { "got $result" }
        assertEquals("NO_RESOLVER", result.reason)
        assertTrue(result.trust.verified)
        assertTrue(result.trust.lightClient)
        assertEquals(21_000_001L, result.trust.block)
    }

    @Test
    fun `any other revert is left for the RPC servers`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Revert("0xdeadbeef", 1L) }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }

        val result = runBlocking { resolver(client, http).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
    }

    @Test
    fun `an undecodable result is left for the RPC servers`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Ok("0x1234", 1L) }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }

        val result = runBlocking { resolver(client, http).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
    }

    @Test
    fun `losing readiness during the read discards the answer and falls back`() {
        lateinit var client: FakeLightClient
        client = FakeLightClient { _, _ ->
            client.generation = null // e.g. the app went to the background mid-read
            lightClientOk
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val settings = EnsResolver.Settings(listOf(rpc))

        val result = runBlocking { resolver(client, http, settings = { settings }).resolveContenthash("vitalik.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `CCIP-Read runs the callback on the light client too`() {
        val callData = "deadbeef".hexToBytes()
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf("https://gw.example/{sender}/{data}"),
            callData = callData,
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        val client = FakeLightClient { _, data ->
            if (data.startsWith("0x9061b923")) {
                EnsLightClient.Call.Revert(revert, 21_000_002L)
            } else {
                assertTrue(data.startsWith("0x11223344"))
                EnsLightClient.Call.Ok(wrapAsOuterInner(ipfsContenthash), 21_000_002L)
            }
        }
        val gateway = object : EnsHttp {
            val urls = mutableListOf<String>()
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int,
                maxBytes: Long,
                followRedirects: Boolean,
            ): EnsHttp.Reply {
                urls += url
                assertTrue(url.startsWith("https://gw.example/"))
                return EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
            }
        }

        val result = runBlocking {
            EnsResolver(
                { EnsResolver.Settings(listOf(rpc)) },
                gateway,
                TezosDomainsResolver(),
                client,
            ).resolveContenthash("1.offchainexample.eth")
        }

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
        assertEquals(2, client.calls.size)
        assertEquals(ur.lowercase(), client.calls[1].first.lowercase())
        // The gateway, and no RPC server.
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `with CCIP-Read off an offchain name is refused without asking anyone else`() {
        val revert = encodeOffchainLookup(ur, listOf("https://gw.example/{data}"), "aa".hexToBytes(), "11223344".hexToBytes(), ByteArray(0))
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Revert(revert, 1L) }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking {
            resolver(client, http, settings = { EnsResolver.Settings(listOf(rpc), ccipRead = false) })
                .resolveContenthash("1.offchainexample.eth")
        }

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_DISABLED", result.reason)
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun `a WNS name calls the registry contract through the light client`() {
        val inner = "0x" + (uint256(0x20L) + uint256(ipfsContenthash.length / 2L) + paddedTo32(ipfsContenthash.hexToBytes())).toHex()
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Ok(inner, 5L) }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking { resolver(client, http).resolveContenthash("example.wei") }

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
        assertEquals(NameSystem.WNS.contractAddress!!.lowercase(), client.calls.single().first.lowercase())
        assertTrue(client.calls.single().second.startsWith("0xbc1c58d1"))
    }

    @Test
    fun `it resolves with no RPC endpoints at all`() {
        val client = FakeLightClient { _, _ -> lightClientOk }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking {
            resolver(client, http, settings = { EnsResolver.Settings(emptyList()) })
                .resolveContenthash("vitalik.eth")
        }

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
    }

    @Test
    fun `with no RPC endpoints a light-client miss is still NO_RPC_ENDPOINTS`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("engine error") }
        val http = OneServer { error("no RPC server should be asked") }

        val result = runBlocking {
            resolver(client, http, settings = { EnsResolver.Settings(emptyList()) })
                .resolveContenthash("vitalik.eth")
        }

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("NO_RPC_ENDPOINTS", result.reason)
    }

    @Test
    fun `the cache yields to the light client only where it holds one server's word`() {
        val client = FakeLightClient(generation = null) { _, _ -> lightClientOk }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        // Not ready: one server's word, cached briefly.
        val first = runBlocking { r.resolveContenthash("vitalik.eth") } as EnsResult.Ok
        assertFalse(first.trust.lightClient)
        // Comes up: the next lookup goes to it rather than to that cache.
        client.generation = 2L
        val second = runBlocking { r.resolveContenthash("vitalik.eth") } as EnsResult.Ok
        assertTrue(second.trust.lightClient)
        // Its verified answer is cached...
        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, client.calls.size)
        // ...and still stands once it's gone (a proven answer, within its TTL).
        client.generation = null
        val third = runBlocking { r.resolveContenthash("vitalik.eth") } as EnsResult.Ok
        assertTrue(third.trust.lightClient)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `readiness flapping keeps the RPC epoch and its cache`() {
        val client = FakeLightClient(generation = null) { _, _ -> EnsLightClient.Call.Unavailable("all snap peers failed") }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, http.urls.size)
        // Ready: asked, misses, RPC answers again.
        client.generation = 3L
        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, client.calls.size)
        assertEquals(2, http.urls.size)
        // Not ready: that answer is still cached.
        client.generation = null
        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(2, http.urls.size)
        assertEquals(1, client.calls.size)
        // Ready again as a new generation: the old one's back-off doesn't
        // carry over, so it's asked again; one server's cached word doesn't
        // stand in the way.
        client.generation = 4L
        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(2, client.calls.size)
        assertEquals(3, http.urls.size)
    }

    @Test
    fun `after the light client misses for its own reasons it is skipped for a while`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("all snap peers failed") }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, backoffMs = 300)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, client.calls.size)
        // The miss was checked against a call no name has a say in.
        assertEquals(1, client.probes.size)
        // A different name straight after: RPC only, no light-client wait.
        val second = runBlocking { r.resolveContenthash("nick.eth") } as EnsResult.Ok
        assertFalse(second.trust.lightClient)
        assertEquals(1, client.calls.size)
        assertEquals(2, http.urls.size)
        // Once the back-off has passed it's asked again.
        Thread.sleep(400)
        runBlocking { r.resolveContenthash("brantly.eth") }
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `a re-check's light-client allowance is its deadline, only while a lookup would ask it`() {
        val on = EnsResolver.Settings(listOf(rpc))
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("all snap peers failed") }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 1_234, backoffMs = 60_000)
        assertEquals(1_234L, r.lightClientWaitFor(on, "vitalik.eth"))
        assertEquals(1_234L, r.lightClientWaitFor(on, "name.wei"))
        // The only source there is: asked even while backing off.
        assertEquals(1_234L, r.lightClientWaitFor(EnsResolver.Settings(emptyList()), "vitalik.eth"))
        // `.tez` never goes to it; not ready isn't asked.
        assertEquals(0L, r.lightClientWaitFor(on, "name.tez"))
        client.generation = null
        assertEquals(0L, r.lightClientWaitFor(on, "vitalik.eth"))
        client.generation = 1L
        // A miss whose probe fails too: skipped, so no wait for it.
        runBlocking { r.resolveContenthash("vitalik.eth") }
        runBlocking { r.resolveContenthash("nick.eth") } // waits the probe out
        assertEquals(1, client.probes.size)
        assertEquals(0L, r.lightClientWaitFor(on, "brantly.eth"))
        // Until it comes back as a new stretch of readiness.
        client.generation = 2L
        assertEquals(1_234L, r.lightClientWaitFor(on, "brantly.eth"))
        assertEquals(0L, EnsResolver(listOf(rpc), http).lightClientWaitFor(on, "vitalik.eth"))
    }

    @Test
    fun `a closed read gate falls back once without backing off`() {
        // The app went to the background (or the service's gate hadn't
        // reopened yet) while this side still thought it ready.
        var gateOpen = false
        val client = FakeLightClient { _, _ ->
            if (gateOpen) lightClientOk else EnsLightClient.parse(baby.freedom.swarm.MyotisNode.NOT_READY_JSON)
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        val first = runBlocking { r.resolveContenthash("vitalik.eth") } as EnsResult.Ok
        assertFalse(first.trust.lightClient)
        assertEquals(1, http.urls.size)
        // The very next lookup asks the light client again.
        gateOpen = true
        val second = runBlocking { r.resolveContenthash("nick.eth") }
        require(second is EnsResult.Ok) { "got $second" }
        assertTrue(second.trust.lightClient)
        assertEquals(2, client.calls.size)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `a back-off ends when the light client comes back as a new generation`() {
        var failing = true
        val client = FakeLightClient { _, _ -> if (failing) EnsLightClient.Call.Unavailable("engine error") else lightClientOk }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, client.calls.size)
        // Same generation: still backing off.
        runBlocking { r.resolveContenthash("nick.eth") }
        assertEquals(1, client.calls.size)
        // Restarted (or toggled off and on): a new stretch of readiness,
        // asked straight away rather than after the old window.
        failing = false
        client.generation = 2L
        val result = runBlocking { r.resolveContenthash("brantly.eth") }
        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `running out of time backs off too`() {
        val client = FakeLightClient { _, _ ->
            Thread.sleep(2_000)
            lightClientOk
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 100)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        runBlocking { r.resolveContenthash("nick.eth") }
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `a name-specific miss does not back off`() {
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Revert("0xdeadbeef", 1L) }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        runBlocking { r.resolveContenthash("nick.eth") }
        assertEquals(2, client.calls.size)
        assertTrue(client.probes.isEmpty())
    }

    /** Whether [data] is the Universal Resolver call for `evil.eth` (its DNS-encoded name inside). */
    private fun isEvil(data: String) = data.startsWith("0x9061b923") && "046576696c0365746800" in data

    @Test
    fun `an engine error on one name's resolver does not back the light client off for others`() {
        // evil.eth's resolver makes the engine fail its call (reads state no
        // snap peer serves, hits the gas cap…); the engine itself is fine.
        val client = FakeLightClient { _, data ->
            if (isEvil(data)) EnsLightClient.parse("""{"error":"state unavailable"}""") else lightClientOk
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        val first = runBlocking { r.resolveContenthash("evil.eth") }
        require(first is EnsResult.Ok) { "got $first" }
        assertFalse(first.trust.lightClient)

        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertTrue(other.trust.lightClient)
        assertEquals(2, client.calls.size)
        assertEquals(1, client.probes.size)
    }

    @Test
    fun `a name whose resolver uses the budget in the engine does not back the light client off`() {
        val client = FakeLightClient { _, data ->
            if (isEvil(data)) {
                Thread.sleep(2_000)
                lightClientOk
            } else {
                lightClientOk
            }
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200)

        val first = runBlocking { r.resolveContenthash("evil.eth") }
        require(first is EnsResult.Ok) { "got $first" }
        assertFalse(first.trust.lightClient)

        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertTrue(other.trust.lightClient)
        assertEquals(1, client.probes.size)
    }

    @Test
    fun `with no RPC endpoints the light client is asked even while backing off`() {
        var fail = true
        val client = FakeLightClient { _, _ -> if (fail) EnsLightClient.Call.Unavailable("engine error") else lightClientOk }
        val http = OneServer { error("no RPC server should be asked") }
        val r = resolver(client, http, settings = { EnsResolver.Settings(emptyList()) })

        runBlocking { r.resolveContenthash("vitalik.eth") }
        fail = false
        val result = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
        assertEquals(2, client.calls.size)
    }

    /**
     * A light client whose first call defers offchain to [gatewayUrls]
     * (and whose every later call answers), served by [gateway] — a
     * CCIP-Read gateway taking as long as it likes — and an RPC server.
     */
    private class SlowGatewaySetup(ur: String, gatewayUrls: List<String>, private val gateway: (String) -> EnsHttp.Reply) {
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = gatewayUrls,
            callData = "deadbeef".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        val gatewayRequests: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
        val http = object : EnsHttp {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int,
                maxBytes: Long,
                followRedirects: Boolean,
            ): EnsHttp.Reply {
                if (url == "https://rpc.test/") {
                    return EnsHttp.Reply(
                        200,
                        """{"jsonrpc":"2.0","id":1,"result":"${wrapAsOuterInner(IPFS)}"}""",
                    )
                }
                gatewayRequests += url to timeoutMs
                return gateway(url)
            }
        }
    }

    @Test
    fun `a name's stalled CCIP gateway does not back the light client off for other names`() {
        val setup = SlowGatewaySetup(ur, listOf("https://gw.example/{sender}/{data}")) {
            Thread.sleep(1_000)
            EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
        }
        var first = true
        val client = FakeLightClient { _, _ ->
            if (first) {
                first = false
                EnsLightClient.Call.Revert(setup.revert, 21_000_000L)
            } else {
                lightClientOk
            }
        }
        val r = resolver(client, setup.http, deadlineMs = 200)

        val offchain = runBlocking { r.resolveContenthash("1.offchainexample.eth") }
        require(offchain is EnsResult.Ok) { "got $offchain" }
        assertFalse(offchain.trust.lightClient) // the gateway ran out the budget: RPC answered

        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertTrue(other.trust.lightClient)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `an engine stuck on a CCIP callback still backs off`() {
        val setup = SlowGatewaySetup(ur, listOf("https://gw.example/{sender}/{data}")) {
            EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
        }
        val client = FakeLightClient { _, data ->
            if (data.startsWith("0x9061b923")) {
                EnsLightClient.Call.Revert(setup.revert, 21_000_000L)
            } else {
                Thread.sleep(2_000)
                lightClientOk
            }
        }
        val r = resolver(client, setup.http, deadlineMs = 200)

        runBlocking { r.resolveContenthash("1.offchainexample.eth") }
        val callsAfterFirst = client.calls.size
        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertFalse(other.trust.lightClient)
        assertEquals(callsAfterFirst, client.calls.size)
    }

    /**
     * A light client whose first call defers offchain to [revert] and
     * whose CCIP callback needs [callbackMs]; given less than that, it
     * waits out the call's `timeoutMs` and reports the timeout itself, as
     * `MyotisLink`'s latch does — a little before the lookup's own
     * deadline, so its answer, not the deadline, is what the lookup sees.
     * Every later lookup's first call answers.
     */
    private fun latchClient(revert: String, callbackMs: Long): FakeLightClient {
        var first = true
        lateinit var client: FakeLightClient
        client = FakeLightClient { _, data ->
            when {
                data.startsWith("0x9061b923") && first -> {
                    first = false
                    EnsLightClient.Call.Revert(revert, 21_000_000L)
                }
                data.startsWith("0x9061b923") -> lightClientOk
                // The CCIP callback.
                else -> {
                    val timeoutMs = client.lastTimeoutMs
                    if (callbackMs < timeoutMs) {
                        Thread.sleep(callbackMs)
                        lightClientOk
                    } else {
                        Thread.sleep((timeoutMs - 60).coerceAtLeast(0))
                        EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms", timedOut = true)
                    }
                }
            }
        }
        return client
    }

    @Test
    fun `a light-client timeout after a slow CCIP gateway does not back off`() {
        val setup = SlowGatewaySetup(ur, listOf("https://gw.example/{sender}/{data}")) {
            Thread.sleep(700)
            EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
        }
        // The callback needs more than the ~300ms the gateway left it.
        val client = latchClient(setup.revert, callbackMs = 800)
        val r = resolver(client, setup.http, deadlineMs = 1_000)

        val offchain = runBlocking { r.resolveContenthash("1.offchainexample.eth") }
        require(offchain is EnsResult.Ok) { "got $offchain" }
        assertFalse(offchain.trust.lightClient)

        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertTrue(other.trust.lightClient)
    }

    @Test
    fun `a light-client timeout on a stuck callback with a fast gateway still backs off`() {
        val setup = SlowGatewaySetup(ur, listOf("https://gw.example/{sender}/{data}")) {
            EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
        }
        val client = latchClient(setup.revert, callbackMs = 5_000)
        val r = resolver(client, setup.http, deadlineMs = 500)

        runBlocking { r.resolveContenthash("1.offchainexample.eth") }
        val callsAfterFirst = client.calls.size
        val other = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(other is EnsResult.Ok) { "got $other" }
        assertFalse(other.trust.lightClient)
        assertEquals(callsAfterFirst, client.calls.size)
    }

    @Test
    fun `an abandoned read fetches no further gateway URLs and none past its budget`() {
        val urls = listOf("https://gw1.example/{data}", "https://gw2.example/{data}", "https://gw3.example/{data}")
        val setup = SlowGatewaySetup(ur, urls) {
            Thread.sleep(400)
            EnsHttp.Reply(500, "")
        }
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Revert(setup.revert, 21_000_000L) }
        val r = resolver(client, setup.http, deadlineMs = 200)

        val result = runBlocking { r.resolveContenthash("1.offchainexample.eth") }
        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        // Long enough for the other two gateways to have been tried, had the read carried on.
        Thread.sleep(1_500)
        assertEquals(listOf("https://gw1.example/"), setup.gatewayRequests.map { it.first.substringBefore("0x") })
        assertTrue("timeout ${setup.gatewayRequests.single().second}", setup.gatewayRequests.single().second in 1..200)
        assertEquals(1, client.calls.size)
    }

    /** An offchain name whose light-client callback lands [moves] times on a newer head than its first call. */
    private fun ccipHeadMoving(moves: Int): Triple<FakeLightClient, EnsHttp, () -> List<String>> {
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf("https://gw.example/{sender}/{data}"),
            callData = "deadbeef".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        var attempt = 0
        val client = FakeLightClient { _, data ->
            if (data.startsWith("0x9061b923")) {
                attempt++
                EnsLightClient.Call.Revert(revert, 100L + attempt)
            } else {
                val block = 100L + attempt + if (attempt <= moves) 1 else 0
                EnsLightClient.Call.Ok(wrapAsOuterInner(ipfsContenthash), block)
            }
        }
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val http = object : EnsHttp {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int,
                maxBytes: Long,
                followRedirects: Boolean,
            ): EnsHttp.Reply {
                urls += url
                return if (url.startsWith("https://gw.example/")) {
                    EnsHttp.Reply(200, JSONObject().put("data", "0x01").toString())
                } else {
                    rpcResult(wrapAsOuterInner(ipfsContenthash))
                }
            }
        }
        return Triple(client, http) { urls.toList() }
    }

    @Test
    fun `a CCIP-Read callback on a newer head is run again at one block`() {
        val (client, http, urls) = ccipHeadMoving(moves = 1)

        val result = runBlocking { resolver(client, http).resolveContenthash("1.offchainexample.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.lightClient)
        // The second attempt's call and callback both ran at #102.
        assertEquals(102L, result.trust.block)
        assertEquals(4, client.calls.size)
        assertTrue(urls().all { it.startsWith("https://gw.example/") })
    }

    @Test
    fun `a head that keeps moving under CCIP-Read falls back to RPC`() {
        val (client, http, urls) = ccipHeadMoving(moves = Int.MAX_VALUE)

        val result = runBlocking { resolver(client, http).resolveContenthash("1.offchainexample.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.lightClient)
        assertEquals(6, client.calls.size)
        assertTrue(urls().any { it == rpc })
    }

    /**
     * `MyotisService` as the resolver meets it (R6-F1): [LOOKUP_SLOTS]
     * engine slots for lookups and one for the probe, anything more
     * answered `busy` at once. Each call runs on its own thread and keeps
     * its slot until the "engine" returns after [engineMs] — the app
     * stops waiting at `timeoutMs`, as `MyotisLink` does, but can't
     * cancel it — and only then is `released` called.
     */
    private class SlotClient(private val ok: EnsLightClient.Call, private val engineMs: (String) -> Long) : EnsLightClient {
        @Volatile var engineMsOverride: Long? = null
        val lookupsHeld = java.util.concurrent.atomic.AtomicInteger()
        val probesHeld = java.util.concurrent.atomic.AtomicInteger()
        val busy = java.util.concurrent.atomic.AtomicInteger()
        val probeAnswers: MutableList<EnsLightClient.Call> = Collections.synchronizedList(mutableListOf())
        val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var generation = 1L
        override fun readyGeneration(): Long = generation
        override fun ethCall(
            to: String,
            data: String,
            timeoutMs: Long,
            probe: Boolean,
            released: (() -> Unit)?,
        ): EnsLightClient.Call {
            val slots = if (probe) probesHeld else lookupsHeld
            if (slots.incrementAndGet() > (if (probe) 1 else LOOKUP_SLOTS)) {
                slots.decrementAndGet()
                released?.invoke()
                busy.incrementAndGet()
                return EnsLightClient.parse("""{"status":"unavailable","reason":"busy","busy":true}""")
                    .also { if (probe) probeAnswers += it }
            }
            if (!probe) started += data
            val task = FutureTask {
                try {
                    Thread.sleep(engineMsOverride ?: engineMs(data))
                    ok
                } finally {
                    slots.decrementAndGet()
                    released?.invoke()
                }
            }
            Thread(task).apply { isDaemon = true }.start()
            val answer = try {
                task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms", timedOut = true)
            }
            if (probe) probeAnswers += answer
            return answer
        }

        fun awaitIdle(withinMs: Long) {
            val until = System.currentTimeMillis() + withinMs
            while ((lookupsHeld.get() > 0 || probesHeld.get() > 0) && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals("engine still busy after ${withinMs}ms", 0, lookupsHeld.get() + probesHeld.get())
        }

        companion object {
            const val LOOKUP_SLOTS = 7
        }
    }

    /** Whether [data] asks about a name with a `slow`-prefixed label (`slow3.eth`). */
    private fun isSlow(data: String) = data.startsWith("0x9061b923") && "736c6f77" in data

    @Test
    fun `one site's slow subnames, one after another, can't take every engine slot or back the light client off`() {
        // R6-F1: a page asks for a1.evil.eth, a2.evil.eth, … in turn; each
        // one's engine call outlives the lookup (4 s of engine time against
        // a 200 ms deadline, standing in for ~90 s against 20 s) and keeps
        // its slot after the resolver gave up on it.
        val client = SlotClient(lightClientOk) { data -> if (isEvil(data) || "046576696c0365746800" in data) 2_000 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200)

        for (i in 1..8) {
            val evil = runBlocking { r.resolveContenthash("a$i.evil.eth") }
            require(evil is EnsResult.Ok) { "got $evil" }
            assertFalse(evil.trust.lightClient)
            assertTrue(r.lightClientCallsHeld("evil.eth") <= EnsResolver.LIGHT_CLIENT_CALLS_PER_SITE)
        }
        // evil.eth got only its share of the engine; the rest went to RPC unasked.
        assertEquals(EnsResolver.LIGHT_CLIENT_CALLS_PER_SITE, client.started.size)
        assertEquals(0, client.busy.get())

        // The user's own name, while evil.eth's calls are still in the engine.
        val during = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(during is EnsResult.Ok) { "got $during" }
        assertTrue(during.trust.lightClient)
        // Every probe the misses asked for was answered: nothing backed off.
        assertTrue(client.probeAnswers.isNotEmpty())
        assertTrue(client.probeAnswers.all { it is EnsLightClient.Call.Ok })

        client.awaitIdle(5_000)
        assertEquals(0, r.lightClientCallsHeld("evil.eth"))
        val after = runBlocking { r.resolveContenthash("nick.eth") }
        require(after is EnsResult.Ok) { "got $after" }
        assertTrue(after.trust.lightClient)
        // Its slots came back: evil.eth may use the light client again.
        val again = r.lightClientCallsHeld("evil.eth")
        assertEquals(0, again)
    }

    @Test
    fun `a burst that fills every engine slot is back-pressure, and later lookups use the light client once slots free`() {
        // Seven slow names on seven different sites fill every lookup slot
        // at once — the engine's full house, not a light client that can't serve.
        // The sites are established ones (each answered in time before), so
        // the fresh sites' shared slots (R2-F4) don't come into it.
        val client = SlotClient(lightClientOk) { data -> if (isSlow(data)) 2_000 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200)
        (1..SlotClient.LOOKUP_SLOTS).forEach { i ->
            val known = runBlocking { r.resolveContenthash("s$i.eth") }
            require(known is EnsResult.Ok && known.trust.lightClient) { "got $known" }
        }

        val burst = (1..SlotClient.LOOKUP_SLOTS).map { i ->
            Thread { runBlocking { r.resolveContenthash("slow.s$i.eth") } }.apply { start() }
        }
        val until = System.currentTimeMillis() + 2_000
        while (client.lookupsHeld.get() < SlotClient.LOOKUP_SLOTS && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals(SlotClient.LOOKUP_SLOTS, client.lookupsHeld.get())

        // Busy: this lookup goes to RPC — once.
        val during = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(during is EnsResult.Ok) { "got $during" }
        assertFalse(during.trust.lightClient)
        assertTrue(client.busy.get() >= 1)
        burst.forEach { it.join(5_000) }
        // The probes the slow misses asked for (run after each miss
        // returned) had their own slot throughout.
        val probed = System.currentTimeMillis() + 2_000
        while (client.probeAnswers.isEmpty() && System.currentTimeMillis() < probed) Thread.sleep(10)
        assertTrue(client.probeAnswers.isNotEmpty())
        assertTrue(client.probeAnswers.none { it is EnsLightClient.Call.Unavailable })

        client.awaitIdle(5_000)
        val after = runBlocking { r.resolveContenthash("nick.eth") }
        require(after is EnsResult.Ok) { "got $after" }
        assertTrue("busy must not have backed the light client off", after.trust.lightClient)
    }

    @Test
    fun `a busy probe is inconclusive and backs nothing off`() {
        // Engine errors on the lookup make it a suspect miss; the probe finds
        // its slot taken (an earlier probe still in the engine) and says busy.
        var lookupFails = true
        val client = FakeLightClient { to, _ ->
            when {
                to == EnsResolver.ENS_REGISTRY -> EnsLightClient.parse("""{"status":"unavailable","reason":"busy","busy":true}""")
                lookupFails -> EnsLightClient.Call.Unavailable("engine error")
                else -> lightClientOk
            }
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        runBlocking { r.resolveContenthash("vitalik.eth") }
        lookupFails = false
        val next = runBlocking { r.resolveContenthash("nick.eth") }
        require(next is EnsResult.Ok) { "got $next" }
        assertTrue(next.trust.lightClient)
        assertEquals(1, client.probes.size)
    }

    @Test
    fun `a busy lookup is not a light-client miss`() {
        var busy = true
        val client = FakeLightClient { _, _ ->
            if (busy) EnsLightClient.parse("""{"error":"native execution busy"}""") else lightClientOk
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http)

        val first = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(first is EnsResult.Ok) { "got $first" }
        assertFalse(first.trust.lightClient)
        assertTrue("busy is no reason to probe", client.probes.isEmpty())
        busy = false
        val next = runBlocking { r.resolveContenthash("nick.eth") }
        require(next is EnsResult.Ok) { "got $next" }
        assertTrue(next.trust.lightClient)
    }

    @Test
    fun `siteOf charges a name to its registration`() {
        assertEquals("evil.eth", EnsResolver.siteOf("a1.b.evil.eth"))
        assertEquals("evil.eth", EnsResolver.siteOf("evil.eth"))
        assertEquals("alice.wei", EnsResolver.siteOf("x.alice.wei"))
        // R1-F4: a subname registrar's names are each their own registration.
        assertEquals("alice.base.eth", EnsResolver.siteOf("alice.base.eth"))
        assertEquals("alice.base.eth", EnsResolver.siteOf("x.alice.base.eth"))
        assertEquals("bob.base.eth", EnsResolver.siteOf("bob.base.eth"))
        assertEquals("base.eth", EnsResolver.siteOf("base.eth"))
        assertEquals("hayden.uni.eth", EnsResolver.siteOf("hayden.uni.eth"))
        // R2-F3: `.id` isn't a suffix the browser resolves; no registrar entry for it.
        assertFalse(EnsResolver.ENS_SUBNAME_REGISTRARS.any { it.endsWith(".id") })
        // A DNS name: its registrable domain, not its public suffix.
        assertEquals("example.co.uk", EnsResolver.siteOf("shop.example.co.uk"))
    }

    /** Whether [data] asks about a name with the label [label] (its DNS-encoded form). */
    private fun hasLabel(data: String, label: String) =
        data.startsWith("0x9061b923") && ("%02x".format(label.length) + label.toByteArray().toHex()) in data

    @Test
    fun `a stuck engine stays backed off while the probe that failed is still in it`() {
        // R1-F1: the engine sits on every call for 2 s (standing in for its
        // ~90 s budget); the lookup deadline and the probe get 200 ms, the
        // back-off 300 ms. Once the back-off runs out the failed probe is
        // still in the engine: a lookup then must not pay the deadline
        // again only to have its probe answered `busy` by the old probe's slot.
        val client = SlotClient(lightClientOk) { 2_000 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200, backoffMs = 300)

        val paid = mutableListOf<Long>()
        val until = System.currentTimeMillis() + 1_600
        var i = 0
        while (System.currentTimeMillis() < until) {
            val started = System.currentTimeMillis()
            val result = runBlocking { r.resolveContenthash("name${i++}.eth") }
            require(result is EnsResult.Ok) { "got $result" }
            assertFalse(result.trust.lightClient)
            paid += System.currentTimeMillis() - started
            Thread.sleep(50)
        }
        // Only the first lookup waited on the engine; every later one,
        // well past the 300 ms back-off, went straight to RPC.
        assertEquals("lookups that reached the engine (times: $paid)", 1, client.started.size)
        assertTrue("lookups after the first: $paid", paid.drop(2).all { it < 150 })
        assertTrue(i > 5)
        // No probe was sent into the slot the stuck one holds.
        assertTrue(client.probeAnswers.none { it is EnsLightClient.Call.Unavailable && it.busy })
        assertEquals(1, client.probeAnswers.size)

        // Once the engine lets go of it, the light client is tried again.
        client.awaitIdle(3_000)
        client.engineMsOverride = 20
        val after = runBlocking { r.resolveContenthash("nick.eth") }
        require(after is EnsResult.Ok) { "got $after" }
        assertTrue(after.trust.lightClient)
    }

    @Test
    fun `a stuck probe from an earlier generation keeps the light client skipped after a readiness flip`() {
        // R2-F1: the engine's probe slot is per process. The gen-1 probe
        // times out and stays in the engine; readiness then flips (a peer
        // flap, a trip to the background) to gen 2. Lookups in gen 2 must
        // not pay the deadline again, nor send a probe only to be told
        // `busy` by the slot the old one still holds.
        val client = SlotClient(lightClientOk) { 2_000 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200, backoffMs = 300)

        val first = runBlocking { r.resolveContenthash("name0.eth") }
        require(first is EnsResult.Ok) { "got $first" }
        Thread.sleep(300)
        client.generation = 2L

        val paid = mutableListOf<Long>()
        val until = System.currentTimeMillis() + 1_000
        var i = 1
        while (System.currentTimeMillis() < until) {
            val started = System.currentTimeMillis()
            val result = runBlocking { r.resolveContenthash("name${i++}.eth") }
            require(result is EnsResult.Ok) { "got $result" }
            assertFalse(result.trust.lightClient)
            paid += System.currentTimeMillis() - started
            Thread.sleep(50)
        }
        assertTrue("lookups after the flip: $paid", paid.all { it < 150 })
        assertEquals("lookups that reached the engine", 1, client.started.size)
        assertTrue(client.probeAnswers.none { it is EnsLightClient.Call.Unavailable && it.busy })

        // Once the engine lets go of the old probe, gen 2 asks it again.
        client.awaitIdle(3_000)
        client.engineMsOverride = 20
        val after = runBlocking { r.resolveContenthash("nick.eth") }
        require(after is EnsResult.Ok) { "got $after" }
        assertTrue(after.trust.lightClient)
    }

    @Test
    fun `a probe inside its wait from an earlier generation is waited for, not taken as stuck`() {
        // R2-F1's other side: the earlier generation's probe is still
        // within its own wait when the next one starts; that one waits for
        // it to finish and then asks, and a healthy engine isn't backed off.
        val probeGate = java.util.concurrent.CountDownLatch(1)
        var probes = 0
        val client = object : EnsLightClient {
            @Volatile var generation = 1L
            @Volatile var lookupFails = true
            override fun readyGeneration() = generation
            override fun ethCall(to: String, data: String, timeoutMs: Long, probe: Boolean, released: (() -> Unit)?): EnsLightClient.Call {
                try {
                    if (probe) {
                        synchronized(this) { probes++ }
                        if (generation == 1L) probeGate.await(timeoutMs, TimeUnit.MILLISECONDS)
                        return lightClientOk
                    }
                    // An engine error: a miss that may be the light client's.
                    return if (lookupFails) EnsLightClient.Call.Unavailable("engine error") else lightClientOk
                } finally {
                    released?.invoke()
                }
            }
        }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 1_000, backoffMs = 60_000)
        // Gen 1 misses; its probe is held (inside its wait) until the gate opens.
        runBlocking { r.resolveContenthash("a.eth") }
        Thread.sleep(50)
        client.generation = 2L
        // Gen 2 misses too; its probe finds gen 1's still in the engine.
        runBlocking { r.resolveContenthash("b.eth") }
        Thread.sleep(100)
        probeGate.countDown()
        // Gen 2's probe waited for it, asked, and found the engine healthy.
        client.lookupFails = false
        val after = runBlocking { r.resolveContenthash("c.eth") }
        require(after is EnsResult.Ok) { "got $after" }
        assertTrue(after.trust.lightClient)
        assertEquals(2, synchronized(client) { probes })
    }

    @Test
    fun `a wave of fresh registrations can't fill the engine, and established names keep the rest`() {
        // R2-F4: four fresh registrations, two slow names each, all at
        // once — before any has outlived a lookup. Together they get
        // LIGHT_CLIENT_FRESH_CALLS slots; a name the user has been
        // resolving all along still gets the light client meanwhile.
        val client = SlotClient(lightClientOk) { data -> if (hasLabel(data, "a") || hasLabel(data, "b")) 1_000 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200, establishedMs = 300)
        val mine = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(mine is EnsResult.Ok && mine.trust.lightClient) { "got $mine" }
        Thread.sleep(350)

        val before = client.started.size
        val names = (1..4).flatMap { listOf("a.x$it.eth", "b.x$it.eth") }
        val wave = names.map { n -> Thread { runBlocking { r.resolveContenthash(n) } }.apply { start() } }
        Thread.sleep(50)
        val during = runBlocking { r.resolveContenthash("vitalik.eth") }
        wave.forEach { it.join(3_000) }
        assertTrue(
            "the wave reached the engine ${client.started.size - before - 1} times",
            client.started.size - before - 1 <= EnsResolver.LIGHT_CLIENT_FRESH_CALLS,
        )
        require(during is EnsResult.Ok) { "got $during" }
        assertTrue(during.trust.lightClient)
        client.awaitIdle(3_000)
    }

    @Test
    fun `a site that answered only just now isn't established yet`() {
        // R2-F4: a registration made to answer fast once, then go slow,
        // still counts against the fresh sites' shared slots.
        val client = SlotClient(lightClientOk) { data -> if (hasLabel(data, "slow")) 1_000 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200, establishedMs = 60_000)
        (1..4).forEach { runBlocking { r.resolveContenthash("fast.x$it.eth") } }

        val before = client.started.size
        val names = (1..4).flatMap { listOf("slow.x$it.eth", "slow.a.x$it.eth") }
        names.map { n -> Thread { runBlocking { r.resolveContenthash(n) } }.apply { start() } }
            .forEach { it.join(3_000) }
        assertTrue(
            "the wave reached the engine ${client.started.size - before} times",
            client.started.size - before <= EnsResolver.LIGHT_CLIENT_FRESH_CALLS,
        )
        client.awaitIdle(3_000)
    }

    @Test
    fun `with no RPC endpoints a site at its share is still asked`() {
        // R1-F2: two of evil.eth's names sit in the engine; with no RPC
        // server to fall back to, a third is asked anyway, not refused.
        val client = SlotClient(lightClientOk) { data -> if (hasLabel(data, "a1") || hasLabel(data, "a2")) 2_000 else 20 }
        val http = OneServer { error("no RPC server should be asked") }
        val r = resolver(client, http, settings = { EnsResolver.Settings(emptyList()) }, deadlineMs = 200)

        val held = listOf("a1.evil.eth", "a2.evil.eth").map { n ->
            Thread { runBlocking { r.resolveContenthash(n) } }.apply { start() }
        }
        held.forEach { it.join(2_000) }
        assertEquals(EnsResolver.LIGHT_CLIENT_CALLS_PER_SITE, r.lightClientCallsHeld("evil.eth"))

        val third = runBlocking { r.resolveContenthash("fast.evil.eth") }
        require(third is EnsResult.Ok) { "got $third" }
        assertTrue(third.trust.lightClient)
        client.awaitIdle(5_000)
    }

    @Test
    fun `slow names from several registrations share a few engine slots once they've outlived a lookup`() {
        // R1-F3: a page loads two slow names from each of four
        // registrations — established ones, each answered in time before
        // (a fresh one's wave is capped anyway, R2-F4). The first wave can
        // fill the engine; once they've outlived their lookups, every
        // one of those sites is slow, and the next wave gets only the slow
        // sites' shared slots — the user's own names keep the rest.
        val client = SlotClient(lightClientOk) { data -> if (hasLabel(data, "a") || hasLabel(data, "b")) 1_000 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 200)
        (1..4).forEach { runBlocking { r.resolveContenthash("x$it.eth") } }
        val names = (1..4).flatMap { listOf("a.x$it.eth", "b.x$it.eth") }
        fun wave() = names.map { n -> Thread { runBlocking { r.resolveContenthash(n) } }.apply { start() } }

        wave().forEach { it.join(3_000) }
        (1..4).forEach { assertTrue("x$it.eth", r.lightClientSlowSite("x$it.eth")) }
        client.awaitIdle(3_000)

        val before = client.started.size
        val second = wave()
        second.forEach { it.join(3_000) }
        assertTrue(
            "second wave reached the engine ${client.started.size - before} times",
            client.started.size - before <= EnsResolver.LIGHT_CLIENT_SLOW_CALLS,
        )
        val during = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(during is EnsResult.Ok) { "got $during" }
        assertTrue(during.trust.lightClient)
        // A name answered in time never makes its site slow.
        assertFalse(r.lightClientSlowSite("vitalik.eth"))
        client.awaitIdle(3_000)
    }

    @Test
    fun `established registrations answering slowly but in time can't hold every engine slot`() {
        // R3-F1: four established registrations whose resolvers take most
        // of the budget but answer inside it; the page keeps two fresh
        // subnames per site in flight. None ever outlives its wait, but
        // once the engine says busy the sites holding a full share are
        // slow, and the user's own name gets the light client again.
        val client = SlotClient(lightClientOk) { data -> if (hasLabel(data, "lagging")) 300 else 20 }
        val http = OneServer { rpcResult(wrapAsOuterInner(ipfsContenthash)) }
        val r = resolver(client, http, deadlineMs = 400)
        (1..4).forEach { runBlocking { r.resolveContenthash("x$it.eth") } }
        val mine = runBlocking { r.resolveContenthash("vitalik.eth") }
        require(mine is EnsResult.Ok && mine.trust.lightClient) { "got $mine" }

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val attackers = (1..4).flatMap { site ->
            (1..2).map {
                Thread {
                    while (!stop.get()) {
                        runBlocking { r.resolveContenthash("lagging.n${counter.incrementAndGet()}.x$site.eth") }
                    }
                }.apply { isDaemon = true; start() }
            }
        }
        Thread.sleep(800)
        val verified = (1..10).count {
            val result = runBlocking { r.resolveContenthash("vitalik.eth") }
            require(result is EnsResult.Ok) { "got $result" }
            Thread.sleep(50)
            result.trust.lightClient
        }
        stop.set(true)
        attackers.forEach { it.join(3_000) }
        assertTrue("user lookups on the light client: $verified of 10", verified >= 9)
        assertTrue("some site marked slow", (1..4).any { r.lightClientSlowSite("x$it.eth") })
        assertFalse(r.lightClientSlowSite("vitalik.eth"))
        client.awaitIdle(3_000)
    }

    @Test
    fun `parse reads the engine's eth_call shapes`() {
        assertEquals(
            EnsLightClient.Call.Ok("0xabcd", 123L),
            EnsLightClient.parse("""{"status":"ok","resultHex":"0xabcd","blockNumber":123,"verified":false}"""),
        )
        assertEquals(
            EnsLightClient.Call.Revert("0x77209fe8", 255L),
            EnsLightClient.parse("""{"status":"revert","dataHex":"0x77209fe8","blockNumber":"0xff","verified":true}"""),
        )
        assertEquals(
            EnsLightClient.Call.Unavailable("no verified head"),
            EnsLightClient.parse("""{"status":"unavailable","reason":"no verified head","verified":false}"""),
        )
        // The host's closed read gate, as opposed to the engine's own "unavailable".
        assertEquals(
            EnsLightClient.Call.Unavailable("light client not ready", notReady = true),
            EnsLightClient.parse(baby.freedom.swarm.MyotisNode.NOT_READY_JSON),
        )
        // A full engine, the engine's own and the host's, is back-pressure.
        assertEquals(
            EnsLightClient.Call.Unavailable("native execution busy", busy = true),
            EnsLightClient.parse("""{"error":"native execution busy"}"""),
        )
        assertEquals(
            EnsLightClient.Call.Unavailable("busy", busy = true),
            EnsLightClient.parse("""{"status":"unavailable","reason":"busy","busy":true}"""),
        )
        assertEquals(
            EnsLightClient.Call.Unavailable("busy", busy = true),
            EnsLightClient.parse("""{"status":"unavailable","reason":"busy"}"""),
        )
        assertEquals(
            EnsLightClient.Call.Unavailable("state unavailable"),
            EnsLightClient.parse("""{"error":"state unavailable"}"""),
        )
        assertTrue(EnsLightClient.parse("""{"status":"ok","resultHex":"0xabc"}""") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse("""{"status":"ok","resultHex":"nothex"}""") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse("not json") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse(null) is EnsLightClient.Call.Unavailable)
        // A verification failure inside the normal result shape is no answer.
        assertEquals(
            EnsLightClient.Call.Unavailable("verification failed: proof mismatch"),
            EnsLightClient.parse("""{"status":"ok","resultHex":"0xabcd","blockNumber":5,"failReason":"proof mismatch"}"""),
        )
        assertTrue(
            EnsLightClient.parse("""{"status":"revert","dataHex":"0x77209fe8","failReason":""}""") is EnsLightClient.Call.Unavailable,
        )
        // `verified` only says finalized vs. head: both are proven answers.
        assertEquals(
            EnsLightClient.Call.Ok("0xabcd", 5L),
            EnsLightClient.parse("""{"status":"ok","resultHex":"0xabcd","blockNumber":5,"verified":false,"failReason":null}"""),
        )
        assertEquals(EnsLightClient.Call.Ok("0x", null), EnsLightClient.parse("""{"status":"ok","resultHex":"0x"}"""))
    }
}

/** `abi.encodeWithSelector(OffchainLookup.selector, sender, urls, callData, callback, extraData)`. */
private fun encodeOffchainLookup(
    sender: String,
    urls: List<String>,
    callData: ByteArray,
    callback: ByteArray,
    extraData: ByteArray,
): String {
    require(callback.size == 4)
    val senderWord = ByteArray(32).also { sender.hexToBytes().copyInto(it, 12) }
    val callbackWord = ByteArray(32).also { callback.copyInto(it, 0) }

    val urlBytes = urls.map { it.toByteArray(Charsets.UTF_8) }
    val urlsTail = run {
        var out = uint256(urls.size.toLong())
        var rel = 32L * urls.size
        for (u in urlBytes) {
            out += uint256(rel)
            rel += 32 + paddedTo32(u).size
        }
        for (u in urlBytes) out += uint256(u.size.toLong()) + paddedTo32(u)
        out
    }
    val callDataTail = uint256(callData.size.toLong()) + paddedTo32(callData)
    val extraDataTail = uint256(extraData.size.toLong()) + paddedTo32(extraData)

    val headSize = 5L * 32
    val urlsOff = headSize
    val callDataOff = urlsOff + urlsTail.size
    val extraDataOff = callDataOff + callDataTail.size

    val body = senderWord + uint256(urlsOff) + uint256(callDataOff) + callbackWord + uint256(extraDataOff) +
        urlsTail + callDataTail + extraDataTail
    return "0x556f1830" + body.toHex()
}

/**
 * Wrap a raw contenthash in the `(bytes result, address resolver)`
 * envelope `resolve(bytes,bytes)` returns, where `result` is itself
 * `abi.encode(bytes contenthash)`. Same layout as ContentHashParseTest.
 */
private fun wrapAsOuterInner(contentHashHex: String): String {
    val contentHash = contentHashHex.hexToBytes()
    val innerEncoded = uint256(0x20L) + uint256(contentHash.size.toLong()) + paddedTo32(contentHash)
    val outerBody = uint256(innerEncoded.size.toLong()) + paddedTo32(innerEncoded)
    return "0x" + (uint256(0x40L) + ByteArray(32) + outerBody).toHex()
}

private fun uint256(v: Long): ByteArray = ByteArray(32).also {
    for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte()
}

private fun paddedTo32(bytes: ByteArray): ByteArray {
    val rem = bytes.size % 32
    return if (rem == 0) bytes.copyOf() else bytes.copyOf(bytes.size + (32 - rem))
}
