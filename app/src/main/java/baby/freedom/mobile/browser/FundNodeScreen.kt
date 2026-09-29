package baby.freedom.mobile.browser

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SwarmFundLabel
import baby.freedom.mobile.wallet.SwarmFunder
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import baby.freedom.swarm.NodeInfo
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Fund the node and buy its stamp in one transaction (#115): the wallet
 * pays SwarmNodeFunder, which swaps xDAI for xBZZ, sends the node its
 * xDAI, and buys a postage batch the node owns — iOS's
 * `fundNodeAndBuyStamp`, priced from the BZZ/WXDAI Uniswap v3 pool
 * ([SwarmFunder]). The page shows the whole quote before anything is
 * signed; the wallet's review then adds the network fee and nonce. Once
 * the call is mined, [SwarmFunding] has the node connect the batch.
 */

/** Why the page can't fund the node now, or null when it can. */
internal fun fundNodeBlockedReason(node: NodeInfo, pending: SwarmFunding.Pending?): String? =
    stampSpendBlockedReason(node) ?: when {
        fundingAddress(node) == null -> "The node isn't running as your wallet's identity."
        pending != null && !pending.mined && pending.tracked -> "A stamp your wallet is buying for the node is still going out."
        pending != null && !pending.mined -> "Connect or dismiss the stamp your wallet stopped following first."
        pending != null -> "Connect the stamp your wallet already bought for the node first."
        else -> null
    }

/** One line on what the transaction pays, for the quote and the review. */
internal fun fundNodeSummary(plan: SwarmFunder.Plan, days: Long): String =
    "${formatStampBytes(effectiveStampBytes(plan.depth))} for ${daysLabel(days)}: " +
        "swaps ${formatXdaiCeiling(plan.xdaiForSwap)} for about ${formatBzz(plan.expectedBzz)} " +
        "(at least ${formatBzz(plan.minBzz)}), buys the stamp for ${formatBzz(plan.stampCostPlur)}, and sends the node " +
        "${formatXdai(plan.xdaiForNode)} and the xBZZ the stamp doesn't use."

/**
 * Whether a Gnosis Chain read with [trust] may be acted on where a wrong
 * answer costs the user: only a proof or a quorum agreeing, or the user's
 * own RPC with no one answering otherwise, counts — as for an onchain
 * app's bytes ([OnchainApp]'s `trusted`).
 *
 * The pool's `slot0()` price sizes the swap only so (#225 R4-F2): the
 * price sets how much xDAI is swapped, while the slippage floor stays at
 * what the stamp needs, so a lone RPC claiming xBZZ is 10x dearer would
 * have the call swap 10x the xDAI and accept ~90% slippage, the surplus
 * open to a sandwich. And a funding call's reverted receipt drops its
 * record at once only so ([SwarmFunding.checkChain], #225 R5-F1).
 */
internal fun chainReadTrusted(trust: ChainTrust): Boolean = when (trust.level) {
    ChainTrust.Level.VERIFIED -> true
    ChainTrust.Level.USER_CONFIGURED -> trust.dissented.isEmpty()
    ChainTrust.Level.UNVERIFIED -> false
}

internal const val POOL_PRICE_UNVERIFIED =
    "The pool's price isn't verified (not enough RPCs agreed on it). Try again, or add an RPC of your own in Settings."

/** The pool's price, as xDAI per xBZZ to 6 significant digits. */
internal fun formatSpotPrice(sqrtPriceX96: BigInteger): String =
    SwarmFunder.spotXdaiPerBzz(sqrtPriceX96).round(java.math.MathContext(6, RoundingMode.HALF_UP)).stripTrailingZeros()
        .toPlainString() + " xDAI"

/**
 * The fund-and-buy page, over publish setup. [onOpenUrl] opens a
 * transaction's explorer page.
 */
@Composable
internal fun FundNodeScreen(nodeInfo: NodeInfo, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context) { Vault.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val sender = remember(context) { WalletSender.get(context) }
    // Read off the main thread: the first one in a process reads its file and starts the sender.
    val funding by produceState(SwarmFunding.loaded(), context) { value = SwarmFunding.load(context) }
    val rpc = remember(context) { WalletRpc(ChainDataRouter.get(context)) }
    val chains by remember(context) { ChainStore.get(context).chains }.collectAsState(initial = null)
    val chain: Chain = chains?.firstOrNull { it.id == SwarmFunder.CHAIN_ID } ?: BuiltInChains.GNOSIS
    val accountList by remember(context) { WalletAccounts.get(context).accounts }.collectAsState()
    val payer: WalletAccount? = accountList?.active
    val vaultState by vault.state.collectAsState()
    val phraseBackedUp = when (val s = vaultState) {
        is Vault.State.Locked -> s.info.backedUp
        is Vault.State.Unlocked -> s.info.backedUp
        else -> true
    }
    val sendStatus by sender.status.collectAsState()
    val pending = funding?.pending?.collectAsState()?.value
    val superseded = funding?.superseded?.collectAsState()?.value
    val connectOwed = funding?.connectOwed?.collectAsState()?.value
    val spend by StampClient.spend.collectAsState()
    val node = fundingAddress(nodeInfo)

    var depth by rememberSaveable { mutableIntStateOf(STAMP_DEPTHS.first()) }
    var days by rememberSaveable { mutableLongStateOf(STAMP_BUY_DAYS.first()) }
    var refresh by remember { mutableIntStateOf(0) }
    var quote by remember { mutableStateOf<SendQuote?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Until the record's been read, a stamp already on its way can't be ruled out.
    val blocked = if (funding == null) "Checking for a stamp your wallet already bought…" else fundNodeBlockedReason(nodeInfo, pending)

    // The node's price for the batch (ant's quote: what a buy would pay per
    // chunk, and the chequebook deposit a first one makes), and the pool's
    // price, read together whenever the choice changes.
    val priced by produceState<Priced>(Priced.Loading, depth, days, refresh, stampsBlockedReason(nodeInfo) == null) {
        value = Priced.Loading
        if (stampsBlockedReason(nodeInfo) != null) return@produceState
        value = try {
            val a = withContext(Dispatchers.IO) {
                StampClient.call("quote", JSONObject().put("depth", depth).put("days", days))
            }
            val q = when (a) {
                is StampClient.Answer.Ok -> stampQuoteFrom(a.json) ?: throw SendException("The node's price couldn't be read")
                is StampClient.Answer.Failed -> throw SendException(a.message)
            }
            val slot0 = rpc.call(SwarmFunder.CHAIN_ID, JSONObject().put("to", SwarmFunder.POOL).put("data", SwarmFunder.SLOT0_DATA))
            if (!chainReadTrusted(slot0.trust)) {
                Log.i(TAG, "pool price not verified (${slot0.trust.level.name.lowercase()}, ${slot0.trust.dissented.size} dissented)")
                throw SendException(POOL_PRICE_UNVERIFIED)
            }
            val sqrt = SwarmFunder.sqrtPriceFrom(slot0.value) ?: throw SendException("The pool's price couldn't be read")
            Priced.Ready(q, sqrt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendException) {
            Priced.Failed(e.message ?: "No price right now")
        } catch (e: ChainRpcException) {
            Priced.Failed(WalletSender.readFailure(e))
        } catch (e: Exception) {
            Log.i(TAG, "pricing failed (${e.javaClass.simpleName})")
            Priced.Failed("No price right now")
        }
    }
    val payerBalance by produceState<BigInteger?>(null, payer?.address, refresh) {
        value = null
        val a = payer?.address ?: return@produceState
        value = runCatching { rpc.balance(SwarmFunder.CHAIN_ID, a).value }.getOrNull()
    }
    // A fresh batch nonce for each pricing: a batch id is used once.
    val nonce = remember(depth, days, refresh) { SwarmFunder.newNonce() }
    val plan = (priced as? Priced.Ready)?.let { p ->
        node?.let { n -> SwarmFunder.Plan(n, depth, p.quote.amountPerChunk, nonce, p.quote.depositPlur, p.sqrtPriceX96) }
    }

    fun review(p: SwarmFunder.Plan) {
        val from = payer ?: return
        busy = true
        error = null
        scope.launch {
            try {
                quote = sender.prepare(
                    SendRequest(
                        chain, TokenRegistry.native(chain), from, SwarmFunder.ADDRESS, p.value,
                        DappCall(null, p.calldata(), null, swarm = SwarmFundLabel(p.node, p.batchId, p.depth, days)),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = safeErrorMessage(e, "price the transaction", phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    val back: () -> Unit = {
        if (quote != null) {
            quote = null
            notice = null
        } else {
            onDismiss()
        }
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = "Fund and buy a stamp", onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val status = sendStatus?.takeIf { it.quote.request.dapp?.swarm != null }
            val q = quote
            item("intro") {
                SectionCard(title = "One transaction") {
                    MutedText(
                        "Your wallet pays for everything at once: it swaps xDAI for xBZZ, buys a postage stamp " +
                            "that belongs to the node, and sends the node xDAI for its own transactions — through " +
                            "the SwarmNodeFunder contract, as Freedom on desktop and iOS do. Once it's mined, the " +
                            "node connects the stamp and, the first time, sets up its chequebook.",
                    )
                }
            }
            val f = funding
            if (pending != null && f != null) {
                item("pending") {
                    PendingStampCard(pending, nodeInfo, spend, superseded == pending.batchId, connectOwed == pending.batchId, onConnect = { f.connectNow() }, onForget = f::forget)
                }
            }
            when {
                status != null -> item("send") {
                    SendStatusSection(
                        status = status,
                        onOpenUrl = onOpenUrl,
                        onRetry = sender::retry,
                        onCheckAgain = sender::checkAgain,
                        onStopTracking = sender::discard,
                        onReviewAgain = {},
                        onDone = {
                            if (!status.unresolved) sender.acknowledge()
                            refresh++
                        },
                    )
                }
                q != null && plan != null -> item("review") {
                    FundReview(
                        quote = q,
                        summary = fundNodeSummary(plan, days),
                        node = plan.node,
                        busy = busy,
                        notice = notice,
                        error = error,
                        onCancel = {
                            quote = null
                            notice = null
                            error = null
                        },
                        onConfirm = {
                            if (sender.isStale(q)) {
                                // Price the pool and the fee again: both can have moved.
                                quote = null
                                refresh++
                                notice = "The quote was over a minute old, so it's been priced again. Check it and review again."
                                return@FundReview
                            }
                            busy = true
                            scope.launch {
                                try {
                                    if (!q.request.from.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                    when (sender.submit(q, WalletSender.signerFor(context, vault, q.request.from) { !sender.isStale(q) })) {
                                        WalletSender.Submit.STARTED -> {
                                            quote = null
                                            notice = null
                                        }
                                        WalletSender.Submit.BUSY -> error = "Another send is still going out, or may have. " +
                                            "Settle it (or stop tracking it) on the wallet's Send page first."
                                        WalletSender.Submit.STALE -> {
                                            quote = null
                                            refresh++
                                            notice = "The quote was over a minute old, so it's been priced again. Check it and review again."
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = safeErrorMessage(e, "unlock the wallet", phraseBackedUp)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
                else -> {
                    item("size") {
                        SectionCard(title = "Stamp size") {
                            STAMP_DEPTHS.forEach { d ->
                                ChoiceRow(selected = d == depth, label = formatStampBytes(effectiveStampBytes(d)), sub = "Depth $d") { depth = d }
                            }
                        }
                    }
                    item("duration") {
                        SectionCard(title = "Duration") {
                            STAMP_BUY_DAYS.forEach { n -> ChoiceRow(selected = n == days, label = daysLabel(n)) { days = n } }
                        }
                    }
                    item("quote") {
                        FundQuoteCard(priced, plan, days, payer, payerBalance, chain)
                    }
                    item("act") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            notice?.let { MutedText(it) }
                            (blocked ?: if (payer == null) "Set up a wallet first." else null)?.let { MutedText(it) }
                            error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = { plan?.let(::review) },
                                enabled = blocked == null && payer != null && plan != null && !busy && sendStatus?.inFlight != true,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Review")
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Priced {
    data object Loading : Priced
    data class Ready(val quote: StampQuote, val sqrtPriceX96: BigInteger) : Priced
    data class Failed(val message: String) : Priced
}

@Composable
private fun FundQuoteCard(
    priced: Priced,
    plan: SwarmFunder.Plan?,
    days: Long,
    payer: WalletAccount?,
    payerBalance: BigInteger?,
    chain: Chain,
) {
    SectionCard(title = "Quote") {
        when {
            priced is Priced.Loading -> MutedText("Asking the node and the pool for today's prices…")
            priced is Priced.Failed -> MutedText("No price right now: ${priced.message}")
            plan == null -> MutedText("The node isn't running as your wallet's identity.")
            priced is Priced.Ready -> {
                DetailRow("Stamp", "${formatStampBytes(effectiveStampBytes(plan.depth))}, ${daysLabel(days)}")
                DetailRow("Stamp cost", formatBzz(plan.stampCostPlur))
                if (plan.depositPlur.signum() > 0) {
                    DetailRow("Chequebook deposit", formatBzz(plan.depositPlur))
                    SubLine("The node moves this into its new chequebook when it connects the stamp.")
                }
                DetailRow("Pool price", "1 xBZZ = ${formatSpotPrice(plan.sqrtPriceX96)}")
                SubLine("Uniswap v3 BZZ/WXDAI, 0.3% fee")
                DetailRow("Swap", formatXdaiCeiling(plan.xdaiForSwap))
                SubLine("For about ${formatBzz(plan.expectedBzz)}; at least ${formatBzz(plan.minBzz)}, or it reverts (5% slippage)")
                DetailRow("To the node", formatXdai(plan.xdaiForNode))
                SubLine("Gas for its chequebook and its own transactions; unused xBZZ goes to the node too")
                DetailRow("Total", formatXdaiCeiling(plan.value))
                SubLine("Plus the network fee, shown in the review")
                payer?.let { p ->
                    DetailRow("Paid by", p.name)
                    SubLine(
                        "Holds " + (payerBalance?.let(::formatXdai) ?: "…") +
                            if (payerBalance != null && payerBalance < plan.value) " — not enough" else "",
                    )
                }
                SubLine("On ${chain.name}")
            }
        }
    }
}

@Composable
private fun FundReview(
    quote: SendQuote,
    summary: String,
    node: String,
    busy: Boolean,
    notice: String?,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val guard = remember(quote) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(quote) { mutableStateOf(false) }
    LaunchedEffect(quote) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }
    Column {
        SectionCard(title = "Review") {
            ReviewRow("What", summary)
            ReviewRow("Node", null, address = node)
            ReviewRow("Network", chain.name)
            ReviewRow("Paid by", request.from.name, address = request.from.address)
            ReviewRow("Contract", "SwarmNodeFunder", address = request.to)
            ReviewRow("Amount", "${SendAmounts.exact(request.amount, request.token.decimals)} ${request.token.symbol}", mono = true)
            ReviewRow("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
            ReviewRow("Nonce", quote.tx.nonce.toString(), detail = nonceDetail(quote))
            Spacer(Modifier.height(4.dp))
            Text(
                "A real transaction on Gnosis Chain that can't be undone. Only the fee the network actually charges " +
                    "is paid; if the pool can't give at least the xBZZ above, it reverts and only that fee is lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        notice?.let { MutedText(it) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { if (guard.accepts()) onConfirm() }, enabled = armed && !busy, modifier = Modifier.weight(1f)) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Confirm and send")
            }
        }
    }
}

/**
 * The pending stamp card's explanation. [superseded]: the chain shows its
 * call can never be mined ([SwarmFunding.superseded]) — the only case in
 * which an unmined record is certain to have bought nothing. [owed]:
 * the app is to connect it itself once the node is free
 * ([SwarmFunding.connectOwed]); otherwise a mined one waits for Connect.
 */
internal fun pendingStampText(
    p: SwarmFunding.Pending,
    nodeInfo: NodeInfo,
    spend: StampClient.Spend,
    superseded: Boolean,
    owed: Boolean = false,
): String {
    val connecting = spend is StampClient.Spend.Running && spend.kind == StampClient.Kind.Connect && spend.batchId == p.batchId
    val failed = (spend as? StampClient.Spend.Failed)?.takeIf { it.kind == StampClient.Kind.Connect && it.batchId == p.batchId }
    val otherNode = fundingAddress(nodeInfo)?.equals(p.node, ignoreCase = true) == false
    // Unmined and not followed by the wallet: say what the chain shows, and what Dismiss would lose.
    val untracked = when {
        superseded -> "Its transaction can never be mined: the paying account's nonce went to another transaction. " +
            "There's no stamp, so dismiss it."
        p.hash != null -> "It isn't mined yet as far as Gnosis Chain shows. The app keeps checking its transaction " +
            "and connects the stamp if it lands. Dismissing forgets the batch: a stamp that lands afterwards " +
            "can't be connected."
        else -> "It may or may not have been mined. If it was, Connect adds the stamp to the node. Dismissing " +
            "forgets the batch: a stamp that lands afterwards can't be connected."
    }
    return when {
        !p.mined && p.tracked -> "Your wallet's transaction is going out; the node connects the stamp once it's mined."
        connecting -> "The node is connecting it…"
        otherNode -> "It was bought for another node account (${p.node}), which has to be running to connect it."
        failed != null && p.mined -> "Connecting it failed: ${failed.message}"
        failed != null -> "Connecting it failed: ${failed.message} $untracked"
        !p.mined -> "Your wallet stopped following its transaction. $untracked"
        owed -> "Mined. The node connects it to publish with it as soon as the stamp work or upload " +
            "it's busy with ends."
        else -> "Mined. Connect adds it to the node to publish with it" +
            (stampsBlockedReason(nodeInfo)?.let { " once it can: $it" } ?: ".")
    }
}

/**
 * The stamp the wallet bought (or is buying) for the node: still going
 * out, connecting, or mined and waiting to be connected — with Connect
 * when the node can, and a way to stop offering it.
 */
@Composable
internal fun PendingStampCard(
    p: SwarmFunding.Pending,
    nodeInfo: NodeInfo,
    spend: StampClient.Spend,
    superseded: Boolean,
    owed: Boolean,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    val connecting = spend is StampClient.Spend.Running && spend.kind == StampClient.Kind.Connect && spend.batchId == p.batchId
    val otherNode = fundingAddress(nodeInfo)?.equals(p.node, ignoreCase = true) == false
    val discovery by StampClient.discovery.collectAsState()
    val publishing by Publisher.state.collectAsState()
    SectionCard(title = "Stamp from your wallet") {
        DetailRow("Stamp", "${formatStampBytes(effectiveStampBytes(p.depth))}, ${daysLabel(p.days)}")
        DetailRow("Batch", shortBatchId(p.batchId), mono = true)
        MutedText(pendingStampText(p, nodeInfo, spend, superseded, owed))
        if (p.mined || !p.tracked) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    // Not over a search or an upload: a connect may reload the gateway.
                    enabled = StampClient.canRestartGateway(spend, discovery, publishing) &&
                        !otherNode && stampSpendBlockedReason(nodeInfo) == null,
                ) { Text("Connect") }
                TextButton(onClick = onForget, enabled = !connecting) { Text("Dismiss") }
            }
        }
    }
}

private const val TAG = "FundNode"
