package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.l10n.Strings
import java.math.BigInteger
import java.util.Date

/**
 * An auto-approve rule (#112, iOS's `AutoApproveRule`): a connected site
 * ([origin]) may call function [selector] on contract [contract] on chain
 * [chainId] without a sheet, while the wallet is unlocked. Nothing wider:
 * another site, contract, function or chain asks as before, and so does
 * any call of this one that sends [value][eligible] along.
 *
 * [contract] is lower-case `0x` + 40 hex, [selector] lower-case `0x` + 8
 * hex; [key] is what the store keeps and what matching compares.
 * [grantedAt] is when the user turned it on (null for one just built).
 */
data class AutoApproveRule(
    val origin: String,
    val contract: String,
    val selector: String,
    val chainId: Long,
    val grantedAt: Long? = null,
) {
    val key: String get() = "$origin|$contract|$selector|$chainId"

    /** Whether this is the same scope as [other], whenever each was granted. */
    fun sameScope(other: AutoApproveRule): Boolean = key == other.key

    companion object {
        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        private val SELECTOR = Regex("^0x[0-9a-fA-F]{8}$")

        /** A rule with its parts normalized, or null if any isn't valid. */
        fun of(origin: String, contract: String, selector: String, chainId: Long): AutoApproveRule? {
            if (origin.isEmpty() || '|' in origin || chainId <= 0) return null
            if (!ADDRESS.matches(contract) || !SELECTOR.matches(selector)) return null
            if (selector.substring(2).all { it == '0' }) return null
            if (selector.lowercase() in REFUSED_SELECTORS) return null
            return AutoApproveRule(origin, contract.lowercase(), selector.lowercase(), chainId)
        }

        /**
         * The rule that would cover a transaction from [origin] to [to] on
         * [chainId] carrying [value] and call [data] — or null if no rule
         * may: one that sends native currency along (the user trusting
         * "always approve transfer" never agreed to "and send funds with
         * it"), and one that isn't a function call (fewer than four bytes
         * of data, or an all-zero selector, which is a plain send to a
         * payable fallback), and one whose function is [refused][REFUSED_SELECTORS].
         * As iOS's `AutoApproveOffer.make`.
         */
        fun eligible(origin: String, to: String, value: BigInteger, data: ByteArray, chainId: Long): AutoApproveRule? {
            if (value.signum() != 0 || data.size < 4) return null
            return of(origin, to, "0x" + hexOf(data, 4), chainId)
        }
    }
}

/**
 * Functions no rule may cover (#234, security audit #229): each hands out
 * more than the one call the user looked at, so "any arguments, no sheet"
 * would be a blank cheque. Approvals and permits let a spender the site
 * picks take tokens later, off the wallet's screen, and an authorization
 * or credit delegation lets someone else withdraw or borrow against the
 * user's position; a multicall, batch or
 * execute entry point runs whatever calls its data carries, so a router
 * that already holds token approvals (its own, or through Permit2) could
 * be told to send them anywhere. Named by signature, so the list can be
 * read against the standards; checked in [AutoApproveRule.of], so a rule
 * granted for one of these before this list existed is skipped when read,
 * and never matched again. A deny list can't name every such function
 * (#253 R1-F1), so the copy says "best-known" and an unnamed function's
 * switch carries [autoApproveWarning].
 */
