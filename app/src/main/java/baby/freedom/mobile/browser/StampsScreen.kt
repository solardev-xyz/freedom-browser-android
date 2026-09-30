package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Why the stamp pages can't read anything now, or null when they can:
 * the node's batches and prices come from its light-mode gateway.
 */
internal fun stampsBlockedReason(node: NodeInfo): String? = when {
    node.status == NodeStatus.Starting -> Strings.get(R.string.stamps_node_starting)
    node.status != NodeStatus.Running -> Strings.get(R.string.stamps_node_off)
    !node.lightMode -> Strings.get(R.string.stamps_need_light_mode)
    else -> null
}

/**
 * Why the node can't buy or extend now, or null when it can. Spending
 * is for the wallet identity only: the device-only key can't be restored
 * anywhere, so what it bought would be lost with the device.
 */
internal fun stampSpendBlockedReason(node: NodeInfo): String? = stampsBlockedReason(node) ?: when {
    !node.walletIdentity -> Strings.get(R.string.stamps_need_wallet_identity)
    else -> null
}

/** What a search for the account's own stamps found. */
internal fun discoverOutcomeText(found: Int): String = when (found) {
    0 -> Strings.get(R.string.stamps_discover_none)
    else -> Strings.plural(R.plurals.stamps_discover_found, found, found)
}

/** The line under "Find stamps you already own": the search running, or what it found. */
internal fun discoverStatusText(discovery: StampClient.Discovery): String? = when (discovery) {
    StampClient.Discovery.Idle -> null
    StampClient.Discovery.Running -> Strings.get(R.string.stamps_discover_running)
    is StampClient.Discovery.Finished -> discovery.found.fold(
        onSuccess = { ids -> discoverOutcomeText(ids.size) },
        // It ran on after the page stopped waiting, and its outcome never reached the app.
        onFailure = { if (it is StampClient.DiscoverOverran) it.message else Strings.get(R.string.stamps_discover_failed, it.message) },
    )
}

/** One line under a spend in flight, or its outcome. */
internal fun spendStatusText(spend: StampClient.Spend): String? = when (spend) {
    StampClient.Spend.Idle -> null
    is StampClient.Spend.Running -> when (spend.kind) {
        StampClient.Kind.Buy -> Strings.get(R.string.stamps_spend_running_buy)
        StampClient.Kind.Extend -> Strings.get(R.string.stamps_spend_running_extend)
        StampClient.Kind.Deposit -> Strings.get(R.string.stamps_spend_running_deposit)
        StampClient.Kind.Connect -> Strings.get(R.string.stamps_spend_running_connect)
    }
    is StampClient.Spend.Done -> when (spend.kind) {
        StampClient.Kind.Buy -> Strings.get(R.string.stamps_spend_done_buy)
        StampClient.Kind.Extend -> Strings.get(R.string.stamps_spend_done_extend)
        StampClient.Kind.Deposit -> Strings.get(R.string.stamps_spend_done_deposit)
        StampClient.Kind.Connect -> Strings.get(R.string.stamps_spend_done_connect)
    }
    is StampClient.Spend.Failed -> Strings.get(
        when (spend.kind) {
            // Outlived the page's wait (#222 R4-F1): no answer, so not a failure either.
            StampClient.Kind.Buy ->
                if (spend.noReport) R.string.stamps_spend_buy_no_report else R.string.stamps_spend_buy_failed
            StampClient.Kind.Extend -> R.string.stamps_spend_extend_failed
            StampClient.Kind.Connect ->
                if (spend.noReport) R.string.stamps_spend_connect_no_report else R.string.stamps_spend_connect_failed
            // Ended without a clear answer (#117): not a failure, it may be out.
            StampClient.Kind.Deposit ->
                if (spend.noReport) R.string.stamps_spend_deposit_no_report else R.string.stamps_spend_deposit_failed
        },
        spend.message,
    )
}

/** Why Buy and Find stamps wait: the gateway they may restart is carrying a publish. */
internal val PUBLISH_RUNNING_NOTE: String get() = Strings.get(R.string.stamps_publish_running_note)

/**
 * The postage stamps pages (#116), over the node page: the node's
 * batches, one batch's detail, buying one, extending one.
 * [startWithBuy] opens straight on the buy page (publish setup's step).
 */
