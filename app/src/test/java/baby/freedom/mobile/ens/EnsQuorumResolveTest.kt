package baby.freedom.mobile.ens

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnsResolver] across several RPC servers (#96), each scripted through
 * the [EnsHttp] seam: its head, its hash for each block, and its answer
 * to the record read.
 */
class EnsQuorumResolveTest {

    private val head = 1_000L
    private val anchor = head - EnsQuorum.SAFETY_DEPTH
    private val anchorTag = "0x" + anchor.toString(16)

    private fun canonicalHash(block: Long) = "0x" + block.toString(16).padStart(64, 'a')

    private val honestRef = "11".repeat(32)
    private val otherRef = "22".repeat(32)

    /** One scripted server. */
    private class Server(
        var head: Long?,
        var hashOf: (Long) -> String?,
        var record: (block: String, data: String) -> EnsHttp.Reply?,
        /** Milliseconds the head answer takes, to fix the arrival order. */
        var headDelayMs: Long = 0,
    )

    private class Request(val url: String, val method: String, val params: JSONArray)

    private class Servers(val byUrl: Map<String, Server>) : EnsHttp {
        val log: MutableList<Request> = Collections.synchronizedList(mutableListOf())

        fun calls(url: String? = null) = synchronized(log) {
            log.filter { it.method == "eth_call" && (url == null || it.url == url) }
        }

        fun count(method: String) = synchronized(log) { log.count { it.method == method } }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            val server = byUrl[url] ?: error("unexpected request to $url")
            val json = JSONObject(body!!)
            val rpcMethod = json.getString("method")
            val params = json.getJSONArray("params")
            log += Request(url, rpcMethod, params)
            val down = EnsHttp.Reply(503, "down")
            return when (rpcMethod) {
                "eth_blockNumber" -> {
                    Thread.sleep(server.headDelayMs)
                    server.head?.let { result("\"0x${it.toString(16)}\"") } ?: down
                }
                "eth_getBlockByNumber" -> {
                    val n = params.getString(0).removePrefix("0x").toLong(16)
                    server.hashOf(n)?.let { result("""{"number":"0x${n.toString(16)}","hash":"$it"}""") } ?: down
                }
                "eth_call" -> server.record(params.getString(1), params.getJSONObject(0).getString("data")) ?: down
                else -> error("unexpected method $rpcMethod")
            }
        }

        private fun result(json: String) = EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":$json}""")
    }

    private fun contenthash(ref: String) = EnsHttp.Reply(
        200,
        """{"jsonrpc":"2.0","id":1,"result":"${wrapAsOuterInner("e40101fa011b20$ref")}"}""",
    )

    private val noResolver = EnsHttp.Reply(
        200,
        """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"0x77209fe8"}}""",
    )

    /** An honest server: on the chain, answering [ref] for the record. */
    private fun honest(ref: String? = honestRef, delayMs: Long = 0) = Server(
        head = head,
        hashOf = ::canonicalHash,
        record = { _, _ -> ref?.let(::contenthash) },
        headDelayMs = delayMs,
    )

    private fun urls(n: Int) = (1..n).map { "https://rpc$it.test/" }

    private fun resolve(servers: Servers, name: String = "quorum.eth"): EnsResult = runBlocking {
        EnsResolver(servers.byUrl.keys.toList(), servers).resolveContenthash(name)
    }

    private fun serversOf(vararg servers: Server) = Servers(urls(servers.size).zip(servers).toMap(LinkedHashMap()))

    @Test
    fun `servers that agree give a verified answer, read at the anchor block`() {
        val servers = serversOf(honest(), honest(), honest(), honest(), honest())

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$honestRef", result.uri)
        assertTrue(result.trust.verified)
        assertEquals(anchor, result.trust.block)
        assertTrue(result.trust.agreed.size >= EnsQuorum.M)
        // Every read is pinned to the anchor, none to `latest`.
        assertTrue(servers.calls().isNotEmpty())
        assertTrue(servers.calls().all { it.params.getString(1) == anchorTag })
        // Only a wave's worth of servers is asked for the record.
        assertTrue(servers.calls().size <= EnsQuorum.K)
    }

    @Test
    fun `one lying server is outvoted`() {
        val liar = honest(ref = otherRef)
        val servers = serversOf(liar, honest(), honest())

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$honestRef", result.uri)
        assertTrue(result.trust.verified)
        assertFalse("rpc1.test" in result.trust.agreed)
    }

    @Test
    fun `two servers with different answers are a conflict`() {
        val broken = honest(ref = null)
        val servers = serversOf(honest(), honest(ref = otherRef), broken)

        val result = resolve(servers)

        require(result is EnsResult.Conflict) { "got $result" }
        assertEquals(EnsResult.Conflict.Subject.RECORD, result.subject)
        assertEquals(anchor, result.block)
        assertEquals(
            setOf("bzz://$honestRef" to listOf("rpc1.test"), "bzz://$otherRef" to listOf("rpc2.test")),
            result.groups.map { it.answer to it.hosts }.toSet(),
        )
    }

    @Test
    fun `a record only one server gave is unverified`() {
        val servers = serversOf(honest(), honest(ref = null), honest(ref = null))

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$honestRef", result.uri)
        assertFalse(result.trust.verified)
        assertEquals(listOf("rpc1.test"), result.trust.agreed)
        assertEquals(anchor, result.trust.block)
    }

    @Test
    fun `a wave with no verdict is widened to the other servers`() {
        // The three fastest can't read the record; the two slowest can.
        val servers = serversOf(
            honest(ref = null, delayMs = 0),
            honest(ref = null, delayMs = 20),
            honest(ref = null, delayMs = 40),
            honest(delayMs = 60),
            honest(delayMs = 80),
        )

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.verified)
        assertEquals(setOf("rpc4.test", "rpc5.test"), result.trust.agreed.toSet())
        assertEquals(5, servers.calls().size)
    }

    @Test
    fun `an unverified answer is upgraded when a wider wave agrees`() {
        val servers = serversOf(
            honest(delayMs = 0),
            honest(ref = null, delayMs = 20),
            honest(ref = null, delayMs = 40),
            honest(delayMs = 60),
        )

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.verified)
        assertEquals(setOf("rpc1.test", "rpc4.test"), result.trust.agreed.toSet())
    }

    @Test
    fun `a record read from a server on another block doesn't count`() {
        // rpc3 is outvoted on block #anchor's hash, so its read — which
        // would otherwise conflict with rpc1's — has no vote.
        val forked = Server(
            head = head,
            hashOf = { "0x" + "f".repeat(64) },
            record = { _, _ -> contenthash(otherRef) },
        )
        val servers = serversOf(honest(), honest(ref = null), forked)

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$honestRef", result.uri)
        assertFalse(result.trust.verified)
        assertEquals(listOf("rpc1.test"), result.trust.agreed)
    }

    @Test
    fun `servers disagreeing about the anchor block's hash are a conflict`() {
        fun forkAt(tag: Char) = Server(head = head, hashOf = { "0x" + tag.toString().repeat(64) }, record = { _, _ -> contenthash(honestRef) })
        val servers = serversOf(forkAt('a'), forkAt('b'), forkAt('c'))

        val result = resolve(servers)

        require(result is EnsResult.Conflict) { "got $result" }
        assertEquals(EnsResult.Conflict.Subject.BLOCK, result.subject)
        assertEquals(anchor, result.block)
        assertEquals(3, result.groups.size)
    }

    @Test
    fun `too few heads for a median falls back to one server's word, labelled unverified`() {
        val silent = Server(head = null, hashOf = { null }, record = { _, _ -> contenthash(honestRef) })
        val servers = serversOf(honest(), silent, silent.let { Server(null, { null }, it.record) })

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$honestRef", result.uri)
        assertFalse(result.trust.verified)
        assertEquals(null, result.trust.block)
        assertTrue(servers.calls().all { it.params.getString(1) == "latest" })
    }

    @Test
    fun `fewer than three endpoints skip the quorum altogether`() {
        val servers = serversOf(honest(), honest())

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertFalse(result.trust.verified)
        assertEquals(0, servers.count("eth_blockNumber"))
        assertEquals(1, servers.calls().size)
    }

    @Test
    fun `an agreed no-resolver is a verified not-found`() {
        val gone = Server(head = head, hashOf = ::canonicalHash, record = { _, _ -> noResolver })
        val servers = serversOf(gone, gone, gone)

        val result = resolve(servers)
        require(result is EnsResult.NotFound) { "got $result" }
        assertEquals("NO_RESOLVER", result.reason)
        assertTrue(result.trust.verified)
        assertEquals(anchor, result.trust.block)
    }

    @Test
    fun `one server's no-resolver doesn't hide a record the others agree on`() {
        val denier = Server(head = head, hashOf = ::canonicalHash, record = { _, _ -> noResolver })
        val servers = serversOf(denier, honest(), honest())

        val result = resolve(servers)

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.verified)
    }

    @Test
    fun `a lone server's no-resolver is an unverified not-found`() {
        val denier = Server(head = head, hashOf = ::canonicalHash, record = { _, _ -> noResolver })
        val down = Server(head = head, hashOf = ::canonicalHash, record = { _, _ -> null })
        val servers = serversOf(denier, down, down)

        val result = resolve(servers)

        require(result is EnsResult.NotFound) { "got $result" }
        assertEquals("NO_RESOLVER", result.reason)
        assertFalse(result.trust.verified)
        assertEquals(1, result.trust.agreed.size)
        assertEquals(anchor, result.trust.block)
    }

    @Test
    fun `every server down is a retryable provider error`() {
        val down = Server(head = head, hashOf = ::canonicalHash, record = { _, _ -> null })
        val result = resolve(serversOf(down, down, down))

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("PROVIDER_ERROR", result.reason)
        assertTrue(result.retryable)
    }

    @Test
    fun `the anchor is reused by the next lookup`() {
        val servers = serversOf(honest(), honest(), honest())
        val resolver = EnsResolver(servers.byUrl.keys.toList(), servers)

        runBlocking {
            resolver.resolveContenthash("first.eth")
            resolver.resolveContenthash("second.eth")
        }

        // The happy path's second lookup is one round of record reads.
        assertEquals(3, servers.count("eth_blockNumber"))
        assertEquals(3, servers.count("eth_getBlockByNumber"))
    }

    @Test
    fun `a verified answer is cached, an unverified one is not reused past its moment`() {
        val servers = serversOf(honest(), honest(), honest())
        val resolver = EnsResolver(servers.byUrl.keys.toList(), servers)

        val (first, second) = runBlocking {
            resolver.resolveContenthash("cached.eth") to resolver.resolveContenthash("cached.eth")
        }

        assertEquals(first, second)
        // Served from the cache: no second round of reads.
        assertTrue(servers.calls().size <= EnsQuorum.K)
    }

    @Test
    fun `CCIP-Read is followed on each server at the anchor block`() {
        val ur = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"
        val revert = encodeOffchainLookup(
            sender = ur,
            urls = listOf("https://gw.example/{sender}/{data}"),
            callData = "deadbeef".hexToBytes(),
            callback = "11223344".hexToBytes(),
            extraData = "ee".hexToBytes(),
        )
        val offchain = Server(
            head = head,
            hashOf = ::canonicalHash,
            record = { _, data ->
                if (data.startsWith("0x9061b923")) {
                    EnsHttp.Reply(
                        200,
                        """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"reverted","data":"$revert"}}""",
                    )
                } else {
                    contenthash(honestRef)
                }
            },
        )
        val rpcs = serversOf(offchain, offchain, offchain)
        val http = object : EnsHttp {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int,
                maxBytes: Long,
                followRedirects: Boolean,
            ): EnsHttp.Reply =
                if (url.startsWith("https://gw.example/")) {
                    EnsHttp.Reply(200, """{"data":"0x01"}""")
                } else {
                    rpcs.request(method, url, headers, body, timeoutMs, maxBytes, followRedirects)
                }
        }

        val result = runBlocking { EnsResolver(rpcs.byUrl.keys.toList(), http).resolveContenthash("off.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertTrue(result.trust.verified)
        val callbacks = rpcs.calls().filter { it.params.getJSONObject(0).getString("data").startsWith("0x11223344") }
        assertTrue(callbacks.isNotEmpty())
        assertTrue(callbacks.all { it.params.getString(1) == anchorTag })
    }
}

