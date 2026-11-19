package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SpendPermit
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/*
 * Chequebook deposit (#117): the node's chequebook, what it holds, and
 * moving xBZZ from the node's account into it — after desktop's
 * chequebook-deposit.js / stamp-service.js (bee's `POST
 * /chequebook/deposit`), on ant's same gateway route. The chequebook pays
 * other nodes for pushing what the node publishes; the first postage
 * stamp sets it up (#116). Unlike a stamp there's no swap: the xBZZ must
 * already be in the node's account. The UI lives in ChequebookScreen.kt.
 *
 * Paid downloads (browsing credit), after desktop's #490/#492: the same
 * chequebook also pays peers for faster downloads and uploads than the
 * free tier carries (bee's swap-enable, ant's `ant_set_swap_enabled`).
 * What it can still pay is bee's `availableBalance` — the on-chain
 * balance less the cheques peers haven't cashed yet — and whether it
 * pays now is ant's `ant_swap_status`.
 */

/**
 * The amounts the deposit page offers, in PLUR: desktop's 0.1, 0.5 and 1
 * xBZZ, and below them 0.001 and 0.01 for a small top-up. (A stamp buy
 * puts in its own deposit, sized by the plan: [settlementDepositTarget].)
 */
internal val DEPOSIT_PRESETS_PLUR: List<BigInteger> =
    listOf("0.001", "0.01", "0.1", "0.5", "1").map { BigDecimal(it).movePointRight(16).toBigIntegerExact() }

/**
 * The preset picked until the user picks one (#425, W44): 0.1 xBZZ (about
 * 38 minutes of HD video), or, when the node's account holds less
 * ([walletPlur]), the largest preset it can pay; with none it can pay, 0.1
 * still, and the page says how much the node holds. The page picks it
 * once, when the balance is first known, and keeps it: a balance read
 * later doesn't move the highlighted row.
 */
internal fun defaultDepositPreset(walletPlur: BigInteger?): BigInteger {
    val preferred = DEPOSIT_PRESETS_PLUR[2]
    if (walletPlur == null || walletPlur >= preferred) return preferred
    return DEPOSIT_PRESETS_PLUR.lastOrNull { it <= walletPlur } ?: preferred
}

/**
 * The deposit page's pick after a balance read: the one already made
 * ([current], the user's or an earlier default) stays; with none yet, the
 * default for [walletPlur] once it's known; null (nothing highlighted)
 * while it isn't.
 */
internal fun depositPick(current: String?, walletPlur: BigInteger?): String? =
    current ?: walletPlur?.let { defaultDepositPreset(it).toString() }

/**
 * The deposit page's pick right after a deposit of [deposited] went out:
 * the default for what the node's account will hold once it lands
 * ([walletPlur], read before it, less [deposited]), not for the balance
 * read before the deposit, which would leave a preset highlighted that
 * the node can no longer pay. Null while the balance isn't known; the
 * page then picks once it is.
 */
internal fun depositPickAfterDeposit(deposited: BigInteger, walletPlur: BigInteger?): String? =
    depositPick(null, walletPlur?.let { (it - deposited).max(BigInteger.ZERO) })

/**
 * What the credit pays for, as the cost note measures it: about 0.16 xBZZ
 * per hour of HD video when the free tier carries most of it.
 */
internal val HD_VIDEO_HOUR_PLUR: BigInteger = BigDecimal("0.16").movePointRight(16).toBigIntegerExact()

/**
 * A deposit preset named by what it buys (#425, W44): "About 38 minutes
 * of HD video", "About 3 hours of HD video" ("Less than a minute of HD
 * video" for 0.001 xBZZ); the amount is its sub-line.
 */
