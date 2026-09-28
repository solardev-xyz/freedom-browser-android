package baby.freedom.mobile.ens

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnsResolver]'s proven tier (#100): what it takes from the Colibri
 * verifier, labelled how, and every way it hands a lookup on to the RPC
 * servers instead. The verifier is scripted per call ([Prover]); the
 * servers are one honest RPC endpoint through [EnsHttp] — the
 * single-server path, so an answer from them is plainly *not* proven.
 */
class EnsColibriResolveTest {

    private val rpc = "https://rpc.test/"
    private val ur = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"
    private val provenRef = "11".repeat(32)
    private val rpcRef = "22".repeat(32)

    /**
     * The verifier: one `prover` request per call (served by [http]),
     * then [answer]'s status for the call's `to` and `data`.
     */
    private class Prover(
        var answer: (to: String, data: String) -> JSONObject,
        override var available: Boolean = true,
        /** How long a call's `execute` takes, to outlast the resolver's wait. */
        var delayMs: Long = 0,
    ) : EnsColibri.Engine {
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        private val ctxs = HashMap<Long, Pair<String, String>>()
        private val served = HashSet<Long>()
        private val unserved = HashSet<Long>()
        private var next = 1L

        /** Contexts freed, i.e. calls that have ended (a background one included). */
        val freed: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        @Synchronized
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            val call = JSONArray(params).getJSONObject(0)
            val c = call.getString("to") to call.getString("data")
            calls += c
            return next++.also { ctxs[it] = c }
        }

        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit

        override fun execute(ctx: Long): String {
            Thread.sleep(delayMs)
            synchronized(this) {
                // The core's answer to a request its servers couldn't serve.
                if (ctx in unserved) return JSONObject().put("status", "error").put("error", "prover unreachable").toString()
                if (ctx !in served) {
                    val req = JSONObject().put("req_ptr", (ctx * 100).toString()).put("type", "prover")
                        .put("method", "post").put("encoding", "ssz").put("url", "")
                    return JSONObject().put("status", "pending").put("requests", JSONArray().put(req)).toString()
                }
                val (to, data) = ctxs.getValue(ctx)
                return answer(to, data).toString()
            }
        }

