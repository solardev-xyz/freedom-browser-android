package baby.freedom.mobile.ens

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost

/**
 * ENSIP-15 normalization where the browser uses it: the resolver hashes
 * the normalized name, refuses names ENSIP-15 rejects, and [EnsInput]
 * canonicalizes what was typed. Expected namehashes and DNS encodings
 * are from ethers v6 (`namehash`, `dnsEncode(name, 255)`) over
 * `@adraffy/ens-normalize` 1.11.1's output — what desktop computes.
 */
class EnsNormalizeTest {

    private val uts46Was = WhatwgHost.uts46

    // `.tez` names get UTS-46 (ICU): icu4j here, android.icu on device.
    @Before fun useIcu4j() { WhatwgHost.uts46 = Icu4jUts46 }

    @After fun restoreUts46() { WhatwgHost.uts46 = uts46Was }

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
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("a\u0661b.eth") }

        require(r is EnsResult.Error) { "got $r" }
        assertEquals("INVALID_NAME", r.reason)
        assertEquals("a\u0661b.eth", r.name)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `invalid-name detail carries no bidi marks`() {
        val http = RecordingRpc()
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("a．b.eth") }

        require(r is EnsResult.Error) { "got $r" }
        assertEquals("INVALID_NAME", r.reason)
        assertTrue(r.error, r.error.contains("disallowed character"))
        assertTrue(r.error, r.error.none { it == '\u200E' || it == '\u200F' || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' })
        // The library does emit them — the cleanup is what removes them.
        val raw = runCatching { io.github.adraffy.ens.ENSNormalize.ENSIP15.normalize("a．b.eth") }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(raw, raw.contains('\u200E'))
    }

    @Test
    fun `plain ASCII names skip ENSIP-15 like desktop fastNormalize`() {
        // Pre-ENSIP-15 registrations: punycode and `--` at 3-4 are refused
        // by the spec but resolve on desktop (and did on main), so they
        // must still be looked up.
        for (name in listOf("xn--2i8h.eth", "ab--c.eth", "AB--C.ETH")) {
            val http = RecordingRpc()
            val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash(name) }
            assertTrue("$name → $r", !(r is EnsResult.Error && r.reason == "INVALID_NAME"))
            assertEquals(name, 1, http.calls.size)
        }
        assertEquals("ab--c.eth", EnsNormalize.fastNormalize("AB--c.eth"))
        assertEquals("xn--2i8h.eth", EnsNormalize.fastNormalize("xn--2i8h.eth"))
        // Anything outside [a-z0-9.-] still gets the full pass.
        assertEquals("vitalik.eth", EnsNormalize.fastNormalize("ＶＩＴＡＬＩＫ.eth"))
    }

    @Test
    fun `resolver refuses a label too long to DNS-encode`() {
        val http = RecordingRpc()
        val name = "a".repeat(256) + ".eth"
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash(name) }

        require(r is EnsResult.Error) { "got $r" }
        // ENSIP-15 accepts it — it's only too long to DNS-encode, so it
        // must not get the "breaks the naming rules" page.
        assertEquals("a".repeat(256) + ".eth", EnsNormalize.normalize(name))
        assertEquals("NAME_TOO_LONG", r.reason)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `an empty label from the ASCII fast path is still INVALID_NAME`() {
        val http = RecordingRpc()
        val r = runBlocking { EnsResolver(listOf(rpc), http).resolveContenthash("a..eth") }
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

    @Test
    fun `tez names are not ENS - ENSIP-15 neither rewrites nor refuses them`() {
        // Refused by ENSIP-15 (`--` at 3-4, a mid-label `_`, mixed
        // digits); ASCII ones are only lowercased, and the Unicode one
        // UTS-46 accepts as-is (bar its bidi check) comes back as typed.
        for (name in listOf("Ab--C.tez", "a_b.tez", "a\u0661b.tez")) {
            assertEquals(name, name.lowercase(), EnsNormalize.normalizeOrNull(name))
            assertEquals(name, name.lowercase(), EnsNormalize.fastNormalize(name))
            assertTrue(name, EnsNormalize.isFastPath(name))
            assertEquals(name, EnsInput.Parsed(name.lowercase(), "/x"), EnsInput.parse("$name/x"))
        }
        // An Ethereum name still gets the full pass.
        assertNull(EnsNormalize.normalizeOrNull("a_b.eth"))
    }

    @Test
    fun `tez names get Tezos Domains' own UTS-46 normalization`() {
        // developers.tezos.domains: UTS-46 ToUnicode, nontransitional.
        // NFC, fullwidth → ASCII, U+FE0F dropped (the form the registry
        // holds, and the one Chromium leaves in the virtual host).
        val cases = mapOf(
            "cafe\u0301.tez" to "caf\u00e9.tez",
            "ａlice.tez" to "alice.tez",
            "\u2764\uFE0F.tez" to "\u2764.tez",
            "\u2764.tez" to "\u2764.tez",
            "Ⓜ.tez" to "m.tez",
            // A fullwidth / non-ASCII suffix is still `.tez` (R1-F2):
            // mapped as Tezos Domains, never ENSIP-15-refused.
            "cafe\u0301.ｔｅｚ" to "caf\u00e9.tez",
            "a_b.ｔｅｚ" to "a_b.tez",
            "ALICE.ＴＥＺ" to "alice.tez",
            "alice。tez" to "alice.tez",
        )
        for ((typed, key) in cases) {
            assertEquals(typed, key, EnsNormalize.normalizeOrNull(typed))
            assertEquals(typed, key, EnsNormalize.fastNormalize(typed))
            assertTrue(typed, EnsNormalize.isFastPath(typed))
            assertTrue(typed, !EnsNormalize.appliesTo(typed))
            assertEquals(typed, EnsInput.Parsed(key, "/x"), EnsInput.parse("$typed/x"))
        }
        // Not `.tez` after mapping either: ENSIP-15 as before.
        assertEquals("caf\u00e9.eth", EnsNormalize.normalizeOrNull("cafe\u0301.ｅｔｈ"))
        assertTrue(EnsNormalize.appliesTo("\u2764\uFE0F.eth"))
    }

    @Test
    fun `resolver hands a tez name ENSIP-15 would refuse to Tezos Domains`() {
        val eth = RecordingRpc()
        val tezHosts = mutableListOf<String>()
        val tezHttp = object : EnsHttp {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int,
                maxBytes: Long,
                followRedirects: Boolean,
            ): EnsHttp.Reply {
                tezHosts += url
                return EnsHttp.Reply(500, "")
            }
        }
        val tezos = TezosDomainsResolver(listOf("https://tez.test"), tezHttp)
        val r = runBlocking { EnsResolver(listOf(rpc), eth, tezos).resolveContenthash("A_b.tez") }
        assertTrue("$r", !(r is EnsResult.Error && r.reason == "INVALID_NAME"))
        assertEquals("a_b.tez", r.name)
        assertTrue(eth.calls.isEmpty())
        assertTrue(tezHosts.isNotEmpty())
    }
}
