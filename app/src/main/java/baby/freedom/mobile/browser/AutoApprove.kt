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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ens.Keccak256
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
 * picks take tokens later, off the wallet's screen; a multicall, batch or
 * execute entry point runs whatever calls its data carries, so a router
 * that already holds token approvals (its own, or through Permit2) could
 * be told to send them anywhere. Named by signature, so the list can be
 * read against the standards; checked in [AutoApproveRule.of], so a rule
 * granted for one of these before this list existed is skipped when read,
 * and never matched again.
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
    // Generic execute entry points: Uniswap's Universal Router, ERC-4337 accounts, ERC-7579/7821, Safe.
    "execute(bytes,bytes[])",
    "execute(bytes,bytes[],uint256)",
    "execute(address,uint256,bytes)",
    "executeBatch(address[],uint256[],bytes[])",
    "executeBatch(address[],bytes[])",
    "executeBatch((address,uint256,bytes)[])",
    "execute(bytes32,bytes)",
    "execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)",
    "execTransactionFromModule(address,uint256,bytes,uint8)",
)

/** [REFUSED_SELECTOR_SIGNATURES] as selectors: lower-case `0x` + 8 hex. */
private val REFUSED_SELECTORS: Set<String> =
    REFUSED_SELECTOR_SIGNATURES.mapTo(HashSet()) { selectorOf(it) }

/** The 4-byte selector of a canonical function [signature], lower-case `0x` + 8 hex. */
internal fun selectorOf(signature: String): String = "0x" + hexOf(Keccak256.digest(signature), 4)

/** What a well-known [selector] does ("token transfers"); null for any other, which is still allowed. */
internal fun selectorLabel(selector: String): String? = when (selector.lowercase()) {
    "0xa9059cbb", "0x23b872dd" -> "token transfers"
    else -> null
}

/**
 * Under the switch, in red, for a function the wallet can't name (#234):
 * the refused list can't know every router, so what an unknown function
 * could do with any arguments is said before the user turns it on.
 */
internal fun autoApproveWarning(rule: AutoApproveRule): String? =
    if (selectorLabel(rule.selector) != null) {
        null
    } else {
        "The wallet can't tell what this function does. If it can move tokens you've approved this contract " +
            "to use, or run calls it's handed (as a swap router can), this rule lets the site do that with " +
            "no sheet, to anyone. Only turn it on for a function you know."
    }

/** The sheet's switch: "Always approve token transfers on this contract". */
internal fun autoApproveSwitchLabel(rule: AutoApproveRule): String =
    "Always approve ${selectorLabel(rule.selector) ?: "this function"} on this contract"

/** Under the switch: exactly what the rule covers, in full. */
internal fun autoApproveScope(rule: AutoApproveRule, chain: String): String =
    "Function ${rule.selector} on ${checksumOf(rule.contract)}, on $chain, from this site only. " +
        "Every such call is covered, whatever its recipient, spender or amount. " +
        "Calls that also send funds still ask."

/** A rule's line on its site's page: "Token transfers" or "Function 0x12345678". */
internal fun autoApproveRuleTitle(rule: AutoApproveRule): String =
    selectorLabel(rule.selector)?.replaceFirstChar { it.uppercase() } ?: "Function ${rule.selector}"

/** A rule's details on its site's page: the function, the network, since when. */
internal fun autoApproveRuleDetail(rule: AutoApproveRule, chains: List<Chain>): String {
    val network = chains.firstOrNull { it.id == rule.chainId }?.name ?: "chain ${rule.chainId}"
    val since = rule.grantedAt?.let { " · since ${txDateFormat().format(Date(it))}" }.orEmpty()
    return "Function ${rule.selector} · $network$since"
}

/** [address] (lower-case, valid) in EIP-55 form, as the sheets show addresses. */
private fun checksumOf(address: String): String = EthereumProvider.checksummed(address) ?: address

internal const val AUTO_APPROVE_EXPLAINER =
    "Transactions from this site that match a rule go out without asking while the wallet is unlocked. " +
        "Each rule covers one function on one contract on one network — with any recipient, spender or amount — " +
        "and only calls that send no funds. " +
        "Approvals, permits, multicalls and execute functions can't have a rule. " +
        "Disconnecting the site removes its rules."
internal const val AUTO_APPROVE_REMOVE_FAILED = "Couldn't remove the rule: the change couldn't be saved. Try again."

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
    SectionCard(title = "Auto-approve rules") {
        Text(
            AUTO_APPROVE_EXPLAINER,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (loading) {
            // Nothing yet: a "None" here would be wrong for a moment on a site with rules.
        } else if (unreadable) {
            Text(
                "Couldn't read the rules right now. None of them apply until they can be read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (rules.isEmpty()) {
            Text(
                "None. Turn one on from a transaction's approval sheet.",
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
                    if (failed?.sameScope(rule) == true) {
                        Text(
                            AUTO_APPROVE_REMOVE_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRemove(rule) }, modifier = Modifier.testTag("auto-approve-remove")) { Text("Remove") }
            }
        }
    }
}
