package baby.freedom.mobile.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `swarm_openAct`'s request half (#540, desktop #648): the one publisher
 * normaliser (`act-publisher.js`), the request check (`act-request.js`)
 * and the node path and headers (`act-service.js`).
 */
class SwarmActTest {
    // The generator point: private key 1's public key, whose address is well known.
    private val gx = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val gy = "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"
    private val g = "02$gx"
    private val gAddress = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
    private val ref = "ab".repeat(32)
    private val history = "cd".repeat(32)

    private fun params(vararg extra: Pair<String, Any?>) = JSONObject()
        .put("reference", ref).put("publisher", g).put("historyAddress", history)
        .apply { for ((k, v) in extra) put(k, v ?: JSONObject.NULL) }

    private fun refusal(p: JSONObject) = SwarmAct.validate(p).also { assertNull(it.first) }.second!!
    private fun share(p: JSONObject) = SwarmAct.validate(p).also { assertNull(it.second) }.first!!

    @Test
    fun `every spelling of a key is one compressed key and its address`() {
        val expected = SwarmAct.Publisher(g, gAddress)
        for (spelling in listOf(g, g.uppercase(), "0x$g", "04$gx$gy", "0x04${gx.uppercase()}$gy")) {
            assertEquals(spelling, expected, SwarmAct.publisher(spelling))
        }
        // -G: the other root, odd y.
        assertEquals("03$gx", SwarmAct.publisher("03$gx")!!.publicKey)
        assertEquals("03$gx", SwarmAct.publisher("04$gx" + "b7c52588d95c3b9aa25b0403f1eef75702e84bb7597aabe663b82f6f04ef2777")!!.publicKey)
    }

    @Test
    fun `anything but an on-curve compressed or uncompressed key is refused`() {
        for (bad in listOf<Any?>(
            null, 7, "", gx, "0X$g", "06$gx$gy", "07$gx$gy",
            "02" + "00".repeat(32), // x = 0: 7 has no square root mod p
            "04$gx" + "00".repeat(32), // off the curve
            "02" + "ff".repeat(32), // x >= p
            "02${gx}00", "$g ", " $g", "02$gx\n",
        )) {
            assertNull("$bad", SwarmAct.publisher(bad))
        }
    }

    @Test
    fun `a valid request comes back canonical`() {
        val s = share(
            JSONObject().put("reference", ref.uppercase() + "EF".repeat(32)).put("publisher", "0x04$gx$gy")
                .put("historyAddress", history.uppercase()).put("timestamp", 1_700_000_000).put("path", "a/b.txt"),
        )
        assertEquals(ref + "ef".repeat(32), s.reference)
        assertEquals(SwarmAct.Publisher(g, gAddress), s.publisher)
        assertEquals(history, s.historyAddress)
        assertEquals(1_700_000_000L, s.timestamp)
        assertEquals("a/b.txt", s.path)
        val plain = share(params("timestamp" to null, "path" to null, "options" to null))
        assertNull(plain.timestamp)
        assertNull(plain.path)
        assertEquals("", share(params("path" to "")).path)
        assertEquals(0L, share(params("timestamp" to 0)).timestamp)
        assertEquals(9_007_199_254_740_991L, share(params("timestamp" to 9_007_199_254_740_991L)).timestamp)
        assertEquals(5L, share(params("timestamp" to 5.0)).timestamp)
    }

