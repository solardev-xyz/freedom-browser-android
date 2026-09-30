package baby.freedom.mobile.node

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainAccessPolicy
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainDataRouter.ErrorRank
import baby.freedom.mobile.chains.rpc.ChainFailure
import baby.freedom.mobile.chains.rpc.RpcTimeoutException
import baby.freedom.mobile.chains.rpc.RpcTransport
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AntChainBridge] (#273): ant's Gnosis reads through the real
 * [ChainDataRouter] over scripted RPCs, as ant would receive them.
 */
class AntChainBridgeTest {
    private val a = "https://a.example"
    private val b = "https://b.example"
    private val c = "https://c.example"
    private val d = "https://d.example"

    private fun ok(result: Any?) = JSONObject().put("jsonrpc", "2.0").put("id", 1)
        .put("result", result ?: JSONObject.NULL).toString()

    private fun err(code: Int, message: String, data: String? = null) = JSONObject().put("jsonrpc", "2.0").put("id", 1)
        .put("error", JSONObject().put("code", code).put("message", message).apply { data?.let { put("data", it) } })
        .toString()

    private class Net {
        val handlers = ConcurrentHashMap<String, suspend (String) -> String>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()

        val transport = RpcTransport { url, body, timeoutMs ->
            calls.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            val handler = handlers[url] ?: throw IOException("no route to $url")
            try {
                withTimeout(timeoutMs) { handler(body) }
            } catch (e: TimeoutCancellationException) {
                throw RpcTimeoutException("no answer from $url within ${timeoutMs}ms")
            }
        }

        fun count(url: String) = calls[url]?.get() ?: 0
    }

    private fun bridge(net: Net, rpcs: List<String> = listOf(a, b, c, d), deadlineMs: Long = 10_000) = AntChainBridge(
        ChainDataRouter(
            chains = { listOf(BuiltInChains.GNOSIS.copy(rpcUrls = rpcs)) },
            transport = net.transport,
            // Gnosis's own ladder (no light client wired: quorum → direct), with a short endpoint timeout.
            policyFor = { ChainAccessPolicy.default(it.id).copy(timeoutMs = 500) },
        ),
        deadlineMs = deadlineMs,
    )

    private fun request(method: String, params: JSONArray = JSONArray(), id: Any = 1) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    private val logsQuery = JSONArray().put(
        JSONObject().put("address", "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da")
            .put("fromBlock", "0x1").put("toBlock", "0x2faf081"),
    )

    private fun serve(bridge: AntChainBridge, method: String, params: JSONArray = JSONArray(), id: Any = 1) =
        JSONObject(bridge.serve(request(method, params, id)))

    private fun JSONObject.error(): JSONObject = getJSONObject("error")

    /** What ant does with a failed `eth_getLogs`: halve the window, or give up on the scan. */
    private fun JSONObject.antHalves(): Boolean = AntChainBridge.antShrinksLogScanOn(error().toString())

    // ---- answers ----

    @Test
    fun aQuorumAnswerReachesAntAsItsResultWithItsId() {
        val net = Net()
        for (u in listOf(a, b, c)) net.handlers[u] = { ok("0x10") }
        val r = serve(bridge(net), "eth_blockNumber", id = 42)
        assertEquals("2.0", r.getString("jsonrpc"))
        assertEquals(42, r.getInt("id"))
        assertEquals("0x10", r.getString("result"))
        assertFalse(r.has("error"))
        assertEquals("the quorum's first three are asked, no more", 0, net.count(d))
    }

    @Test
    fun aNullReceiptIsAResultNotAnError() {
        val net = Net()
        for (u in listOf(a, b, c)) net.handlers[u] = { ok(null) }
        val r = serve(bridge(net), "eth_getTransactionReceipt", JSONArray().put("0x" + "ab".repeat(32)))
        assertTrue(r.has("result"))
        assertTrue(r.isNull("result"))
        assertFalse(r.has("error"))
    }

    @Test
    fun aRevertKeepsItsCodeWordingAndData() {
        val net = Net()
        for (u in listOf(a, b, c)) net.handlers[u] = { err(3, "execution reverted: liquid balance not sufficient", "0x08c379a0") }
        val r = serve(bridge(net), "eth_call", JSONArray().put(JSONObject().put("to", "0x" + "11".repeat(20)).put("data", "0x")).put("latest"))
        assertEquals(3, r.error().getInt("code"))
        assertEquals("execution reverted: liquid balance not sufficient", r.error().getString("message"))
        assertEquals("0x08c379a0", r.error().getString("data"))
    }

    // ---- a lagging RPC ----

    @Test
    fun aLaggingRpcIsOutvotedByTheQuorum() {
        val net = Net()
        val logs = JSONArray().put(JSONObject().put("blockNumber", "0x5"))
        net.handlers[a] = { err(-32000, "block range extends beyond current head block") }
        net.handlers[b] = { ok(logs) }
        net.handlers[c] = { ok(logs) }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertEquals(logs.toString(), r.getJSONArray("result").toString())
    }

    @Test
    fun aLaggingRpcDoesNotEndTheWalk() {
        val net = Net()
        val logs = JSONArray().put(JSONObject().put("blockNumber", "0x5"))
        net.handlers[a] = { err(-32000, "block range extends beyond current head block") }
        net.handlers[b] = { err(-32005, "rate limit exceeded") }
        net.handlers[c] = { throw IOException("connection reset") }
        net.handlers[d] = { ok(logs) }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertEquals("the RPC the quorum never asked answers", logs.toString(), r.getJSONArray("result").toString())
        assertEquals(1, net.count(d))
    }

    @Test
    fun onlyLaggingAndThrottledRpcsGiveAntAnErrorItDoesNotHalveOn() {
        val net = Net()
        net.handlers[a] = { err(-32000, "block range extends beyond current head block") }
        net.handlers[b] = { err(-32005, "rate limit exceeded") }
        net.handlers[c] = { err(-32005, "project ID request rate exceeded") }
        net.handlers[d] = { throw IOException("HTTP 503") }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertFalse(r.has("result"))
        assertFalse("ant must give up (and retry later), not halve against a throttle: ${r.error()}", r.antHalves())
        assertTrue(r.error().getInt("code") != AntChainBridge.ANT_CANT_SERVE)
    }

    // ---- a range-capped RPC ----

    @Test
    fun aRangeCapEndsTheWalkAndReachesAntIntact() {
        val net = Net()
        net.handlers[a] = { err(-32005, "query exceeds max block range 50000") }
        net.handlers[b] = { err(-32005, "rate limit exceeded") }
        net.handlers[c] = { err(35, "ranges over 10000 blocks are not supported on free plan") }
        net.handlers[d] = { ok(JSONArray()) }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertFalse(r.has("result"))
        assertEquals(-32005, r.error().getInt("code"))
        assertTrue(r.error().getString("message").contains("query exceeds max block range 50000"))
        assertTrue("ant halves its window on it", r.antHalves())
        assertEquals("no further RPC is asked once the query itself was refused", 0, net.count(d))
    }

    @Test
    fun anAnswerBeatsARangeCap() {
        // Gnosis's shipped RPCs as they answer a wide scan today: one serves
        // any range, the other two cap it (publicnode at 50000 with -32701,
        // drpc's free plan at 10000 with code 35).
        val net = Net()
        val logs = JSONArray().put(JSONObject().put("blockNumber", "0x1"))
        net.handlers[a] = { ok(logs) }
        net.handlers[b] = { err(-32701, "exceed maximum block range: 50000") }
        net.handlers[c] = { err(35, "ranges over 10000 blocks are not supported on free plan") }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertEquals(
            "direct reuses the member that answered (unverified), as the wallet's reads would",
            logs.toString(),
            r.getJSONArray("result").toString(),
        )
        assertEquals("with no new request", 0, net.count(d))
    }

    @Test
    fun anAnswerStillInFlightBeatsFastRangeCaps() {
        // The same RPCs, timed as they are: the caps come back at once,
        // the answer a moment later.
        val net = Net()
        val logs = JSONArray().put(JSONObject().put("blockNumber", "0x1"))
        net.handlers[a] = { kotlinx.coroutines.delay(200); ok(logs) }
        net.handlers[b] = { err(-32701, "exceed maximum block range: 50000") }
        net.handlers[c] = { err(35, "ranges over 10000 blocks are not supported on free plan") }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertEquals(logs.toString(), r.getJSONArray("result").toString())
        assertEquals(listOf(1, 0), listOf(net.count(a), net.count(d)))
    }

    @Test
    fun aRangeCapSurvivesALaterThrottleAndTransportFailure() {
        val net = Net()
        net.handlers[a] = { err(-32005, "rate limit exceeded") }
        net.handlers[b] = { err(-32000, "query returned more than 10000 results") }
        net.handlers[c] = { throw IOException("connection refused") }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertTrue(r.error().getString("message").contains("query returned more than 10000 results"))
        assertTrue(r.antHalves())
        assertEquals(
            "a node's -32000 never goes out as such: ant would replay the read on its one RPC",
            AntChainBridge.GENERIC_ERROR,
            r.error().getInt("code"),
        )
        assertEquals(0, net.count(d))
    }

    @Test
    fun oneRangeCappedRpcIsOutvotedByTwoThatServeTheRange() {
        val net = Net()
        val logs = JSONArray().put(JSONObject().put("blockNumber", "0x7"))
        net.handlers[a] = { err(-32005, "query exceeds max block range 10000") }
        net.handlers[b] = { ok(logs) }
        net.handlers[c] = { ok(logs) }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertEquals("two RPCs that can serve the range outvote one that caps it", logs.toString(), r.getJSONArray("result").toString())
    }

    @Test
    fun aLogScanTimeoutReadsAsQueryTimeout() {
        val net = Net()
        for (u in listOf(a, b, c, d)) net.handlers[u] = { awaitCancellation() }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertFalse(r.has("result"))
        assertTrue(r.error().getString("message"), r.error().getString("message").contains("query timeout"))
        assertTrue("ant halves on a timeout", r.antHalves())
    }

    // ---- a quorum disagreement ----

    @Test
    fun aQuorumDisagreementFallsToTheDirectTierLikeTheWallet() {
        val net = Net()
        net.handlers[a] = { ok("0x1") }
        net.handlers[b] = { ok("0x2") }
        net.handlers[c] = { ok("0x3") }
        net.handlers[d] = { ok("0x4") }
        val r = serve(bridge(net), "eth_getBalance", JSONArray().put("0x" + "22".repeat(20)).put("latest"))
        // The router's own rule: direct reuses the quorum's first answer
        // (labelled unverified) rather than asking another RPC.
        assertEquals("0x1", r.getString("result"))
        assertEquals(0, net.count(d))
    }

    @Test
    fun aDisagreementWithNoUsableAnswerIsAnErrorNeverAnEmptyResult() {
        val net = Net()
        net.handlers[a] = { err(-32603, "internal error") }
        net.handlers[b] = { err(-32601, "method not found") }
        net.handlers[c] = { throw IOException("HTTP 502") }
        net.handlers[d] = { throw IOException("HTTP 502") }
        val r = serve(bridge(net), "eth_getLogs", logsQuery)
        assertFalse("never [] — that would read as 'no batch'", r.has("result"))
        assertFalse(r.antHalves())
        val bal = serve(bridge(net), "eth_getBalance", JSONArray().put("0x" + "22".repeat(20)).put("latest"))
        assertFalse("never 0x0 — that would read as an empty wallet", bal.has("result"))
        assertTrue(bal.error().getString("message").startsWith("Chain request failed"))
    }

    // ---- the bridge itself ----

    @Test
    fun onlyAntsReadsAreServed() {
        val net = Net()
        val bridge = bridge(net)
        val send = serve(bridge, "eth_sendRawTransaction", JSONArray().put("0x02"))
        assertEquals(-32601, send.error().getInt("code"))
        assertEquals(-32601, serve(bridge, "eth_accounts").error().getInt("code"))
        assertEquals(-32700, JSONObject(bridge.serve("not json")).error().getInt("code"))
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun theBridgesDeadlineIsAQueryTimeout() {
        val net = Net()
        for (u in listOf(a, b, c, d)) net.handlers[u] = { awaitCancellation() }
        val started = System.nanoTime()
        val r = serve(bridge(net, deadlineMs = 300), "eth_getLogs", logsQuery)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals("Chain request failed: query timeout", r.error().getString("message"))
        assertTrue(r.antHalves())
    }

    @Test
    fun aClosedBridgeAnswersAnErrorAtOnce() {
        val net = Net()
        val bridge = bridge(net)
        bridge.close()
        val r = serve(bridge, "eth_blockNumber")
        assertFalse(r.has("result"))
        assertTrue(r.error().getInt("code") != AntChainBridge.ANT_CANT_SERVE)
    }

    @Test
    fun urlsAreTakenOutOfWhatAntSees() {
        assertEquals(
            "boom at [url] now",
            AntChainBridge.sanitize("boom at https://rpc.example/secret-key\u0000 now"),
        )
    }

    // ---- ranking (desktop's rankLogScanError) ----

    @Test
    fun logScanFailuresRankLikeDesktop() {
        fun rank(code: Int?, message: String, timeout: Boolean = false) =
            AntChainBridge.rankLogScanError(ChainFailure(code, message, null, timeout))
        assertEquals(ErrorRank.REQUEST, rank(-32005, "query exceeds max block range 50000"))
        assertEquals(ErrorRank.REQUEST, rank(-32000, "query returned more than 10000 results"))
        assertEquals(ErrorRank.REQUEST, rank(-32602, "response size exceeded"))
        assertEquals(ErrorRank.HINT, rank(-32005, "limit exceeded"))
        assertEquals(ErrorRank.HINT, rank(-32005, "query exceeds limit of 10000 logs"))
        assertEquals(ErrorRank.HINT, rank(35, "ranges over 10000 blocks are not supported on free plan"))
        assertEquals(ErrorRank.REQUEST, rank(-32701, "exceed maximum block range: 50000"))
        // Worded outside desktop's lists: a possible cap (and, as -32602, the router's deterministic answer anyway).
        assertEquals(ErrorRank.HINT, rank(-32602, "Block range 50001 exceeds the maximum of 10000 blocks per logs request."))
        assertEquals(ErrorRank.TIMEOUT, rank(null, "no answer within 5000ms", timeout = true))
        assertEquals(ErrorRank.TIMEOUT, rank(-32000, "query timeout exceeded"))
        assertEquals(ErrorRank.ENDPOINT, rank(-32005, "rate limit exceeded"))
        assertEquals(ErrorRank.ENDPOINT, rank(-32005, "project ID request rate exceeded"))
        assertEquals(ErrorRank.ENDPOINT, rank(-32000, "block range extends beyond current head block"))
        assertEquals(ErrorRank.ENDPOINT, rank(null, "connection refused: limit reached"))
        assertEquals(ErrorRank.ENDPOINT, rank(-32601, "method not found"))
    }
}
