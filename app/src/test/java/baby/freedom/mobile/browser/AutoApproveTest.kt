package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val rule = AutoApproveRule.eligible(site, usdc.lowercase(), BigInteger.ZERO, call("0x23b872dd", 3), 1)!!
        assertEquals("Token transfers", autoApproveRuleTitle(rule))
        assertEquals(
            "Function 0x23b872dd on $usdc, on Ethereum, from this site only. " +
                "Every such call is covered, whatever its recipient, spender or amount. Calls that also send funds still ask.",
            autoApproveScope(rule, "Ethereum"),
        )
    }

    // #234 (security audit #229): each of these hands out more than the one call on the sheet — an
    // approval or permit lets a spender the site picks take tokens later; a multicall or execute entry
    // point runs whatever calls its data carries, so a router holding the user's approvals could send
    // them anywhere. Written as hex, not derived, so a slip in the list or in the hashing shows here.
    private val refused = mapOf(
        "0x095ea7b3" to "approve(address,uint256)",
        "0x39509351" to "increaseAllowance(address,uint256)",
        "0xa22cb465" to "setApprovalForAll(address,bool)",
        "0xd505accf" to "permit (EIP-2612)",
        "0x87517c45" to "Permit2 approve",
        "0x2b67b570" to "Permit2 permit",
        "0x2a2d80d1" to "Permit2 permit batch",
        "0xac9650d8" to "multicall(bytes[])",
        "0x5ae401dc" to "multicall(uint256,bytes[])",
        "0x252dba42" to "Multicall aggregate",
        "0x82ad56cb" to "Multicall3 aggregate3",
        "0x24856bc3" to "Universal Router execute(bytes,bytes[])",
        "0x3593564c" to "Universal Router execute(bytes,bytes[],uint256)",
        "0xb61d27f6" to "ERC-4337 execute(address,uint256,bytes)",
        "0x47e1da2a" to "ERC-4337 executeBatch(address[],uint256[],bytes[])",
        "0xe9ae5c53" to "ERC-7579 execute(bytes32,bytes)",
        "0x6a761202" to "Safe execTransaction",
        // #253 R1-F1: authorizations, delegations, NFT permits and more execute shapes.
        "0x110496e5" to "Compound III allow(address,bool)",
        "0xc04a8a10" to "Aave approveDelegation(address,uint256)",
        "0xeecea000" to "Morpho Blue setAuthorization(address,bool)",
        "0x7ac2ff7b" to "Uniswap V3 positions permit(address,uint256,uint256,uint8,bytes32,bytes32)",
        "0x745a41bc" to "ERC-4494 permit(address,uint256,uint256,bytes)",
        "0xa3b22fc4" to "Maker hope(address)",
        "0x51945447" to "Kernel execute(address,uint256,bytes,uint8)",
        "0xdd46508f" to "Uniswap V4 modifyLiquidities(bytes,uint256)",
        // #253 R1-M2: relayer/operator approvals, pre-signed orders and signed authorizations.
        "0xfa6e671d" to "Balancer V2 setRelayerApproval(address,address,bool)",
        "0x9f5c462a" to "Euler EVC setAccountOperator(address,address,bool)",
        "0xc14c11bf" to "Euler EVC setOperator(bytes19,address,uint256)",
        "0xc16ae7a4" to "Euler EVC batch((address,address,uint256,bytes)[])",
        "0xec6cb13f" to "CoW setPreSignature(bytes,bool)",
        "0x8069218f" to "Morpho Blue setAuthorizationWithSig",
        "0x0b52d558" to "Aave delegationWithSig",
        "0xbb24d994" to "Compound III allowBySig",
        // #253 R2-M1: V4's no-unlock entry point and EIP-7702/smart-account batch entry points.
        "0x4afe393c" to "Uniswap V4 modifyLiquiditiesWithoutUnlock(bytes,bytes[])",
        "0xabc5345e" to "Ambire executeBySender((address,uint256,bytes)[])",
        "0x6769de82" to "Ambire executeBySelf((address,uint256,bytes)[])",
        "0x6171d1c9" to "Ambire execute((address,uint256,bytes)[],bytes)",
    )

    @Test
    fun `an approval, permit, multicall or execute function can't have a rule, whatever the case of its selector`() {
        for ((selector, name) in refused) {
            assertNull(name, AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call(selector, 4), 100))
            assertNull(name, AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call(selector.uppercase().replace("0X", "0x"), 4), 100))
            assertNull(name, AutoApproveRule.of(site, usdc, selector, 100))
            assertNull(name, AutoApproveRule.of(site, usdc, selector.uppercase().replace("0X", "0x"), 100))
        }
    }

    @Test
    fun `every refused signature hashes to its selector, and every one listed here is refused`() {
        assertEquals("0xa9059cbb", selectorOf("transfer(address,uint256)"))
        val listed = REFUSED_SELECTOR_SIGNATURES.map { selectorOf(it) }
        assertEquals("no duplicates", listed.size, listed.toSet().size)
        assertTrue(listed.containsAll(refused.keys))
    }

    @Test
    fun `transfers have no warning, a function the wallet can't name has one`() {
        assertNull(autoApproveWarning(AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0xa9059cbb"), 100)!!))
        assertNull(autoApproveWarning(AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0x23b872dd", 3), 100)!!))
        // Uniswap V2's swapExactTokensForTokens: a router function no list could name in full.
        val swap = AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0x38ed1739", 5), 100)!!
        assertEquals(
            "The wallet can't tell what this function does. If it can move tokens you've approved this contract " +
                "to use, run calls it's handed (as a swap router can), or let someone else spend, borrow or " +
                "withdraw for you, this rule lets the site do that with no sheet, to anyone. " +
                "Only turn it on for a function you know.",
            autoApproveWarning(swap),
        )
    }

    @Test
    fun `a stored rule for a function the wallet can't name is warned about on its site's page, a transfer's isn't`() {
        assertNull(autoApproveRuleWarning(AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0xa9059cbb"), 100)!!))
        assertNull(autoApproveRuleWarning(AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0x23b872dd", 3), 100)!!))
        val swap = AutoApproveRule.eligible(site, usdc, BigInteger.ZERO, call("0x38ed1739", 5), 100)!!
        assertEquals(
            "The wallet can't tell what this function does. If it can move tokens you've approved this contract " +
                "to use, run calls it's handed (as a swap router can), or let someone else spend, borrow or " +
                "withdraw for you, this rule lets the site do that with no sheet, to anyone. " +
                "Remove it unless you know the function.",
            autoApproveRuleWarning(swap),
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
