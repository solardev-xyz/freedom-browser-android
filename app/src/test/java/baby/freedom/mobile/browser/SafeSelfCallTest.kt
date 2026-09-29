package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.Erc20
import baby.freedom.mobile.wallet.SafeProtocol
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A co-sign request whose call is from the Safe to itself: decoded and flagged, not raw hex (#235, audit #229). */
class SafeSelfCallTest {
    private val safe = "0x6d21181D5e0F3a4a438F0CC65FACFd418443b096"
    private val attacker = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val owner = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val sentinel = "0x0000000000000000000000000000000000000001"

    private fun word(address: String) = "0".repeat(24) + address.removePrefix("0x").lowercase()
    private fun word(n: Long) = n.toString(16).padStart(64, '0')
    private fun call(name: String, vararg words: String) = (SAFE_SELECTORS.getValue(name) + words.joinToString("")).hexBytes()
    private fun String.hexBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A shared co-sign request, parsed exactly as the co-sign page does. */
    private fun request(to: String, data: ByteArray): SafeProtocol.Request =
        SafeProtocol.parseRequest(SafeProtocol.safeTxTypedData(safe, 100, SafeProtocol.SafeTx(to, BigInteger.ZERO, data, BigInteger.ZERO)).toString())

    @Test
    fun `the audit's takeover request is read as adding an owner, not shown as hex`() {
        // #229: a co-owner shares `to` = the Safe, `addOwnerWithThreshold(attacker, 1)`.
        val r = request(safe, call("addOwnerWithThreshold", word(attacker), word(1)))
        val decoded = safeSelfCall(r)
        assertEquals(SafeSelfCall.AddOwner(attacker, BigInteger.ONE), decoded)
        assertFalse(decoded!!.harmless)
        assertTrue(safeSelfCallRisk(decoded).contains("new owner"))
        // Any spelling of the Safe's address is still the Safe.
        val lower = SafeProtocol.parseRequest(
            SafeProtocol.safeTxTypedData(safe, 100, SafeProtocol.SafeTx(safe.lowercase(), BigInteger.ZERO, call("enableModule", word(attacker)), BigInteger.ZERO))
                .toString(),
        )
        assertEquals(SafeSelfCall.EnableModule(attacker), safeSelfCall(lower))
    }

    @Test
    fun `the selectors are the Safe's own`() {
        assertEquals(
            mapOf(
                "addOwnerWithThreshold" to "0d582f13",
                "removeOwner" to "f8dc5dd9",
                "swapOwner" to "e318b52b",
                "changeThreshold" to "694e80c3",
                "enableModule" to "610b5925",
                "disableModule" to "e009cfde",
                "setGuard" to "e19a9dd9",
                "setFallbackHandler" to "f08a0323",
            ),
            SAFE_SELECTORS,
        )
    }

    @Test
    fun `every admin call is decoded`() {
        assertEquals(SafeSelfCall.RemoveOwner(owner, BigInteger.TWO), safeSelfCall(call("removeOwner", word(sentinel), word(owner), word(2))))
        assertEquals(SafeSelfCall.SwapOwner(owner, attacker), safeSelfCall(call("swapOwner", word(sentinel), word(owner), word(attacker))))
        assertEquals(SafeSelfCall.ChangeThreshold(BigInteger.ONE), safeSelfCall(call("changeThreshold", word(1))))
        assertEquals(SafeSelfCall.EnableModule(attacker), safeSelfCall(call("enableModule", word(attacker))))
        assertEquals(SafeSelfCall.DisableModule(attacker), safeSelfCall(call("disableModule", word(sentinel), word(attacker))))
        assertEquals(SafeSelfCall.SetGuard(attacker), safeSelfCall(call("setGuard", word(attacker))))
        assertEquals(SafeSelfCall.SetFallbackHandler(attacker), safeSelfCall(call("setFallbackHandler", word(attacker))))
        for (c in SAFE_SELECTORS.keys) {
            val decoded = safeSelfCall(request(safe, call(c, *Array(if (c in setOf("removeOwner", "swapOwner")) 3 else if (c in setOf("addOwnerWithThreshold", "disableModule")) 2 else 1) { word(attacker) })))
            assertFalse(c, decoded!!.harmless)
            assertTrue(c, decoded != SafeSelfCall.Unknown)
        }
    }

    @Test
    fun `anything not exactly an admin call is unknown, and still flagged`() {
        val dirty = "ff" + word(attacker).substring(2)
        val cases = listOf(
            call("enableModule", dirty),
            call("enableModule", word(attacker), word(1)),
            call("enableModule"),
            call("addOwnerWithThreshold", word(attacker)),
            call("setGuard", word(attacker)) + byteArrayOf(0),
            byteArrayOf(0x12, 0x34),
            ("6a761202" + word(1)).hexBytes(), // execTransaction and anything else unread
            Erc20.transferData(attacker, BigInteger.TEN),
        )
        for (data in cases) {
            val decoded = safeSelfCall(request(safe, data))
            assertEquals(data.toHex(), SafeSelfCall.Unknown, decoded)
            assertFalse(decoded!!.harmless)
        }
    }

    @Test
    fun `a call with no data to the Safe is a cancellation, and a call elsewhere is no self-call`() {
        val cancel = safeSelfCall(request(safe, ByteArray(0)))
        assertEquals(SafeSelfCall.Cancel, cancel)
        assertTrue(cancel!!.harmless)
        assertNull(safeSelfCall(request(attacker, call("addOwnerWithThreshold", word(attacker), word(1)))))
        assertNull(safeSelfCall(SafeProtocol.parseRequest(SafeProtocol.shareText(SafeProtocol.messageTypedData(safe, 100, "hi"), "hi"))))
    }
}
