package baby.freedom.mobile.ens

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A private tab's lookups (#464) keep to a cache of their own: a name
 * one asked about is not answered from the cache — instantly, a timing
 * tell — for a normal tab, and nothing of it outlives the session.
 */
class EnsPrivateCacheTest {

    private val ref = "11".repeat(32)
    private val calls = AtomicInteger()

    /** Three honest servers on one chain, all answering [ref]'s Swarm contenthash. */
    private val http = object : EnsHttp {
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
                "eth_call" -> {
                    calls.incrementAndGet()
                    "\"${urContenthash("e40101fa011b20$ref")}\""
                }
                else -> error("unexpected ${json.getString("method")}")
            }
            return EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
        }
    }

    private fun resolver() = EnsResolver(
        settings = { EnsResolver.Settings((1..3).map { "https://rpc$it.test/" }, colibri = false) },
        http = http,
    )

    @Test
    fun `a private lookup isn't answered from the cache for a normal tab`() = runBlocking {
        val resolver = resolver()
        val first = resolver.resolveContenthash("secret.eth", private = true)
        require(first is EnsResult.Ok && first.trust.verified) { "got $first" }
        val asked = calls.get()

        resolver.resolveContenthash("secret.eth", private = true)
        assertEquals("the private session reuses its own answer", asked, calls.get())

        resolver.resolveContenthash("secret.eth")
        assertTrue("a normal tab asks the servers itself", calls.get() > asked)
    }

    @Test
    fun `a normal answer isn't changed by a private lookup`() = runBlocking {
        val resolver = resolver()
        resolver.resolveContenthash("public.eth")
        val asked = calls.get()
        resolver.resolveContenthash("public.eth")
        assertEquals(asked, calls.get())
        resolver.resolveContenthash("other.eth", private = true)
        val afterPrivate = calls.get()
        resolver.resolveContenthash("public.eth")
        assertEquals("the normal cache still answers", afterPrivate, calls.get())
    }

    @Test
    fun `the private session's answers go when it ends`() = runBlocking {
        val resolver = resolver()
        resolver.resolveContenthash("secret.eth", private = true)
        val asked = calls.get()
        resolver.privateSessionEnded()
        resolver.resolveContenthash("secret.eth", private = true)
        assertTrue("the next private session asks again", calls.get() > asked)
    }

    private fun urContenthash(hex: String): String {
        val ch = hex.hexToBytes()
        val inner = word(0x20) + word(ch.size.toLong()) + padded(ch)
        return "0x" + (word(0x40) + ByteArray(32) + word(inner.size.toLong()) + padded(inner)).toHex()
    }

    private fun word(v: Long): ByteArray = ByteArray(32).also { for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte() }

    private fun padded(b: ByteArray): ByteArray = if (b.size % 32 == 0) b else b.copyOf(b.size + 32 - b.size % 32)
}
