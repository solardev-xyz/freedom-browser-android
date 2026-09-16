package baby.freedom.mobile.ens

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCIP-Read (EIP-3668) coverage for [EnsResolver], scripted through the
 * [EnsHttp] seam so no network is involved.
 *
 * `ccip/offchainexample-revert.hex` is the real `OffchainLookup` revert
 * the Universal Resolver returned for `1.offchainexample.eth` (ENS's
 * own offchain-resolver demo name) in September 2026: sender = the
 * Universal Resolver, urls = `[https://ccip-v3.ens.xyz,
 * x-batch-gateway:true]`, callback = `0xef46c0b8`.
 */
class CcipReadTest {

    private val rpc = "https://rpc.test/"
    private val ur = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"

    private class Request(val method: String, val url: String, val body: String?)

    /** Routes every request through [handler] and keeps a log. */
    private class ScriptedHttp(
        private val handler: (Request) -> EnsHttp.Reply,
    ) : EnsHttp {
        val log = mutableListOf<Request>()
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            val r = Request(method, url, body)
            log += r
            return handler(r)
        }
    }

    private val fixtureRevert: String by lazy {
        javaClass.getResource("/ccip/offchainexample-revert.hex")!!.readText().trim()
    }

    private fun rpcRevert(data: String) = EnsHttp.Reply(
        200,
        """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"$data"}}""",
    )

    private fun rpcResult(hex: String) =
        EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"$hex"}""")

    private fun gatewayReply(bytes: ByteArray) =
        EnsHttp.Reply(200, """{"data":"0x${bytes.toHex()}"}""")

    private fun rpcCallData(r: Request): String =
        JSONObject(r.body!!).getJSONArray("params").getJSONObject(0).getString("data")

    private fun rpcTo(r: Request): String =
        JSONObject(r.body!!).getJSONArray("params").getJSONObject(0).getString("to")

    private val ipfsContenthash =
        "e30101701220" + "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"

    @Test
    fun `decodes the real Universal Resolver OffchainLookup revert`() {
        val lookup = EnsResolver.decodeOffchainLookup(fixtureRevert)!!
        assertEquals(ur.lowercase(), lookup.sender)
        assertEquals(listOf("https://ccip-v3.ens.xyz", "x-batch-gateway:true"), lookup.urls)
        assertEquals("ef46c0b8", lookup.callback.toHex())
        assertEquals(676, lookup.callData.size)
        assertEquals(2272, lookup.extraData.size)
    }

    @Test
    fun `follows an OffchainLookup through the gateway and the callback`() {
        val lookup = EnsResolver.decodeOffchainLookup(fixtureRevert)!!
        val gatewayAnswer = "cafe0042".hexToBytes()
        val expectedCallback = "0x" +
            (lookup.callback + abiEncodeTwoBytes(gatewayAnswer, lookup.extraData)).toHex()

        val http = ScriptedHttp { r ->
            when {
                r.url == rpc && rpcCallData(r).startsWith("0x9061b923") -> rpcRevert(fixtureRevert)
                r.url == "https://ccip-v3.ens.xyz" -> {
                    assertEquals("POST", r.method)
                    val body = JSONObject(r.body!!)
                    assertEquals(ur.lowercase(), body.getString("sender"))
                    assertEquals("0x" + lookup.callData.toHex(), body.getString("data"))
                    gatewayReply(gatewayAnswer)
                }
                r.url == rpc -> {
                    assertEquals(ur.lowercase(), rpcTo(r).lowercase())
                    assertEquals(expectedCallback, rpcCallData(r))
                    rpcResult(wrapAsOuterInner(ipfsContenthash))
                }
                else -> error("unexpected request ${r.method} ${r.url}")
            }
        }

        val result = runBlocking {
            EnsResolver(listOf(rpc), http).resolveContenthash("1.offchainexample.eth")
        }

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("ipfs", result.protocol)
        assertTrue(result.decoded.startsWith("bafy"))
        assertEquals(3, http.log.size)
    }

    @Test
    fun `GET templates get sender and data substituted, non-https gateways are skipped`() {
        val callData = "deadbeef".hexToBytes()
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf(
                "http://insecure.example/{sender}/{data}",
                "https://127.0.0.1/{data}",
                "https://gw.example/v1/{sender}/{data}",
            ),
            callData = callData,
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        val http = ScriptedHttp { r ->
            when {
                r.url == rpc && rpcCallData(r).startsWith("0x9061b923") -> rpcRevert(revert)
                r.url.startsWith("https://gw.example/") -> {
                    assertEquals("GET", r.method)
                    assertEquals(null, r.body)
                    assertEquals("https://gw.example/v1/${ur.lowercase()}/0xdeadbeef", r.url)
                    gatewayReply("01".hexToBytes())
                }
                r.url == rpc -> {
                    assertTrue(rpcCallData(r).startsWith("0x11223344"))
                    rpcResult(wrapAsOuterInner("e40101fa011b20" + "ab".repeat(32)))
                }
                else -> error("unexpected request ${r.method} ${r.url}")
            }
        }

        val result = runBlocking {
            EnsResolver(listOf(rpc), http).resolveContenthash("gettest.eth")
        }

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz", result.protocol)
        // Only the https, non-loopback gateway was ever contacted.
        assertEquals(listOf(rpc, "https://gw.example/v1/${ur.lowercase()}/0xdeadbeef", rpc), http.log.map { it.url })
    }

    @Test
    fun `gateway failure is a retryable error and is not cached`() {
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf("https://down.example/{data}", "https://also-down.example"),
            callData = "00".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = ByteArray(0),
        )
        val http = ScriptedHttp { r ->
            when {
                r.url == rpc -> rpcRevert(revert)
                r.url.startsWith("https://down.example/") -> EnsHttp.Reply(500, "nope")
                r.url == "https://also-down.example" -> EnsHttp.Reply(200, """{"data":"not hex"}""")
                else -> error("unexpected request ${r.method} ${r.url}")
            }
        }
        val resolver = EnsResolver(listOf(rpc), http)

        val first = runBlocking { resolver.resolveContenthash("down.eth") }
        require(first is EnsResult.Error) { "got $first" }
        assertEquals("CCIP_GATEWAY_FAILED", first.reason)
        assertTrue(first.retryable)

        val callsAfterFirst = http.log.size
        runBlocking { resolver.resolveContenthash("down.eth") }
        assertTrue("second attempt should hit the network again", http.log.size > callsAfterFirst)
    }

    @Test
    fun `a lookup from any contract other than the Universal Resolver is refused`() {
        val revert = encodeOffchainLookup(
            sender = "0x000000000000000000000000000000000000dEaD",
            urls = listOf("https://gw.example"),
            callData = "00".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = ByteArray(0),
        )
        val http = ScriptedHttp { r ->
            when (r.url) {
                rpc -> rpcRevert(revert)
                else -> error("gateway must not be contacted: ${r.url}")
            }
        }

        val result = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("evil.eth") }

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        assertTrue(result.error.contains("sender"))
        assertEquals(1, http.log.size)
    }

    @Test
    fun `an endless chain of lookups stops after the round limit`() {
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf("https://gw.example"),
            callData = "00".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = ByteArray(0),
        )
        val http = ScriptedHttp { r ->
            when (r.url) {
                rpc -> rpcRevert(revert) // every callback reverts with another lookup
                "https://gw.example" -> gatewayReply("01".hexToBytes())
                else -> error("unexpected request ${r.method} ${r.url}")
            }
        }

        val result = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("loop.eth") }

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("CCIP_GATEWAY_FAILED", result.reason)
        assertEquals(10, http.log.count { it.url == "https://gw.example" })
    }
}

// ---- encoders ----

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
