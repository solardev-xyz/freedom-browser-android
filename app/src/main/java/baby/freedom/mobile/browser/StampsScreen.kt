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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
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
    node.status == NodeStatus.Starting -> "The Swarm node is starting…"
    node.status != NodeStatus.Running -> "Turn on the Swarm node to see its postage stamps."
    !node.lightMode -> "Postage stamps need light mode, which connects the node to Gnosis Chain. " +
        "Switch it on under Publishing on the node page."
    else -> null
}

/**
 * Why the node can't buy or extend now, or null when it can. Spending
 * is for the wallet identity only: the device-only key can't be restored
 * anywhere, so what it bought would be lost with the device.
 */
internal fun stampSpendBlockedReason(node: NodeInfo): String? = stampsBlockedReason(node) ?: when {
    !node.walletIdentity -> "Set up a wallet first, so the node runs as your wallet's identity " +
        "(publish setup, step 1)."
    else -> null
}

/** What a search for the account's own stamps found. */
internal fun discoverOutcomeText(found: Int): String = when (found) {
    0 -> "No other stamps: this account owns none that are still paid for."
    1 -> "Found 1 stamp this account owns; it's in the list below."
    else -> "Found $found stamps this account owns; they're in the list below."
}

/** The line under "Find stamps you already own": the search running, or what it found. */
internal fun discoverStatusText(discovery: StampClient.Discovery): String? = when (discovery) {
    StampClient.Discovery.Idle -> null
    StampClient.Discovery.Running -> "Searching Gnosis Chain for stamps this account bought…"
    is StampClient.Discovery.Finished -> discovery.found.fold(
        onSuccess = { ids -> discoverOutcomeText(ids.size) },
        // It ran on after the page stopped waiting, and its outcome never reached the app.
        onFailure = { if (it.message == StampClient.DISCOVER_OVERRAN) it.message else "Couldn't look: ${it.message}" },
    )
}

/** One line under a spend in flight, or its outcome. */
internal fun spendStatusText(spend: StampClient.Spend): String? = when (spend) {
    StampClient.Spend.Idle -> null
    is StampClient.Spend.Running -> when (spend.kind) {
        StampClient.Kind.Buy -> "Buying the stamp… The node swaps xDAI for the xBZZ it needs, buys the stamp, and waits for " +
            "each transaction to confirm on Gnosis Chain. This takes a minute or two."
        StampClient.Kind.Extend -> "Extending the stamp… The node swaps xDAI for the xBZZ it needs, tops the stamp up, and " +
            "waits for each transaction to confirm. This takes a minute or two."
        StampClient.Kind.Deposit -> "Depositing… The node moves the xBZZ into its chequebook and waits for the " +
            "transaction to confirm on Gnosis Chain. This takes up to a minute."
        StampClient.Kind.Connect -> "Connecting the stamp your wallet bought… The node checks it on Gnosis Chain and, " +
            "the first time, sets up its chequebook. This takes up to a few minutes."
    }
    is StampClient.Spend.Done -> when (spend.kind) {
        StampClient.Kind.Buy -> "Stamp bought. It becomes usable once the network has seen it, usually within a minute."
        StampClient.Kind.Extend -> "Stamp extended."
        StampClient.Kind.Deposit -> "Deposited into the chequebook."
        StampClient.Kind.Connect -> "Stamp connected: the node publishes with it."
    }
    is StampClient.Spend.Failed -> when (spend.kind) {
        // Outlived the page's wait (#222 R4-F1): no answer, so not a failure either.
        StampClient.Kind.Buy ->
            if (spend.message == StampClient.BUY_OVERRAN) "The stamp purchase didn't report back: " else "Buying the stamp failed: "
        StampClient.Kind.Extend -> "Extending the stamp failed: "
        StampClient.Kind.Connect ->
            if (spend.message == StampClient.CONNECT_OVERRAN) "Connecting the stamp didn't report back: " else "Connecting the stamp your wallet bought failed: "
        // Ended without a clear answer (#117): not a failure, it may be out.
        StampClient.Kind.Deposit ->
            if (spend.message.startsWith(SwarmNode.DEPOSIT_MAYBE_SENT)) "The deposit didn't report back: " else "The deposit failed: "
    } + spend.message
}

