package baby.freedom.mobile.ens

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WNS (`.wei`) and GNS (`.gwei`) resolution: same namehash and record
 * decoding as ENS, but `contenthash(bytes32)` goes straight to the
 * system's NameNFT registry instead of `resolve()` on the Universal
 * Resolver. Scripted through [EnsHttp]; the canned replies are the real
 * mainnet `eth_call` results captured in September 2026.
 */
class NameSystemTest {

    private val rpc = "https://rpc.test/"

    private class Call(val to: String, val data: String)

    /** Answers every `eth_call` with [reply] and records its target. */
    private class ScriptedRpc(private val reply: (Call) -> EnsHttp.Reply) : EnsHttp {
        val calls = mutableListOf<Call>()
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            val tx = JSONObject(body!!).getJSONArray("params").getJSONObject(0)
            val call = Call(tx.getString("to"), tx.getString("data"))
            calls += call
            return reply(call)
        }
    }

    private fun result(hex: String) =
        EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"$hex"}""")

    /** `abi.encode(bytes)` of a raw contenthash. */
    private fun abiBytes(contentHashHex: String): String {
        val len = contentHashHex.length / 2
        val padded = contentHashHex.padEnd(((contentHashHex.length + 63) / 64) * 64, '0')
        return "0x" + "20".padStart(64, '0') + len.toString(16).padStart(64, '0') + padded
    }

    @Test
    fun `suffix picks the system`() {
        assertEquals(NameSystem.WNS, NameSystem.forName("alice.wei"))
        assertEquals(NameSystem.WNS, NameSystem.forName("Sub.Alice.WEI"))
        assertEquals(NameSystem.GNS, NameSystem.forName("apoorv.gwei"))
        assertEquals(NameSystem.ENS, NameSystem.forName("vitalik.eth"))
        assertEquals(NameSystem.ENS, NameSystem.forName("foo.box"))
        assertEquals(listOf(".eth", ".box", ".wei", ".gwei"), NameSystem.navigableSuffixes)
    }

    @Test
    fun `wei name calls contenthash on the WNS registry`() {
        // meinhard.wei → a Swarm manifest.
        val http = ScriptedRpc {
            result(abiBytes("e40101fa011b203d354b79aeff22c2328f20279645bdb11ec96bbdf2dd197cc1b519bca389923b"))
        }
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("meinhard.wei") }

        require(r is EnsResult.Ok) { "got $r" }
        assertEquals("bzz://3d354b79aeff22c2328f20279645bdb11ec96bbdf2dd197cc1b519bca389923b", r.uri)
        val call = http.calls.single()
        assertEquals("0x0000000000696760E15f265e828DB644A0c242EB", call.to)
        assertEquals(
            "0xbc1c58d1" + EnsResolver.namehash("meinhard.wei").toHex(),
            call.data,
        )
        // The ENS namehash of the full name, suffix label included.
        assertEquals(
            "0xbc1c58d170cb2ec1671c0f4f576994bf7081f7132eae4371488c089edc332dae629a6610",
            call.data,
        )
    }

    @Test
    fun `gwei name calls contenthash on the GNS registry`() {
        // apoorv.gwei → IPFS CIDv1 dag-pb.
        val http = ScriptedRpc {
            result(abiBytes("e30101701220845659152d8b56b8f655fe44174a19bc0079d773a4febf516b6bf373b9bf0fa1"))
        }
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("apoorv.gwei") }

        require(r is EnsResult.Ok) { "got $r" }
        assertEquals("ipfs://bafybeieekzmrklmlk24pmvp6iqluugn4ab45o45e727vc23l6nz3tpypue", r.uri)
        assertEquals("0x9D51D507BC7264d4fE8Ad1cf7Fe191933A0a81d6", http.calls.single().to)
    }

    @Test
    fun `unregistered or empty name is not found`() {
        // What both registries return for a name with no record
        // (alice.wei at the time of writing): an empty `bytes`.
        val http = ScriptedRpc { result(abiBytes("")) }
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("alice.wei") }
        assertEquals(EnsResult.NotFound("alice.wei", "EMPTY_CONTENTHASH"), r)
    }

    @Test
    fun `a registry revert is a resolution error, never a CCIP-Read`() {
        // An OffchainLookup-shaped revert from a NameNFT registry must
        // not send us to any gateway: only the Universal Resolver defers
        // offchain.
        val http = ScriptedRpc {
            EnsHttp.Reply(
                200,
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"0x556f1830${"00".repeat(160)}"}}""",
            )
        }
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("x.wei") }
        require(r is EnsResult.Error) { "got $r" }
        assertEquals("RESOLUTION_ERROR", r.reason)
        assertTrue(http.calls.all { it.to == NameSystem.WNS.contractAddress })
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `eth names still go through the Universal Resolver`() {
        val http = ScriptedRpc { result(abiBytes("")) }
        runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("swarm.eth") }
        assertEquals("0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe", http.calls.single().to)
        assertTrue(http.calls.single().data.startsWith("0x9061b923"))
    }
}
