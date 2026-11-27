package baby.freedom.mobile.names

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameOperation.State
import baby.freedom.mobile.names.NameOperation.Step
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date

/*
 * The words the name screen shows, kept out of Compose so they can be
 * tested: the review's rows (desktop's `presentPlan`, `domain-approval.js`)
 * and each operation's status line.
 */

/** [wei] in ETH, every digit kept: a review is where the price is checked. */
internal fun ethText(wei: BigInteger): String = "${SendAmounts.exact(wei, 18)} ETH"

/**
 * A price on the check card (#617): ETH rounded *up* to six decimals, the
 * way the wallet shows other amounts — a price never reads lower than it
 * is. The review ([ethText]) keeps every digit.
 */
internal fun ethPriceText(wei: BigInteger): String {
    if (wei.signum() == 0) return "0 ETH"
    val v = java.math.BigDecimal(wei, 18).setScale(PRICE_DECIMALS, java.math.RoundingMode.UP).stripTrailingZeros()
    return "${v.toPlainString()} ETH"
}

private const val PRICE_DECIMALS = 6

/**
 * The name the check card prices for [input] (#617): a bare label (no
 * dot), like `alice`, is `alice.eth`; anything else is checked as typed.
 */
internal fun completedName(input: String): String {
    val t = input.trim()
    return if (t.isNotEmpty() && '.' !in t) "$t.${NameSpace.ETH.tld}" else t
}

/** The endings offered under a bare label (#617): `.eth` first, then `.wei` and `.gwei`; none once it has a dot. */
internal fun endingChoices(input: String): List<NameSpace> {
    val t = input.trim()
    return if (t.isNotEmpty() && '.' !in t) NameSpace.entries.toList() else emptyList()
}

/** "1 year", "2 years". */
internal fun yearsText(duration: Long): String {
    val years = (duration / NameContracts.YEAR).toInt()
    return Strings.plural(R.plurals.namereg_years, years, years)
}

/** One labelled row of the review. */
internal data class NameReviewRow(val label: String, val value: String)

/**
 * What the review of [plan] (quoted as [quote]) for [op] shows, or null
 * when [quote] doesn't carry exactly [plan]'s transaction from [op]'s
 * account: a step priced again during the review can never be described
 * by an older plan (the Fund node review's rule, #242).
 */
internal class NameReview(val headline: String, val rows: List<NameReviewRow>, val contract: NameContracts.Role)

internal fun nameReview(
    op: NameOperation,
    plan: NameRegistrar.Plan,
    quote: SendQuote,
    /** The wallet's addresses, for an address write's "Belongs to" row; null when they couldn't be read. */
    own: List<WalletAccount>? = null,
): NameReview? {
    val r = quote.request
    val matches = plan.operationId == op.id && plan.step == op.step &&
        r.chain.id == NameContracts.CHAIN_ID && quote.tx.chainId == NameContracts.CHAIN_ID &&
        r.from.address.equals(op.account, ignoreCase = true) &&
        r.to.equals(plan.to, ignoreCase = true) && quote.tx.to.equals(plan.to, ignoreCase = true) &&
        r.amount == plan.value && quote.tx.value == plan.value &&
        r.dapp?.data?.contentEquals(plan.data) == true && quote.tx.data.contentEquals(plan.data) &&
        r.dapp.name?.name == op.name
    if (!matches) return null
    val headline = when (plan.step) {
        Step.COMMIT -> Strings.get(R.string.namereg_review_commit_headline, op.name)
        Step.REVEAL -> Strings.get(R.string.namereg_review_reveal_headline, op.name)
        Step.RENEW -> Strings.get(R.string.namereg_review_renew_headline, op.name)
        Step.RECORD -> Strings.get(
            when (op.intent) {
                Intent.SET_WEBSITE -> R.string.namereg_review_website_headline
                Intent.CLEAR_WEBSITE -> R.string.namereg_review_clear_website_headline
                Intent.SET_ADDRESS -> R.string.namereg_review_address_headline
                else -> R.string.namereg_review_text_headline
            },
            op.name,
        )
    }
    val rows = ArrayList<NameReviewRow>()
    rows += NameReviewRow(Strings.get(R.string.namereg_review_name), op.name)
    if (op.intent == Intent.REGISTER) {
        rows += NameReviewRow(
            Strings.get(R.string.namereg_review_step_label),
            Strings.get(if (plan.step == Step.COMMIT) R.string.namereg_review_step_commit else R.string.namereg_review_step_reveal),
        )
    }
    if (plan.step == Step.RECORD) {
        recordRows(op, own).let { rows += it }
        return NameReview(headline, rows, plan.contract)
    }
    rows += NameReviewRow(
        Strings.get(R.string.namereg_review_term),
        Strings.get(R.string.namereg_review_term_value, yearsText(op.duration), (op.duration / 86_400).toInt()),
    )
    val price = plan.quotedWei
    if (price != null) {
        rows += NameReviewRow(
            Strings.get(R.string.namereg_review_price),
            Strings.get(if (plan.step == Step.COMMIT) R.string.namereg_review_price_at_step2 else R.string.namereg_review_price_value, ethText(price)),
        )
        val atCommit = op.commitQuoteWei
        if (plan.step == Step.REVEAL && atCommit != null && price > atCommit) {
            rows += NameReviewRow(
                Strings.get(R.string.namereg_review_price_change),
                Strings.get(R.string.namereg_review_price_change_value, ethText(atCommit), ethText(price)),
            )
        }
        if (plan.step != Step.COMMIT) {
            rows += NameReviewRow(
                Strings.get(R.string.namereg_review_buffer),
                Strings.get(R.string.namereg_review_buffer_value, ethText(plan.value), NameContracts.PRICE_BUFFER_PERCENT),
            )
        }
    }
    if (plan.step == Step.RENEW) {
        rows += NameReviewRow(Strings.get(R.string.namereg_review_ownership), Strings.get(R.string.namereg_review_ownership_renew))
    }
    return NameReview(headline, rows, plan.contract)
}