internal fun depositPresetLabel(plur: BigInteger): String {
    val minutes = BigDecimal(plur).multiply(BigDecimal(60)).divide(BigDecimal(HD_VIDEO_HOUR_PLUR), 0, RoundingMode.HALF_UP).toLong()
    val time = when {
        minutes < 1 -> return Strings.get(R.string.stamps_deposit_preset_under_a_minute)
        minutes < 90 -> Strings.plural(R.plurals.stamps_minutes, minutes.toInt(), minutes)
        else -> {
            val hours = (minutes + 30) / 60
            Strings.plural(R.plurals.stamps_hours, hours.toInt(), hours)
        }
    }
    return Strings.get(R.string.stamps_deposit_preset_video, time)
}

/**
 * PLUR as xBZZ, to at most 6 decimals, rounded down so a balance never
 * reads as more than it is; a dust amount shows as `< 0.000001`.
 */
internal fun formatBzz(plur: BigInteger): String {
    val shown = BigDecimal(plur).movePointLeft(16).setScale(6, RoundingMode.DOWN).stripTrailingZeros()
    val text = if (shown.signum() == 0 && plur.signum() > 0) "< 0.000001" else shown.toPlainString()
    return "$text xBZZ"
}

/** A preset amount exactly (they're all whole decimals of xBZZ). */
internal fun formatBzzExact(plur: BigInteger): String =
    "${BigDecimal(plur).movePointLeft(16).stripTrailingZeros().toPlainString()} xBZZ"

/** What the chequebook page reads from the light node's gateway; null for not known (yet). */
internal data class ChequebookState(
    /** `0x` + 40 hex; `""` for none, null for not known. */
    val address: String? = null,
    /** What the chequebook holds, from `GET /chequebook/balance`. */
    val balancePlur: BigInteger? = null,
    /** The node account's own xBZZ, from `GET /wallet`: what a deposit moves. */
    val walletPlur: BigInteger? = null,
    /** The node account's xDAI, from `GET /wallet`: what a top-up swaps for the xBZZ it lacks (#543). */
    val walletXdaiWei: BigInteger? = null,
    /** What the chequebook can still pay: bee's `availableBalance`. */
    val availablePlur: BigInteger? = null,
    /** [availablePlur] is only an upper bound: ant couldn't count its cheques (`availableBalanceError`). */
    val availableUpperBound: Boolean = false,
    /** The node's cheque ledger was lost and nobody confirmed it since (`chequeLedgerLost`): it pays no cheques. */
    val ledgerLost: Boolean = false,
    /**
     * The ledger was lost and the user confirmed it ([withConfirmedLedgers]):
     * [availablePlur] leaves out the cheques written before the loss, so it
     * is an upper bound for good, though ant no longer says so.
     */
    val ledgerConfirmed: Boolean = false,
    /** The deposit ant keeps the chequebook at for the node's plan ([planDepositTarget]); null for not known. */
    val depositTargetPlur: BigInteger? = null,
)

/**
 * [this] with the record of confirmed lost ledgers ([confirmed], lowercase
 * addresses) applied: after a confirmation ant drops `chequeLedgerLost`
 * and `availableBalanceError` and counts only the cheques written since,
 * so for that chequebook the spendable credit stays an upper bound.
 */
internal fun ChequebookState.withConfirmedLedgers(confirmed: Set<String>): ChequebookState {
    val chequebook = address?.lowercase()
    if (chequebook.isNullOrEmpty() || ledgerLost || chequebook !in confirmed) return this
    return copy(availableUpperBound = true, ledgerConfirmed = true)
}

/**
 * Confirm [chequebook]'s lost cheque ledger: [record] it as confirmed
 * first, and only then ask the node ([confirm]). The record goes first
 * because only ant knows whether its confirmation landed: a 60 s timeout
 * while `:node` confirms late (a gateway restart holding its lock), or the
 * app killed before an after-the-answer write, would lose it for good, and
 * a retry then gets "nothing to confirm". A record whose confirmation
 * never landed costs nothing: [withConfirmedLedgers] ignores it while ant
 * still reports the loss, and an upper bound on the credit stays true.
 * If the record can't be saved the node isn't asked at all.
 */