        @Synchronized
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) {
            served += req / 100
        }

        @Synchronized
        override fun setError(req: Long, error: String, nodeIndex: Int) {
            unserved += req / 100
        }

        override fun free(ctx: Long) {
            freed += ctx
        }
    }

    /** Whether the provers answer at all (a 503 from every one of them when not). */
    @Volatile
    private var proversUp = true

    private val proverHttp = EnsColibri.Http { _, _, _, _, _ ->
        if (proversUp) EnsColibri.Http.Reply(200, ByteArray(8)) else EnsColibri.Http.Reply(503, ByteArray(0))
    }

    private fun returned(hex: String) = JSONObject().put("status", "success").put("result", hex)
    private fun reverted(hex: String) = JSONObject().put("status", "revert").put("data", hex)

    private class Rpc(private val handler: (to: String, data: String) -> EnsHttp.Reply) : EnsHttp {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val gateway: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var gatewayReply: EnsHttp.Reply? = null
        /** How long each gateway fetch hangs before answering (a black-holed gateway). */
        @Volatile
        var gatewayDelayMs = 0L
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            if (url != "https://rpc.test/") {
                gateway += url
                Thread.sleep(gatewayDelayMs)
                return gatewayReply ?: EnsHttp.Reply(503, "down")
            }
            val params = JSONObject(body!!).getJSONArray("params").getJSONObject(0)
            calls += params.getString("data")
            return handler(params.getString("to"), params.getString("data"))
        }
    }

    private fun rpcAnswers(ref: String = rpcRef) = Rpc { _, _ ->
        EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"${wrapAsOuterInner("e40101fa011b20$ref")}"}""")
    }

    private fun resolver(
        prover: Prover,
        http: EnsHttp,
        colibri: Boolean = true,
        ccipRead: Boolean = true,
        waitMs: Long = 2_000,
        gatewayMs: Long = 20_000,
        clock: () -> Long = System::currentTimeMillis,
    ) = EnsResolver(
        settings = { EnsResolver.Settings(listOf(rpc), ccipRead = ccipRead, colibri = colibri) },
        http = http,
        colibri = EnsColibri(prover, proverHttp),
        colibriWaitMs = waitMs,
        colibriGatewayMs = gatewayMs,
        clock = clock,
    )

    private fun resolve(r: EnsResolver, name: String = "proven.eth") = runBlocking { r.resolveContenthash(name) }

    @Test
    fun `a proven answer is labelled proven, names the prover, and asks no RPC server for the record`() {
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http))

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$provenRef", result.uri)
        assertTrue(result.trust.verified)
        assertTrue(result.trust.proven)
        assertEquals(EnsTrust.Source.COLIBRI, result.trust.source)
        assertEquals(listOf("mainnet1.colibri-proof.tech"), result.trust.agreed)
        // The Universal Resolver's resolve(bytes,bytes), for this name.
        val (to, data) = prover.calls.single()
        assertEquals(ur, to)
        assertTrue(data.startsWith("0x9061b923"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a proven answer is cached like a cross-checked one`() {
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) })
        val r = resolver(prover, rpcAnswers())

        resolve(r)
        val again = resolve(r)

        assertTrue((again as EnsResult.Ok).trust.proven)
        assertEquals(1, prover.calls.size)
    }

    @Test
    fun `WNS and GNS names are proven straight on their registry`() {
        val prover = Prover({ _, _ ->
            // The registry's contenthash(bytes32) return: ABI bytes.
            val ch = "e40101fa011b20$provenRef".hexToBytes()
            returned("0x" + (uint256(0x20) + uint256(ch.size.toLong()) + paddedTo32(ch)).toHex())
        })

        val result = resolve(resolver(prover, rpcAnswers()), "name.wei")

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.proven)
        val (to, data) = prover.calls.single()
        assertEquals(NameSystem.WNS.contractAddress, to)
        assertTrue(data.startsWith("0xbc1c58d1"))
    }

    @Test
    fun `when the verifier fails the servers answer, unproven`() {
        val prover = Prover({ _, _ -> JSONObject().put("status", "error").put("error", "prover down") })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http))

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$rpcRef", result.uri)
        assertFalse(result.trust.proven)
        assertEquals(EnsTrust.Source.RPC, result.trust.source)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `a build without the verifier goes straight to the servers`() {
        val prover = Prover({ _, _ -> error("not asked") }, available = false)
        val result = resolve(resolver(prover, rpcAnswers()))
        assertEquals(EnsTrust.Source.RPC, (result as EnsResult.Ok).trust.source)
        assertTrue(prover.calls.isEmpty())
    }

    @Test
    fun `switched off in Settings, the verifier isn't asked`() {
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) })

        val result = resolve(resolver(prover, rpcAnswers(), colibri = false))

        assertEquals("bzz://$rpcRef", (result as EnsResult.Ok).uri)
        assertTrue(prover.calls.isEmpty())
    }

    @Test
    fun `a proof that doesn't come in time leaves the lookup to the servers`() {
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) }, delayMs = 1_000)
        val t0 = System.currentTimeMillis()

        val result = resolve(resolver(prover, rpcAnswers(), waitMs = 300))

        assertEquals("bzz://$rpcRef", (result as EnsResult.Ok).uri)
        assertFalse(result.trust.proven)
        assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 900)
    }

    @Test
    fun `after a failure the verifier is skipped for a while, then asked again, and a proof resets it`() {
        var now = 1_000_000L
        proversUp = false
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) })
        val r = resolver(prover, rpcAnswers(), clock = { now })

        assertFalse((resolve(r, "a.eth") as EnsResult.Ok).trust.proven)
        assertEquals(1, prover.calls.size)
        // Backing off: a new name goes straight to the servers.
        assertFalse((resolve(r, "b.eth") as EnsResult.Ok).trust.proven)
        assertEquals(1, prover.calls.size)
        // Past the back-off it is asked again; a second failure doubles it.
        now += EnsResolver.COLIBRI_BACKOFF_MS
        resolve(r, "c.eth")
        assertEquals(2, prover.calls.size)
        now += EnsResolver.COLIBRI_BACKOFF_MS
        resolve(r, "d.eth")
        assertEquals(2, prover.calls.size)
        now += EnsResolver.COLIBRI_BACKOFF_MS
        proversUp = true
        assertTrue((resolve(r, "e.eth") as EnsResult.Ok).trust.proven)
        assertTrue((resolve(r, "f.eth") as EnsResult.Ok).trust.proven)
        assertEquals(4, prover.calls.size)
    }

    @Test
    fun `one name's proof failing doesn't make the others skip the verifier`() {
        val prover = Prover({ _, _ ->
            JSONObject().put("status", "error").put("error", "proof doesn't check out")
        })
        val r = resolver(prover, rpcAnswers())

        assertFalse((resolve(r, "a.eth") as EnsResult.Ok).trust.proven)
        assertNull(r.colibriBackoff.remainingMs())
        // The next name still asks the verifier.
        resolve(r, "b.eth")
        assertEquals(2, prover.calls.size)
    }

    @Test
    fun `a missed wait whose background call then fails is one failure, not two`() {
        var now = 1_000_000L
        proversUp = false
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) }, delayMs = 400)
        val r = resolver(prover, rpcAnswers(), waitMs = 100, clock = { now })

        assertFalse((resolve(r, "a.eth") as EnsResult.Ok).trust.proven)
        // Let the background call run to its own (unreachable) failure.
        val deadline = System.currentTimeMillis() + 5_000
        while (prover.freed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, prover.freed.size)
        // Counted once: the first, un-doubled back-off, and no longer.
        assertEquals(EnsResolver.COLIBRI_BACKOFF_MS, r.colibriBackoff.remainingMs())
        now += EnsResolver.COLIBRI_BACKOFF_MS
        assertNull(r.colibriBackoff.remainingMs())
    }

    @Test
    fun `a missed wait backs off until the background call comes in`() {
        val prover = Prover({ _, _ -> returned(wrapAsOuterInner("e40101fa011b20$provenRef")) }, delayMs = 600)
        val r = resolver(prover, rpcAnswers(), waitMs = 100)

        assertFalse((resolve(r, "a.eth") as EnsResult.Ok).trust.proven)
        assertFalse((resolve(r, "b.eth") as EnsResult.Ok).trust.proven)
        assertEquals(1, prover.calls.size)
        // The first call's proof arrives in the background and ends the back-off.
        val deadline = System.currentTimeMillis() + 5_000
        while (r.colibriBackoff.remainingMs() != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNull(r.colibriBackoff.remainingMs())
        prover.delayMs = 0
        assertTrue((resolve(r, "c.eth") as EnsResult.Ok).trust.proven)
    }

    @Test
    fun `a proven ResolverNotFound is a proven negative`() {
        val prover = Prover({ _, _ -> reverted("0x77209fe8" + "00".repeat(32)) })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http))

        require(result is EnsResult.NotFound) { "got $result" }
        assertEquals("NO_RESOLVER", result.reason)
        assertTrue(result.trust.proven)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a revert that proves nothing about the record goes to the servers`() {
        // No data (ambiguous), and a resolver's own error (proves it
        // failed, not that the record is absent).
        for (revert in listOf("0x", "0x08c379a0" + "00".repeat(64))) {
            val prover = Prover({ _, _ -> reverted(revert) })
            val http = rpcAnswers()

            val result = resolve(resolver(prover, http))

            require(result is EnsResult.Ok) { "$revert: got $result" }
            assertFalse(revert, result.trust.proven)
            assertEquals(revert, 1, http.calls.size)
        }
    }

    @Test
    fun `a NameNFT registry's revert goes to the servers`() {
        val prover = Prover({ _, _ -> reverted("0x77209fe8") })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http), "name.gwei")

        // (The scripted server answers in the Universal Resolver's
        // envelope, which a registry's read doesn't decode: what counts
        // here is who answered.)
        val trust = when (result) {
            is EnsResult.Ok -> result.trust
            is EnsResult.Unsupported -> result.trust
            else -> error("got $result")
        }
        assertEquals(EnsTrust.Source.RPC, trust.source)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `an OffchainLookup's callback is proven too, so the gateway's answer comes back proven`() {
        val lookup = EnsResolver.decodeOffchainLookup(fixtureRevert())!!
        val gatewayAnswer = "cafe0042".hexToBytes()
        val expectedCallback = "0x" + (lookup.callback + abiEncodeTwoBytes(gatewayAnswer, lookup.extraData)).toHex()
        val prover = Prover({ _, data ->
            if (data.startsWith("0x9061b923")) reverted(fixtureRevert()) else returned(wrapAsOuterInner("e40101fa011b20$provenRef"))
        })
        val http = rpcAnswers().apply { gatewayReply = EnsHttp.Reply(200, """{"data":"0x${gatewayAnswer.toHex()}"}""") }

        val result = resolve(resolver(prover, http), "1.offchainexample.eth")

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.proven)
        // What was proven is the resolver's acceptance, not an on-chain record.
        assertTrue(result.trust.offchain)
        assertEquals(listOf("https://ccip-v3.ens.xyz"), http.gateway)
        assertEquals(expectedCallback, prover.calls[1].second)
        assertEquals(ur.lowercase(), prover.calls[1].first.lowercase())
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `with CCIP-Read off a proven OffchainLookup stops there, no gateway asked`() {
        val prover = Prover({ _, _ -> reverted(fixtureRevert()) })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http, ccipRead = false), "1.offchainexample.eth")

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_DISABLED", result.reason)
        assertTrue(http.gateway.isEmpty() && http.calls.isEmpty())
    }

    @Test
    fun `a gateway that fails a proven lookup ends it there, without asking the gateway again`() {
        val prover = Prover({ _, _ -> reverted(fixtureRevert()) })
        // The servers' own read would defer to the same failing gateway.
        val http = Rpc { _, _ ->
            EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"${fixtureRevert()}"}}""")
        }
        val gateways = EnsResolver.decodeOffchainLookup(fixtureRevert())!!.urls.filter { it.startsWith("https://") }

        val result = resolve(resolver(prover, http), "1.offchainexample.eth")

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        assertTrue(result.retryable)
        // Each gateway was asked once, by the proven pass; the servers
        // weren't asked to fetch it all over again.
        assertEquals(gateways.size, http.gateway.size)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a stalled gateway fails a proven lookup within the quorum's read budget, not the whole CCIP budget`() {
        val prover = Prover({ _, _ -> reverted(fixtureRevert()) })
        val http = Rpc { _, _ ->
            EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"${fixtureRevert()}"}}""")
        }.apply { gatewayDelayMs = 10_000 }

        val t0 = System.currentTimeMillis()
        val result = resolve(resolver(prover, http, gatewayMs = 500), "1.offchainexample.eth")
        val took = System.currentTimeMillis() - t0

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        // Held to the gateway deadline, not to the gateway's own hang or
        // the 30 s proven-pass budget.
        assertTrue("took ${took}ms", took < 3_000)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `a pass that gave up on a stalled gateway sends the name to no further gateway`() {
        val revert = offchainLookupRevert(
            sender = ur,
            urls = listOf("https://gw1.example/{sender}/{data}", "https://gw2.example/{sender}/{data}"),
            callData = "deadbeef".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        val prover = Prover({ _, _ -> reverted(revert) })
        val http = rpcAnswers().apply { gatewayDelayMs = 800 }

        val result = resolve(resolver(prover, http, gatewayMs = 200), "1.offchainexample.eth")

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        // Long enough for the stalled first fetch to end and the loop to
        // reach the second URL, had nothing stopped it.
        Thread.sleep(1_500)
        assertEquals(1, http.gateway.size)
        assertTrue(http.gateway.single().startsWith("https://gw1.example/"))
    }

    @Test
    fun `gateways that refuse in time are reported as failing, not as out of time`() {
        val prover = Prover({ _, _ -> reverted(fixtureRevert()) })
        val http = rpcAnswers()

        val result = resolve(resolver(prover, http), "1.offchainexample.eth")

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        assertEquals("CCIP gateways unavailable or returned invalid data", result.error)
    }

    @Test
    fun `the gateway deadline starts when the gateways are asked, not with the proof before it`() {
        val prover = Prover({ _, data ->
            if (data.startsWith("0x9061b923")) {
                // A slow first proof, longer than the gateway deadline.
                Thread.sleep(600)
                reverted(fixtureRevert())
            } else {
                returned(wrapAsOuterInner("e40101fa011b20$provenRef"))
            }
        })
        val http = rpcAnswers().apply {
            gatewayReply = EnsHttp.Reply(200, """{"data":"0xcafe0042"}""")
            gatewayDelayMs = 200
        }

        val result = resolve(resolver(prover, http, gatewayMs = 500), "1.offchainexample.eth")

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.proven)
    }

    @Test
    fun `a gateway answer whose callback can't be proven hands the lookup to the servers`() {
        val prover = Prover({ _, data ->
            if (data.startsWith("0x9061b923")) reverted(fixtureRevert()) else JSONObject().put("status", "error").put("error", "bad proof")
        })
        val http = rpcAnswers().apply { gatewayReply = EnsHttp.Reply(200, """{"data":"0xcafe0042"}""") }

        val result = resolve(resolver(prover, http), "1.offchainexample.eth")

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals(EnsTrust.Source.RPC, result.trust.source)
        assertFalse(http.calls.isEmpty())
    }

    @Test
    fun `a re-check's proof allowance is the wait, only while the verifier would be asked for that name`() {
        val on = EnsResolver.Settings(listOf(rpc), colibri = true)
        val r = resolver(Prover({ _, _ -> returned("0x") }), rpcAnswers(), waitMs = 1_234)
        assertEquals(1_234L, r.colibriWaitFor(on, "proven.eth"))
        assertEquals(1_234L, r.colibriWaitFor(on, "name.wei"))
        // A `.tez` name never goes to the verifier, so it gets no wait.
        assertEquals(0L, r.colibriWaitFor(on, "name.tez"))
        assertEquals(0L, r.colibriWaitFor(on.copy(colibri = false), "proven.eth"))
        r.colibriBackoff.failed()
        assertEquals(0L, r.colibriWaitFor(on, "proven.eth"))
        r.colibriBackoff.succeeded()
        assertEquals(0L, resolver(Prover({ _, _ -> returned("0x") }, available = false), rpcAnswers()).colibriWaitFor(on, "proven.eth"))
    }

    @Test
    fun `Tezos names never go to the verifier`() {
        val prover = Prover({ _, _ -> error("not asked") })
        val r = EnsResolver(
            settings = { EnsResolver.Settings(listOf(rpc)) },
            http = rpcAnswers(),
            tezos = TezosDomainsResolver(emptyList()),
            colibri = EnsColibri(prover, proverHttp),
        )
        runBlocking { r.resolveContenthash("name.tez") }
        assertTrue(prover.calls.isEmpty())
    }

    @Test
    fun `an answer the servers give is never labelled proven`() {
        assertNull(EnsTrust(verified = true).takeIf { it.proven })
        assertNull(EnsTrust(verified = false, source = EnsTrust.Source.COLIBRI).takeIf { it.proven })
    }

    private fun fixtureRevert(): String = javaClass.getResource("/ccip/offchainexample-revert.hex")!!.readText().trim()
}

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
