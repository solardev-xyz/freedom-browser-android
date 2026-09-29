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
            return AutoApproveRule(origin, contract.lowercase(), selector.lowercase(), chainId)
        }

        /**
         * The rule that would cover a transaction from [origin] to [to] on
         * [chainId] carrying [value] and call [data] — or null if no rule
         * may: one that sends native currency along (the user trusting
         * "always approve transfer" never agreed to "and send funds with
         * it"), and one that isn't a function call (fewer than four bytes
         * of data, or an all-zero selector, which is a plain send to a
         * payable fallback). As iOS's `AutoApproveOffer.make`.
         */
        fun eligible(origin: String, to: String, value: BigInteger, data: ByteArray, chainId: Long): AutoApproveRule? {
            if (value.signum() != 0 || data.size < 4) return null
            return of(origin, to, "0x" + hexOf(data, 4), chainId)
        }
    }
}

/** What a well-known [selector] does ("token transfers"); null for any other, which is still allowed. */
internal fun selectorLabel(selector: String): String? = when (selector.lowercase()) {
    "0xa9059cbb", "0x23b872dd" -> "token transfers"
    "0x095ea7b3" -> "token approvals"
    else -> null
}

/** The sheet's switch: "Always approve token transfers on this contract". */
internal fun autoApproveSwitchLabel(rule: AutoApproveRule): String =
    "Always approve ${selectorLabel(rule.selector) ?: "this function"} on this contract"

/** Under the switch: exactly what the rule covers, in full. */
internal fun autoApproveScope(rule: AutoApproveRule, chain: String): String =
    "Function ${rule.selector} on ${checksumOf(rule.contract)}, on $chain, from this site only. " +
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
        "Each rule covers one function on one contract on one network, and only calls that send no funds. " +
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
) {
    SectionCard(title = "Auto-approve rules") {
        Text(
            AUTO_APPROVE_EXPLAINER,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (unreadable) {
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
