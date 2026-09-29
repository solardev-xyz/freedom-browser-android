package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Which transactions an auto-approve rule (#112) may cover, and how one is named — as iOS's `AutoApproveOfferTests`. */
class AutoApproveTest {
    private val site = "https://app.uniswap.org"
    private val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private fun call(selector: String, words: Int = 2) =
        (selector.removePrefix("0x") + "00".repeat(32 * words)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `a function call that sends nothing along can have a rule, scoped to site, contract, function and chain`() {
        val rule = AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0xa9059cbb"), 100)
        assertEquals(AutoApproveRule(site, usdc.lowercase(), "0xa9059cbb", 100), rule)
        assertEquals("$site|${usdc.lowercase()}|0xa9059cbb|100", rule!!.key)
        assertEquals("Always approve token transfers on this contract", autoApproveSwitchLabel(rule))
    }

    @Test
    fun `a call that sends funds along, a plain send, a zero selector or a cut-off selector can't`() {
        assertNull("value", AutoApproveRule.eligible(site, usdc, BigInteger.ONE, call("0xa9059cbb"), 100))
        assertNull("no data", AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, ByteArray(0), 100))
        assertNull("zero selector", AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0x00000000"), 100))
        assertNull("three bytes", AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte()), 100))
    }

    @Test
    fun `an unknown function can still have a rule, named by its selector`() {
        val rule = AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0xABCDEF01", 1), 100)
        assertNotNull(rule)
        assertEquals("0xabcdef01", rule!!.selector)
        assertEquals("Always approve this function on this contract", autoApproveSwitchLabel(rule))
        assertEquals("Function 0xabcdef01", autoApproveRuleTitle(rule))
        assertEquals("Function 0xabcdef01 · Gnosis Chain", autoApproveRuleDetail(rule, BuiltInChains.ALL))
        assertEquals("Function 0xabcdef01 · chain 424242", autoApproveRuleDetail(rule.copy(chainId = 424242), BuiltInChains.ALL))
    }

    @Test
    fun `the sheet's words say exactly what the rule covers, the contract in full`() {
        val rule = AutoApproveRule.eligible(site, usdc.lowercase(), BigInteger.ZERO, call("0x095ea7b3"), 1)!!
        assertEquals("Token approvals", autoApproveRuleTitle(rule))
        assertEquals(
            "Function 0x095ea7b3 on $usdc, on Ethereum, from this site only. " +
                "Every such call is covered, whatever its recipient, spender or amount. Calls that also send funds still ask.",
            autoApproveScope(rule, "Ethereum"),
        )
    }

    @Test
    fun `a rule's parts are checked`() {
        assertNull(AutoApproveRule.of("", usdc, "0xa9059cbb", 1))
        assertNull(AutoApproveRule.of("https://a|b", usdc, "0xa9059cbb", 1))
        assertNull(AutoApproveRule.of(site, "0x1234", "0xa9059cbb", 1))
        assertNull(AutoApproveRule.of(site, usdc, "0xa9059c", 1))
        assertNull(AutoApproveRule.of(site, usdc, "0xa9059cbb", 0))
    }
}