internal suspend fun confirmLostLedger(
    chequebook: String,
    record: suspend (String) -> Unit,
    confirm: suspend (String) -> StampClient.Answer,
): StampClient.Answer {
    try {
        record(chequebook)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return StampClient.Answer.Failed(Strings.get(R.string.stamps_credit_lost_not_recorded))
    }
    return confirm(chequebook)
}

/** What a `/chequebook/balance` body says, beyond [chequebookBalanceFrom]'s total. */
internal data class ChequebookFunds(
    val totalPlur: BigInteger,
    val availablePlur: BigInteger,
    val availableUpperBound: Boolean,
    val ledgerLost: Boolean,
)

/**
 * The balances from a `/chequebook/balance` body: `totalBalance`, and
 * `availableBalance` (the total, an upper bound, when ant couldn't work it
 * out and says why in `availableBalanceError`, or when it's missing).
 */
internal fun chequebookFundsFrom(body: String): ChequebookFunds? {
    val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val total = chequebookBalanceFrom(body) ?: return null
    val available = runCatching { BigInteger(o.getString("availableBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }
    val lost = o.optString("chequeLedgerLost").isNotBlank()
    return ChequebookFunds(
        totalPlur = total,
        availablePlur = available ?: total,
        availableUpperBound = available == null || o.optString("availableBalanceError").isNotBlank() || lost,
        ledgerLost = lost,
    )
}

/** ant's `ant_swap_status`, the parts the page shows. */
internal data class SwapStatus(
    /** This build can pay peers with cheques at all. */
    val supported: Boolean,
    /** The switch as the node runs it now. */
    val swapEnabled: Boolean,
    /** Cheques are being paid right now. */
    val paying: Boolean,
)

internal fun swapStatusFrom(o: JSONObject): SwapStatus? {
    if (!o.has("swap_enabled") || !o.has("paying")) return null
    return SwapStatus(
        supported = o.optBoolean("supported", false),
        swapEnabled = o.optBoolean("swap_enabled", false),
        paying = o.optBoolean("paying", false),
    )
}

/**
 * Bee's smallest cheque, as desktop counts it: 90 000 accounting units at
 * 100 000 PLUR. Below it the credit can't pay a peer: it's used up.
 */
internal val MIN_CHEQUE_PLUR: BigInteger = BigInteger.valueOf(90_000L * 100_000L)

/** ant's settlement deposit target at depth 21: 0.1 xBZZ. */
private val PLAN_DEPOSIT_AT_21_PLUR: BigInteger = BigInteger.TEN.pow(15)

/** ant's settlement deposit target with no plan, and its floor: 0.001 xBZZ. */
internal val NO_PLAN_DEPOSIT_PLUR: BigInteger = BigInteger.TEN.pow(13)

/** The most ant's settlement deposit target asks for: 1 xBZZ. */
private val MAX_PLAN_DEPOSIT_PLUR: BigInteger = BigInteger.TEN.pow(16)

/**
 * The deposit ant keeps behind the node's chequebook for a storage plan
 * [depth] deep (ant 0.5.68+, `deposit_target_for_depth`): 0.1 xBZZ at
 * depth 21, doubling per level above and halving per level below,
 * between 0.001 and 1 xBZZ. Null [depth] (no plan): 0.001 xBZZ.
 */
internal fun settlementDepositTarget(depth: Int?): BigInteger {
    if (depth == null) return NO_PLAN_DEPOSIT_PLUR
    val target = if (depth >= 21) {
        PLAN_DEPOSIT_AT_21_PLUR.shiftLeft((depth - 21).coerceAtMost(64))
    } else {
        PLAN_DEPOSIT_AT_21_PLUR.shiftRight((21 - depth).coerceAtMost(64))
    }
    return target.max(NO_PLAN_DEPOSIT_PLUR).min(MAX_PLAN_DEPOSIT_PLUR)
}

/**
 * The deposit target for the node's plan as ant reckons it
 * (`plan_deposit_target`): for the deepest of [stamps] the node can stamp
 * with or has just bought and is still propagating, or with none, no plan.
 * Counting a propagating batch matters: the chequebook page reads this
 * right after a buy, while the new batch still reads `usable: false`.
 */
internal fun planDepositTarget(stamps: List<PostageBatch>): BigInteger =
    settlementDepositTarget(stamps.filter { it.usable || it.propagating }.maxOfOrNull { it.depth })

/**
 * Below this the credit is low: half the deposit the chequebook is kept at
 * for the node's plan ([depositTargetPlur], [planDepositTarget]), as desktop
 * calls half its deposit low; half of the no-plan 0.001 xBZZ until that's known.
 */
internal fun lowCreditPlur(depositTargetPlur: BigInteger?): BigInteger =
    (depositTargetPlur ?: NO_PLAN_DEPOSIT_PLUR).shiftRight(1)

/** Why downloads are on the free tier. */
internal enum class FreeTierReason { NotSupported, SwitchedOff, NoChequebook, LedgerLost, NoCredit, NotSetUp }

/** Whether downloads (and uploads) pay peers from the chequebook now. */
internal sealed interface CreditStatus {
    data object Checking : CreditStatus
    /**
     * [low]: the credit is surely below [lowCreditPlur]. [uncertain]: the
     * figure is only an upper bound, so what is left may be less still.
     */
    data class Paying(val low: Boolean, val uncertain: Boolean = false) : CreditStatus
    data class FreeTier(val reason: FreeTierReason) : CreditStatus
}

/**
 * Paying peers or free tier, and why, from ant's own `paying` and the
 * chequebook's figures. Used-up credit reads as free tier whatever the
 * node's last funds read says (desktop does the same): it can't pay
 * another cheque. An upper-bound figure under a threshold is surely under
 * it; one above it only may be, which [CreditStatus.Paying.uncertain] says.
 */
internal fun creditStatus(swap: SwapStatus?, state: ChequebookState): CreditStatus {
    val available = state.availablePlur
    return when {
        swap == null -> CreditStatus.Checking
        !swap.supported -> CreditStatus.FreeTier(FreeTierReason.NotSupported)
        !swap.swapEnabled -> CreditStatus.FreeTier(FreeTierReason.SwitchedOff)
        state.address == "" -> CreditStatus.FreeTier(FreeTierReason.NoChequebook)
        state.ledgerLost -> CreditStatus.FreeTier(FreeTierReason.LedgerLost)
        available != null && available < MIN_CHEQUE_PLUR -> CreditStatus.FreeTier(FreeTierReason.NoCredit)
        swap.paying -> CreditStatus.Paying(
            low = available != null && available < lowCreditPlur(state.depositTargetPlur),
            uncertain = state.availableUpperBound,
        )
        else -> CreditStatus.FreeTier(FreeTierReason.NotSetUp)
    }
}

/** The status line: "Paying peers", or "Free tier: <why>". */
internal fun creditStatusText(status: CreditStatus): String = when (status) {
    CreditStatus.Checking -> Strings.get(R.string.stamps_checking)
    is CreditStatus.Paying -> Strings.get(R.string.stamps_credit_paying)
    is CreditStatus.FreeTier -> Strings.get(
        R.string.stamps_credit_free_tier,
        Strings.get(
            when (status.reason) {
                FreeTierReason.NotSupported -> R.string.stamps_credit_free_not_supported
                FreeTierReason.SwitchedOff -> R.string.stamps_credit_free_switched_off
                FreeTierReason.NoChequebook -> R.string.stamps_credit_free_no_chequebook
                FreeTierReason.LedgerLost -> R.string.stamps_credit_free_ledger_lost
                FreeTierReason.NoCredit -> R.string.stamps_credit_free_no_credit
                FreeTierReason.NotSetUp -> R.string.stamps_credit_free_not_set_up
            },
        ),
    )
}

/**
 * Does the deposit need funds the node's account doesn't hold: less xBZZ
 * than [amountPlur] and no xDAI to swap for the rest? Then the page says
 * how to fund the node first, with its address.
 */
internal fun depositNeedsFunding(state: ChequebookState, amountPlur: BigInteger?): Boolean {
    val wallet = state.walletPlur ?: return false
    val short = wallet.signum() == 0 || (amountPlur != null && amountPlur > wallet)
    return short && swappableXdaiWei(state)?.signum() == 0
}

/**
 * The xDAI a top-up may swap: the node's xDAI as the page shows it,
 * rounded down to the 6 decimals shown ([shownXdaiWei]), so dust reads as
 * none everywhere alike; null while it isn't known. The one figure the
 * blocked reason, the funding hint and the review all go by.
 */
internal fun swappableXdaiWei(state: ChequebookState): BigInteger? = state.walletXdaiWei?.let(::shownXdaiWei)

/**
 * What a deposit the user is about to confirm does, captured when the
 * review opens: that's what is sent. [swapWei] null: the node's own xBZZ
 * covers [amountPlur], and it moves it (#117). Otherwise a top-up (#543):
 * the node swaps xDAI, at most [swapWei], for the xBZZ it lacks beyond the
 * [walletPlur] it holds, then deposits.
 */
internal data class DepositReview(
    val chequebook: String,
    val amountPlur: BigInteger,
    val walletPlur: BigInteger,
    val swapWei: BigInteger?,
)

/**
 * The review for depositing [amountPlur] now, or null while it can't go
 * ahead ([depositBlockedReason]). A swap may use the node's xDAI as the
 * page shows it, rounded down to the 6 decimals shown, so the most it can
 * swap is exactly the figure the review names (desktop: never more than
 * the screen showed).
 */
internal fun depositReview(state: ChequebookState, amountPlur: BigInteger?): DepositReview? {
    val chequebook = state.address?.takeIf { it.isNotEmpty() } ?: return null
    val wallet = state.walletPlur ?: return null
    amountPlur ?: return null
    if (wallet >= amountPlur) return DepositReview(chequebook, amountPlur, wallet, null)
    val xdai = swappableXdaiWei(state)?.takeIf { it.signum() > 0 } ?: return null
    return DepositReview(chequebook, amountPlur, wallet, xdai)
}

/** [wei] rounded down to the 6 decimals [formatXdai] shows. */
internal fun shownXdaiWei(wei: BigInteger): BigInteger = wei.divide(XDAI_SHOWN_STEP_WEI).multiply(XDAI_SHOWN_STEP_WEI)

private val XDAI_SHOWN_STEP_WEI: BigInteger = BigInteger.TEN.pow(12)

/** The chequebook's balance from a `/chequebook/balance` body (bee's `totalBalance`, PLUR). */
internal fun chequebookBalanceFrom(body: String): BigInteger? =
    runCatching { BigInteger(JSONObject(body).getString("totalBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }

/** The node account's xBZZ from a `/wallet` body (`bzzBalance`, PLUR). */
internal fun walletBzzFrom(body: String): BigInteger? =
    runCatching { BigInteger(JSONObject(body).getString("bzzBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }

/** The node account's xDAI from a `/wallet` body (`nativeTokenBalance`, wei). */
internal fun walletXdaiFrom(body: String): BigInteger? =
    runCatching { BigInteger(JSONObject(body).getString("nativeTokenBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }

/** Why the chequebook page can't read anything now, or null when it can. */
internal fun chequebookBlockedReason(node: NodeInfo): String? = when {
    node.status == NodeStatus.Starting -> Strings.get(R.string.stamps_node_starting)
    node.status != NodeStatus.Running -> Strings.get(R.string.stamps_chequebook_node_off)
    !node.lightMode -> Strings.get(R.string.stamps_chequebook_need_light_mode)
    else -> null
}

/**
 * Why a deposit of [amountPlur] can't go ahead now, or null when it can.
 * Like a stamp, only the wallet identity's node spends (see
 * [stampSpendBlockedReason]).
 */
internal fun depositBlockedReason(node: NodeInfo, state: ChequebookState, amountPlur: BigInteger?): String? =
    chequebookBlockedReason(node) ?: when {
        !node.walletIdentity -> Strings.get(R.string.stamps_need_wallet_identity)
        state.address == null -> Strings.get(R.string.stamps_chequebook_checking)
        state.address.isEmpty() -> Strings.get(R.string.stamps_chequebook_none)
        state.walletPlur == null -> Strings.get(R.string.stamps_chequebook_checking_xbzz)
        amountPlur == null -> Strings.get(R.string.stamps_chequebook_choose_amount)
        amountPlur <= state.walletPlur -> null
        // Short of xBZZ: the node swaps xDAI for the rest (#543), if it has any.
        swappableXdaiWei(state) == null -> Strings.get(R.string.stamps_chequebook_checking_xbzz)
        swappableXdaiWei(state)?.signum() == 0 ->
            if (state.walletPlur.signum() == 0) {
                Strings.get(R.string.stamps_chequebook_no_xbzz)
            } else {
                Strings.get(R.string.stamps_chequebook_holds_only, formatBzz(state.walletPlur))
            }
        else -> null
    }

/**
 * The confirmation's body: the amount, where it goes, and the most it can
 * cost in gas; for a top-up (#543), also what the node holds and the most
 * xDAI it may swap.
 */
internal fun depositConfirmText(review: DepositReview): String = when (val swap = review.swapWei) {
    null -> Strings.get(
        R.string.stamps_chequebook_deposit_confirm,
        formatBzzExact(review.amountPlur), review.chequebook, formatXdai(SpendPermit.DEPOSIT_MAX_GAS_WEI),
    )
    else -> Strings.get(
        R.string.stamps_chequebook_top_up_confirm,
        formatBzz(review.walletPlur), formatXdai(swap), formatBzzExact(review.amountPlur), review.chequebook,
        formatXdai(SpendPermit.TOP_UP_MAX_GAS_WEI),
    )
}

/**
 * What the chequebook paid peers recently (#543): in the last 24 hours
 * ([dayPlur]) and 7 days ([weekPlur]), counted by `:node` since [since]
 * (wall-clock ms). Null figures: no history for this chequebook yet.
 */
internal data class CreditSpendFigures(
    val dayPlur: BigInteger?,
    val weekPlur: BigInteger?,
    val since: Long?,
)

/** `:node`'s `creditSpend` answer; null when it isn't one. */
internal fun creditSpendFrom(o: JSONObject): CreditSpendFigures? {
    if (!o.has("chequebook")) return null
    fun plur(key: String) = o.optString(key).takeIf { it.isNotEmpty() }?.let { runCatching { BigInteger(it) }.getOrNull() }
        ?.takeIf { it.signum() >= 0 }
    val day = plur("dayPlur")
    val week = plur("weekPlur")
    val since = o.optLong("since", -1L).takeIf { it >= 0 }
    if (day == null || week == null || since == null) return CreditSpendFigures(null, null, null)
    return CreditSpendFigures(day, week, since)
}

/** The note under the spend figures: since when they count, and what they leave out. */
internal fun creditSpendNote(spend: CreditSpendFigures): String = when (val since = spend.since) {
    null -> Strings.get(R.string.stamps_credit_spent_note_none)
    else -> Strings.get(
        R.string.stamps_credit_spent_note,
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(since)),
    )
}

/** A spend figure, "Not counted yet" before there is a history; "Checking…" until read. */
internal fun creditSpendText(spend: CreditSpendFigures?, plur: (CreditSpendFigures) -> BigInteger?): String = when {
    spend == null -> Strings.get(R.string.stamps_checking)
    else -> plur(spend)?.let(::formatBzz) ?: Strings.get(R.string.stamps_credit_spent_none_yet)
}
