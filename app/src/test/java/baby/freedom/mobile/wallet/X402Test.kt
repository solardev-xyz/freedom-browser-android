package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** x402 terms, authorizations and payment headers (#140), against `@x402/core` shapes and an ethers v6 signature. */
class X402Test {
    private val cow = Keccak256.digest("cow".toByteArray())
    private val cowAddress = "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"
    private val baseUsdc = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val payTo = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"

    private fun accept(
        network: String = "eip155:8453",
        amount: String = "10000",
        asset: String = baseUsdc,
        to: String = payTo,
        extra: JSONObject? = JSONObject().put("name", "USD Coin").put("version", "2"),
        scheme: String = "exact",
        timeout: Int = 60,
    ) = JSONObject()
        .put("scheme", scheme).put("network", network).put("amount", amount).put("asset", asset)
        .put("payTo", to).put("maxTimeoutSeconds", timeout)
        .apply { extra?.let { put("extra", it) } }

    private fun b64(o: Any) = Base64.getEncoder().encodeToString(o.toString().toByteArray())

    private fun v2(vararg accepts: JSONObject) = JSONObject()
        .put("x402Version", 2)
        .put("error", "Payment required")
        .put("resource", JSONObject().put("url", "https://api.example/paid").put("description", "A weather report").put("mimeType", "text/html"))
        .put("accepts", JSONArray(accepts.toList()))
        .put("extensions", JSONObject().put("bazaar", JSONObject().put("x", 1)))

    @Test
    fun `a v2 PAYMENT-REQUIRED header reads as its payable offers`() {
        val r = X402.parseRequired(b64(v2(accept())))!!
        assertEquals(2, r.version)
        assertEquals("A weather report", r.description)
        val o = r.offers.single()
        assertEquals(8453L, o.chainId)
        assertEquals(BigInteger.valueOf(10000), o.amount)
        assertEquals(baseUsdc, o.asset)
        assertEquals(payTo, o.payTo)
        assertEquals("USD Coin", o.domainName)
        assertEquals("2", o.domainVersion)
        assertEquals("8453:${baseUsdc.lowercase()}", o.assetKey)
        assertTrue(r.unusable.isEmpty())
    }

    @Test
    fun `an offer whose time limit can't outlast the runway is listed as unpayable, not dropped after Pay`() {
        val r = X402.parseRequired(b64(v2(accept(timeout = 10), accept(timeout = 29), accept(timeout = 30))))!!
        assertEquals(listOf(2), r.offers.map { it.index })
        assertEquals(
            listOf(
                "Offer 1: its time limit (10 s) is too short to pay in; at least 30 s is needed",
                "Offer 2: its time limit (29 s) is too short to pay in; at least 30 s is needed",
            ),
            r.unusable,
        )
        // The shortest accepted limit still leaves the runway after signing.
        val auth = X402.authorize(2, r.offers.single(), payTo, 1_000L, ByteArray(32))
        assertTrue(X402.runway(auth, 1_000L + 9) >= X402.MIN_RUNWAY_SECONDS)
    }

    @Test
    fun `offers the wallet can't pay are listed with why, never signed`() {
        val r = X402.parseRequired(
            b64(
                v2(
                    accept(network = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"),
                    accept(extra = JSONObject().put("name", "USD Coin").put("version", "2").put("assetTransferMethod", "permit2")),
                    accept(extra = null),
                    accept(to = "0x0000000000000000000000000000000000000000"),
                    accept(to = baseUsdc),
                    accept(amount = "0"),
                    accept(amount = "1e6"),
                    accept(asset = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913".replace("833589fcd", "833589FCd")),
                    accept(scheme = "upto"),
                    accept(timeout = 0),
                    accept(network = "eip155:0x2105"),
                    accept(),
                ),
            ),
        )!!
        assertEquals(1, r.offers.size)
        assertEquals(11, r.offers.single().index)
        assertEquals(
            listOf(
                "Offer 1: network “solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp” isn't supported",
                "Offer 2: only EIP-3009 transfers are supported, not “permit2”",
                "Offer 3: the token's signing domain isn't named",
                "Offer 4: it pays the zero address",
                "Offer 5: it pays the token contract itself",
                "Offer 6: no valid amount",
                "Offer 7: no valid amount",
                "Offer 8: no valid token address",
                "Offer 9: scheme “upto” isn't supported",
                "Offer 10: no valid time limit",
                "Offer 11: network “eip155:0x2105” isn't supported",
            ),
            r.unusable,
        )
    }

    @Test
    fun `a v1 header reads network names and maxAmountRequired`() {
        val v1 = JSONObject().put("x402Version", 1).put(
            "accepts",
            JSONArray().put(
                JSONObject()
                    .put("scheme", "exact").put("network", "base").put("maxAmountRequired", "5000")
                    .put("resource", "https://api.example/paid").put("description", "Old style")
                    .put("payTo", payTo).put("asset", baseUsdc).put("maxTimeoutSeconds", 60)
                    .put("extra", JSONObject().put("name", "USD Coin").put("version", "2")),
            ).put(accept(network = "base-sepolia").put("maxAmountRequired", "1")),
        )
        val r = X402.requiredFrom(mapOf("x-payment-required" to b64(v1)))!!
        assertEquals(1, r.version)
        assertEquals("Old style", r.description)
        assertEquals(8453L, r.offers.single().chainId)
        assertEquals(BigInteger.valueOf(5000), r.offers.single().amount)
        assertEquals(listOf("Offer 2: network “base-sepolia” isn't supported"), r.unusable)
    }

    @Test
    fun `headers are matched in any case, v2's wins, and anything malformed is nothing`() {
        assertEquals(2, X402.requiredFrom(mapOf("Payment-Required" to b64(v2(accept())), "X-PAYMENT-REQUIRED" to "junk"))!!.version)
        assertNull(X402.requiredFrom(mapOf("Content-Type" to "text/html")))
        assertNull(X402.requiredFrom(null))
        assertNull(X402.parseRequired("not base64 at all!"))
        assertNull(X402.parseRequired(b64("[1,2,3]")))
        assertNull(X402.parseRequired(b64(JSONObject().put("x402Version", 3).put("accepts", JSONArray().put(accept())))))
        assertNull(X402.parseRequired(b64(JSONObject().put("x402Version", 2).put("accepts", JSONArray()))))
        assertNull(X402.parseRequired("A".repeat(X402.MAX_HEADER_CHARS + 1)))
        // A v1 body under the v2 header (or the other way round) isn't read.
        val v1 = JSONObject().put("x402Version", 1).put("accepts", JSONArray().put(accept(network = "base")))
        assertNull(X402.requiredFrom(mapOf("PAYMENT-REQUIRED" to b64(v1))))
        // Deep nesting can't blow the stack.
        assertNull(X402.parseRequired(Base64.getEncoder().encodeToString(("[".repeat(100_000)).toByteArray())))
        // URL-safe base64 is read too.
        assertNotNull(X402.parseRequired(Base64.getUrlEncoder().encodeToString(v2(accept()).toString().toByteArray())))
    }

    @Test
    fun `the authorization signs as ethers signs the same EIP-3009 transfer`() {
        val offer = X402.parseRequired(b64(v2(accept())))!!.offers.single()
        val auth = X402.authorize(2, offer, cowAddress.lowercase(), nowSeconds = 1_790_000_000, nonce = ByteArray(32) { 0x11 })
        assertEquals(cowAddress, auth.from)
        assertEquals(payTo, auth.to)
        assertEquals("10000", auth.value)
        assertEquals("0", auth.validAfter)
        assertEquals("1790000060", auth.validBefore)
        assertEquals("0x" + "11".repeat(32), auth.nonce)
        val digest = X402.digest(offer, auth)
        // ethers v6: TypedDataEncoder.hash / Wallet.signTypedData over the same domain, types and message.
        assertEquals("d2dbf811101aa899463dd24683f5649ea84f83c9ad5629a0fa2d3304560a3e18", digest.toHex())
        val sig = MessageSigning.sign(cow, cowAddress, digest)
        assertEquals(
            "0xce331eecb15c39aa4a80faf7e7e6a1b68248d74bf4a952f4719896e8ba5f38d7" +
                "2064d70a65b010baa2917a9722c03cf40a9d53223061f71e0ad4bda4991b5b851b",
            sig,
        )
        assertEquals(cowAddress.lowercase(), Secp256k1.recover(digest, sig)?.lowercase())
    }

    @Test
    fun `an authorization lasts at most ten minutes, and v1 backdates validAfter`() {
        val offer = X402.parseRequired(b64(v2(accept(timeout = 86_400))))!!.offers.single()
        val auth = X402.authorize(2, offer, cowAddress, nowSeconds = 1_000_000, nonce = ByteArray(32))
        assertEquals((1_000_000 + X402.MAX_VALIDITY_SECONDS).toString(), auth.validBefore)
        assertEquals(X402.MAX_VALIDITY_SECONDS - 5, X402.runway(auth, 1_000_005))
        assertEquals("999400", X402.authorize(1, offer, cowAddress, 1_000_000, ByteArray(32)).validAfter)
        assertThrows(IllegalArgumentException::class.java) { X402.authorize(2, offer, cowAddress, 0, ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { X402.authorize(2, offer, "0x1234", 0, ByteArray(32)) }
    }

    @Test
    fun `the v2 payment echoes the offer, resource and extensions untouched`() {
        val raw = accept().put("extra", JSONObject().put("name", "USD Coin").put("version", "2").put("feePayer", "x"))
        val required = X402.parseRequired(b64(v2(raw)))!!
        val offer = required.offers.single()
        val auth = X402.authorize(2, offer, cowAddress, 1_790_000_000, ByteArray(32) { 0x11 })
        val (name, value) = X402.paymentHeader(required, offer, auth, "0xsig")
        assertEquals("PAYMENT-SIGNATURE", name)
        val body = JSONObject(String(Base64.getDecoder().decode(value)))
        assertEquals(2, body.getInt("x402Version"))
        assertEquals(raw.toString(), body.getJSONObject("accepted").toString())
        assertEquals("https://api.example/paid", body.getJSONObject("resource").getString("url"))
        assertEquals(1, body.getJSONObject("extensions").getJSONObject("bazaar").getInt("x"))
        val payload = body.getJSONObject("payload")
        assertEquals("0xsig", payload.getString("signature"))
        val a = payload.getJSONObject("authorization")
        assertEquals(listOf(cowAddress, payTo, "10000", "0", "1790000060", "0x" + "11".repeat(32)),
            listOf("from", "to", "value", "validAfter", "validBefore", "nonce").map(a::getString))
    }

    @Test
    fun `the v1 payment names its scheme and network`() {
        val v1 = JSONObject().put("x402Version", 1).put(
            "accepts",
            JSONArray().put(accept(network = "ethereum", asset = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48").put("maxAmountRequired", "7")),
        )
        val required = X402.parseRequired(b64(v1))!!
        val offer = required.offers.single()
        val (name, value) = X402.paymentHeader(required, offer, X402.authorize(1, offer, cowAddress, 1000, ByteArray(32)), "0xsig")
        assertEquals("X-PAYMENT", name)
        val body = JSONObject(String(Base64.getDecoder().decode(value)))
        assertEquals(1, body.getInt("x402Version"))
        assertEquals("exact", body.getString("scheme"))
        assertEquals("ethereum", body.getString("network"))
        assertEquals("7", body.getJSONObject("payload").getJSONObject("authorization").getString("value"))
        assertTrue(!body.has("accepted"))
    }
}