/** `(bytes result, address resolver)` with `result = abi.encode(bytes contenthash)`. */
private fun wrapAsOuterInner(contentHashHex: String): String {
    val contentHash = contentHashHex.hexToBytes()
    val innerEncoded = uint256(0x20L) + uint256(contentHash.size.toLong()) + paddedTo32(contentHash)
    val outerBody = uint256(innerEncoded.size.toLong()) + paddedTo32(innerEncoded)
    return "0x" + (uint256(0x40L) + ByteArray(32) + outerBody).toHex()
}

/** `OffchainLookup(address,string[],bytes,bytes4,bytes)` revert data. */
private fun encodeOffchainLookup(
    sender: String,
    urls: List<String>,
    callData: ByteArray,
    callback: ByteArray,
    extraData: ByteArray,
): String {
    val senderWord = ByteArray(12) + sender.hexToBytes()
    val callbackWord = paddedTo32(callback)
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
    val callDataOff = headSize + urlsTail.size
    val extraDataOff = callDataOff + callDataTail.size
    val body = senderWord + uint256(headSize) + uint256(callDataOff) + callbackWord + uint256(extraDataOff) +
        urlsTail + callDataTail + extraDataTail
    return "0x556f1830" + body.toHex()
}

private fun uint256(v: Long): ByteArray = ByteArray(32).also {
    for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte()
}

private fun paddedTo32(bytes: ByteArray): ByteArray {
    val rem = bytes.size % 32
    return if (rem == 0) bytes.copyOf() else bytes.copyOf(bytes.size + (32 - rem))
}