@Composable
internal fun StampsScreen(nodeInfo: NodeInfo, startWithBuy: Boolean = false, onDismiss: () -> Unit) {
    // "list", "buy", "detail:<id>" or "extend:<id>".
    var route by rememberSaveable { mutableStateOf(if (startWithBuy) "buy" else "list") }
    val spend by StampClient.spend.collectAsState()
    val discoveryAny by StampClient.discovery.collectAsState()
    // A buy or extend can't start while a search for owned stamps runs, nor that during one.
    val canSpendNow = StampClient.canSpend(spend, discoveryAny)
    // A buy or search may restart the gateway, so neither starts under a publish's upload.
    val publishing by Publisher.state.collectAsState()
    val canRestartNow = StampClient.canRestartGateway(spend, discoveryAny, publishing)
    val publishingNote = PUBLISH_RUNNING_NOTE.takeIf { canSpendNow && !canRestartNow }
    // Only what was found for the account the node runs as now.
    val discovery = discoveryAny.forAccount(nodeInfo.accountAddress)
    val blocked = stampsBlockedReason(nodeInfo)
    val light = blocked == null

    // Bumped after a spend ends, so the list shows it at once.
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(spend) {
        if (spend is StampClient.Spend.Done || spend is StampClient.Spend.Failed) refresh++
    }
    LaunchedEffect(discovery) {
        if (discovery is StampClient.Discovery.Finished) refresh++
    }
    val batches by produceState<List<PostageBatch>?>(null, light, refresh) {
        while (light) {
            gatewayGet("/stamps", STAMPS_TIMEOUT_MS)?.let(::stampsFrom)?.let { value = it }
            delay(STAMPS_POLL_MS)
        }
    }
    // Which batch ant would top up: only that one can be extended here.
    val connected by produceState<String?>(null, light, refresh) {
        value = null
        if (light) {
            val a = withContext(Dispatchers.IO) { StampClient.call("status") }
            if (a is StampClient.Answer.Ok && a.json.optBoolean("enabled")) {
                value = normalizeBatchId(a.json.optString("batch_id"))
            }
        }
    }

    val back: () -> Unit = {
        when {
            route == "list" || (route == "buy" && startWithBuy) -> onDismiss()
            route.startsWith("extend:") -> route = "detail:" + route.removePrefix("extend:")
            else -> route = "list"
        }
    }
    BackHandler(onBack = back)

    val title = when {
        route == "buy" -> stringResource(R.string.stamps_title_buy)
        route.startsWith("extend:") -> stringResource(R.string.stamps_title_extend)
        route.startsWith("detail:") -> stringResource(R.string.stamps_title_detail)
        else -> stringResource(R.string.stamps_title_list)
    }
    FullScreenScaffold(title = title, onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // First, and even with the node off: a spend the node goes on
            // with after the user turned it off (it stops once the spend
            // ends) still shows its progress and outcome here.
            spendStatusText(spend)?.let { text ->
                item("spend") { SpendBanner(spend, text) }
            }
            if (blocked != null) {
                item("blocked") { MutedText(blocked) }
                return@LazyColumn
            }
            when {
                route == "buy" -> item("buy") {
                    BuyPage(nodeInfo, canRestartNow, publishingNote) { quote ->
                        if (StampClient.buy(quote)) route = "list"
                    }
                }
                route.startsWith("detail:") || route.startsWith("extend:") -> {
                    val id = route.substringAfter(':')
                    val batch = batches?.firstOrNull { it.id == id }
                    item("batch") {
                        when {
                            batch == null -> MutedText(stringResource(if (batches == null) R.string.stamps_reading else R.string.stamps_no_longer_listed))
                            route.startsWith("extend:") -> ExtendPage(nodeInfo, batch, connected, canSpendNow) { quote ->
                                if (StampClient.extend(batch.id, quote)) route = "detail:${batch.id}"
                            }
                            else -> DetailPage(nodeInfo, batch, connected, canSpendNow) { route = "extend:${batch.id}" }
                        }
                    }
                }
                else -> listPage(
                    nodeInfo, batches, canRestartNow, publishingNote, discovery,
                    onBuy = { route = "buy" },
                    onOpen = { route = "detail:${it.id}" },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.listPage(
    nodeInfo: NodeInfo,
    batches: List<PostageBatch>?,
    canBuyNow: Boolean,
    publishingNote: String?,
    discovery: StampClient.Discovery,
    onBuy: () -> Unit,
    onOpen: (PostageBatch) -> Unit,
) {
    item("intro") {
        SectionCard(title = stringResource(R.string.stamps_title_list)) {
            MutedText(stringResource(R.string.stamps_intro))
            val cantSpend = stampSpendBlockedReason(nodeInfo)
            if (cantSpend != null) {
                Spacer(Modifier.height(6.dp))
                MutedText(cantSpend)
            }
            if (cantSpend == null && publishingNote != null) {
                Spacer(Modifier.height(6.dp))
                MutedText(publishingNote)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBuy, enabled = cantSpend == null && canBuyNow) {
                Text(stringResource(R.string.stamps_buy_a_stamp))
            }
            FindOwnedStamps(nodeInfo.accountAddress, discovery, canStart = canBuyNow)
        }
    }
    when {
        batches == null -> item("loading") { MutedText(stringResource(R.string.stamps_reading)) }
        batches.isEmpty() -> item("empty") { MutedText(stringResource(R.string.stamps_none_yet)) }
        else -> items(batches, key = { it.id }) { batch -> BatchCard(batch) { onOpen(batch) } }
    }
}

/**
 * Finding the stamps this account already owns (#118): bought on another
 * device, or before Freedom was reinstalled. The node registers each one
 * that's still funded, and the list shows it. The search and its outcome
 * are [StampClient]'s, so scrolling this off or leaving the page neither
 * cancels nor forgets it — though it shows only for the [account] it
 * ran for; [canStart] is false while it or a spend runs.
 */
@Composable
private fun FindOwnedStamps(account: String, discovery: StampClient.Discovery, canStart: Boolean) {
    Spacer(Modifier.height(4.dp))
    TextButton(enabled = canStart, onClick = { StampClient.discover(account) }) { Text(stringResource(R.string.stamps_find_owned)) }
    discoverStatusText(discovery)?.let { SubLine(it) }
}

@Composable
private fun BatchCard(batch: PostageBatch, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClickLabel = stringResource(R.string.stamps_open_stamp), onClick = onClick)) {
        SectionCard(title = shortBatchId(batch.id)) {
            UsableBadge(batch.usable)
            DetailRow(stringResource(R.string.stamps_capacity), formatStampBytes(batch.capacityBytes))
            DetailRow(stringResource(R.string.stamps_used), usedText(batch))
            DetailRow(stringResource(R.string.stamps_time_left), batch.ttlSeconds?.let(::formatStampTtl) ?: stringResource(R.string.stamps_unknown))
            batch.ttlSeconds?.let { ttl ->
                SubLine(stringResource(R.string.stamps_until, expiryText(ttl)))
            }
        }
    }
}

@Composable
private fun DetailPage(
    nodeInfo: NodeInfo,
    batch: PostageBatch,
    connected: String?,
    canSpendNow: Boolean,
    onExtend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.stamps_stamp)) {
            UsableBadge(batch.usable)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stamps_batch_id),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            SelectionContainer {
                Text(batch.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            DetailRow(stringResource(R.string.stamps_capacity), formatStampBytes(batch.capacityBytes))
            SubLine(stringResource(R.string.stamps_up_to_if_even, formatStampBytes(batch.theoreticalBytes)))
            DetailRow(stringResource(R.string.stamps_used), usedText(batch))
            DetailRow(stringResource(R.string.stamps_time_left), batch.ttlSeconds?.let(::formatStampTtl) ?: stringResource(R.string.stamps_unknown))
            batch.ttlSeconds?.let { SubLine(stringResource(R.string.stamps_until, expiryText(it))) }
            DetailRow(stringResource(R.string.stamps_depth), batch.depth.toString())
            DetailRow(stringResource(R.string.stamps_immutable), stringResource(if (batch.immutable) R.string.stamps_yes else R.string.stamps_no))
        }
        val cantSpend = stampSpendBlockedReason(nodeInfo)
        val active = connected == batch.id
        SectionCard(title = stringResource(R.string.stamps_extend)) {
            MutedText(
                when {
                    cantSpend != null -> cantSpend
                    connected == null -> stringResource(R.string.stamps_checking_connected)
                    !active -> stringResource(R.string.stamps_extend_other_active)
                    else -> stringResource(R.string.stamps_extend_intro)
                },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onExtend, enabled = cantSpend == null && active && canSpendNow) {
                Text(stringResource(R.string.stamps_extend))
            }
        }
    }
}

