package baby.freedom.mobile.ens

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnsResolver.resolveAddress] (#277): a send's recipient name read
 * through the same tiers as a page's contenthash, for the coin type of
 * the send's chain. Scripted through the [EnsHttp] seam and a fake
 * [EnsLightClient]; each server decodes the call it was sent, so the
 * tests pin the call data too.
 */
class EnsAddressResolveTest {

    private val head = 1_000L
    private val ur = "0xeeeeeeee14d718c2b47d9923deab1335e144eeee"
    private val alice = "0x" + "a1".repeat(20)
    private val bob = "0x" + "b0".repeat(20)

    /** What one `eth_call` asked: the contract, the record call, and the name (UR only). */
    private class Asked(val to: String, val record: ByteArray, val dnsName: ByteArray?)

    /** Every server on the chain; each answers the record read with [answer]. */
    private class Servers(
        val urls: List<String>,
        val answer: (url: String, asked: Asked) -> EnsHttp.Reply?,
    ) : EnsHttp {
        val asked: MutableList<Asked> = Collections.synchronizedList(mutableListOf())

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            require(url in urls) { "unexpected request to $url" }
            val json = JSONObject(body!!)
            val params = json.getJSONArray("params")
            return when (json.getString("method")) {
                "eth_blockNumber" -> result("\"0x${1_000L.toString(16)}\"")
                "eth_getBlockByNumber" -> {
                    val n = params.getString(0).removePrefix("0x").toLong(16)
                    result("""{"number":"0x${n.toString(16)}","hash":"0x${n.toString(16).padStart(64, 'a')}"}""")
                }
                "eth_call" -> {
                    val call = params.getJSONObject(0)
                    val a = parse(call.getString("to").lowercase(), call.getString("data").hexToBytes())
                    asked += a
                    answer(url, a) ?: EnsHttp.Reply(503, "down")
                }
                else -> error("unexpected method")
            }
        }

        private fun parse(to: String, data: ByteArray): Asked {
            if (!data.copyOfRange(0, 4).contentEquals("9061b923".hexToBytes())) return Asked(to, data, null)
            val args = data.copyOfRange(4, data.size)
            fun bytesAt(slot: Int): ByteArray {
                val off = word(args, slot * 32).toInt()
                val len = word(args, off).toInt()
                return args.copyOfRange(off + 32, off + 32 + len)
            }
            return Asked(to, bytesAt(1), bytesAt(0))
        }

        private fun result(json: String) = EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":$json}""")
    }

    private fun urls(n: Int) = (1..n).map { "https://rpc$it.test/" }

    private fun reply(hex: String) = EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"$hex"}""")

    private fun revert(data: String) = EnsHttp.Reply(
        200,
        """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"$data"}}""",
    )

    /** `addr(bytes32)`'s return: an ABI `address`. */
    private fun abiAddress(address: String) = ByteArray(12) + address.hexToBytes()

    /** `addr(bytes32,uint256)`'s return: ABI `bytes`. */
    private fun abiBytes(bytes: ByteArray) = uint256(0x20) + uint256(bytes.size.toLong()) + padded(bytes)

    /** The Universal Resolver's `(bytes result, address resolver)` around [inner]. */
    private fun viaUr(inner: ByteArray) =
        "0x" + (uint256(0x40) + ByteArray(32) + uint256(inner.size.toLong()) + padded(inner)).toHex()

    /** A server answering an address record the way [send] says, for whatever was asked. */
    private fun answering(send: (Asked) -> String?): (String, Asked) -> EnsHttp.Reply? =
        { _, asked -> send(asked)?.let { hex -> if (hex.startsWith("revert:")) revert(hex.removePrefix("revert:")) else reply(hex) } }

    /** [address] as the record read asked for it: the ABI shape its selector returns, wrapped by the UR if it went there. */
    private fun encoded(asked: Asked, address: String): String {
        val multicoin = asked.record.copyOfRange(0, 4).contentEquals("f1cb7e06".hexToBytes())
        val inner = if (multicoin) abiBytes(address.hexToBytes()) else abiAddress(address)
        return if (asked.dnsName != null) viaUr(inner) else "0x" + inner.toHex()
    }

    private fun resolve(servers: EnsHttp, endpoints: List<String>, name: String, chainId: Long, resolver: EnsResolver? = null) =
        runBlocking { (resolver ?: EnsResolver(endpoints, servers)).resolveAddress(name, chainId) }

    @Test
    fun `an eth name on Ethereum asks addr(bytes32) through the Universal Resolver, verified by the quorum`() {
        val servers = Servers(urls(3), answering { encoded(it, alice) })

        val result = resolve(servers, servers.urls, "Alice.eth", chainId = 1)

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals("alice.eth", result.name)
        assertEquals(alice, result.address)
        assertTrue(result.trust.verified)
        assertEquals(head - EnsQuorum.SAFETY_DEPTH, result.trust.block)
        val asked = servers.asked.first()
        assertEquals(ur, asked.to)
        assertEquals(EnsResolver.dnsEncode("alice.eth").toHex(), asked.dnsName!!.toHex())
        assertEquals("3b3b57de" + EnsResolver.namehash("alice.eth").toHex(), asked.record.toHex())
    }

    @Test
    fun `on Gnosis an eth name asks for Gnosis's ENSIP-11 coin type`() {
        val servers = Servers(urls(3), answering { encoded(it, bob) })

        val result = resolve(servers, servers.urls, "alice.eth", chainId = 100)

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals(bob, result.address)
        val coinType = uint256(0x80000000L + 100).toHex()
        assertEquals("f1cb7e06" + EnsResolver.namehash("alice.eth").toHex() + coinType, servers.asked.first().record.toHex())
        assertTrue(coinType.endsWith("80000064"))
    }

    @Test
    fun `a wei name is read from the WNS registry directly`() {
        val servers = Servers(urls(3), answering { encoded(it, alice) })

        val result = resolve(servers, servers.urls, "alice.wei", chainId = 1)

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals(alice, result.address)
        val asked = servers.asked.first()
        assertEquals(NameSystem.WNS.contractAddress!!.lowercase(), asked.to)
        assertEquals(null, asked.dnsName)
        assertEquals("3b3b57de" + EnsResolver.namehash("alice.wei").toHex(), asked.record.toHex())
    }

    @Test
    fun `a wei or gwei name off Ethereum is refused without asking anyone`() {
        val servers = Servers(urls(3)) { _, _ -> error("nothing should be asked") }

        for (name in listOf("alice.wei", "alice.gwei")) {
            val result = resolve(servers, servers.urls, name, chainId = 100)
            require(result is EnsAddressResult.NoAddress) { "got $result" }
            assertEquals("CHAIN_UNSUPPORTED", result.reason)
            assertEquals(null, result.trust)
        }
        assertTrue(servers.asked.isEmpty())
    }

    @Test
    fun `a chain id past ENSIP-11's range has no address, and nobody is asked`() {
        val servers = Servers(urls(3)) { _, _ -> error("nothing should be asked") }

        for (chainId in listOf(0x80000000L, 0x1_0000_0000L, 0L, -1L)) {
            val result = resolve(servers, servers.urls, "Alice.eth", chainId)
            require(result is EnsAddressResult.NoAddress) { "got $result" }
            assertEquals("CHAIN_ID_UNSUPPORTED", result.reason)
            assertEquals("alice.eth", result.name)
        }
        assertTrue(servers.asked.isEmpty())
    }

    @Test
    fun `a DNS name goes through the Universal Resolver like any ENS name`() {
        val servers = Servers(urls(3), answering { encoded(it, alice) })

        val result = resolve(servers, servers.urls, "gregskril.com", chainId = 1)

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals(alice, result.address)
        val asked = servers.asked.first()
        assertEquals(ur, asked.to)
        assertEquals(EnsResolver.dnsEncode("gregskril.com").toHex(), asked.dnsName!!.toHex())
    }

    @Test
    fun `a name with no address for the chain has none, and says how that was checked`() {
        // Empty multicoin bytes, the zero address, and a resolver without the multicoin profile.
        val answers = listOf<(Asked) -> String>(
            { viaUr(abiBytes(ByteArray(0))) },
            { encoded(it, "0x" + "00".repeat(20)) },
            { "revert:0x7b1c461bf1cb7e0600000000000000000000000000000000000000000000000000000000" },
        )
        for (answer in answers) {
            val servers = Servers(urls(3), answering(answer))
            val result = resolve(servers, servers.urls, "alice.eth", chainId = 100)
            require(result is EnsAddressResult.NoAddress) { "got $result" }
            assertEquals("NO_ADDRESS", result.reason)
            assertTrue(result.trust!!.verified)
        }
    }

    @Test
    fun `an unset name has no resolver`() {
        val servers = Servers(urls(3), answering { "revert:0x77209fe8" })

        val result = resolve(servers, servers.urls, "nobody.eth", chainId = 1)

        require(result is EnsAddressResult.NoAddress) { "got $result" }
        assertEquals("NO_RESOLVER", result.reason)
    }

    @Test
    fun `servers disagreeing on the address are a conflict naming both addresses`() {
        val servers = Servers(urls(3)) { url, asked ->
            when (url) {
                "https://rpc1.test/" -> reply(encoded(asked, alice))
                "https://rpc2.test/" -> reply(encoded(asked, bob))
                else -> null
            }
        }

        val result = resolve(servers, servers.urls, "alice.eth", chainId = 1)

        require(result is EnsAddressResult.Conflict) { "got $result" }
        assertEquals(EnsResult.Conflict.Subject.RECORD, result.subject)
        assertEquals(
            setOf(alice to listOf("rpc1.test"), bob to listOf("rpc2.test")),
            result.groups.map { it.answer to it.hosts }.toSet(),
        )
    }

    @Test
    fun `one server's word is unverified`() {
        val servers = Servers(urls(1), answering { encoded(it, alice) })

        val result = resolve(servers, servers.urls, "alice.eth", chainId = 1)

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertFalse(result.trust.verified)
        assertEquals(listOf("rpc1.test"), result.trust.agreed)
    }

    @Test
    fun `a record that isn't an EVM address is an error, not an address`() {
        val servers = Servers(urls(3), answering { viaUr(abiBytes(ByteArray(32) { 1 })) })

        val result = resolve(servers, servers.urls, "alice.eth", chainId = 100)

        require(result is EnsAddressResult.Error) { "got $result" }
        assertEquals("RESOLUTION_ERROR", result.reason)
    }

    @Test
    fun `a tez name names no Ethereum account`() {
        val servers = Servers(urls(3)) { _, _ -> error("nothing should be asked") }

        val result = resolve(servers, servers.urls, "alice.tez", chainId = 1)

        require(result is EnsAddressResult.NoAddress) { "got $result" }
        assertEquals("UNSUPPORTED_SYSTEM", result.reason)
    }

    @Test
    fun `a cached conflict is served again, but a fresh lookup (the field's Try again) asks the servers`() {
        // R3-M1: Try again on a conflict must not just read the conflict
        // back out of the cache for its 10 s.
        var agree = false
        val servers = Servers(urls(3)) { url, asked ->
            when (url) {
                "https://rpc1.test/" -> reply(encoded(asked, alice))
                "https://rpc2.test/" -> reply(encoded(asked, if (agree) alice else bob))
                else -> null
            }
        }
        val resolver = EnsResolver(servers.urls, servers)

        assertTrue(resolve(servers, servers.urls, "alice.eth", 1, resolver) is EnsAddressResult.Conflict)
        val askedOnce = servers.asked.size
        agree = true
        assertTrue(resolve(servers, servers.urls, "alice.eth", 1, resolver) is EnsAddressResult.Conflict)
        assertEquals(askedOnce, servers.asked.size)

        val retried = runBlocking { resolver.resolveAddress("alice.eth", 1, fresh = true) }
        require(retried is EnsAddressResult.Ok) { "got $retried" }
        assertEquals(alice, retried.address)
        assertTrue(servers.asked.size > askedOnce)
    }

    @Test
    fun `addresses are cached per chain and apart from contenthash, and a fresh lookup asks again`() {
        var current = alice
        val servers = Servers(urls(1), answering { encoded(it, current) })
        val resolver = EnsResolver(servers.urls, servers)

        assertEquals(alice, (resolve(servers, servers.urls, "alice.eth", 1, resolver) as EnsAddressResult.Ok).address)
        current = bob
        // Cached for Ethereum; Gnosis is its own record.
        assertEquals(alice, (resolve(servers, servers.urls, "alice.eth", 1, resolver) as EnsAddressResult.Ok).address)
        assertEquals(bob, (resolve(servers, servers.urls, "alice.eth", 100, resolver) as EnsAddressResult.Ok).address)
        assertEquals(2, servers.asked.size)
        // The re-check before signing skips the cache.
        val fresh = runBlocking { resolver.resolveAddress("alice.eth", 1, fresh = true) }
        assertEquals(bob, (fresh as EnsAddressResult.Ok).address)
        assertEquals(3, servers.asked.size)
    }

    @Test
    fun `a ready light client proves the address on its own`() {
        val client = object : EnsLightClient {
            val calls = mutableListOf<String>()
            override fun readyGeneration(): Long? = 1L
            override fun ethCall(to: String, data: String, timeoutMs: Long, probe: Boolean, released: (() -> Unit)?): EnsLightClient.Call {
                released?.invoke()
                calls += data
                return EnsLightClient.Call.Ok(viaUr(abiAddress(alice)), block = 21_000_000L)
            }
        }
        val servers = Servers(urls(1)) { _, _ -> error("no RPC server should be asked") }
        val resolver = EnsResolver(
            { EnsResolver.Settings(servers.urls) }, servers, TezosDomainsResolver(), client,
            lightClientEstablishedMs = 0,
        )

        val result = runBlocking { resolver.resolveAddress("alice.eth", 1) }

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals(alice, result.address)
        assertTrue(result.trust.proven)
        assertTrue(result.trust.lightClient)
        assertEquals(21_000_000L, result.trust.block)
        assertTrue(client.calls.single().contains("3b3b57de" + EnsResolver.namehash("alice.eth").toHex()))
    }
}

private fun word(bytes: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 24 until 32) v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
    return v
}

private fun uint256(v: Long): ByteArray = ByteArray(32).also {
    for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte()
}

private fun padded(bytes: ByteArray): ByteArray {
    val rem = bytes.size % 32
    return if (rem == 0) bytes.copyOf() else bytes.copyOf(bytes.size + (32 - rem))
}