/**
 * A record write's rows (desktop's `presentPlan` record items): which
 * record, its key, what it holds now and what it will hold — written as
 * set by whoever controls the name, so every character that could hide
 * or rearrange text shows as an escape ([Eip712.visible]) — and for an
 * address, whether it's one of this wallet's.
 */
private fun recordRows(op: NameOperation, own: List<WalletAccount>?): List<NameReviewRow> {
    val record = op.record ?: return emptyList()
    val rows = ArrayList<NameReviewRow>()
    rows += NameReviewRow(
        Strings.get(R.string.namereg_review_record),
        Strings.get(
            when (record.kind) {
                NameRecord.Kind.CONTENTHASH -> R.string.namereg_review_record_website
                NameRecord.Kind.ADDRESS -> R.string.namereg_review_record_address
                NameRecord.Kind.TEXT -> R.string.namereg_review_record_text
            },
        ),
    )
    record.key?.let { rows += NameReviewRow(Strings.get(R.string.namereg_review_key), Eip712.visible(it)) }
    val before = op.before.orEmpty()
    // "Unset" as each kind has it: no contenthash bytes, the zero address, an empty text — a text that reads "0x" is text.
    val unset = when (record.kind) {
        NameRecord.Kind.CONTENTHASH -> before.isEmpty() || before == "0x"
        NameRecord.Kind.ADDRESS -> before.isEmpty() || before.equals(NameContracts.ZERO_ADDRESS, ignoreCase = true)
        NameRecord.Kind.TEXT -> before.isEmpty()
    }
    rows += NameReviewRow(Strings.get(R.string.namereg_review_current), if (unset) Strings.get(R.string.namereg_review_empty) else Eip712.visible(before))
    val cleared = when (record.kind) {
        NameRecord.Kind.CONTENTHASH -> record.value == "0x"
        NameRecord.Kind.ADDRESS -> false
        NameRecord.Kind.TEXT -> record.value.isEmpty()
    }
    val next = when {
        record.kind == NameRecord.Kind.CONTENTHASH && op.reference != null -> "bzz://${op.reference}"
        cleared -> Strings.get(R.string.namereg_review_cleared)
        else -> Eip712.visible(record.value)
    }
    rows += NameReviewRow(Strings.get(R.string.namereg_review_new), next)
    if (record.kind == NameRecord.Kind.ADDRESS) {
        val mine = own?.firstOrNull { it.address.equals(record.value, ignoreCase = true) }
        rows += NameReviewRow(
            Strings.get(R.string.namereg_review_belongs_to),
            when {
                mine != null -> Strings.get(R.string.namereg_review_belongs_own, Eip712.visible(mine.name))
                own != null -> Strings.get(R.string.namereg_review_belongs_other, op.name)
                else -> Strings.get(R.string.namereg_review_belongs_unknown)
            },
        )
    }
    return rows
}

/** The step an operation's next review is for, said plainly (the wallet history's label: "record" names a record write). */
internal fun stepLabel(step: Step): String = when (step) {
    Step.COMMIT -> "commit"
    Step.REVEAL -> "reveal"
    Step.RENEW -> "renew"
    Step.RECORD -> "record"
}

