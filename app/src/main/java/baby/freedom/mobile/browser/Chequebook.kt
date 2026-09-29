package baby.freedom.mobile.browser

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
    node.status == NodeStatus.Starting -> "The Swarm node is starting…"
    node.status != NodeStatus.Running -> "Turn on the Swarm node to see its chequebook."
    !node.lightMode -> "The chequebook needs light mode, which connects the node to Gnosis Chain. " +
        "Switch it on under Publishing on the node page."
    else -> null
}

/**
 * Why a deposit of [amountPlur] can't go ahead now, or null when it can.
 * Like a stamp, only the wallet identity's node spends (see
 * [stampSpendBlockedReason]).
 */
internal fun depositBlockedReason(node: NodeInfo, state: ChequebookState, amountPlur: BigInteger?): String? =
    chequebookBlockedReason(node) ?: when {
        !node.walletIdentity -> "Set up a wallet first, so the node runs as your wallet's identity " +
            "(publish setup, step 1)."
        state.address == null -> "Checking the node's chequebook…"
        state.address.isEmpty() -> "The node has no chequebook yet. It sets one up with its first postage stamp."
        state.walletPlur == null -> "Checking the node's xBZZ…"
        state.walletPlur.signum() == 0 -> "The node's account holds no xBZZ. Send xBZZ on Gnosis Chain to the " +
            "node's address first: a deposit moves xBZZ the node already holds, it doesn't swap xDAI."
        amountPlur == null -> "Choose an amount."
        amountPlur > state.walletPlur -> "The node's account holds only ${formatBzz(state.walletPlur)}. Choose a " +
            "smaller amount, or send xBZZ on Gnosis Chain to the node's address first."
        else -> null
    }

/** The confirmation's body: the amount, where it goes, and the most it can cost in gas. */
internal fun depositConfirmText(amountPlur: BigInteger, chequebook: String): String =
    "The node moves ${formatBzzExact(amountPlur)} from its account into its chequebook at $chequebook, " +
        "and pays up to ${formatXdai(SpendPermit.DEPOSIT_MAX_GAS_WEI)} of gas for the one transaction. " +
        "The xBZZ then pays other nodes for pushing what you publish. This is a real transaction on " +
        "Gnosis Chain and can't be undone."
