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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.AutoApproveStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.data.SwarmGrantStore
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
internal const val GONE_ACCOUNT = "An account this wallet no longer has"

/** The wallet account [grant] shares, if the wallet still has it. */
internal fun grantAccount(grant: DappGrantStore.Grant, accounts: List<WalletAccount>): WalletAccount? =
    accounts.firstOrNull { it.address.equals(grant.account, ignoreCase = true) }

/** The network [grant]'s site is on, by name; "chain 1234" for one the chain list doesn't have. */
internal fun grantNetwork(grant: DappGrantStore.Grant, chains: List<Chain>): String =
    chains.firstOrNull { it.id == grant.chainId }?.name ?: "chain ${grant.chainId}"

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
    SectionCard(title = "Connected sites") {
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
                            DISCONNECT_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text("Disconnect") }
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
    FullScreenScaffold(title = "Connected site", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("site") {
                SectionCard(title = permissionOriginDisplay(grant.origin)) {
                    Text(
                        CONNECTED_SITE_EXPLAINER,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TxField("Site", grant.origin)
                    TxField(if (account != null) "Account · ${account.name}" else "Account · $GONE_ACCOUNT", null, address = grant.account)
                    TxField("Network", grantNetwork(grant, chains))
                    grant.connectedAt?.let { TxField("Connected", txDateFormat().format(Date(it))) }
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
                        Text("Disconnect")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (failed) DISCONNECT_FAILED else DISCONNECT_EXPLAINER,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal const val CONNECTED_SITE_EXPLAINER =
    "This site can see the account below and ask you to sign messages or send transactions from it. " +
        "Each signature and transaction still needs your approval, unless an auto-approve rule below covers it."
internal const val DISCONNECT_EXPLAINER =
    "The site's open pages lose the account at once, and its auto-approve rules are removed. It can ask to connect " +
        "again; nothing is shared until you approve."
internal const val DISCONNECT_FAILED = "Couldn't disconnect: the change couldn't be saved. Try again."
internal const val ASK_EACH_TIME_FAILED = "Couldn't switch to asking each time: the change couldn't be saved. Try again."

/** One read of the auto-approve rules; [rules] null when the store couldn't be read. */
private class RulesRead(val rules: List<AutoApproveRule>?)

/** What a Swarm-connected site's line says: what it may do without asking (#120). */
internal fun swarmSiteSummary(grant: SwarmGrantStore.Grant): String {
    val always = listOfNotNull(
        "publishes".takeIf { "publish" in grant.autoApprove },
        "manages feeds".takeIf { "feeds" in grant.autoApprove },
        "signs".takeIf { "signing" in grant.autoApprove },
    )
    return if (always.isEmpty()) {
        "Asks before each upload and signature"
    } else {
        always.joinToString(", ").replaceFirstChar { it.uppercase() } + " without asking"
    }
}

/** What a site's line says about its permission manifest (#122): the rows it allowed through it, or null. */
internal fun swarmManifestSummary(rows: List<ManifestCapability>?): String? =
    rows?.takeIf { it.isNotEmpty() }?.let { r ->
        "Allowed by the app's permission manifest: " + r.joinToString(", ") { manifestRowLabel(it).first.lowercase() }
    }

/**
 * Sites connected to Swarm through `window.swarm` (#120), each with what
 * it may do without asking, and Disconnect — which also drops its
 * "always allow"s and feed access. [disconnectFailed] is the site whose
 * Disconnect couldn't be saved; its line says so. A site whose
 * permission manifest (#122) manages some rows says which
 * ([manifestRows]), with Ask each time to go back to a sheet per upload
 * and signature ([onAskEachTime]); [askEachTimeFailed] is the site where
 * that couldn't be saved.
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
    SectionCard(title = "Connected to Swarm") {
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
                        ) { Text("Ask each time") }
                    }
                    if (askEachTimeFailed == grant.origin) {
                        Text(
                            ASK_EACH_TIME_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (disconnectFailed == grant.origin) {
                        Text(
                            DISCONNECT_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text("Disconnect") }
            }
        }
    }
}
