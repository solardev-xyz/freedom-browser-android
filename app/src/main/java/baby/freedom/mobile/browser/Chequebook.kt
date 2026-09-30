package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SpendPermit
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import org.json.JSONObject

/*
 * Chequebook deposit (#117): the node's chequebook, what it holds, and
 * moving xBZZ from the node's account into it — after desktop's
 * chequebook-deposit.js / stamp-service.js (bee's `POST
 * /chequebook/deposit`), on ant's same gateway route. The chequebook pays
 * other nodes for pushing what the node publishes; the first postage
 * stamp sets it up (#116). Unlike a stamp there's no swap: the xBZZ must
 * already be in the node's account. The UI lives in ChequebookScreen.kt.
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
)

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
