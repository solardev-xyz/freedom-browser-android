package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.AutoApproveStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.data.SwarmGrantStore
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.WalletAccount
import java.util.Date
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Connected sites (#111): the sites connected to the wallet through
 * `window.ethereum` (#110), listed on the wallet page and in Settings →
 * Site permissions, each with a page of its own. Disconnecting from any
 * of them goes through [EthereumProviders.disconnect], so the site's open
 * pages are told (`accountsChanged []`) whichever screen it came from.
 */

/** What a connected site's line says under its name, when it hasn't the account: the wallet page's wording (#215). */
internal val GONE_ACCOUNT: String get() = Strings.get(R.string.send_gone_account)

/** The wallet account [grant] shares, if the wallet still has it. */
internal fun grantAccount(grant: DappGrantStore.Grant, accounts: List<WalletAccount>): WalletAccount? =
    accounts.firstOrNull { it.address.equals(grant.account, ignoreCase = true) }

/** The network [grant]'s site is on, by name; "chain 1234" for one the chain list doesn't have. */
internal fun grantNetwork(grant: DappGrantStore.Grant, chains: List<Chain>): String =
    chains.firstOrNull { it.id == grant.chainId }?.name ?: Strings.get(R.string.send_chain_fallback, grant.chainId.toString())

/** "Account 1 · 0x9858…da94 · Gnosis Chain": which account a connected site sees, and on which network. */
internal fun connectedSiteSummary(
    grant: DappGrantStore.Grant,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
): String =
    "${grantAccount(grant, accounts)?.name ?: GONE_ACCOUNT} · ${shortAddress(grant.account)} · ${grantNetwork(grant, chains)}"

/**
 * Sites connected to the wallet through `window.ethereum` (#110): which
 * account each was given and which network it's on. A site can drop its
 * own connection (`wallet_revokePermissions`); this is the user's way to
 * drop it for them. Tapping a site opens its page ([ConnectedSitePage]).
 * [disconnectFailed] is the site whose Disconnect couldn't be saved; its
 * line says so.
 */
@Composable
internal fun DappSitesSection(
    grants: List<DappGrantStore.Grant>,
    chains: List<Chain>,
    accounts: List<WalletAccount>,
    onOpen: (String) -> Unit,
    onRevoke: (String) -> Unit,
    disconnectFailed: String? = null,
) {
    SectionCard(title = stringResource(R.string.send_connected_sites)) {
        grants.forEach { grant ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpen(grant.origin) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(permissionOriginDisplay(grant.origin), fontWeight = FontWeight.Medium)
                    Text(
                        connectedSiteSummary(grant, accounts, chains),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (disconnectFailed == grant.origin) {
                        Text(
                            stringResource(R.string.send_disconnect_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text(stringResource(R.string.common_disconnect)) }
            }
        }
    }
}

/**
 * One connected site in full (desktop's per-site permissions screen, iOS's
 * `ConnectedSiteDetailView`): the site, the account it sees, the network
 * it's on, since when, its auto-approve rules (#112) each with Remove, and
 * Disconnect. [onDisconnect] reports whether the
 * change was saved; the page closes when it was, and says so when it wasn't.
 */
@Composable
internal fun ConnectedSitePage(
    grant: DappGrantStore.Grant,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
    onDisconnect: suspend (String) -> Boolean,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val account = grantAccount(grant, accounts)
    val context = LocalContext.current
    val ruleStore = remember(context) { AutoApproveStore.get(context) }
    // Wrapped, so "not read yet" (null) is told apart from "unreadable" (a null list).
    val read by remember(ruleStore) { ruleStore.allOrUnreadable.map { RulesRead(it) } }
        .collectAsState(initial = null)
    val allRules = read?.rules
    val rules = allRules.orEmpty().filter { it.origin == grant.origin }
    var removeFailed by remember { mutableStateOf<AutoApproveRule?>(null) }
    FullScreenScaffold(title = stringResource(R.string.send_connected_site), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("site") {
                SectionCard(title = permissionOriginDisplay(grant.origin)) {
                    Text(
                        stringResource(R.string.send_connected_site_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TxField(stringResource(R.string.send_site), grant.origin)
                    TxField(
                        stringResource(R.string.send_account_named, account?.name ?: stringResource(R.string.send_gone_account)),
                        null,
                        address = grant.account,
                    )
                    TxField(stringResource(R.string.send_label_network), grantNetwork(grant, chains))
                    val connected = stringResource(R.string.send_connected)
                    grant.connectedAt?.let { TxField(connected, txDateFormat().format(Date(it))) }
                }
            }
            item("rules") {
                AutoApproveRulesSection(
                    rules = rules,
                    chains = chains,
                    onRemove = { rule ->
                        scope.launch { removeFailed = if (ruleStore.revoke(rule)) null else rule }
                    },
                    failed = removeFailed,
                    unreadable = read != null && allRules == null,
                    loading = read == null,
                )
            }
            item("disconnect") {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            failed = false
                            scope.launch {
                                val done = try {
                                    onDisconnect(grant.origin)
                                } finally {
                                    busy = false
                                }
                                if (done) onBack() else failed = true
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_disconnect))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(if (failed) R.string.send_disconnect_failed else R.string.send_disconnect_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal val CONNECTED_SITE_EXPLAINER: String get() = Strings.get(R.string.send_connected_site_explainer)
internal val DISCONNECT_EXPLAINER: String get() = Strings.get(R.string.send_disconnect_explainer)
internal val DISCONNECT_FAILED: String get() = Strings.get(R.string.send_disconnect_failed)
internal val ASK_EACH_TIME_FAILED: String get() = Strings.get(R.string.send_ask_each_time_failed)

/** One read of the auto-approve rules; [rules] null when the store couldn't be read. */
private class RulesRead(val rules: List<AutoApproveRule>?)

/** What a Swarm-connected site's line says: what it may do without asking (#120), and whether it may message (#121). */
internal fun swarmSiteSummary(grant: SwarmGrantStore.Grant): String {
    val always = listOfNotNull(
        R.string.send_swarm_publishes.takeIf { "publish" in grant.autoApprove },
        R.string.send_swarm_manages_feeds.takeIf { "feeds" in grant.autoApprove },
        R.string.send_swarm_signs.takeIf { "signing" in grant.autoApprove },
        R.string.send_swarm_sends_messages.takeIf { grant.messaging && "messaging" in grant.autoApprove },
    ).map { Strings.get(it) }
    val summary = if (always.isEmpty()) {
        Strings.get(R.string.send_swarm_asks_each)
    } else {
        Strings.get(R.string.send_swarm_without_asking, always.joinToString(", ").replaceFirstChar { it.uppercase() })
    }
    return if (grant.messaging) Strings.get(R.string.send_swarm_can_message, summary) else summary
}

/** What a site's line says about its permission manifest (#122): the rows it allowed through it, or null. */
internal fun swarmManifestSummary(rows: List<ManifestCapability>?): String? =
    rows?.takeIf { it.isNotEmpty() }?.let { r ->
        Strings.get(R.string.send_swarm_manifest_allowed, r.joinToString(", ") { manifestRowLabel(it).first.lowercase() })
    }

/**
 * Sites connected to Swarm through `window.swarm` (#120), each with what
 * it may do without asking, and Disconnect — which also drops its
 * "always allow"s, feed access and messaging (closing its subscriptions).
 * [disconnectFailed] is the site whose Disconnect couldn't be saved; its
 * line says so. A site whose permission manifest (#122) manages some rows
 * says which ([manifestRows]), with Ask each time to go back to a sheet
 * per upload and signature ([onAskEachTime]); [askEachTimeFailed] is the
 * site where that couldn't be saved.
 */
@Composable
internal fun SwarmSitesSection(
    grants: List<SwarmGrantStore.Grant>,
    onRevoke: (String) -> Unit,
    disconnectFailed: String? = null,
    manifestRows: Map<String, List<ManifestCapability>> = emptyMap(),
    onAskEachTime: (String) -> Unit = {},
    askEachTimeFailed: String? = null,
) {
    SectionCard(title = stringResource(R.string.send_connected_to_swarm)) {
        grants.forEach { grant ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(permissionOriginDisplay(grant.origin), fontWeight = FontWeight.Medium)
                    Text(
                        swarmSiteSummary(grant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    swarmManifestSummary(manifestRows[grant.origin])?.let { summary ->
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("swarm-manifest-summary"),
                        )
                        TextButton(
                            onClick = { onAskEachTime(grant.origin) },
                            modifier = Modifier.testTag("swarm-ask-each-time"),
                        ) { Text(stringResource(R.string.send_ask_each_time)) }
                    }
                    if (askEachTimeFailed == grant.origin) {
                        Text(
                            stringResource(R.string.send_ask_each_time_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (disconnectFailed == grant.origin) {
                        Text(
                            stringResource(R.string.send_disconnect_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text(stringResource(R.string.common_disconnect)) }
            }
        }
    }
}