@Composable
private fun BuyPage(nodeInfo: NodeInfo, canBuyNow: Boolean, publishingNote: String?, onConfirmed: (StampQuote) -> Unit) {
    var depth by rememberSaveable { mutableIntStateOf(DEFAULT_STAMP_DEPTH) }
    var days by rememberSaveable { mutableLongStateOf(DEFAULT_STAMP_DAYS) }
    val quote = rememberQuote(depth, days) { JSONObject().put("depth", depth).put("days", days).let { "quote" to it } }
    var confirming by remember { mutableStateOf<StampQuote?>(null) }
    val cantSpend = stampSpendBlockedReason(nodeInfo)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.stamps_size)) {
            STAMP_DEPTHS.forEach { d ->
                ChoiceRow(
                    selected = d == depth,
                    label = formatStampBytes(effectiveStampBytes(d)),
                    sub = stringResource(R.string.stamps_depth_n, d),
                ) { depth = d }
            }
        }
        SectionCard(title = stringResource(R.string.stamps_duration)) {
            STAMP_BUY_DAYS.forEach { n -> ChoiceRow(selected = n == days, label = daysLabel(n)) { days = n } }
        }
        QuoteCard(quote, deposit = true)
        (cantSpend ?: publishingNote)?.let { MutedText(it) }
        val q = (quote as? QuoteState.Ready)?.quote
        Button(
            onClick = { confirming = q },
            enabled = cantSpend == null && q != null && q.sufficientFunds && canBuyNow,
        ) { Text(stringResource(R.string.stamps_buy)) }
    }
    confirming?.let { q ->
        SpendConfirmDialog(
            title = stringResource(R.string.stamps_buy_confirm_title),
            body = q.depositBzz?.let { deposit ->
                stringResource(
                    R.string.stamps_buy_confirm_body_deposit,
                    formatStampBytes(effectiveStampBytes(q.depth)), daysLabel(q.days), withUnit(q.totalCostBzz, "xBZZ"),
                    withUnit(deposit, "xBZZ"), spendCostText(q, buy = true),
                )
            } ?: stringResource(
                R.string.stamps_buy_confirm_body,
                formatStampBytes(effectiveStampBytes(q.depth)), daysLabel(q.days), withUnit(q.totalCostBzz, "xBZZ"),
                spendCostText(q, buy = true),
            ),
            confirmLabel = stringResource(R.string.stamps_buy),
            onConfirm = {
                confirming = null
                onConfirmed(q)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun ExtendPage(
    nodeInfo: NodeInfo,
    batch: PostageBatch,
    connected: String?,
    canSpendNow: Boolean,
    onConfirmed: (StampQuote) -> Unit,
) {
    var days by rememberSaveable { mutableLongStateOf(DEFAULT_EXTEND_DAYS) }
    val quote = rememberQuote(days, batch.id) {
        JSONObject().put("batchId", batch.id).put("days", days).let { "extendQuote" to it }
    }
    var confirming by remember { mutableStateOf<StampQuote?>(null) }
    val cantSpend = stampSpendBlockedReason(nodeInfo)
        ?: if (connected != batch.id) stringResource(R.string.stamps_extend_only_active) else null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = shortBatchId(batch.id)) {
            DetailRow(stringResource(R.string.stamps_capacity), formatStampBytes(batch.capacityBytes))
            DetailRow(stringResource(R.string.stamps_time_left), batch.ttlSeconds?.let(::formatStampTtl) ?: stringResource(R.string.stamps_unknown))
        }
        SectionCard(title = stringResource(R.string.stamps_add)) {
            STAMP_EXTEND_DAYS.forEach { n -> ChoiceRow(selected = n == days, label = daysLabel(n)) { days = n } }
        }
        QuoteCard(quote, deposit = false)
        cantSpend?.let { MutedText(it) }
        val q = (quote as? QuoteState.Ready)?.quote
        Button(
            onClick = { confirming = q },
            enabled = cantSpend == null && q != null && q.sufficientFunds && canSpendNow,
        ) { Text(stringResource(R.string.stamps_extend)) }
    }
    confirming?.let { q ->
        SpendConfirmDialog(
            title = stringResource(R.string.stamps_extend_confirm_title),
            body = stringResource(
                R.string.stamps_extend_confirm_body,
                daysLabel(q.days), shortBatchId(batch.id), withUnit(q.totalCostBzz, "xBZZ"), spendCostText(q, buy = false),
            ),
            confirmLabel = stringResource(R.string.stamps_extend),
            onConfirm = {
                confirming = null
                onConfirmed(q)
            },
            onDismiss = { confirming = null },
        )
    }
}

private sealed interface QuoteState {
    data object Loading : QuoteState
    data class Ready(val quote: StampQuote) : QuoteState
    data class Failed(val message: String) : QuoteState
}

/** ant's price for the current choice, fetched again whenever it changes. */
@Composable
private fun rememberQuote(key1: Any, key2: Any, request: () -> Pair<String, JSONObject>): QuoteState {
    val state by produceState<QuoteState>(QuoteState.Loading, key1, key2) {
        value = QuoteState.Loading
        val (method, args) = request()
        value = try {
            when (val a = withContext(Dispatchers.IO) { StampClient.call(method, args) }) {
                is StampClient.Answer.Ok -> stampQuoteFrom(a.json)?.let { QuoteState.Ready(it) }
                    ?: QuoteState.Failed(Strings.get(R.string.stamps_price_unreadable))
                is StampClient.Answer.Failed -> QuoteState.Failed(a.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuoteState.Failed(Strings.get(R.string.stamps_price_unreadable))
        }
    }
    return state
}

@Composable
private fun QuoteCard(state: QuoteState, deposit: Boolean) {
    SectionCard(title = stringResource(R.string.stamps_estimated_cost)) {
        when (state) {
            QuoteState.Loading -> MutedText(stringResource(R.string.stamps_asking_price))
            is QuoteState.Failed -> MutedText(stringResource(R.string.stamps_no_price_reason, state.message))
            is QuoteState.Ready -> {
                val q = state.quote
                DetailRow(stringResource(R.string.stamps_cost), withUnit(q.totalCostBzz, "xBZZ"))
                if (deposit && q.depositBzz != null) {
                    DetailRow(stringResource(R.string.stamps_chequebook_deposit), withUnit(q.depositBzz, "xBZZ"))
                    SubLine(stringResource(R.string.stamps_deposit_first_stamp_note))
                }
                DetailRow(stringResource(R.string.stamps_node_xbzz), withUnit(q.accountBzz, "xBZZ"))
                DetailRow(stringResource(R.string.stamps_swapped_from_xdai), withUnit(q.neededBzz, "xBZZ"))
                DetailRow(stringResource(R.string.stamps_xdai_needed), withUnit(q.xdaiRequiredDisplay, "xDAI"))
                SubLine(stringResource(R.string.stamps_xdai_needed_note))
                DetailRow(stringResource(R.string.stamps_node_xdai), withUnit(q.accountXdai, "xDAI"))
                if (!q.sufficientFunds) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.stamps_send_more_xdai, withUnit(q.xdaiToSend, "xDAI")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The confirmation every spend goes through. Its buttons (and a tap
 * outside) ignore taps for the first [PromptTapGuard.SPEND_PROTECTION_MS]
 * it's on screen, so a tap meant for the page under it can't confirm;
 * confirm also drops a press begun before then or one another app's
 * window covered, and other apps' overlays are hidden while it's up
 * (#240, [protectedPress]).
 */
@Composable
internal fun SpendConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tap = rememberArmedTapGuard(Unit, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    AlertDialog(
        onDismissRequest = { if (guard.accepts()) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                ObscuredTapNotice(tap)
            }
        },
        confirmButton = {
            TextButton(
                enabled = armed,
                onClick = { if (guard.accepts()) onConfirm() },
                modifier = Modifier.protectedPress(tap),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(enabled = armed, onClick = { if (guard.accepts()) onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun SpendBanner(spend: StampClient.Spend, text: String) {
    SectionCard(
        title = when (spend) {
            is StampClient.Spend.Running -> stringResource(R.string.stamps_banner_in_progress)
            is StampClient.Spend.Failed -> stringResource(R.string.stamps_banner_failed)
            else -> stringResource(R.string.stamps_banner_done)
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            when (spend) {
                is StampClient.Spend.Running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                is StampClient.Spend.Failed -> Icon(
                    Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.stamps_cd_failed),
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.stamps_cd_done),
                    tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                if (spend !is StampClient.Spend.Running) {
                    TextButton(onClick = StampClient::acknowledge) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

@Composable
internal fun ChoiceRow(selected: Boolean, label: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            sub?.let { SubLine(it) }
        }
    }
}

@Composable
private fun UsableBadge(usable: Boolean) {
    Text(
        stringResource(if (usable) R.string.stamps_usable else R.string.stamps_not_usable_yet),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (usable) Color(0xFF22C55E) else Color(0xFFF59E0B),
    )
}

@Composable
internal fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SubLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

private fun usedText(batch: PostageBatch): String = Strings.get(R.string.stamps_used_percent, Math.round(batch.usedFraction * 100).toInt())

private fun expiryText(ttlSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(System.currentTimeMillis() + ttlSeconds * 1000))

private const val STAMPS_POLL_MS = 10_000L

/** ant reads each batch's balance from the chain for `/stamps`, giving up after 8 s. */
private const val STAMPS_TIMEOUT_MS = 15_000
