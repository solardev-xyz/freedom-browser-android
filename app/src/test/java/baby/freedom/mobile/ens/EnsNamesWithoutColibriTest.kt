package baby.freedom.mobile.ens

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Names are never proven by Colibri (#529, desktop #1076). Colibri runs
 * a lookup's `eth_call` on contract code it takes from an RPC server
 * without checking it against the proven code hash, so a call that reads
 * no storage proves nothing: one dishonest server could make up an
 * answer that showed as a *Proven name*. With *Proof check* (Colibri) on in
 * Settings (they still prove chain reads), a name's answer is the RPC
 * servers' and labelled as theirs.
 */
class EnsNamesWithoutColibriTest {

    private val rpc = "https://rpc.test/"
    private val forgedRef = "66".repeat(32)

    /** One RPC server, answering every `eth_call` with a forged record. */
    private class Forger(private val result: String) : EnsHttp {
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            val m = JSONObject(body!!).getString("method")
            methods += m
            return EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"$result"}""")
        }
    }

    /** The resolver as the app builds it, from Settings with Proof check (Colibri) on. */
    private fun resolver(http: EnsHttp): EnsResolver {
        val config = EnsRpcConfig(customEndpoints = listOf(rpc), colibri = true)
        // Only this server, so a forged answer has nobody to disagree with.
        val settings = config.resolverSettings.copy(endpoints = listOf(rpc))
        return EnsResolver(settings = { settings }, http = http)
    }

    @Test
    fun `a forged contenthash from one server isn't labelled verified, with Colibri proofs on`() {
        val http = Forger(wrapAsOuterInner("e40101fa011b20$forgedRef"))

        val result = runBlocking { resolver(http).resolveContenthash("forged.eth") }

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz://$forgedRef", result.uri)
        // One server's word: the browser asks before loading it.
        assertFalse(result.trust.verified)
        assertFalse(result.trust.proven)
        assertEquals(EnsTrust.Source.RPC, result.trust.source)
        assertEquals("eth_call", http.methods.single())
    }

    @Test
    fun `a forged recipient address from one server isn't labelled verified, with Colibri proofs on`() {
        // The Universal Resolver's resolve() return: (bytes result, address resolver),
        // result = abi.encode(address) for addr(bytes32).
        val forged = ByteArray(32).also { b -> for (i in 12 until 32) b[i] = 0x66 }
        val http = Forger(wrapAbiBytes(forged))

        val result = runBlocking { resolver(http).resolveAddress("forged.eth", 1L) }

        require(result is EnsAddressResult.Ok) { "got $result" }
        assertEquals("0x" + "66".repeat(20), result.address.lowercase())
        assertFalse(result.trust.verified)
        assertFalse(result.trust.proven)
    }

    @Test
    fun `only the light client's answer earns the proven seal`() {
        // No other source of a name answer exists any more.
        assertEquals(setOf(EnsTrust.Source.RPC, EnsTrust.Source.MYOTIS), EnsTrust.Source.values().toSet())
        assertFalse(EnsTrust(verified = true).proven)
        assertFalse(EnsTrust(verified = false, source = EnsTrust.Source.MYOTIS).proven)
        assertTrue(EnsTrust(verified = true, source = EnsTrust.Source.MYOTIS).proven)
    }
}

private fun wrapAsOuterInner(contentHashHex: String): String {
    val contentHash = contentHashHex.hexToBytes()
    val innerEncoded = uint256(0x20L) + uint256(contentHash.size.toLong()) + paddedTo32(contentHash)
    return wrapAbiBytes(innerEncoded)
}

/** `abi.encode(bytes inner, address resolver)`, the Universal Resolver's `resolve()` return. */
private fun wrapAbiBytes(inner: ByteArray): String {
    val outerBody = uint256(inner.size.toLong()) + paddedTo32(inner)
    return "0x" + (uint256(0x40L) + ByteArray(32) + outerBody).toHex()
}

private fun uint256(v: Long): ByteArray = ByteArray(32).also {
    for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte()
}

private fun paddedTo32(bytes: ByteArray): ByteArray {
    val rem = bytes.size % 32
    return if (rem == 0) bytes.copyOf() else bytes.copyOf(bytes.size + (32 - rem))
}
