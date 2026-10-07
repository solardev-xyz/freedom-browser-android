package baby.freedom.mobile.browser

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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.l10n.Said
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SigningHeldException
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
import kotlinx.coroutines.flow.StateFlow
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
        fundingAddress(node) == null -> Strings.get(R.string.stamps_fund_not_wallet_identity)
        pending != null && !pending.mined && pending.tracked -> Strings.get(R.string.stamps_fund_pending_going_out)
        pending != null && !pending.mined -> Strings.get(R.string.stamps_fund_pending_untracked)
        pending != null -> Strings.get(R.string.stamps_fund_pending_mined)
        else -> null
    }

/** One line on what the transaction pays, for the quote and the review. */
internal fun fundNodeSummary(plan: SwarmFunder.Plan, days: Long): String =
    Strings.get(
        R.string.stamps_fund_summary,
        formatStampBytes(effectiveStampBytes(plan.depth)), daysLabel(days),
        formatXdaiCeiling(plan.xdaiForSwap), formatBzz(plan.expectedBzz), formatBzz(plan.minBzz),
        formatBzz(plan.stampCostPlur), formatXdai(plan.xdaiForNode),
    )

/** What the fund-node review shows beside the quote's own rows: [fundNodeSummary] and the node paid. */
internal data class FundReviewRows(val summary: String, val node: String)

/**
 * The review's summary and Node row for [quote], from the [plan] and [days]
 * it was built from — or null when [quote] doesn't carry exactly that plan's
 * value, call data and label, so a plan priced again during the review (or
 * a node that restarted as another identity) can never describe the
 * transaction being signed (#242, audit #229).
 */
internal fun fundReviewRows(quote: SendQuote, plan: SwarmFunder.Plan, days: Long): FundReviewRows? {
    val request = quote.request
    val data = plan.calldata()
    val matches = request.to.equals(SwarmFunder.ADDRESS, ignoreCase = true) &&
        quote.tx.to.equals(SwarmFunder.ADDRESS, ignoreCase = true) &&
        request.amount == plan.value && quote.tx.value == plan.value &&
        request.dapp?.data?.contentEquals(data) == true && quote.tx.data.contentEquals(data) &&
        request.dapp.swarm == SwarmFundLabel(plan.node, plan.batchId, plan.depth, days)
    return if (matches) FundReviewRows(fundNodeSummary(plan, days), plan.node) else null
}

/**
 * Why an open review's Confirm is held, or null: funding is [blocked] now,
 * or the running node's funding address ([running]) is no longer the node
 * the review pays ([reviewNode]). Read at render, at the tap, and again once
 * the wallet is unlocked, since the node can change while the prompt is up.
 */
internal fun fundReviewHeld(blocked: String?, running: String?, reviewNode: String): String? =
    blocked ?: if (running?.equals(reviewNode, ignoreCase = true) != true) FUND_REVIEW_NODE_CHANGED else null

/**
 * [fundReviewHeld] for a send already under way, read from the live [node]
 * and [pending] record (null [node]: the page is gone, so neither can be
 * watched any more). The record this send itself starts — [SwarmFunding]
 * notes it the moment the send shows Signing — is the send, not a reason to
 * hold it, so a record for the review's own [batchId] is left out; any other
 * still holds, as at the tap.
 */
internal fun fundSendHeld(node: NodeInfo?, pending: SwarmFunding.Pending?, batchId: String, reviewNode: String): String? {
    node ?: return FUND_PAGE_CLOSED
    val other = pending?.takeUnless { it.batchId.equals(batchId, ignoreCase = true) }
    return fundReviewHeld(fundNodeBlockedReason(node, other), fundingAddress(node), reviewNode)
}

/**
 * The node as `:node` last reported it, for a send's coroutines and the
 * Ledger's ready check to read after the review that started them is gone
 * (#291 R3-F1): kept at page level, not in the review's lazy item, and
 * null once the page itself is [closed][close].
 *
 * Read straight from [source] ([StampClient.node], which `:node`'s
 * callback moves while the Activity is stopped too), never from the
 * page's `nodeInfo` parameter: that only moves on recomposition, which
 * pauses in the background, so a payer who backgrounds the app while the
 * Ledger connects would be checked against the node as it was when they
 * left (#291 R4-M1).
 */