/**
 * Where [op] stands, one line: [nowSeconds] (the phone's clock) only says
 * roughly how long until a reveal can go — whether it can is the chain's
 * block time's call ([NameRegistrar.refresh]).
 */
internal fun operationStatus(op: NameOperation, nowSeconds: Long): String {
    val base = when (op.state) {
        State.READY -> when (op.step) {
            Step.COMMIT -> Strings.get(R.string.namereg_op_ready_commit)
            Step.REVEAL -> Strings.get(R.string.namereg_op_ready_reveal)
            Step.RENEW -> Strings.get(R.string.namereg_op_ready_renew)
            Step.RECORD -> Strings.get(R.string.namereg_op_ready_record)
        }
        State.DISPATCHING -> Strings.get(R.string.namereg_op_dispatching)
        State.PENDING -> Strings.get(R.string.namereg_op_pending)
        State.WAITING_COMMITMENT -> {
            val left = (op.readyAt ?: 0) - nowSeconds
            if (left > 5) Strings.get(R.string.namereg_op_waiting, durationText(left)) else Strings.get(R.string.namereg_op_waiting_soon)
        }
        State.COMPLETED -> Strings.get(
            when {
                op.intent == Intent.REGISTER -> R.string.namereg_op_completed_register
                op.intent.recordWrite -> R.string.namereg_op_completed_record
                else -> R.string.namereg_op_completed_renew
            },
        )
        State.STALE -> Strings.get(R.string.namereg_op_stale)
        State.FAILED -> Strings.get(R.string.namereg_state_reverted)
        State.EXPIRED -> Strings.get(R.string.namereg_state_commitment_expired)
        State.DISPATCH_UNKNOWN -> Strings.get(R.string.namereg_state_dispatch_unknown)
        // Reached for more than one reason (a reveal mined with no effect seen, a commitment not seen, before or
        // without any transaction of this step): the registrar's own note is the whole line when it left one.
        State.VERIFICATION_NEEDED -> op.message ?: Strings.get(R.string.namereg_state_verification_needed)
    }
    // A note the registrar left (why a send didn't go, a reverted reveal) comes after, unless it's the same words.
    val note = op.message?.takeIf { it != base && op.state != State.COMPLETED }
    return if (note != null) "$base $note" else base
}

/**
 * What Register or Renew says when it hands back [op], an open operation of
 * the same choice that can't be reviewed now (waiting for its commitment,
 * pending, an unknown outcome): the check makes way for it, so the line says
 * why and where it stands. Null when it's ready, since its review opens
 * instead, and once it has ended (completed, reverted, expired, stale): it no
 * longer carries on, and its card below says how it ended.
 */
internal fun carriedOnNotice(op: NameOperation, nowSeconds: Long): String? {
    if (op.state == State.READY || op.state.terminal) return null
    if (op.intent.recordWrite) return null
    val text = if (op.registering) R.string.namereg_open_registration_continues else R.string.namereg_open_renewal_continues
    return Strings.get(text, op.name, operationStatus(op, nowSeconds))
}

/**
 * The line for the operation [id] that Register or Renew handed back, from
 * [operations] as they are now and the phone's clock: null once it's ready
 * (its card offers the review), ended (its card says how) or gone, or when
 * nothing was handed back.
 */
internal fun carriedOnLine(id: String?, operations: List<NameOperation>?, nowSeconds: Long): String? {
    val op = id?.let { operations?.firstOrNull { o -> o.id == it } } ?: return null
    return carriedOnNotice(op, nowSeconds)
}

/** The deadline line under a reveal that's ready or waiting: when its commitment runs out. */
internal fun revealDeadline(op: NameOperation): String? {
    if (op.step != Step.REVEAL || (op.state != State.READY && op.state != State.WAITING_COMMITMENT)) return null
    val at = op.expiresAt ?: return null
    return Strings.get(R.string.namereg_op_reveal_by, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at * 1000)))
}

/** "45 s", "2 min". */
internal fun durationText(seconds: Long): String =
    if (seconds < 120) Strings.get(R.string.namereg_seconds, seconds.toInt()) else Strings.get(R.string.namereg_minutes, ((seconds + 59) / 60).toInt())

/** A date (seconds since the epoch) for an expiry. */
internal fun dateText(seconds: BigInteger): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(seconds.min(BigInteger.valueOf(Long.MAX_VALUE / 1000)).toLong() * 1000))
