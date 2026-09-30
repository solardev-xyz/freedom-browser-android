package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.ens.EnsColibri
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The router's proof tiers (#329): [MyotisChainSource] over a scripted light client, [ColibriChainSource] over a scripted verifier. */
class ProofSourcesTest {
    private val address = "0x" + "ab".repeat(20)
    private val hash = "0x" + "cd".repeat(32)

    // ---- Myotis ----

    private class Link(var ready: Set<Long> = setOf(1L, 100L), val reply: (String) -> String) : MyotisChainSource.Link {
        val asked = mutableListOf<String>()
        val pages = mutableListOf<Boolean>()
        override fun isReady(chainId: Long) = chainId in ready
        override suspend fun read(chainId: Long, method: String, paramsJson: String, page: Boolean): String {
            pages += page
            asked += "$chainId $method $paramsJson"
            return reply(method)
        }
    }

    @Test
    fun `the light client is available only on a chain it has ready`() {
        val link = Link(ready = setOf(100L)) { "{}" }
        val source = MyotisChainSource(link)
        assertTrue(source.isAvailable(100))
        // Mainnet switched off, still syncing, parked on a stale anchor or
        // asleep in the background: the service publishes it as not ready.
        assertFalse(source.isAvailable(1))
        // Not a chain the light client covers, even if something says ready.
        link.ready = setOf(137L)
        assertFalse(source.isAvailable(137))
        assertFalse(source.canBroadcast)
    }

    @Test
    fun `a proven answer is verified by the light client, at its block`() = runTest {
        val link = Link { """{"result":"0x2a","blockNumber":1234}""" }
        val r = MyotisChainSource(link).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList())
        assertEquals("0x2a", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(ChainSource.MYOTIS, r.trust.source)
        assertEquals(1234L, r.trust.block)
        assertEquals(listOf("""100 eth_getBalance ["$address","latest"]"""), link.asked)
    }

    @Test
    fun `a receipt keeps its shape`() = runTest {
        val link = Link { """{"result":{"transactionHash":"$hash","status":"0x1"},"blockNumber":16}""" }
        val r = MyotisChainSource(link).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
        assertEquals(hash, (r.result as JSONObject).getString("transactionHash"))
    }