/** Why Buy and Find stamps wait: the gateway they may restart is carrying a publish. */
internal const val PUBLISH_RUNNING_NOTE = "A publish is uploading. Buy or search for stamps once it has finished."

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
        route == "buy" -> "Buy a postage stamp"
        route.startsWith("extend:") -> "Extend stamp"
        route.startsWith("detail:") -> "Postage stamp"
        else -> "Postage stamps"
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
                            batch == null -> MutedText(if (batches == null) "Reading the node's stamps…" else "The node no longer lists this stamp.")
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
        SectionCard(title = "Postage stamps") {
            MutedText(
                "A postage stamp pre-pays the Swarm network for storing what you publish: a capacity, " +
                    "for a time. The node pays for stamps from its own account on Gnosis Chain.",
            )
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
                Text("Buy a stamp")
            }
            FindOwnedStamps(nodeInfo.accountAddress, discovery, canStart = canBuyNow)
        }
    }
    when {
        batches == null -> item("loading") { MutedText("Reading the node's stamps…") }
        batches.isEmpty() -> item("empty") { MutedText("No stamps yet.") }
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
    TextButton(enabled = canStart, onClick = { StampClient.discover(account) }) { Text("Find stamps you already own") }
    discoverStatusText(discovery)?.let { SubLine(it) }
}

