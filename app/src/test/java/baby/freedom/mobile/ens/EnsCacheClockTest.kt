package baby.freedom.mobile.ens

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnsResolver]'s answer cache against a device clock that steps back
 * (a wrong clock at boot, then NTP): an answer is reused for its TTL
 * from when it was stored, never for the clock's error on top.
 */
class EnsCacheClockTest {

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

    @Test
    fun `an answer cached while the clock ran ahead is asked again once it's set right`() = runBlocking {
        val real = 1_800_000_000_000L
        var now = real + 6 * 3_600_000L
        val resolver = EnsResolver(
            settings = { EnsResolver.Settings((1..3).map { "https://rpc$it.test/" }, colibri = false) },
            http = http,
            clock = { now },
        )

        val first = resolver.resolveContenthash("clock.eth")
        require(first is EnsResult.Ok && first.trust.verified) { "got $first" }
        val asked = calls.get()
        resolver.resolveContenthash("clock.eth")
        assertEquals("cached within its TTL", asked, calls.get())

        now = real
        resolver.resolveContenthash("clock.eth")
        assertTrue("asked again, got ${calls.get()} reads", calls.get() > asked)
    }

    private fun urContenthash(hex: String): String {
        val ch = hex.hexToBytes()
        val inner = word(0x20) + word(ch.size.toLong()) + padded(ch)
        return "0x" + (word(0x40) + ByteArray(32) + word(inner.size.toLong()) + padded(inner)).toHex()
    }

    private fun word(v: Long): ByteArray = ByteArray(32).also { for (i in 0..7) it[31 - i] = (v ushr (8 * i)).toByte() }

    private fun padded(b: ByteArray): ByteArray = if (b.size % 32 == 0) b else b.copyOf(b.size + 32 - b.size % 32)
}