internal class FundPageNode(private val source: StateFlow<NodeInfo>) {
    @Volatile
    private var open = true

    val info: NodeInfo? get() = if (open) source.value else null

    fun close() {
        open = false
    }

    /**
     * The hold a send of [batchId] to [reviewNode] is under right now: the
     * live node and [records]' live pending entry, read on every call —
     * nothing here is the review item's state, which is disposed the moment
     * the send starts.
     */
    fun heldNow(records: StateFlow<SwarmFunding.Pending?>?, batchId: String, reviewNode: String): () -> String? = {
        fundSendHeld(info, records?.value, batchId, reviewNode)
    }
}

/**
 * The Ledger's [fresh][WalletSender.signerFor] check for a Fund node review:
 * asked once the device is connected and unlocked (up to ~90 s after the
 * tap), before it shows the transaction. The node can restart as another
 * account, or funding be blocked, in that wait too, so a [held] reason ends
 * it with [SigningHeldException] naming it; else the quote must not be [stale].
 */
internal fun fundLedgerFresh(held: () -> String?, stale: () -> Boolean): () -> Boolean = {
    held()?.let { throw SigningHeldException(it) }
    !stale()
}

internal val FUND_PAGE_CLOSED: String get() = Strings.get(R.string.stamps_fund_page_closed)

internal val FUND_REVIEW_NODE_CHANGED: String get() = Strings.get(R.string.stamps_fund_review_node_changed)