@Composable
private fun BatchCard(batch: PostageBatch, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClickLabel = "Open stamp", onClick = onClick)) {
        SectionCard(title = shortBatchId(batch.id)) {
            UsableBadge(batch.usable)
            DetailRow("Capacity", formatStampBytes(batch.capacityBytes))
            DetailRow("Used", usedText(batch))
            DetailRow("Time left", batch.ttlSeconds?.let(::formatStampTtl) ?: "Unknown")
            batch.ttlSeconds?.let { ttl ->
                SubLine("Until ${expiryText(ttl)}")
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
        SectionCard(title = "Stamp") {
            UsableBadge(batch.usable)
            Spacer(Modifier.height(4.dp))
            Text(
                "Batch ID",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            SelectionContainer {
                Text(batch.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            DetailRow("Capacity", formatStampBytes(batch.capacityBytes))
            SubLine("Up to ${formatStampBytes(batch.theoreticalBytes)} if every bucket fills evenly")
            DetailRow("Used", usedText(batch))
            DetailRow("Time left", batch.ttlSeconds?.let(::formatStampTtl) ?: "Unknown")
            batch.ttlSeconds?.let { SubLine("Until ${expiryText(it)}") }
            DetailRow("Depth", batch.depth.toString())
            DetailRow("Immutable", if (batch.immutable) "Yes" else "No")
        }
        val cantSpend = stampSpendBlockedReason(nodeInfo)
        val active = connected == batch.id
        SectionCard(title = "Extend") {
            MutedText(
                when {
                    cantSpend != null -> cantSpend
                    connected == null -> "Checking which stamp the node uploads with…"
                    !active -> "Only the stamp the node uploads with can be extended in this version of Freedom, " +
                        "and that's another one."
                    else -> "Add time to this stamp before it runs out: once it expires, what was " +
                        "published with it is no longer paid for."
                },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onExtend, enabled = cantSpend == null && active && canSpendNow) {
                Text("Extend")
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
        SectionCard(title = "Size") {
            STAMP_DEPTHS.forEach { d ->
                ChoiceRow(
                    selected = d == depth,
                    label = formatStampBytes(effectiveStampBytes(d)),
                    sub = "Depth $d",
                ) { depth = d }
            }
        }
        SectionCard(title = "Duration") {
            STAMP_BUY_DAYS.forEach { n -> ChoiceRow(selected = n == days, label = daysLabel(n)) { days = n } }
        }
        QuoteCard(quote, deposit = true)
        (cantSpend ?: publishingNote)?.let { MutedText(it) }
        val q = (quote as? QuoteState.Ready)?.quote
        Button(
            onClick = { confirming = q },
            enabled = cantSpend == null && q != null && q.sufficientFunds && canBuyNow,
        ) { Text("Buy") }
    }
    confirming?.let { q ->
        SpendConfirmDialog(
            title = "Buy this postage stamp?",
            body = "${formatStampBytes(effectiveStampBytes(q.depth))} for ${daysLabel(q.days)}, for " +
                withUnit(q.totalCostBzz, "xBZZ") +
                (q.depositBzz?.let { ", plus ${withUnit(it, "xBZZ")} into the node's new chequebook" } ?: "") +
                ". " + spendCostText(q, buy = true) + " These are real transactions on Gnosis Chain " +
                "and can't be undone.",
            confirmLabel = "Buy",
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
        ?: if (connected != batch.id) "Only the stamp the node uploads with can be extended in this version of Freedom." else null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = shortBatchId(batch.id)) {
            DetailRow("Capacity", formatStampBytes(batch.capacityBytes))
            DetailRow("Time left", batch.ttlSeconds?.let(::formatStampTtl) ?: "Unknown")
        }
        SectionCard(title = "Add") {
            STAMP_EXTEND_DAYS.forEach { n -> ChoiceRow(selected = n == days, label = daysLabel(n)) { days = n } }
        }
        QuoteCard(quote, deposit = false)
        cantSpend?.let { MutedText(it) }
        val q = (quote as? QuoteState.Ready)?.quote
        Button(
            onClick = { confirming = q },
            enabled = cantSpend == null && q != null && q.sufficientFunds && canSpendNow,
        ) { Text("Extend") }
    }
    confirming?.let { q ->
        SpendConfirmDialog(
            title = "Extend this postage stamp?",
            body = "${daysLabel(q.days)} more for ${shortBatchId(batch.id)}, for ${withUnit(q.totalCostBzz, "xBZZ")}. " +
                spendCostText(q, buy = false) + " These are real transactions on Gnosis Chain " +
                "and can't be undone.",
            confirmLabel = "Extend",
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
                    ?: QuoteState.Failed("The node's price couldn't be read")
                is StampClient.Answer.Failed -> QuoteState.Failed(a.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuoteState.Failed("The node's price couldn't be read")
        }
    }
    return state
}

@Composable
private fun QuoteCard(state: QuoteState, deposit: Boolean) {
    SectionCard(title = "Estimated cost") {
        when (state) {
            QuoteState.Loading -> MutedText("Asking the node for today's price…")
            is QuoteState.Failed -> MutedText("No price right now: ${state.message}")
            is QuoteState.Ready -> {
                val q = state.quote
                DetailRow("Cost", withUnit(q.totalCostBzz, "xBZZ"))
                if (deposit && q.depositBzz != null) {
                    DetailRow("Chequebook deposit", withUnit(q.depositBzz, "xBZZ"))
                    SubLine("Once, with the first stamp: backs what the node pays other nodes for storing your data.")
                }
                DetailRow("Node's xBZZ", withUnit(q.accountBzz, "xBZZ"))
                DetailRow("Swapped from xDAI", withUnit(q.neededBzz, "xBZZ"))
                DetailRow("xDAI needed", withUnit(q.xdaiRequiredDisplay, "xDAI"))
                SubLine("The swap plus gas. What isn't used stays in the node's account.")
                DetailRow("Node's xDAI", withUnit(q.accountXdai, "xDAI"))
                if (!q.sufficientFunds) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Send ${withUnit(q.xdaiToSend, "xDAI")} more to the node's address first (publish setup, step 3).",
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
            TextButton(enabled = armed, onClick = { if (guard.accepts()) onDismiss() }) { Text("Cancel") }
        },
    )
}

@Composable
internal fun SpendBanner(spend: StampClient.Spend, text: String) {
    SectionCard(
        title = when (spend) {
            is StampClient.Spend.Running -> "In progress"
            is StampClient.Spend.Failed -> "Didn't finish"
            else -> "Done"
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            when (spend) {
                is StampClient.Spend.Running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                is StampClient.Spend.Failed -> Icon(
                    Icons.Filled.ErrorOutline, contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Done",
                    tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                if (spend !is StampClient.Spend.Running) {
                    TextButton(onClick = StampClient::acknowledge) { Text("OK") }
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
        if (usable) "Usable" else "Not usable yet",
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

private fun usedText(batch: PostageBatch): String = "${Math.round(batch.usedFraction * 100)}%"

private fun expiryText(ttlSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(System.currentTimeMillis() + ttlSeconds * 1000))

private const val STAMPS_POLL_MS = 10_000L

/** ant reads each batch's balance from the chain for `/stamps`, giving up after 8 s. */
private const val STAMPS_TIMEOUT_MS = 15_000
