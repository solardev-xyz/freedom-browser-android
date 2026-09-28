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
        override fun ethCall(to: String, data: String, timeoutMs: Long): EnsLightClient.Call {
            lastTimeoutMs = timeoutMs
            if (to == EnsResolver.ENS_REGISTRY && data == EnsResolver.PROBE_CALL_DATA) {
                probes += timeoutMs
                val task = FutureTask { answer(to, data) }
                Thread(task).apply { isDaemon = true }.start()
                return try {
                    task.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms", timedOut = true)
                }
            }
            calls += to to data
            return answer(to, data)
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
    ) = EnsResolver({ settings() }, http, TezosDomainsResolver(), client, deadlineMs, backoffMs)

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
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("busy") }
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
        val client = FakeLightClient { _, _ -> EnsLightClient.Call.Unavailable("busy") }
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
        val client = FakeLightClient { _, _ -> if (failing) EnsLightClient.Call.Unavailable("busy") else lightClientOk }
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
        val client = FakeLightClient { _, _ -> if (fail) EnsLightClient.Call.Unavailable("busy") else lightClientOk }
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
        assertEquals(
            EnsLightClient.Call.Unavailable("native execution busy"),
            EnsLightClient.parse("""{"error":"native execution busy"}"""),
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