internal val REFUSED_SELECTOR_SIGNATURES: List<String> = listOf(
    // Token approvals and permits: ERC-20, ERC-721/1155, ERC-777, ERC-1363, ERC-6909, EIP-2612, DAI, Permit2.
    "approve(address,uint256)",
    "increaseAllowance(address,uint256)",
    "increaseApproval(address,uint256)",
    "setApprovalForAll(address,bool)",
    "authorizeOperator(address)",
    "approveAndCall(address,uint256)",
    "approveAndCall(address,uint256,bytes)",
    "setOperator(address,bool)",
    "permit(address,address,uint256,uint256,uint8,bytes32,bytes32)",
    "permit(address,address,uint256,uint256,bool,uint8,bytes32,bytes32)",
    "approve(address,address,uint160,uint48)",
    "permit(address,((address,uint160,uint48,uint48),address,uint256),bytes)",
    "permit(address,((address,uint160,uint48,uint48)[],address,uint256),bytes)",
    // NFT permits: Uniswap V3 positions (ERC-721 permit), ERC-4494.
    "permit(address,uint256,uint256,uint8,bytes32,bytes32)",
    "permit(address,uint256,uint256,bytes)",
    // Authorizations and delegations over a position, and their signed forms: Compound III, Aave,
    // Morpho Blue, Maker, Balancer V2's Vault relayers, Euler's EVC operators, CoW's pre-signed orders.
    "allow(address,bool)",
    "allowBySig(address,address,bool,uint256,uint256,uint8,bytes32,bytes32)",
    "approveDelegation(address,uint256)",
    "delegationWithSig(address,address,uint256,uint256,uint8,bytes32,bytes32)",
    "setAuthorization(address,bool)",
    "setAuthorizationWithSig((address,address,bool,uint256,uint256),(uint8,bytes32,bytes32))",
    "hope(address)",
    "setRelayerApproval(address,address,bool)",
    "setAccountOperator(address,address,bool)",
    "setOperator(bytes19,address,uint256)",
    "setPreSignature(bytes,bool)",
    // Multicalls and batches: Uniswap V3 periphery, Multicall/Multicall3, BoringBatchable.
    "multicall(bytes[])",
    "multicall(uint256,bytes[])",
    "multicall(bytes32,bytes[])",
    "aggregate((address,bytes)[])",
    "tryAggregate(bool,(address,bytes)[])",
    "blockAndAggregate((address,bytes)[])",
    "tryBlockAndAggregate(bool,(address,bytes)[])",
    "aggregate3((address,bool,bytes)[])",
    "aggregate3Value((address,bool,uint256,bytes)[])",
    "batch(bytes[],bool)",
    // Euler's EVC: runs calls (each on behalf of one of the user's accounts) its data carries.
    "batch((address,address,uint256,bytes)[])",
    "call(address,address,uint256,bytes)",
    // Generic execute entry points: Uniswap's Universal Router, ERC-4337 accounts, ERC-7579/7821, Safe, Kernel.
    "execute(bytes,bytes[])",
    "execute(bytes,bytes[],uint256)",
    "execute(address,uint256,bytes)",
    "executeBatch(address[],uint256[],bytes[])",
    "executeBatch(address[],bytes[])",
    "executeBatch((address,uint256,bytes)[])",
    "execute(bytes32,bytes)",
    "execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)",
    "execTransactionFromModule(address,uint256,bytes,uint8)",
    "execute(address,uint256,bytes,uint8)",
    // EIP-7702 / smart-account batches (Ambire): runs every call it's handed. Under 7702 the
    // account is the user's own address, so even the "by self" form is a plain transaction.
    "executeBySender((address,uint256,bytes)[])",
    "executeBySelf((address,uint256,bytes)[])",
    "execute((address,uint256,bytes)[],bytes)",
    // Uniswap V4 PositionManager: runs whatever actions its data encodes.
    "modifyLiquidities(bytes,uint256)",
    "modifyLiquiditiesWithoutUnlock(bytes,bytes[])",
)

/** [REFUSED_SELECTOR_SIGNATURES] as selectors: lower-case `0x` + 8 hex. */
private val REFUSED_SELECTORS: Set<String> =
    REFUSED_SELECTOR_SIGNATURES.mapTo(HashSet()) { selectorOf(it) }

/** The 4-byte selector of a canonical function [signature], lower-case `0x` + 8 hex. */
internal fun selectorOf(signature: String): String = "0x" + hexOf(Keccak256.digest(signature), 4)

/** What a well-known [selector] does ("token transfers"); null for any other, which is still allowed. */
internal fun selectorLabel(selector: String): String? = when (selector.lowercase()) {
    "0xa9059cbb", "0x23b872dd" -> Strings.get(R.string.send_auto_approve_token_transfers)
    else -> null
}

/**
 * The risk of an auto-approve rule for a function the wallet can't name,
 * shared by the sheet's switch ([autoApproveWarning]) and the site's page
 * ([autoApproveRuleWarning]) so the two surfaces can't describe it
 * differently (#253 R3-M1); each adds only its own closing advice.
 */
private val UNKNOWN_FUNCTION_RISK: String get() = Strings.get(R.string.send_auto_approve_unknown_risk)

/**
 * Under the switch, in red, for a function the wallet can't name (#234):
 * the refused list can't know every router, so what an unknown function
 * could do with any arguments is said before the user turns it on.
 */
