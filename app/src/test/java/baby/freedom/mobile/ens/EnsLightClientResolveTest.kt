package baby.freedom.mobile.ens

import java.util.Collections
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
class EnsLightClientResolveTest {

    private val rpc = "https://rpc.test/"
    private val ur = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"
    private val ipfsContenthash =
        "e30101701220" + "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"

    /** Answers every call with [answer]; records the calls. */
    private class FakeLightClient(
        @Volatile var generation: Long? = 1L,
        val answer: (to: String, data: String) -> EnsLightClient.Call,
    ) : EnsLightClient {
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        override fun readyGeneration(): Long? = generation
        override fun ethCall(to: String, data: String, timeoutMs: Long): EnsLightClient.Call {
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
        settings: () -> EnsResolver.Settings = { EnsResolver.Settings(listOf(rpc), lightClient = client.readyGeneration()) },
        deadlineMs: Long = EnsResolver.LIGHT_CLIENT_DEADLINE_MS,
    ) = EnsResolver({ settings() }, http, TezosDomainsResolver(), client, deadlineMs)

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
        val settings = EnsResolver.Settings(listOf(rpc), lightClient = 1L)

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
                { EnsResolver.Settings(listOf(rpc), lightClient = 1L) },
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
            resolver(client, http, settings = { EnsResolver.Settings(listOf(rpc), ccipRead = false, lightClient = 1L) })
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
            resolver(client, http, settings = { EnsResolver.Settings(emptyList(), lightClient = 1L) })
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
            resolver(client, http, settings = { EnsResolver.Settings(emptyList(), lightClient = 1L) })
                .resolveContenthash("vitalik.eth")
        }

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("NO_RPC_ENDPOINTS", result.reason)
    }

    @Test
    fun `a readiness transition starts a fresh cache either way`() {
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
        // Cached under that readiness...
        runBlocking { r.resolveContenthash("vitalik.eth") }
        assertEquals(1, client.calls.size)
        // ...and not once it's gone.
        client.generation = null
        val third = runBlocking { r.resolveContenthash("vitalik.eth") } as EnsResult.Ok
        assertFalse(third.trust.lightClient)
        assertEquals(2, http.urls.size)
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
        assertEquals(
            EnsLightClient.Call.Unavailable("native execution busy"),
            EnsLightClient.parse("""{"error":"native execution busy"}"""),
        )
        assertTrue(EnsLightClient.parse("""{"status":"ok","resultHex":"0xabc"}""") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse("""{"status":"ok","resultHex":"nothex"}""") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse("not json") is EnsLightClient.Call.Unavailable)
        assertTrue(EnsLightClient.parse(null) is EnsLightClient.Call.Unavailable)
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
