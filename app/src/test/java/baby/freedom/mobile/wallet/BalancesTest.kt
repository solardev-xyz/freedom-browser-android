package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.balanceText
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading balances through the chain-data router (#104), and what the wallet keeps of them. */
class BalancesTest {
    private val holder = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val urls = listOf("https://a.example", "https://b.example", "https://c.example")
    private val gnosis = BuiltInChains.GNOSIS.copy(rpcUrls = urls)
    private val sent = mutableListOf<JSONObject>()

    /** A router whose every RPC answers with [answer] (a JSON-RPC member), or throws. */
    private fun fetcher(answer: (JSONObject) -> String) = BalanceFetcher(
        WalletRpc(
            ChainDataRouter(
                chains = { listOf<Chain>(gnosis) },
                transport = RpcTransport { _, body, _ ->
                    val req = JSONObject(body)
                    synchronized(sent) { sent += req }
                    """{"jsonrpc":"2.0","id":1,${answer(req)}}"""
                },
            ),
        ),
    )

    private val xdai = TokenRegistry.native(gnosis)
    private val xbzz = TokenRegistry.builtins.first { it.symbol == "xBZZ" }
    private val eure = TokenRegistry.builtins.first { it.symbol == "EURe" }

    private fun word(n: Long) = "\"0x" + n.toString(16).padStart(64, '0') + "\""

    @Test
    fun `native by eth_getBalance, each ERC-20 by balanceOf, every answer with its trust`() = runBlocking {
        val f = fetcher { req ->
            when (req.getString("method")) {
                "eth_getBalance" -> "\"result\":\"0xde0b6b3a7640000\""
                "eth_call" -> {
                    val to = req.getJSONArray("params").getJSONObject(0).getString("to")
                    "\"result\":" + if (to.equals(xbzz.address, true)) word(5 * 10_000_000_000_000_000L) else "\"0x\""
                }
                else -> "\"error\":{\"code\":-32601,\"message\":\"no\"}"
            }
        }
        val got = f.fetch(holder, listOf(xdai, xbzz, eure))
        val native = got[xdai.key] as TokenBalance.Known
        assertEquals(BigInteger.TEN.pow(18), native.raw)
        assertEquals(ChainTrust.Level.VERIFIED, native.trust.level) // three agreeing RPCs
        assertEquals(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(16)), (got[xbzz.key] as TokenBalance.Known).raw)
        // No contract answer is a failure, not a zero.
        assertTrue(got[eure.key] is TokenBalance.Failed)

        val getBalance = sent.first { it.getString("method") == "eth_getBalance" }.getJSONArray("params")
        assertEquals(holder, getBalance.getString(0))
        assertEquals("latest", getBalance.getString(1))
        val call = sent.first { it.getString("method") == "eth_call" }.getJSONArray("params").getJSONObject(0)
        assertEquals(Erc20.balanceOfData(holder), call.getString("data"))
    }

    @Test
    fun `no RPC answering is a failure the row says, never a zero`() = runBlocking {
        val f = BalanceFetcher(
            WalletRpc(ChainDataRouter(chains = { listOf<Chain>(gnosis) }, transport = RpcTransport { _, _, _ -> throw IOException("down") })),
        )
        val got = f.fetch(holder, listOf(xdai)).getValue(xdai.key) as TokenBalance.Failed
        assertEquals("no RPC answered", got.reason)
        val text = balanceText(got, 18, refreshing = false)
        assertEquals(null, text.amount)
        assertTrue(text.warn)
    }

    @Test
    fun `a failed refresh keeps the last reading, marked not updated`() = runBlocking {
        var up = true
        val balances = WalletBalances {
            fetcher { if (up) "\"result\":\"0x2386f26fc10000\"" else "\"error\":{\"code\":-32000,\"message\":\"busy\"}" }
        }
        balances.refresh(holder, listOf(xdai))
        val first = balances.byAddress.value[holder.lowercase()]!![xdai.key] as TokenBalance.Known
        up = false
        balances.refresh(holder, listOf(xdai))
        val failed = balances.byAddress.value[holder.lowercase()]!![xdai.key] as TokenBalance.Failed
        assertEquals(first, failed.previous)
        val text = balanceText(failed, 18, refreshing = false)
        assertEquals("0.01", text.amount)
        assertTrue(text.detail.startsWith("Not updated"))
        // And a second failure still carries it.
        balances.refresh(holder, listOf(xdai))
        assertEquals(first, (balances.byAddress.value[holder.lowercase()]!![xdai.key] as TokenBalance.Failed).previous)
    }

    @Test
    fun `a read still running when the wallet is removed files nothing`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val balances = WalletBalances {
            fetcher {
                runBlocking { gate.await() }
                "\"result\":\"0x1\""
            }
        }
        val running = async(Dispatchers.IO) { balances.refresh(holder, listOf(xdai)) }
        Thread.sleep(200)
        balances.forget()
        gate.complete(Unit)
        running.await()
        assertTrue(balances.byAddress.value.isEmpty())
    }
}
