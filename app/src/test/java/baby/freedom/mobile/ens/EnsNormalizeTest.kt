package baby.freedom.mobile.ens

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENSIP-15 normalization where the browser uses it: the resolver hashes
 * the normalized name, refuses names ENSIP-15 rejects, and [EnsInput]
 * canonicalizes what was typed. Expected namehashes and DNS encodings
 * are from ethers v6 (`namehash`, `dnsEncode(name, 255)`) over
 * `@adraffy/ens-normalize` 1.11.1's output — what desktop computes.
 */
class EnsNormalizeTest {

    private val rpc = "https://rpc.test/"

    /** Records every `eth_call`'s data and answers "no resolver" (empty). */
    private class RecordingRpc : EnsHttp {
        val calls = mutableListOf<String>()
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            calls += JSONObject(body!!).getJSONArray("params").getJSONObject(0).getString("data")
            return EnsHttp.Reply(200, """{"jsonrpc":"2.0","id":1,"result":"0x"}""")
        }
    }

    @Test
    fun `normalization maps what other clients map`() {
        assertEquals("vitalik.eth", EnsNormalize.normalize("Vitalik.ETH"))
        assertEquals("vitalik.eth", EnsNormalize.normalize("ＶＩＴＡＬＩＫ.eth"))
        assertEquals("m.eth", EnsNormalize.normalize("Ⓜ️.eth"))
        assertEquals("🦊.eth", EnsNormalize.normalize("🦊.eth"))
        // Composed (NFC): e + U+0301 → é.
        assertEquals("café.eth", EnsNormalize.normalize("café.eth"))
        // FE0F dropped from the emoji sequence.
        assertEquals("🏴‍☠.eth", EnsNormalize.normalize("🏴‍☠️.eth"))
        assertEquals("", EnsNormalize.normalize(""))
    }

    @Test
    fun `names ENSIP-15 rejects are refused`() {
        for (bad in listOf("a．b.eth", "ab--c.eth", "a_b.eth", "a..eth", "te st.eth", "́a.eth")) {
            assertNull(bad, EnsNormalize.normalizeOrNull(bad))
        }
    }

    @Test
    fun `namehash and dns encoding of normalized names match ethers`() {
        val cases = mapOf(
            "🦊.eth" to Pair(
                "44639fcabf2f26d9e3160578dbda00cbd963f15cfed2add60b0f870ca8aa0da2",
                "04f09fa68a0365746800",
            ),
            "café.eth" to Pair(
                "a7369e1df22e06ec6d91162508e400d7af475860638f927e6d1085bb0134a74a",
                "05636166c3a90365746800",
            ),
            "🏴‍☠.eth" to Pair(
                "c764b3f7b16739a4d9720d916e21ff48daa4fce157aaf6a229e53bcc6bc1cea4",
                "0af09f8fb4e2808de298a00365746800",
            ),
            // 64 UTF-8 bytes: past DNS's 63, within ENS's 255.
            "💩".repeat(16) + ".eth" to Pair(
                "b48e9f5905be47c2c1283dbda38ad88f077ab2fcae932a15f0ac537e2529e701",
                "40" + "f09f92a9".repeat(16) + "0365746800",
            ),
        )
        for ((name, expected) in cases) {
            assertEquals(name, expected.first, EnsResolver.namehash(name).toHex())
            assertEquals(name, expected.second, EnsResolver.dnsEncode(name).toHex())
        }
    }

    @Test
    fun `resolver queries the normalized name`() {
        val http = RecordingRpc()
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("Ⓜ️.eth") }

        assertEquals("m.eth", r.name)
        val data = http.calls.single()
        // resolve(dnsEncode("m.eth"), contenthash(namehash("m.eth")))
        assertTrue(data, data.contains("016d0365746800"))
        assertTrue(data, data.contains("6ae37ccb7297ebcfea7e1487d2872776a2a9ee8bb02f4034314763cceab2f950"))
    }

    @Test
    fun `resolver hashes emoji names like every other client`() {
        val http = RecordingRpc()
        runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("🦊.eth") }

        val data = http.calls.single()
        assertTrue(data, data.contains("04f09fa68a0365746800"))
        assertTrue(data, data.contains("44639fcabf2f26d9e3160578dbda00cbd963f15cfed2add60b0f870ca8aa0da2"))
    }

    @Test
    fun `resolver answers INVALID_NAME without a lookup`() {
        val http = RecordingRpc()
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("ab--c.eth") }

        require(r is EnsResult.Error) { "got $r" }
        assertEquals("INVALID_NAME", r.reason)
        assertEquals("ab--c.eth", r.name)
        assertTrue(r.error, r.error.contains("invalid label extension"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `resolver refuses a label too long to DNS-encode`() {
        val http = RecordingRpc()
        val name = "a".repeat(256) + ".eth"
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash(name) }

        require(r is EnsResult.Error) { "got $r" }
        assertEquals("INVALID_NAME", r.reason)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `typed names are canonicalized`() {
        assertEquals(EnsInput.Parsed("m.eth", "/x"), EnsInput.parse("Ⓜ️.eth/x"))
        assertEquals(EnsInput.Parsed("🦊.eth", ""), EnsInput.parse("ens://🦊.eth"))
        assertEquals(EnsInput.Parsed("vitalik.eth", ""), EnsInput.parse("ＶＩＴＡＬＩＫ.eth"))
        assertEquals(
            EnsInput.Constrained("café.eth", "", "bzz"),
            EnsInput.parseConstrained("bzz://Café.eth"),
        )
        // Rejected by ENSIP-15, but still a name: the resolver says why.
        assertEquals(EnsInput.Parsed("ab--c.eth", ""), EnsInput.parse("AB--c.eth"))
    }
}