/** An open review: the [quote] and the [plan] and [days] it was built from, kept together (#242). */
private class FundReviewing(val plan: SwarmFunder.Plan, val days: Long, val quote: SendQuote)

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
        is Vault.State.Locked -> s.info.phraseKnown
        is Vault.State.Unlocked -> s.info.phraseKnown
        else -> true
    }
    val sendStatus by sender.status.collectAsState()
    val pending = funding?.pending?.collectAsState()?.value
    val superseded = funding?.superseded?.collectAsState()?.value
    val connectOwed = funding?.connectOwed?.collectAsState()?.value
    val spend by StampClient.spend.collectAsState()
    val node = fundingAddress(nodeInfo)
    // Outlives the review item, which is gone as soon as a send starts.
    val liveNode = remember { FundPageNode(StampClient.node) }
    DisposableEffect(liveNode) { onDispose { liveNode.close() } }

    // The same size and duration Buy opens on (#425, W41).
    var depth by rememberSaveable { mutableIntStateOf(DEFAULT_STORAGE_CHOICE.depth) }
    var days by rememberSaveable { mutableLongStateOf(DEFAULT_STORAGE_CHOICE.days) }
    var refresh by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf<FundReviewing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Until the record's been read, a stamp already on its way can't be ruled out.
    val blocked = if (funding == null) stringResource(R.string.stamps_fund_checking_pending) else fundNodeBlockedReason(nodeInfo, pending)

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
                is StampClient.Answer.Ok -> stampQuoteFrom(a.json) ?: throw SendException(Strings.said(R.string.stamps_price_unreadable))
                is StampClient.Answer.Failed -> throw SendException(Said.of(a.message))
            }
            val slot0 = rpc.call(SwarmFunder.CHAIN_ID, JSONObject().put("to", SwarmFunder.POOL).put("data", SwarmFunder.SLOT0_DATA))
            if (!chainReadTrusted(slot0.trust)) {
                Log.i(TAG, "pool price not verified (${slot0.trust.level.name.lowercase()}, ${slot0.trust.dissented.size} dissented)")
                throw SendException(Strings.said(R.string.stamps_pool_price_unverified))
            }
            val sqrt = SwarmFunder.sqrtPriceFrom(slot0.value) ?: throw SendException(Strings.said(R.string.stamps_pool_price_unreadable))
            Priced.Ready(q, sqrt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendException) {
            Priced.Failed(e.message ?: Strings.get(R.string.stamps_no_price))
        } catch (e: ChainRpcException) {
            Priced.Failed(WalletSender.readFailure(e))
        } catch (e: Exception) {
            Log.i(TAG, "pricing failed (${e.javaClass.simpleName})")
            Priced.Failed(Strings.get(R.string.stamps_no_price))
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
        // The duration as tapped: the review describes the quote built now, whatever the page does meanwhile.
        val d = days
        busy = true
        error = null
        scope.launch {
            try {
                val q = sender.prepare(
                    SendRequest(
                        chain, TokenRegistry.native(chain), from, SwarmFunder.ADDRESS, p.value,
                        DappCall(null, p.calldata(), null, swarm = SwarmFundLabel(p.node, p.batchId, p.depth, d)),
                    ),
                )
                reviewing = FundReviewing(p, d, q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = safeErrorMessage(e, Strings.get(R.string.stamps_action_price_transaction), phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    val back: () -> Unit = {
        if (reviewing != null) {
            reviewing = null
            notice = null
        } else {
            onDismiss()
        }
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = stringResource(R.string.stamps_fund_title), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val status = sendStatus?.takeIf { it.quote.request.dapp?.swarm != null }
            val r = reviewing
            // From the plan the quote was built from, never the one priced live (#242).
            val rows = r?.let { fundReviewRows(it.quote, it.plan, it.days) }
            item("intro") {
                SectionCard(title = stringResource(R.string.stamps_fund_one_transaction)) {
                    MutedText(stringResource(R.string.stamps_fund_intro))
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
                r != null && rows != null -> item("review") {
                    val q = r.quote
                    // The node the review pays is no longer the one running, or funding it is blocked now.
                    val changed = fundReviewHeld(blocked, node, rows.node)
                    // Read live after the unlock prompt and when the Ledger is ready: from the
                    // page's own state and the record's flow, never this item's, which is
                    // disposed the moment the send starts (#291 R3-F1).
                    val heldNow = liveNode.heldNow(funding?.pending, r.plan.batchId, rows.node)
                    FundReview(
                        quote = q,
                        summary = rows.summary,
                        node = rows.node,
                        busy = busy,
                        notice = notice,
                        held = changed,
                        error = error,
                        onCancel = {
                            reviewing = null
                            notice = null
                            error = null
                        },
                        onConfirm = {
                            if (changed != null) return@FundReview
                            if (sender.isStale(q)) {
                                // Price the pool and the fee again: both can have moved.
                                reviewing = null
                                refresh++
                                notice = Strings.get(R.string.stamps_fund_quote_stale)
                                return@FundReview
                            }
                            busy = true
                            scope.launch {
                                try {
                                    if (!q.request.from.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                    // The node may have restarted (or funding been blocked) while the prompt was up.
                                    if (heldNow() != null) return@launch
                                    when (sender.submit(q, WalletSender.signerFor(context, vault, q.request.from, fundLedgerFresh(heldNow) { sender.isStale(q) }))) {
                                        WalletSender.Submit.STARTED -> {
                                            reviewing = null
                                            notice = null
                                        }
                                        WalletSender.Submit.BUSY -> error = Strings.get(R.string.stamps_fund_send_busy)
                                        WalletSender.Submit.STALE -> {
                                            reviewing = null
                                            refresh++
                                            notice = Strings.get(R.string.stamps_fund_quote_stale)
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = safeErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
                else -> {
                    item("size") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StoragePicker(depth = depth, onDepth = { depth = it }, days = days, onDays = { days = it })
                        }
                    }
                    item("quote") {
                        FundQuoteCard(priced, plan, payer, payerBalance)
                    }
                    item("act") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            notice?.let { MutedText(it) }
                            (blocked ?: if (payer == null) stringResource(R.string.stamps_fund_set_up_wallet) else null)?.let { MutedText(it) }
                            error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = { plan?.let(::review) },
                                enabled = blocked == null && payer != null && plan != null && !busy && sendStatus?.inFlight != true,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.stamps_fund_review))
                            }
                        }
                    }
                    item("advanced") {
                        DetailsExpander(title = stringResource(R.string.stamps_advanced)) {
                            AdvancedDepths(depth) { depth = it }
                            Spacer(Modifier.height(8.dp))
                            FundBreakdown(priced, plan, days, chain)
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

/** The one total the surface shows (#425, W42): what the wallet pays, and whether it holds that. */
@Composable
private fun FundQuoteCard(
    priced: Priced,
    plan: SwarmFunder.Plan?,
    payer: WalletAccount?,
    payerBalance: BigInteger?,
) {
    SectionCard(title = stringResource(R.string.stamps_total)) {
        when {
            priced is Priced.Loading -> MutedText(stringResource(R.string.stamps_fund_asking_prices))
            priced is Priced.Failed -> MutedText(stringResource(R.string.stamps_no_price_reason, priced.message))
            plan == null -> MutedText(stringResource(R.string.stamps_fund_not_wallet_identity))
            priced is Priced.Ready -> {
                TotalFigure(formatXdaiCeiling(plan.value))
                SubLine(stringResource(R.string.stamps_fund_total_note))
                payer?.let { p ->
                    val short = payerBalance != null && payerBalance < plan.value
                    Text(
                        stringResource(
                            if (short) R.string.stamps_fund_paid_by_short else R.string.stamps_fund_paid_by_holds,
                            p.name,
                            payerBalance?.let(::formatXdai) ?: "…",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The price in full, under Advanced: the stamp, the deposit, the pool, the swap and what the node gets. */
@Composable
private fun FundBreakdown(priced: Priced, plan: SwarmFunder.Plan?, days: Long, chain: Chain) {
    if (priced !is Priced.Ready || plan == null) return
    Column {
        DetailRow(
            stringResource(R.string.stamps_stamp),
            stringResource(R.string.stamps_size_and_duration, formatStampBytes(effectiveStampBytes(plan.depth)), daysLabel(days)),
        )
        DetailRow(stringResource(R.string.stamps_fund_stamp_cost), formatBzz(plan.stampCostPlur))
        if (plan.depositPlur.signum() > 0) {
            DetailRow(stringResource(R.string.stamps_chequebook_deposit), formatBzz(plan.depositPlur))
            SubLine(stringResource(R.string.stamps_fund_deposit_note))
        }
        DetailRow(stringResource(R.string.stamps_fund_pool_price), stringResource(R.string.stamps_fund_pool_price_value, formatSpotPrice(plan.sqrtPriceX96)))
        SubLine(stringResource(R.string.stamps_fund_pool_name))
        DetailRow(stringResource(R.string.stamps_fund_swap), formatXdaiCeiling(plan.xdaiForSwap))
        SubLine(stringResource(R.string.stamps_fund_swap_note, formatBzz(plan.expectedBzz), formatBzz(plan.minBzz)))
        DetailRow(stringResource(R.string.stamps_fund_to_node), formatXdai(plan.xdaiForNode))
        SubLine(stringResource(R.string.stamps_fund_to_node_note))
        SubLine(stringResource(R.string.stamps_fund_via_contract, chain.name))
    }
}

@Composable
private fun FundReview(
    quote: SendQuote,
    summary: String,
    node: String,
    busy: Boolean,
    notice: String?,
    held: String?,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val confirmable = held == null
    // Armed afresh whenever Confirm comes back from being held, not only for a new quote:
    // the review stays up through a node change, so re-enabling must not land under a tapping finger.
    val tap = rememberArmedTapGuard(quote to confirmable, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    Column {
        SectionCard(title = stringResource(R.string.stamps_fund_review)) {
            ReviewRow(stringResource(R.string.stamps_fund_what), summary)
            ReviewRow(stringResource(R.string.stamps_fund_node), null, address = node)
            ReviewRow(stringResource(R.string.stamps_fund_network), chain.name)
            ReviewRow(stringResource(R.string.stamps_fund_paid_by), request.from.name, address = request.from.address)
            ReviewRow(stringResource(R.string.stamps_fund_amount), "${SendAmounts.exact(request.amount, request.token.decimals)} ${request.token.symbol}", mono = true)
            ReviewRow(
                stringResource(R.string.stamps_fund_network_fee),
                stringResource(R.string.stamps_fund_fee_up_to, feeText(quote.maxFee, chain)),
                mono = true,
                detail = feeDetail(quote),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stamps_fund_review_note, feeFootnote(quote.tx)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What only an expert checks, one tap away (#425): the contract called and the nonce.
            DetailsExpander {
                ReviewRow(stringResource(R.string.stamps_fund_contract), "SwarmNodeFunder", address = request.to)
                ReviewRow(stringResource(R.string.stamps_fund_nonce), quote.tx.nonce.toString(), detail = nonceDetail(quote))
            }
        }
        Spacer(Modifier.height(12.dp))
        notice?.let { MutedText(it) }
        // Why Confirm is disabled first, then any earlier failure: neither hides the other.
        held?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ObscuredTapNotice(tap)
        SheetButtonRow {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
            Button(onClick = { if (guard.accepts()) onConfirm() }, enabled = armed && !busy && confirmable, modifier = Modifier.protectedPress(tap)) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.stamps_fund_confirm_and_send))
            }
        }
    }
}

/**
 * The pending stamp card's explanation. [superseded]: the chain shows its
 * call can never be mined ([SwarmFunding.superseded]) — the only case in
 * which an unmined record is certain to have bought nothing. [owed]:
 * the app is to connect it itself once the node is free
 * ([SwarmFunding.connectOwed]) — which outranks an earlier failed Connect,
 * since it was found mined after that; otherwise a mined one waits for Connect.
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
        superseded -> Strings.get(R.string.stamps_pending_superseded)
        p.hash != null -> Strings.get(R.string.stamps_pending_not_mined_yet)
        else -> Strings.get(R.string.stamps_pending_maybe_mined)
    }
    return when {
        !p.mined && p.tracked -> Strings.get(R.string.stamps_pending_going_out)
        connecting -> Strings.get(R.string.stamps_pending_connecting)
        otherNode -> Strings.get(R.string.stamps_pending_other_node, p.node)
        // Ahead of an earlier failed Connect: a connect owed now supersedes it (found mined since).
        owed && p.mined -> Strings.get(R.string.stamps_pending_owed)
        failed != null && p.mined -> Strings.get(R.string.stamps_pending_connect_failed, failed.message)
        failed != null -> Strings.get(R.string.stamps_pending_connect_failed_untracked, failed.message, untracked)
        !p.mined -> Strings.get(R.string.stamps_pending_stopped_following, untracked)
        else -> stampsBlockedReason(nodeInfo)?.let { Strings.get(R.string.stamps_pending_mined_blocked, it) }
            ?: Strings.get(R.string.stamps_pending_mined)
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
    var confirmingDismiss by remember(p.batchId) { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.stamps_pending_title)) {
        DetailRow(
            stringResource(R.string.stamps_stamp),
            stringResource(R.string.stamps_size_and_duration, formatStampBytes(effectiveStampBytes(p.depth)), daysLabel(p.days)),
        )
        DetailRow(stringResource(R.string.stamps_pending_batch), shortBatchId(p.batchId), mono = true)
        MutedText(pendingStampText(p, nodeInfo, spend, superseded, owed))
        if (p.mined || !p.tracked) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    // Not over a search or an upload: the #222 publish/stamp lock, kept from
                    // when a connect could reload the gateway (it no longer does, ant 0.5.52+).
                    enabled = StampClient.canRestartGateway(spend, discovery, publishing) &&
                        !otherNode && stampSpendBlockedReason(nodeInfo) == null,
                ) { Text(stringResource(R.string.stamps_connect)) }
                TextButton(
                    onClick = { if (dismissNeedsConfirm(superseded)) confirmingDismiss = true else onForget() },
                    enabled = !connecting,
                ) { Text(stringResource(R.string.stamps_dismiss)) }
            }
        }
    }
    if (confirmingDismiss) {
        SpendConfirmDialog(
            title = stringResource(R.string.stamps_dismiss_confirm_title),
            body = stringResource(R.string.stamps_dismiss_confirm_body),
            confirmLabel = stringResource(R.string.stamps_dismiss),
            onConfirm = {
                confirmingDismiss = false
                onForget()
            },
            onDismiss = { confirmingDismiss = false },
        )
    }
}

/**
 * Whether Dismiss on a pending stamp asks first (#425, W45): always, unless
 * the chain shows its call can never be mined ([superseded]) — the only
 * case in which it surely bought nothing. Otherwise it may be a paid stamp,
 * and dismissing forgets the only record the node could connect it from.
 */
internal fun dismissNeedsConfirm(superseded: Boolean): Boolean = !superseded

private const val TAG = "FundNode"