    @Test
    fun `each bad field has desktop's reason`() {
        val cases = listOf(
            params("options" to JSONObject().put("pin", true)) to "unsupported_option",
            params("options" to "x") to "invalid_params",
            params("options" to org.json.JSONArray()) to "invalid_params",
            params("reference" to "ab".repeat(31)) to "invalid_reference",
            params("reference" to "ab".repeat(48)) to "invalid_reference",
            params("reference" to "0x$ref") to "invalid_reference",
            params("reference" to 5) to "invalid_reference",
            params("publisher" to "06$gx$gy") to "invalid_publisher",
            params("historyAddress" to "ab".repeat(64)) to "invalid_history_address",
            params("historyAddress" to null) to "invalid_history_address",
            params("timestamp" to -1) to "invalid_timestamp",
            params("timestamp" to 1.5) to "invalid_timestamp",
            params("timestamp" to "1700000000") to "invalid_timestamp",
            params("timestamp" to 9_007_199_254_740_992L) to "invalid_timestamp",
            params("timestamp" to JSONObject().put("\$bigint", "1")) to "invalid_timestamp",
            params("path" to 5) to "invalid_path",
            params("path" to "/a") to "invalid_path",
            params("path" to "a\\b") to "invalid_path",
            params("path" to "a//b") to "invalid_path",
            params("path" to "a/") to "invalid_path",
            params("path" to "./a") to "invalid_path",
            params("path" to "a/..") to "invalid_path",
            params("path" to "a\nb") to "invalid_path",
            params("path" to "a\u007fb") to "invalid_path",
            params("path" to "a\uD800b") to "invalid_path",
            params("path" to "a\uDC00") to "invalid_path",
            params("path" to "a".repeat(1025)) to "invalid_path",
            params("path" to "é".repeat(513)) to "invalid_path",
        )
        for ((p, reason) in cases) assertEquals(p.toString(), reason, refusal(p).reason)
        assertEquals("pin", refusal(params("options" to JSONObject().put("pin", true))).option)
        // Checked in desktop's order: options first, then reference, publisher, history.
        assertEquals("invalid_reference", refusal(JSONObject().put("publisher", "x")).reason)
        assertEquals("invalid_publisher", refusal(JSONObject().put("reference", ref)).reason)
        // At the cap, and an astral character (a well-formed pair), are fine.
        assertEquals("a".repeat(1024), share(params("path" to "a".repeat(1024))).path)
        assertEquals("😀.txt", share(params("path" to "😀.txt")).path)
    }

    @Test
    fun `paths are percent-encoded per segment as encodeURIComponent does`() {
        assertEquals("a%20b", SwarmAct.encodeUriComponent("a b"))
        assertEquals("%2B%23%3F%26%3D%25%3A", SwarmAct.encodeUriComponent("+#?&=%:"))
        assertEquals("-_.!~*'()AZaz09", SwarmAct.encodeUriComponent("-_.!~*'()AZaz09"))
        assertEquals("%C3%A9%F0%9F%98%80", SwarmAct.encodeUriComponent("é😀"))
        assertEquals("/bytes/$ref", SwarmAct.nodePath(share(params())))
        assertEquals("/bzz/$ref/", SwarmAct.nodePath(share(params("path" to ""))))
        assertEquals("/bzz/$ref/docs/a%20b%23c%3F.txt", SwarmAct.nodePath(share(params("path" to "docs/a b#c?.txt"))))
    }

    @Test
    fun `the node gets the canonical key and history, and a timestamp only when given`() {
        assertEquals(
            mapOf(
                "Accept" to "*/*", "Accept-Encoding" to "identity",
                "Swarm-Act-Publisher" to g, "Swarm-Act-History-Address" to history,
            ),
            SwarmAct.headers(share(params("publisher" to "0x04${gx.uppercase()}$gy", "historyAddress" to history.uppercase()))),
        )
        assertEquals("42", SwarmAct.headers(share(params("timestamp" to 42)))["Swarm-Act-Timestamp"])
    }

    @Test
    fun `the node's own not-found wording is what tells no grant from no content`() {
        assertEquals(true, SwarmAct.noGrant("act or history entry not found"))
        assertEquals(true, SwarmAct.noGrant("ACT or history entry not found "))
        assertEquals(false, SwarmAct.noGrant("not found"))
    }

    @Test
    fun `the sheet shows a manifest root as a slash and escapes the rest`() {
        assertEquals("/", swarmActPath(""))
        assertEquals("a<U+202E>txt.exe", swarmActPath("a‮txt.exe"))
    }
}