internal fun autoApproveWarning(rule: AutoApproveRule): String? =
    if (selectorLabel(rule.selector) != null) {
        null
    } else {
        Strings.get(R.string.send_auto_approve_warning_switch, UNKNOWN_FUNCTION_RISK)
    }

/**
 * Under a rule on its site's page, in red, for a function the wallet can't
 * name (#253 R1-M1): a rule granted before the sheet warned (or before this
 * list grew) still works, so the risk [autoApproveWarning] names at the
 * switch is said again wherever the rule can be seen and removed.
 */
internal fun autoApproveRuleWarning(rule: AutoApproveRule): String? =
    if (selectorLabel(rule.selector) != null) {
        null
    } else {
        Strings.get(R.string.send_auto_approve_warning_rule, UNKNOWN_FUNCTION_RISK)
    }

/** The sheet's switch: "Always approve token transfers on this contract". */
internal fun autoApproveSwitchLabel(rule: AutoApproveRule): String =
    selectorLabel(rule.selector)?.let { Strings.get(R.string.send_auto_approve_switch_label, it) }
        ?: Strings.get(R.string.send_auto_approve_switch_label_unknown)

/** Under the switch: exactly what the rule covers, in full. */
internal fun autoApproveScope(rule: AutoApproveRule, chain: String): String =
    Strings.get(R.string.send_auto_approve_scope, rule.selector, checksumOf(rule.contract), chain)

/** A rule's line on its site's page: "Token transfers" or "Function 0x12345678". */
internal fun autoApproveRuleTitle(rule: AutoApproveRule): String =
    selectorLabel(rule.selector)?.replaceFirstChar { it.uppercase() } ?: Strings.get(R.string.send_auto_approve_function, rule.selector)

/** A rule's details on its site's page: the function, the network, since when. */
internal fun autoApproveRuleDetail(rule: AutoApproveRule, chains: List<Chain>): String {
    val network = chains.firstOrNull { it.id == rule.chainId }?.name ?: Strings.get(R.string.send_chain_fallback, rule.chainId.toString())
    return rule.grantedAt?.let {
        Strings.get(R.string.send_auto_approve_rule_detail_since, rule.selector, network, txDateFormat().format(Date(it)))
    } ?: Strings.get(R.string.send_auto_approve_rule_detail, rule.selector, network)
}

/** [address] (lower-case, valid) in EIP-55 form, as the sheets show addresses. */
private fun checksumOf(address: String): String = EthereumProvider.checksummed(address) ?: address

internal val AUTO_APPROVE_EXPLAINER: String get() = Strings.get(R.string.send_auto_approve_explainer)
internal val AUTO_APPROVE_REMOVE_FAILED: String get() = Strings.get(R.string.send_auto_approve_remove_failed)

/**
 * A connected site's auto-approve rules, each with Remove (#112). The
 * contract is shown whole: the end of an address is what a look-alike
 * changes. [failed] is the rule whose Remove couldn't be saved.
 */
@Composable
internal fun AutoApproveRulesSection(
    rules: List<AutoApproveRule>,
    chains: List<Chain>,
    onRemove: (AutoApproveRule) -> Unit,
    failed: AutoApproveRule? = null,
    unreadable: Boolean = false,
    /** The rules haven't been read yet: say nothing about them rather than "None". */
    loading: Boolean = false,
) {
    SectionCard(title = stringResource(R.string.send_auto_approve_rules)) {
        Text(
            stringResource(R.string.send_auto_approve_explainer),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (loading) {
            // Nothing yet: a "None" here would be wrong for a moment on a site with rules.
        } else if (unreadable) {
            Text(
                stringResource(R.string.send_auto_approve_unreadable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (rules.isEmpty()) {
            Text(
                stringResource(R.string.send_auto_approve_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("auto-approve-rule")) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(autoApproveRuleTitle(rule), fontWeight = FontWeight.Medium)
                    AddressText(
                        checksumOf(rule.contract),
                        MaterialTheme.typography.bodySmall,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        autoApproveRuleDetail(rule, chains),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    autoApproveRuleWarning(rule)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp).testTag("auto-approve-rule-warning"),
                        )
                    }
                    if (failed?.sameScope(rule) == true) {
                        Text(
                            stringResource(R.string.send_auto_approve_remove_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRemove(rule) }, modifier = Modifier.testTag("auto-approve-remove")) { Text(stringResource(R.string.common_remove)) }
            }
        }
    }
}
