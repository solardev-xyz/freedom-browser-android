package baby.freedom.mobile.ens

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [EnsColibri] (#100) driving a scripted verifier ([ScriptEngine], the
 * [EnsColibri.Engine] seam) over a scripted network ([ScriptHttp]): how
 * each request the core lists is routed, retried and answered, and that
 * the native context is freed however the call ends.
 */
class EnsColibriTest {

    private val rpcs = listOf("https://rpc1.test", "https://rpc2.test/")

    /**
     * A verifier that asks for [rounds] of requests, one round per
     * `execute`, then answers [final]. Records what the core was told.
     */
    private class ScriptEngine(
        private val rounds: List<List<JSONObject>>,
        private val final: JSONObject,
        override val available: Boolean = true,
    ) : EnsColibri.Engine {
        val created = mutableListOf<Pair<String, String>>()
        val responses: MutableMap<Long, Pair<ByteArray, Int>> = Collections.synchronizedMap(mutableMapOf())
        val errors: MutableMap<Long, String> = Collections.synchronizedMap(mutableMapOf())
        val freed = mutableListOf<Long>()
        var minLatestTs = -1L
        private var step = 0

        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            created += method to params
            return 42L
        }

        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) {
            minLatestTs = unixSeconds
        }

        override fun execute(ctx: Long): String {
            val status = if (step < rounds.size) {
                JSONObject().put("status", "pending").put("requests", JSONArray(rounds[step]))
            } else {
                final
            }
            step++
            return status.toString()
        }

        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) {
            responses[req] = data to nodeIndex
        }

        override fun setError(req: Long, error: String, nodeIndex: Int) {
            errors[req] = error
        }

        override fun free(ctx: Long) {
            freed += ctx
        }
    }

    private class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private class ScriptHttp(private val answer: (Sent) -> EnsColibri.Http.Reply) : EnsColibri.Http {
        val log: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            opened: (java.net.HttpURLConnection) -> Unit,
        ): EnsColibri.Http.Reply {
            val sent = Sent(method, url, headers, body?.toString(Charsets.UTF_8))
            log += sent
            return answer(sent)
        }
    }

    private fun ok(body: String) = EnsColibri.Http.Reply(200, body.toByteArray())

    private fun request(ptr: Long, type: String, url: String = "", method: String = "post", encoding: String = "json", exclude: Long = 0, payload: JSONObject? = null) =
        JSONObject()
            .put("req_ptr", ptr.toString())
            .put("type", type)
            .put("url", url)
            .put("method", method)
            .put("encoding", encoding)
            .put("exclude_mask", exclude.toString())
            .apply { if (payload != null) put("payload", payload) }

    private val success = JSONObject().put("status", "success").put("result", "0xabcd")

    private fun call(colibri: EnsColibri) = runBlocking {
        colibri.ethCall("0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe", byteArrayOf(1, 2), rpcs)
    }

    @Test
    fun `each request goes to its type's servers and the answer comes back proven`() {
        val engine = ScriptEngine(
            rounds = listOf(
                listOf(
                    request(1, "eth_rpc", payload = JSONObject().put("method", "eth_getCode")),
                    request(2, "checkpointz", url = "eth/v1/beacon/blocks/15315040/root", method = "get"),
                ),
                listOf(request(3, "prover", encoding = "ssz", payload = JSONObject().put("method", "colibri_proofCall"))),
            ),
            final = success,
        )
        val http = ScriptHttp { ok("{}") }

        val proven = call(EnsColibri(engine, http, clock = { 1_000_000_000L }))

        assertEquals(EnsColibri.Outcome.Returned("0xabcd"), proven.outcome)
        assertEquals(listOf("mainnet1.colibri-proof.tech"), proven.provers)
        // The call itself, as the core was given it.
        val (method, params) = engine.created.single()
        assertEquals("eth_call", method)
        val p = JSONArray(params)
        assertEquals("0x0102", p.getJSONObject(0).getString("data"))
        assertEquals("latest", p.getString(1))
        // A proof for `latest` may be a minute old, no more.
        assertEquals(1_000_000L - 60, engine.minLatestTs)

        val byPtr = http.log.associateBy { s -> s.url }
        // eth_rpc: the first of the resolver's endpoints, as given.
        val rpc = byPtr.getValue("https://rpc1.test")
        assertEquals("POST", rpc.method)
        assertEquals("application/json", rpc.headers["accept"])
        assertEquals("application/json", rpc.headers["content-type"])
        assertEquals("eth_getCode", JSONObject(rpc.body!!).getString("method"))
        // checkpointz: a path under the first checkpointz server.
        val checkpoint = byPtr.getValue("https://sync-mainnet.beaconcha.in/eth/v1/beacon/blocks/15315040/root")
        assertEquals("GET", checkpoint.method)
        assertEquals(null, checkpoint.body)
        // prover: SSZ back.
        val prover = byPtr.getValue("https://mainnet1.colibri-proof.tech")
        assertEquals("application/octet-stream", prover.headers["accept"])

        assertEquals(setOf(1L, 2L, 3L), engine.responses.keys)
        assertArrayEquals("{}".toByteArray(), engine.responses.getValue(1L).first)
        assertTrue(engine.errors.isEmpty())
        assertEquals(listOf(42L), engine.freed)
    }

    @Test
    fun `a failing server hands the request to the next, and the core learns which one answered`() {
        val engine = ScriptEngine(
            rounds = listOf(listOf(request(1, "eth_rpc"), request(2, "prover"))),
            final = success,
        )
        val http = ScriptHttp { s ->
            when (s.url) {
                "https://rpc1.test" -> EnsColibri.Http.Reply(503, ByteArray(0))
                "https://mainnet1.colibri-proof.tech" -> throw IOException("connection refused")
                else -> ok("{}")
            }
        }

        val proven = call(EnsColibri(engine, http))

        assertEquals(1, engine.responses.getValue(1L).second)
        assertEquals(1, engine.responses.getValue(2L).second)
        // The prover that actually served the proof is the one named.
        assertEquals(listOf("mainnet.colibri-proof.tech"), proven.provers)
    }

    @Test
    fun `servers the core excludes are skipped`() {
        // Bit 0 set: rpc1 already failed this request.
        val engine = ScriptEngine(rounds = listOf(listOf(request(1, "eth_rpc", exclude = 1))), final = success)
        val http = ScriptHttp { ok("{}") }

        call(EnsColibri(engine, http))

        assertEquals(listOf("https://rpc2.test/"), http.log.map { it.url })
        assertEquals(1, engine.responses.getValue(1L).second)
    }

    @Test
    fun `a request no server answers is failed back to the core, not dropped`() {
        val engine = ScriptEngine(rounds = listOf(listOf(request(1, "eth_rpc"))), final = success)
        val http = ScriptHttp { EnsColibri.Http.Reply(500, ByteArray(0)) }

        call(EnsColibri(engine, http))

        assertEquals(rpcs, http.log.map { it.url })
        assertTrue(engine.errors.getValue(1L).contains("HTTP 500"))
        assertFalse(1L in engine.responses)
    }

    @Test
    fun `a keyed endpoint's API key never reaches the error the core and logcat get`() {
        val keyed = "https://mainnet.infura.io/v3/0123456789abcdef0123456789abcdef"
        val engine = ScriptEngine(rounds = listOf(listOf(request(1, "eth_rpc"))), final = success)
        val http = ScriptHttp { s -> throw IOException("failed to connect to ${s.url} after 8000ms") }

        runBlocking { EnsColibri(engine, http).ethCall("0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe", byteArrayOf(1), listOf(keyed)) }

        val error = engine.errors.getValue(1L)
        assertFalse(error, "0123456789abcdef" in error)
        assertTrue(error, "mainnet.infura.io" in error)
    }

    @Test
    fun `a req_ptr above Long MAX_VALUE (an arm64 tagged heap pointer) is still answered`() {
        // 0xb400007a1c2d3e40, as the core prints it: unsigned decimal.
        val tagged = "12970367451285765696"
        val bits = 0xb400007a1c2d3e40uL.toLong()
        val engine = ScriptEngine(
            rounds = listOf(
                listOf(
                    request(1, "eth_rpc").put("req_ptr", tagged),
                    request(2, "eth_rpc").put("req_ptr", "18446744073709551615"),
                ),
            ),
            final = success,
        )

        call(EnsColibri(engine, ScriptHttp { ok("{}") }))

        // Handed back as the same 64 bits in a jlong.
        assertTrue(bits < 0)
        assertEquals(setOf(bits, -1L), engine.responses.keys.toSet())
    }

    @Test
    fun `a request with no usable req_ptr fails the call instead of being skipped`() {
        for (bad in listOf("", "0xb400007a1c2d3e40", "18446744073709551616", "nope")) {
            val engine = ScriptEngine(rounds = listOf(listOf(request(1, "eth_rpc").put("req_ptr", bad))), final = success)
            try {
                call(EnsColibri(engine, ScriptHttp { ok("{}") }))
                fail("expected a failure for req_ptr '$bad'")
            } catch (e: EnsColibri.Failure) {
                assertTrue(e.message!!.contains("req_ptr"))
            }
            assertTrue(engine.responses.isEmpty() && engine.errors.isEmpty())
            assertEquals(listOf(42L), engine.freed)
        }
    }

    @Test
    fun `an exclude_mask with bit 63 set still excludes the lower servers`() {
        // Bits 0 and 63: rpc1 is excluded even though the mask is above Long.MAX_VALUE.
        val engine = ScriptEngine(
            rounds = listOf(listOf(request(1, "eth_rpc").put("exclude_mask", "9223372036854775809"))),
            final = success,
        )
        val http = ScriptHttp { ok("{}") }

        call(EnsColibri(engine, http))

        assertEquals(listOf("https://rpc2.test/"), http.log.map { it.url })
    }

    @Test
    fun `a proven revert comes back with its data`() {
        val engine = ScriptEngine(emptyList(), JSONObject().put("status", "revert").put("data", "0x77209fe8"))

        val proven = call(EnsColibri(engine, ScriptHttp { ok("") }))

        assertEquals(EnsColibri.Outcome.Reverted("0x77209fe8"), proven.outcome)
        assertEquals(listOf(42L), engine.freed)
    }

    @Test
    fun `a verification error is a failure, and the context is still freed`() {
        val engine = ScriptEngine(emptyList(), JSONObject().put("status", "error").put("error", "invalid blockhash"))

        try {
            call(EnsColibri(engine, ScriptHttp { ok("") }))
            fail("expected a failure")
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.message!!.contains("invalid blockhash"))
            // This call's own proof: says nothing about other names.
            assertFalse(e.unreachable)
        }
        assertEquals(listOf(42L), engine.freed)
    }

    @Test
    fun `a latest proof refused as too old counts against every call, like an unreachable prover`() {
        // A prover lagging the chain, or this device's clock running
        // ahead of it, fails every name's head proof alike: the resolver
        // must back off instead of paying a proof round per name.
        val engine = ScriptEngine(
            emptyList(),
            JSONObject().put("status", "error").put("error", "eth_call: ${EnsColibri.STALE_LATEST}"),
        )

        try {
            call(EnsColibri(engine, ScriptHttp { ok("") }))
            fail("expected a failure")
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.unreachable)
        }
        assertEquals(listOf(42L), engine.freed)
    }

    @Test
    fun `without the verifier nothing is asked`() {
        val engine = ScriptEngine(emptyList(), success, available = false)

        try {
            call(EnsColibri(engine, ScriptHttp { error("no request expected") }))
            fail("expected a failure")
        } catch (e: EnsColibri.Failure) {
            // expected
        }
        assertTrue(engine.created.isEmpty())
    }

    @Test
    fun `cancelling a call stuck on a stalled server returns at once, aborts it and frees the context`() {
        val engine = ScriptEngine(rounds = listOf(listOf(request(1, "prover"))), final = success)
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val http = object : EnsColibri.Http {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
                opened: (java.net.HttpURLConnection) -> Unit,
            ): EnsColibri.Http.Reply {
                // A connection whose disconnect() is how the abort shows.
                opened(object : java.net.HttpURLConnection(java.net.URL(url)) {
                    override fun disconnect() = aborted.countDown()
                    override fun usingProxy() = false
                    override fun connect() = Unit
                })
                started.countDown()
                // A read that never ends on its own.
                released.await(30, TimeUnit.SECONDS)
                throw IOException("released")
            }
        }
        val colibri = EnsColibri(engine, http)

        val t0 = System.currentTimeMillis()
        runBlocking {
            val job = async(Dispatchers.Default) { colibri.ethCall("0x00", ByteArray(0), rpcs) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            delay(100)
            job.cancel()
            try {
                withTimeout(1_000) { job.await() }
                fail("expected cancellation")
            } catch (e: CancellationException) {
                // expected: cancelled, within the second
            }
        }
        assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 3_000)
        assertTrue("connection not closed", aborted.await(2, TimeUnit.SECONDS))
        released.countDown()
        assertEquals(listOf(42L), engine.freed)
        // Nothing reached the core after the context was freed.
        assertTrue(engine.responses.isEmpty() && engine.errors.isEmpty())
    }

    @Test
    fun `a connection opened after the cancel is refused and closed, not left to its timeouts`() {
        val engine = ScriptEngine(rounds = listOf(listOf(request(1, "prover"))), final = success)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val refused = CountDownLatch(1)
        val http = object : EnsColibri.Http {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
                opened: (java.net.HttpURLConnection) -> Unit,
            ): EnsColibri.Http.Reply {
                started.countDown()
                // Still resolving/queued when the cancel lands; only then opens.
                cancelled.await(10, TimeUnit.SECONDS)
                try {
                    opened(object : java.net.HttpURLConnection(java.net.URL(url)) {
                        override fun disconnect() = aborted.countDown()
                        override fun usingProxy() = false
                        override fun connect() = Unit
                    })
                } catch (e: IOException) {
                    refused.countDown()
                    throw e
                }
                proceeded.set(true)
                return ok("never")
            }
        }
        val colibri = EnsColibri(engine, http)

        runBlocking {
            val job = async(Dispatchers.Default) { colibri.ethCall("0x00", ByteArray(0), rpcs) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            try {
                withTimeout(1_000) { job.await() }
                fail("expected cancellation")
            } catch (e: CancellationException) {
                // expected
            }
        }
        cancelled.countDown()
        assertTrue("late connection not refused", refused.await(2, TimeUnit.SECONDS))
        assertTrue("late connection not closed", aborted.await(2, TimeUnit.SECONDS))
        assertFalse(proceeded.get())
    }

    @Test
    fun `the core's request types map to corpus core's servers`() {
        val colibri = EnsColibri(ScriptEngine(emptyList(), success), ScriptHttp { ok("") })
        assertEquals(EnsColibri.PROVERS, colibri.serversFor(JSONObject().put("type", "prover"), rpcs))
        // Light-client updates come from the prover, never a beacon node.
        assertEquals(EnsColibri.PROVERS, colibri.serversFor(JSONObject().put("type", "beacon_api"), rpcs))
        assertEquals(
            EnsColibri.CHECKPOINTZ + EnsColibri.BEACON_APIS,
            colibri.serversFor(JSONObject().put("type", "checkpointz"), rpcs),
        )
        assertEquals(rpcs, colibri.serversFor(JSONObject().put("type", "eth_rpc"), rpcs))
        assertEquals(rpcs, colibri.serversFor(JSONObject(), rpcs))
    }

    @Test
    fun `an onion eth_rpc goes through TorRouting and is refused without Tor, never dialled`() {
        // Tor isn't running in a unit test: TorRouting refuses the onion
        // before any DNS lookup or connection (#143).
        val onion = "http://" + "a".repeat(56) + ".onion/"
        var opened = 0
        try {
            EnsColibri.Http.Default.request("POST", onion, emptyMap(), ByteArray(1)) { opened++ }
            fail("reached $onion")
        } catch (_: baby.freedom.mobile.browser.TorRouting.RefusedException) {
        }
        assertEquals(0, opened)
    }
}