    @Test
    fun `a light client's proven revert is a deterministic error`() = runTest {
        val link = Link { """{"revert":"0x08c379a0","blockNumber":9}""" }
        try {
            MyotisChainSource(link).request(1, "eth_call", JSONArray().put(JSONObject().put("to", address)), emptyList())
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.deterministic)
            assertEquals("0x08c379a0", e.data)
        }
    }

    @Test
    fun `no proven answer, or a stale or parked chain, is a failure the router moves on from`() = runTest {
        val replies = listOf(
            """{"status":"unavailable","reason":"light client not ready","notReady":true}""",
            """{"status":"unavailable","reason":"busy","busy":true}""",
            """{"status":"unavailable","reason":"not seen in the blocks the light client scanned"}""",
            """{"result":null}""",
            "not json",
        )
        for (reply in replies) {
            try {
                MyotisChainSource(Link { reply }).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
                fail(reply)
            } catch (e: ChainRpcException) {
                fail("$reply: ${e.message}")
            } catch (e: MyotisChainSource.Unanswered) {
            }
        }
    }

    @Test
    fun `a method it doesn't prove never reaches the light client`() = runTest {
        val link = Link { """{"result":"0x1"}""" }
        try {
            MyotisChainSource(link).request(1, "eth_getLogs", JSONArray().put(JSONObject()), emptyList())
            fail()
        } catch (_: MyotisChainSource.Unanswered) {
        }
        assertTrue(link.asked.isEmpty())
    }

    // ---- Colibri ----

    /** A verifier that answers [final] at once; records what it was asked. */
    private class Verifier(private val final: JSONObject) : EnsColibri.Engine {
        override val available = true
        val created = mutableListOf<Triple<String, String, Long>>()
        var freed = 0
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            created += Triple(method, params, chainId)
            return 7L
        }
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit
        override fun execute(ctx: Long) = final.toString()
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = Unit
        override fun setError(req: Long, error: String, nodeIndex: Int) = Unit
        override fun free(ctx: Long) {
            freed++
        }
    }

    private fun colibri(v: Verifier) = ColibriChainSource(
        EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("no network in this test") }),
        present = { true },
    )

    @Test
    fun `a proof is verified by the Colibri prover`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", "0x64"))
        val r = colibri(v).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), listOf("https://rpc.gnosis.test"))
        assertEquals("0x64", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(ChainSource.COLIBRI, r.trust.source)
        assertEquals(listOf("gnosis.colibri-proof.tech"), r.trust.agreed)
        assertEquals(Triple("eth_getBalance", """["$address","latest"]""", 100L), v.created.single())
        assertEquals(1, v.freed)
    }

    @Test
    fun `a proof failure is a failure the router moves on from`() = runTest {
        val v = Verifier(JSONObject().put("status", "error").put("error", "invalid proof: state root mismatch"))
        try {
            colibri(v).request(1, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList())
            fail()
        } catch (e: ChainRpcException) {
            fail("a proof failure isn't the chain's answer: ${e.message}")
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.message!!.contains("state root mismatch"))
        }
        assertEquals(1, v.freed)
    }

    @Test
    fun `a Colibri-proven revert is a deterministic error`() = runTest {
        val v = Verifier(JSONObject().put("status", "revert").put("data", "0xdead"))
        try {
            colibri(v).request(1, "eth_call", JSONArray().put(JSONObject().put("to", address)).put("latest"), emptyList())
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.deterministic)
            assertEquals("0xdead", e.data)
        }
    }

    @Test
    fun `a null receipt is not a proven no`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", JSONObject.NULL))
        try {
            colibri(v).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
            fail()
        } catch (_: ColibriChainSource.Unanswered) {
        }
    }

    @Test
    fun `a pending nonce and unprovable methods never reach the verifier`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", "0x1"))
        for ((method, params) in listOf(
            "eth_getTransactionCount" to JSONArray().put(address).put("pending"),
            "eth_gasPrice" to JSONArray(),
            "eth_getLogs" to JSONArray().put(JSONObject()),
        )) {
            try {
                colibri(v).request(1, method, params, emptyList())
                fail(method)
            } catch (_: ColibriChainSource.Unanswered) {
            }
        }
        assertTrue(v.created.isEmpty())
    }

    @Test
    fun `the block number comes from a proven latest block`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", JSONObject().put("number", "0x2a").put("hash", "0x01")))
        val r = colibri(v).request(1, "eth_blockNumber", JSONArray(), emptyList())
        assertEquals("0x2a", r.result)
        assertEquals(42L, r.trust.block)
        assertEquals(Triple("eth_getBlockByNumber", """["latest",false]""", 1L), v.created.single())
    }

    @Test
    fun `Colibri covers Ethereum and Gnosis only, and only when the verifier may be present`() {
        val source = colibri(Verifier(JSONObject()))
        assertTrue(source.isAvailable(1))
        assertTrue(source.isAvailable(100))
        assertFalse(source.isAvailable(137))
        assertFalse(ColibriChainSource(EnsColibri(Verifier(JSONObject())), present = { false }).isAvailable(1))
        assertFalse(source.canBroadcast)
        assertNull(EnsColibri.CHAINS[137])
    }

    // ---- Colibri: the switch and the back-off ----

    /**
     * A verifier whose first round asks for one prover request and whose
     * next reports [final]; [http] decides what that request gets.
     */
    private class Roundtrip(private val final: JSONObject) : EnsColibri.Engine {
        override val available = true
        val created = AtomicInteger()
        private val rounds = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        private val next = AtomicLong(10)
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            created.incrementAndGet()
            return next.incrementAndGet()
        }
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit
        override fun execute(ctx: Long): String {
            val round = rounds.merge(ctx, 1, Int::plus)!!
            return if (round == 1) {
                JSONObject().put("status", "pending")
                    .put("requests", JSONArray().put(JSONObject().put("type", "prover").put("req_ptr", "5")))
                    .toString()
            } else {
                final.toString()
            }
        }
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = Unit
        override fun setError(req: Long, error: String, nodeIndex: Int) = Unit
        override fun free(ctx: Long) = Unit
    }

    private val proven = JSONObject().put("status", "success").put("result", "0x64")
    private val balance = JSONArray().put(address).put("latest")

    @Test
    fun `with Colibri proofs off nothing goes to the prover`() = runTest {
        val v = Verifier(proven)
        var on = false
        val source = ColibriChainSource(EnsColibri(v), present = { true }, enabled = { on })
        assertFalse(source.isAvailable(100))
        assertEquals(ProofTierGap.OFF, source.gap(100))
        try {
            source.request(100, "eth_getBalance", balance, emptyList())
            fail()
        } catch (_: ColibriChainSource.Unanswered) {
        }
        assertTrue(v.created.isEmpty())
        on = true
        assertTrue(source.isAvailable(100))
        assertEquals("0x64", source.request(100, "eth_getBalance", balance, emptyList()).result)
    }

    @Test
    fun `an unreachable prover is skipped for a while, then asked again`() = runTest {
        val now = AtomicLong(1_000_000)
        val v = Roundtrip(JSONObject().put("status", "error").put("error", "all provers failed"))
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("connect timed out") }),
            present = { true },
            clock = now::get,
        )
        try {
            source.request(100, "eth_getBalance", balance, emptyList())
            fail()
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.unreachable)
        }
        // Gnosis backs off; mainnet's provers are other hosts, still asked.
        assertEquals(ProofTierGap.UNREACHABLE, source.gap(100))
        assertFalse(source.isAvailable(100))
        assertTrue(source.isAvailable(1))
        try {
            source.request(100, "eth_getBalance", balance, emptyList())
            fail()
        } catch (_: ColibriChainSource.Unanswered) {
        }
        assertEquals(1, v.created.get())

        // After the back-off: asked again; a second failure doubles it.
        now.addAndGet(ColibriChainSource.BACKOFF_MS + 1)
        assertTrue(source.isAvailable(100))
        runCatching { source.request(100, "eth_getBalance", balance, emptyList()) }
        assertEquals(2, v.created.get())
        now.addAndGet(ColibriChainSource.BACKOFF_MS + 1)
        assertFalse(source.isAvailable(100))
        now.addAndGet(ColibriChainSource.BACKOFF_MS)
        assertTrue(source.isAvailable(100))
    }

    @Test
    fun `a proof that fails its own check doesn't back the prover off`() = runTest {
        val v = Verifier(JSONObject().put("status", "error").put("error", "invalid proof: state root mismatch"))
        val source = colibri(v)
        runCatching { source.request(100, "eth_getBalance", balance, emptyList()) }
        assertTrue(source.isAvailable(100))
        assertNull(source.backoffRemainingMs(100))
    }

    @Test
    fun `a missed wait backs off at once, and the call finishing in the background ends it`() = runBlocking {
        val gate = CountDownLatch(1)
        val v = Roundtrip(proven)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ ->
                gate.await(10, TimeUnit.SECONDS)
                EnsColibri.Http.Reply(200, ByteArray(0))
            }),
            present = { true },
        )
        // The router's wait (its tier timeout) runs out on a stalled prover.
        assertNull(withRouterWait(200) { source.request(100, "eth_getBalance", balance, emptyList()) })
        assertEquals(ProofTierGap.UNREACHABLE, source.gap(100))
        // The next read doesn't wait again: it isn't even asked.
        assertFalse(source.isAvailable(100))
        assertEquals(1, v.created.get())
        // The stalled call carries on and proves: the back-off ends.
        gate.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!source.isAvailable(100) && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(source.isAvailable(100))
    }

    @Test
    fun `a reader that stops waiting before the router does doesn't back the prover off`() = runBlocking {
        val gate = CountDownLatch(1)
        val v = Roundtrip(proven)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ ->
                gate.await(10, TimeUnit.SECONDS)
                EnsColibri.Http.Reply(200, ByteArray(0))
            }),
            present = { true },
        )
        try {
            // A caller's own timeout, shorter than the router's wait (R4-M1).
            assertNull(withTimeoutOrNull(100) { withRouterWait(5_000) { source.request(100, "eth_getBalance", balance, emptyList()) } })
            assertNull(source.backoffRemainingMs(100))
            // The reader going away: the user left the balances page mid-read.
            val reader = launch(Dispatchers.Default) {
                withRouterWait(5_000) { source.request(100, "eth_getBalance", balance, emptyList()) }
            }
            Thread.sleep(100)
            reader.cancelAndJoin()
            assertNull(source.backoffRemainingMs(100))
            assertTrue(source.isAvailable(100))
            // A source asked outside the router (no wait of its own) isn't backed off by its reader either.
            assertNull(withTimeoutOrNull(100) { source.request(100, "eth_getBalance", balance, emptyList()) })
            assertTrue(source.isAvailable(100))
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `the router's wait is null when it runs out, and passes the caller's own cancellation on`() = runBlocking {
        assertNull(withRouterWait(50) { kotlinx.coroutines.delay(5_000); 1 })
        assertEquals(1, withRouterWait(5_000) { 1 })
        val ranOut = mutableListOf<Boolean>()
        assertNull(
            withTimeoutOrNull(50) {
                withRouterWait(5_000) {
                    try {
                        kotlinx.coroutines.delay(5_000)
                    } finally {
                        ranOut += currentCoroutineContext()[RouterWait]!!.ranOut
                    }
                }
            },
        )
        assertEquals(listOf(false), ranOut)
        withRouterWait(50) {
            try {
                kotlinx.coroutines.delay(5_000)
            } finally {
                ranOut += currentCoroutineContext()[RouterWait]!!.ranOut
            }
        }
        assertEquals(listOf(false, true), ranOut)
    }

    // ---- Colibri: a page's read doesn't back the wallet's off (R2-F1) ----

    /**
     * A verifier whose `eth_call`s ask for one prover request (which
     * [http] may stall or fail) and whose `latest`-block canaries answer
     * [canary] at once, or ask for a request too when [canary] is null.
     */
    private class PageAndCanary(private val canary: JSONObject?) : EnsColibri.Engine {
        override val available = true
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val byCtx = java.util.concurrent.ConcurrentHashMap<Long, String>()
        private val rounds = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        private val next = AtomicLong(10)
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            methods += method
            return next.incrementAndGet().also { byCtx[it] = method }
        }
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit
        override fun execute(ctx: Long): String {
            val round = rounds.merge(ctx, 1, Int::plus)!!
            if (byCtx[ctx] == "eth_getBlockByNumber" && canary != null) return canary.toString()
            return if (round == 1) {
                JSONObject().put("status", "pending")
                    .put("requests", JSONArray().put(JSONObject().put("type", "prover").put("req_ptr", "5")))
                    .toString()
            } else {
                JSONObject().put("status", "error").put("error", "all provers failed").toString()
            }
        }
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = Unit
        override fun setError(req: Long, error: String, nodeIndex: Int) = Unit
        override fun free(ctx: Long) = Unit
    }

    private val page = RoutingContext.forPage("https://dapp.example")
    private val call = JSONArray().put(JSONObject().put("to", "0x" + "11".repeat(20)).put("data", "0x")).put("latest")
    private val latestBlock = JSONObject().put("status", "success")
        .put("result", JSONObject().put("number", "0x10"))

    /** `eth_call`s ask for a prover request ([http] may stall it); everything else proves at once. */
    private class SlowCalls : EnsColibri.Engine {
        override val available = true
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val byCtx = java.util.concurrent.ConcurrentHashMap<Long, String>()
        private val rounds = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        private val next = AtomicLong(10)
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            methods += method
            return next.incrementAndGet().also { byCtx[it] = method }
        }
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit
        override fun execute(ctx: Long): String {
            val round = rounds.merge(ctx, 1, Int::plus)!!
            if (byCtx[ctx] != "eth_call") return JSONObject().put("status", "success").put("result", "0x64").toString()
            return if (round == 1) {
                JSONObject().put("status", "pending")
                    .put("requests", JSONArray().put(JSONObject().put("type", "prover").put("req_ptr", "5")))
                    .toString()
            } else {
                JSONObject().put("status", "success").put("result", "0x").toString()
            }
        }
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = Unit
        override fun setError(req: Long, error: String, nodeIndex: Int) = Unit
        override fun free(ctx: Long) = Unit
    }

    @Test
    fun `a site's slow calls can't take every slot from the wallet's reads`() = runBlocking {
        val gate = CountDownLatch(1)
        val v = SlowCalls()
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ ->
                gate.await(10, TimeUnit.SECONDS)
                EnsColibri.Http.Reply(200, ByteArray(0))
            }),
            present = { true },
        )
        try {
            // A site keeps slow calls going: each misses the router's wait
            // and carries on in the background, holding its slot.
            repeat(ColibriChainSource.MAX_IN_FLIGHT) {
                runCatching { withRouterWait(200) { source.request(100, "eth_call", call, emptyList(), page) } }
            }
            // Only the sites' share of them reached the verifier.
            assertEquals(ColibriChainSource.MAX_PAGE_IN_FLIGHT, v.methods.count { it == "eth_call" })
            // The wallet's reads are still proven, one after another and side by side.
            repeat(3) {
                assertEquals("0x64", source.request(100, "eth_getBalance", balance, emptyList()).result)
            }
            val both = listOf(
                async { source.request(100, "eth_getBalance", balance, emptyList()) },
                async { source.request(100, "eth_getBalance", balance, emptyList()) },
            )
            both.forEach { assertEquals(ChainSource.COLIBRI, it.await().trust.source) }
            assertTrue(source.isAvailable(100))
        } finally {
            gate.countDown()
        }
        // Once the sites' calls end, their slots come back.
        val deadline = System.nanoTime() + 5_000_000_000L
        var admitted = false
        while (!admitted && System.nanoTime() < deadline) {
            val before = v.methods.count { it == "eth_call" }
            runCatching { withRouterWait(500) { source.request(100, "eth_call", call, emptyList(), page) } }
            admitted = v.methods.count { it == "eth_call" } > before
            if (!admitted) Thread.sleep(20)
        }
        assertTrue(admitted)
    }

    @Test
    fun `a page's call that misses its wait doesn't back off a healthy prover`() = runBlocking {
        val gate = CountDownLatch(1)
        val v = PageAndCanary(latestBlock)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ ->
                gate.await(10, TimeUnit.SECONDS)
                throw IOException("still stalled")
            }),
            present = { true },
        )
        // The router's 2 s page wait runs out on a call the prover is slow to prove.
        assertNull(withRouterWait(200) { source.request(100, "eth_call", call, emptyList(), page) })
        // A canary no page shapes checks the prover; it proves at once.
        val deadline = System.nanoTime() + 5_000_000_000L
        while ("eth_getBlockByNumber" !in v.methods && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        assertTrue("eth_getBlockByNumber" in v.methods)
        assertNull(source.backoffRemainingMs(100))
        assertTrue(source.isAvailable(100))
        // Even when the page's call finally fails in the background, as unreachable.
        gate.countDown()
        Thread.sleep(200)
        assertNull(source.backoffRemainingMs(100))
        assertTrue(source.isAvailable(100))
    }

    @Test
    fun `a page's miss backs off only when the canary can't reach the prover either`() = runBlocking {
        val v = PageAndCanary(canary = null)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("connect timed out") }),
            present = { true },
        )
        try {
            source.request(100, "eth_call", call, emptyList(), page)
            fail()
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.unreachable)
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (source.isAvailable(100) && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(ProofTierGap.UNREACHABLE, source.gap(100))
        assertEquals(listOf("eth_call", "eth_getBlockByNumber"), v.methods.toList())
    }

    @Test
    fun `a page looping misses runs one canary per interval`() = runBlocking {
        val now = AtomicLong(1_000_000)
        val v = PageAndCanary(latestBlock)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("the prover can't prove this call in time") }),
            present = { true },
            clock = now::get,
        )
        repeat(5) { runCatching { source.request(100, "eth_call", call, emptyList(), page) } }
        val deadline = System.nanoTime() + 5_000_000_000L
        while ("eth_getBlockByNumber" !in v.methods && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        assertEquals(1, v.methods.count { it == "eth_getBlockByNumber" })
        assertTrue(source.isAvailable(100))
        // Past the interval, the next page miss may check again.
        now.addAndGet(ColibriChainSource.CANARY_INTERVAL_MS)
        runCatching { source.request(100, "eth_call", call, emptyList(), page) }
        val d2 = System.nanoTime() + 5_000_000_000L
        while (v.methods.count { it == "eth_getBlockByNumber" } < 2 && System.nanoTime() < d2) Thread.sleep(20)
        assertEquals(2, v.methods.count { it == "eth_getBlockByNumber" })
        assertTrue(source.isAvailable(100))
    }

    @Test
    fun `the same miss from the wallet still backs off`() = runBlocking {
        val v = PageAndCanary(latestBlock)
        val source = ColibriChainSource(
            EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("connect timed out") }),
            present = { true },
        )
        runCatching { source.request(100, "eth_call", call, emptyList()) }
        assertEquals(ProofTierGap.UNREACHABLE, source.gap(100))
        assertEquals(listOf("eth_call"), v.methods.toList())
    }

    @Test
    fun `a page's read reaches the light client marked as a page's`() = runTest {
        val link = Link { """{"result":"0x1","blockNumber":5}""" }
        MyotisChainSource(link).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList(), page)
        MyotisChainSource(link).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList())
        assertEquals(listOf(true, false), link.pages.toList())
    }
}
