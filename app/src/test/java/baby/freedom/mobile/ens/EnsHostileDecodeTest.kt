package baby.freedom.mobile.ens

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hostile bytes in a record read. A name's owner writes its resolver,
 * so whatever that contract returns reaches the resolver byte for byte
 * from *every* honest server — the quorum agrees on it and the light
 * client or Colibri proves it. So the decoders must turn any shape of
 * return data into an [EnsResult], never throw: the tab's lookup and the
 * request interceptor don't catch, and one throw there takes the whole
 * app down for anyone who opens a link to the name.
 */
class EnsHostileDecodeTest {

    private val head = 1_000L

    /** Servers that are all on one chain and all return [reply] for the record read. */
    private class Agreeing(private val reply: String) : EnsHttp {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            val json = JSONObject(body!!)
            val result = when (json.getString("method")) {
                "eth_blockNumber" -> "\"0x3e8\""
                "eth_getBlockByNumber" -> {
                    val n = json.getJSONArray("params").getString(0).removePrefix("0x").toLong(16)
                    """{"hash":"0x${n.toString(16).padStart(64, 'a')}"}"""
                }
                "eth_call" -> "\"$reply\""
                else -> error("unexpected ${json.getString("method")}")
            }
            return EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
        }
    }

    private fun resolveAll(reply: String, servers: Int = 3): EnsResult = runBlocking {
        EnsResolver((1..servers).map { "https://rpc$it.test/" }, Agreeing(reply)).resolveContenthash("hostile.eth")
    }

    private fun resolveAddress(reply: String, chainId: Long): EnsAddressResult = runBlocking {
        EnsResolver((1..3).map { "https://rpc$it.test/" }, Agreeing(reply)).resolveAddress("hostile.eth", chainId)
    }

    /** The Universal Resolver's `(bytes result, address resolver)` around [inner]. */
    private fun urTuple(inner: ByteArray): String =
        "0x" + (word(0x40) + ByteArray(32) + word(inner.size.toLong()) + padded(inner)).toHex()

    @Test
    fun `a resolver return whose bytes offset overflows an Int is an error, not a crash`() {
        // `contenthash()` returning ABI `bytes` whose head points just
        // under 2^31: offset + 32 wraps negative past the bounds check.
        val result = resolveAll(urTuple(word(0x7fffffe0) + word(0)))

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("UNSUPPORTED_CONTENTHASH_FORMAT", result.reason)
    }

    @Test
    fun `a resolver return whose bytes length overflows an Int is an error, not a crash`() {
        val result = resolveAll(urTuple(word(0x20) + word(0x7fffffff)))

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("UNSUPPORTED_CONTENTHASH_FORMAT", result.reason)
    }

    @Test
    fun `an outer tuple whose offset overflows is an error, not a crash`() {
        val result = resolveAll("0x" + (word(0x7fffffe0) + ByteArray(32)).toHex())

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("RESOLUTION_ERROR", result.reason)
    }

    @Test
    fun `one server's non-hex result is an error, not a crash`() {
        // One configured server: its word is all there is, garbage included.
        val result = resolveAll("0x" + "zz".repeat(96), servers = 1)

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("RESOLUTION_ERROR", result.reason)
    }

    @Test
    fun `an address record whose bytes offset overflows is an error, not a crash`() {
        // `addr(bytes32,uint256)` (not chain 1) returns ABI `bytes`.
        val result = resolveAddress(urTuple(word(0x7fffffe0) + word(0)), chainId = 100)

        require(result is EnsAddressResult.Error) { "got $result" }
    }

    @Test
    fun `an OffchainLookup whose offsets overflow decodes to null`() {
        val sender = ByteArray(12) + "eeeeeeee14d718c2b47d9923deab1335e144eeee".hexToBytes()
        val callback = ByteArray(32).also { byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(it) }
        val head = sender + word(0xa0) + word(0xc0) + callback + word(0xe0)
        // One URL whose relative offset is just under 2^31: base + rel wraps.
        val tail = word(1) + word(0x7fffffe0) + word(0) + word(0)
        assertNull(EnsResolver.decodeOffchainLookup("0x556f1830" + (head + tail).toHex()))
        // The URL array's own offset just under 2^31.
        val head2 = sender + word(0x7fffffe0) + word(0xc0) + callback + word(0xe0)
        assertNull(EnsResolver.decodeOffchainLookup("0x556f1830" + (head2 + tail).toHex()))
    }

    private fun word(v: Long): ByteArray = ByteArray(32).also { for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte() }

    private fun padded(b: ByteArray): ByteArray = if (b.size % 32 == 0) b else b.copyOf(b.size + 32 - b.size % 32)
}
