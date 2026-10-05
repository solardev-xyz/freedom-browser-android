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
 * xBZZ, below them ant's own settlement-deposit target (0.001 xBZZ, what
 * a first stamp puts in) and 0.01.
 */
internal val DEPOSIT_PRESETS_PLUR: List<BigInteger> =
    listOf("0.001", "0.01", "0.1", "0.5", "1").map { BigDecimal(it).movePointRight(16).toBigIntegerExact() }

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

/** Below this the credit is low: half the node's default 0.001 xBZZ deposit, as on desktop. */
internal val LOW_CREDIT_PLUR: BigInteger = BigInteger("5000000000000")

/** Why downloads are on the free tier. */
internal enum class FreeTierReason { NotSupported, SwitchedOff, NoChequebook, LedgerLost, NoCredit, NotSetUp }

/** Whether downloads (and uploads) pay peers from the chequebook now. */
internal sealed interface CreditStatus {
    data object Checking : CreditStatus
    /**
     * [low]: the credit is surely below [LOW_CREDIT_PLUR]. [uncertain]: the
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
            low = available != null && available < LOW_CREDIT_PLUR,
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
 * Does the deposit need xBZZ the node's account doesn't hold? Then the page
 * says how to fund the node first, with its address.
 */
internal fun depositNeedsFunding(state: ChequebookState, amountPlur: BigInteger?): Boolean {
    val wallet = state.walletPlur ?: return false
    return wallet.signum() == 0 || (amountPlur != null && amountPlur > wallet)
}

/** The chequebook's balance from a `/chequebook/balance` body (bee's `totalBalance`, PLUR). */
internal fun chequebookBalanceFrom(body: String): BigInteger? =
    runCatching { BigInteger(JSONObject(body).getString("totalBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }

/** The node account's xBZZ from a `/wallet` body (`bzzBalance`, PLUR). */
internal fun walletBzzFrom(body: String): BigInteger? =
    runCatching { BigInteger(JSONObject(body).getString("bzzBalance")) }.getOrNull()?.takeIf { it.signum() >= 0 }

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
        state.walletPlur.signum() == 0 -> Strings.get(R.string.stamps_chequebook_no_xbzz)
        amountPlur == null -> Strings.get(R.string.stamps_chequebook_choose_amount)
        amountPlur > state.walletPlur -> Strings.get(R.string.stamps_chequebook_holds_only, formatBzz(state.walletPlur))
        else -> null
    }

/** The confirmation's body: the amount, where it goes, and the most it can cost in gas. */
internal fun depositConfirmText(amountPlur: BigInteger, chequebook: String): String =
    Strings.get(
        R.string.stamps_chequebook_deposit_confirm,
        formatBzzExact(amountPlur), chequebook, formatXdai(SpendPermit.DEPOSIT_MAX_GAS_WEI),
    )
