package baby.freedom.mobile.browser

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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
 * A row in a list of standing permissions (#423, audit W31): the site and
 * what it may do, opening its page when [onOpen] is given (the whole text
 * area, at least 56 dp tall, with a chevron and "Open" for TalkBack), and
 * the one way to take it away — the same labelled text button
 * ([actionLabel], read as [actionDescription]) on the wallet page and in
 * Settings alike.
 */
@Composable
internal fun PermissionRow(
    title: String,
    actionLabel: String,
    actionDescription: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    leading: ImageVector? = null,
    actionTag: String? = null,
    enabled: Boolean = true,
    lines: @Composable ColumnScope.() -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .then(
                    if (onOpen != null) Modifier.clickable(onClickLabel = stringResource(R.string.common_open), onClick = onOpen)
                    else Modifier,
                )
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
        ) {
            leading?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                lines()
            }
            if (onOpen != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = actionDescription }
                .then(actionTag?.let { Modifier.testTag(it) } ?: Modifier),
        ) { Text(actionLabel) }
    }
}

/**
 * Snackbars that offer Undo after a removal (#423, audit W31): Disconnect,
 * Revoke and Remove act at once — a site the user distrusts loses its
 * access straight away — and Undo puts back exactly what was there, if it
 * still can. One at a time: a new removal replaces the last one's notice
 * (its Undo is then gone, the removal stays). [scope] should outlive the
 * page that shows the snackbar for as long as the host does.
 */
@Stable
internal class UndoNotices(val host: SnackbarHostState, private val scope: CoroutineScope) {
    private var job: Job? = null

    /** Show [message] with Undo; [undo] puts it back, true if it could. */
    fun show(message: String, undo: suspend () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            val result = host.showSnackbar(
                message,
                actionLabel = Strings.get(R.string.send_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && !undo()) {
                host.showSnackbar(Strings.get(R.string.send_undo_failed), duration = SnackbarDuration.Long)
            }
        }
    }

    /** Show [message] alone (a removal that couldn't be saved). */
    fun say(message: String) {
        job?.cancel()
        job = scope.launch { host.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long) }
    }
}

@Composable
internal fun rememberUndoNotices(): UndoNotices {
    val scope = rememberCoroutineScope()
    return remember(scope) { UndoNotices(SnackbarHostState(), scope) }
}

/** Where [notices] show: at the bottom of the page that composes it, clear of the navigation bar. */
@Composable
internal fun BoxScope.UndoSnackbarHost(notices: UndoNotices) {
    SnackbarHost(
        hostState = notices.host,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
    ) { data -> Snackbar(snackbarData = data) }
}

/**
 * Disconnect [grant]'s site ([EthereumProviders.disconnect]) and offer
 * Undo, which connects it again with the same account, network and
 * auto-approve rules ([EthereumProviders.reconnect]) unless something
 * changed meanwhile. False if the disconnect couldn't be saved (nothing
 * is offered then).
 */
internal suspend fun disconnectWithUndo(context: Context, grant: DappGrantStore.Grant, notices: UndoNotices): Boolean {
    // Read before they're dropped with the connection; unreadable, Undo brings back the connection alone.
    val rules = AutoApproveStore.get(context).allOrUnreadable.first().orEmpty().filter { it.origin == grant.origin }
    if (!EthereumProviders.disconnect(context, grant.origin)) return false
    notices.show(Strings.get(R.string.send_undo_disconnected, permissionOriginDisplay(grant.origin))) {
        EthereumProviders.reconnect(context, grant.origin, grant.account, grant.chainId, rules)
    }
    return true
}

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
    onRevoke: (DappGrantStore.Grant) -> Unit,
    disconnectFailed: String? = null,
) {
    SectionCard(title = stringResource(R.string.send_connected_sites)) {
        grants.forEach { grant ->
            val site = permissionOriginDisplay(grant.origin)
            PermissionRow(
                title = site,
                actionLabel = stringResource(R.string.common_disconnect),
                actionDescription = stringResource(R.string.settings_dapp_disconnect, site),
                onAction = { onRevoke(grant) },
                onOpen = { onOpen(grant.origin) },
                actionTag = "dapp-disconnect",
                modifier = Modifier.testTag("dapp-site"),
            ) {
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
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/**
 * One connected site in full (desktop's per-site permissions screen, iOS's
 * `ConnectedSiteDetailView`): the site, the account it sees, the network
 * it's on, since when, its auto-approve rules (#112) each with Remove, and
 * Disconnect. [onDisconnect] reports whether the
 * change was saved; the page closes when it was (the page underneath then
 * offers Undo), and says so when it wasn't. A removed rule offers Undo on
 * [notices], shown here.
 */
@Composable
internal fun ConnectedSitePage(
    grant: DappGrantStore.Grant,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
    notices: UndoNotices,
    onDisconnect: suspend (DappGrantStore.Grant) -> Boolean,
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
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
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
                            scope.launch {
                                if (ruleStore.revoke(rule)) {
                                    removeFailed = null
                                    notices.show(Strings.get(R.string.send_undo_rule_removed, autoApproveRuleTitle(rule))) {
                                        EthereumProviders.restoreRule(context, grant.account, rule)
                                    }
                                } else {
                                    removeFailed = rule
                                }
                            }
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
                                        onDisconnect(grant)
                                    } finally {
                                        busy = false
                                    }
                                    if (done) onBack() else failed = true
                                }
                            },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.common_disconnect))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(if (failed) R.string.send_disconnect_failed else R.string.send_disconnect_explainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (failed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                        )
                    }
                }
            }
            UndoSnackbarHost(notices)
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
