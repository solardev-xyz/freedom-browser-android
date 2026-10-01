package baby.freedom.mobile.chains.rpc

import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.chains.Chain
import java.util.Collections
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A JSON-RPC answer nested thousands of levels deep, on the platform's
 * own `org.json` — which, unlike the `org.json:json` the JVM unit tests
 * run against, has no nesting limit: its parser recurses once per level
 * and a few thousand levels (a 10 KB body, far under the transport's
 * 8 MiB cap) overflow the stack. A `StackOverflowError` isn't an
 * `Exception`: thrown out of [JsonRpc.parse] it went through the router's
 * `call` and either the caller (a send, a balance read) or a quorum leg,
 * whose detached scope has no handler — so any RPC in a chain's pool, a
 * public one or one a site added with `wallet_addEthereumChain`, could
 * take the app down with one answer.
 */
@RunWith(AndroidJUnit4::class)
class HostileRpcJsonDeviceTest {
    private val deep = """{"jsonrpc":"2.0","id":1,"result":""" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
    private val deepError = """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"x","data":""" +
        "{\"a\":".repeat(20_000) + "1" + "}".repeat(20_000) + "}}"

    @Test
    fun aDeeplyNestedAnswerIsMalformedNotACrash() {
        assertTrue(JsonRpc.parse(deep) is JsonRpc.Envelope.Malformed)
        assertTrue(JsonRpc.parse(deepError) is JsonRpc.Envelope.Malformed)
    }

    @Test
    fun aRouterReadFailsWithAChainRpcException() = runBlocking {
        val crashes = Collections.synchronizedList(ArrayList<Throwable>())
        val legs = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> crashes += e })
        val sane = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""
        // A quorum of three where two answer with the nested body: the legs run detached.
        val pool = Chain(id = 1_337, name = "Test", symbol = "TST", rpcUrls = listOf("https://a.example", "https://b.example", "https://c.example"))
        // One RPC, the hostile one: the direct tier parses its answer in the caller.
        val single = Chain(id = 1_338, name = "Test", symbol = "TST", rpcUrls = listOf("https://d.example"))
        val transport = RpcTransport { url, _, _ -> if (url == "https://b.example") sane else deep }
        val router = ChainDataRouter(chains = { listOf(pool, single) }, transport = transport, legScope = legs)

        val read = router.request(pool.id, "eth_blockNumber")
        assertEquals("the one sane RPC still answers, unverified", "0x10", read.result)
        assertEquals(ChainTrust.Level.UNVERIFIED, read.trust.level)
        try {
            router.request(single.id, "eth_blockNumber")
            fail("a malformed answer is no answer")
        } catch (e: ChainRpcException.AllSourcesFailed) {
            // What the router promises to throw, and nothing else.
        }
        assertEquals("a quorum leg crashed: $crashes", emptyList<Throwable>(), crashes.toList())
    }
}
