package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.SendAmounts
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/*
 * What a site's transaction or typed data does, in words (#423, audit
 * W21/W22): an ERC-20 transfer or approval, an NFT collection approval,
 * an EIP-2612 or DAI permit, and Permit2's allowances and one-time
 * transfers. Everything here is decoded only when it matches the
 * standard's shape exactly — the selector and the call's exact length
 * for a transaction, every declared field name and type for typed data —
 * so a summary never describes bytes it didn't read; anything else gets
 * the generic sheet, with the raw data under Details. A token is named
 * by its symbol only when it's on the wallet's own list
 * ([X402Payments.knownToken]): never by the site's word, nor an RPC's.
 */

/** How loudly a sheet's note speaks (W24): plain text, an amber card, an error card. */
internal enum class WarningLevel { Info, Caution, Danger }

/** One note on an approval sheet, at its [level]; [tag] names it for tests. */
internal data class SheetWarning(val level: WarningLevel, val text: String, val tag: String? = null)

/** A token a call or a permit names: [symbol] and [decimals] only when the wallet lists it. */
data class TokenRef(val address: String, val symbol: String?, val decimals: Int?) {
    internal val listed: Boolean get() = symbol != null && decimals != null

    /** "USDC", or "token 0xA0b8…eB48" for one the wallet doesn't list. */
    internal val label: String get() = symbol ?: Strings.get(R.string.send_decode_token_unlisted, shortAddress(address))

    companion object {
        /** [chainId] null: the data names no chain, so no list can say what the token is. */
        internal fun of(chainId: Long?, address: String): TokenRef {
            val checksummed = EthereumProvider.checksummed(address) ?: address
            val known = chainId?.let { X402Payments.knownToken(it, address) }
            return TokenRef(checksummed, known?.first, known?.second)
        }
    }
}

/** A transaction's call to a contract, decoded ([TxDecode.call]). */
internal sealed interface DecodedCall {
    /** `transfer(to, amount)`, or `transferFrom(from, to, amount)` when [from] is set. */
    data class Transfer(val token: TokenRef, val to: String, val amount: BigInteger, val from: String? = null) : DecodedCall

    /** `approve(spender, amount)`. */
    data class Approve(val token: TokenRef, val spender: String, val amount: BigInteger) : DecodedCall {
        val unlimited: Boolean get() = TxDecode.unlimited(amount)
    }

    /** `setApprovalForAll(operator, approved)`: every token of [collection]. */
    data class ApproveAll(val collection: String, val operator: String, val approved: Boolean) : DecodedCall
}

/** One token a permit lets its spender take: [expires] in Unix seconds, null for no end. */
data class PermitGrant(val token: TokenRef, val amount: BigInteger, val expires: Long?) {
    val unlimited: Boolean get() = TxDecode.unlimited(amount)
}

/** Typed data that is a permit ([TxDecode.permit]). */
data class DecodedPermit(
    val kind: Kind,
    val spender: String,
    val grants: List<PermitGrant>,
    /** Until when the signature itself can be used (Unix seconds); null for no end. */
    val signatureDeadline: Long?,
    /** Through Permit2 (Uniswap's shared approval contract) rather than the token itself. */
    val permit2: Boolean,
) {
    enum class Kind {
        /** An allowance the spender can draw on, until it's used up, changed or expires. */
        Allowance,

        /** One transfer, once ([PermitGrant.amount] at most). */
        OneTime,
    }
}

internal object TxDecode {
    /** The largest uint256: what "unlimited" approvals use. */
    val MAX_UINT256: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

    /** The largest uint160: Permit2's "unlimited". Anything this large or larger is called unlimited. */
    val MAX_UINT160: BigInteger = BigInteger.ONE.shiftLeft(160) - BigInteger.ONE

    /** A deadline further off than this (or none) makes a permit long-lived. */
    const val LONG_LIVED_SECONDS = 30L * 24 * 60 * 60

    /** Uniswap's Permit2, at the same address on every chain. */
    const val PERMIT2 = "0x000000000022D473030F116dDEE9F6B43aC78BA3"

    const val TRANSFER = "a9059cbb"
    const val TRANSFER_FROM = "23b872dd"
    const val APPROVE = "095ea7b3"
    const val SET_APPROVAL_FOR_ALL = "a22cb465"

    /** Past year 9999 a deadline means "never" (and wouldn't fit a date). */
    private const val NO_END_SECONDS = 253_402_300_799L

    fun unlimited(amount: BigInteger): Boolean = amount >= MAX_UINT160

    /** [deadline] (Unix seconds; null for none) is further than [LONG_LIVED_SECONDS] past [nowSeconds]. */
    fun longLived(deadline: Long?, nowSeconds: Long): Boolean = deadline == null || deadline - nowSeconds > LONG_LIVED_SECONDS

    /**
     * The call [data] makes on contract [to] (on [chainId]), or null when
     * it isn't one of the calls above in exactly its standard encoding: the
     * selector, then one 32-byte word per argument and nothing more, each
     * address word with its upper 12 bytes zero and each bool 0 or 1.
     * `transfer`, `approve` and `transferFrom` are decoded only for a
     * listed token: on any other contract they may be an NFT's (ERC-721,
     * or a pre-ERC-721 one like CryptoKitties for `transfer`), whose last
     * argument is a token ID, not an amount.
     */
    fun call(to: String, data: ByteArray, chainId: Long): DecodedCall? {
        if (data.size < 4) return null
        val words = (data.size - 4) / 32
        if ((data.size - 4) % 32 != 0) return null
        fun word(i: Int) = data.copyOfRange(4 + i * 32, 4 + (i + 1) * 32)
        fun address(i: Int): String? {
            val w = word(i)
            if ((0 until 12).any { w[it] != 0.toByte() }) return null
            return EthereumProvider.checksummed("0x" + hexOf(w.copyOfRange(12, 32)))
        }
        fun uint(i: Int) = BigInteger(1, word(i))
        return when (hexOf(data, 4)) {
            // ERC-721's transferFrom and approve have these same selectors and
            // encodings, the last word a token ID rather than an amount (R1-M1),
            // and so has pre-ERC-721 NFTs' transfer(address,uint256) — CryptoKitties'
            // hands over kitty #id (R5-M2): each read as ERC-20 only for a token on
            // the wallet's own list.
            TRANSFER -> if (words != 2) null else {
                val token = TokenRef.of(chainId, to).takeIf { it.listed } ?: return null
                DecodedCall.Transfer(token, address(0) ?: return null, uint(1))
            }
            TRANSFER_FROM -> if (words != 3) null else {
                val token = TokenRef.of(chainId, to).takeIf { it.listed } ?: return null
                DecodedCall.Transfer(token, address(1) ?: return null, uint(2), from = address(0) ?: return null)
            }
            APPROVE -> if (words != 2) null else {
                val token = TokenRef.of(chainId, to).takeIf { it.listed } ?: return null
                DecodedCall.Approve(token, address(0) ?: return null, uint(1))
            }
            SET_APPROVAL_FOR_ALL -> if (words != 2) null else {
                val flag = uint(1)
                if (flag > BigInteger.ONE) return null
                DecodedCall.ApproveAll(EthereumProvider.checksummed(to) ?: to, address(0) ?: return null, flag == BigInteger.ONE)
            }
            else -> null
        }
    }

    /**
     * [data] as a permit, or null when it isn't one of: EIP-2612 `Permit`,
     * DAI's `Permit`, or Permit2's `PermitSingle`, `PermitBatch`,
     * `PermitTransferFrom` and `PermitBatchTransferFrom` — each matched on
     * its exact field names and types, with every field present, and the
     * token (or Permit2) as the domain's signed `verifyingContract`.
     * [chainId] is the chain the signature is bound to, for naming tokens:
     * null when the domain signs none (R1-M2), and then no token is named
     * by symbol, since the same address may be another token elsewhere.
     */
    fun permit(data: Eip712.TypedData, chainId: Long?): DecodedPermit? = try {
        decodePermit(data, chainId)
    } catch (e: Exception) {
        // A value that isn't what its type says: not a permit this sheet can describe.
        null
    }

    private fun decodePermit(data: Eip712.TypedData, chainId: Long?): DecodedPermit? {
        val contract = Eip712.signedDomainString(data, "verifyingContract")?.trim() ?: return null
        val contractAddress = EthereumProvider.checksummed(contract.takeIf { ADDRESS.matches(it) } ?: return null) ?: return null
        val permit2 = contractAddress.equals(PERMIT2, ignoreCase = true)
        val types = data.types
        val m = data.message
        fun shape(name: String) = types[name]?.map { it.name to it.type }
        fun addr(o: JSONObject, k: String): String {
            val s = (o.opt(k) as? String)?.trim() ?: throw IllegalArgumentException(k)
            require(ADDRESS.matches(s))
            return EthereumProvider.checksummed(s) ?: throw IllegalArgumentException(k)
        }
        fun num(o: JSONObject, k: String): BigInteger {
            val v = o.opt(k)
            require(v != null && v != JSONObject.NULL)
            return Eip712.integer(v, k).also { require(it.signum() >= 0) }
        }
        fun seconds(v: BigInteger): Long? = if (v.signum() == 0 || v > BigInteger.valueOf(NO_END_SECONDS)) null else v.toLong()
        fun obj(o: JSONObject, k: String) = o.opt(k) as? JSONObject ?: throw IllegalArgumentException(k)
        fun arr(o: JSONObject, k: String) = o.opt(k) as? JSONArray ?: throw IllegalArgumentException(k)
        return when (data.primaryType) {
            "Permit" -> if (permit2) null else when (shape("Permit")) {
                EIP2612 -> DecodedPermit(
                    DecodedPermit.Kind.Allowance,
                    addr(m, "spender"),
                    listOf(PermitGrant(TokenRef.of(chainId, contractAddress), num(m, "value"), expires = null)),
                    // A deadline of 0 is already past: it can't be used at all, so "no end" would be wrong.
                    signatureDeadline = num(m, "deadline").let { if (it.signum() == 0) 0L else seconds(it) },
                    permit2 = false,
                )
                DAI -> {
                    val allowed = m.opt("allowed") as? Boolean ?: return null
                    DecodedPermit(
                        DecodedPermit.Kind.Allowance,
                        addr(m, "spender"),
                        // DAI's permit is all or nothing: allowed means every token, revoked means none.
                        listOf(PermitGrant(TokenRef.of(chainId, contractAddress), if (allowed) MAX_UINT256 else BigInteger.ZERO, expires = null)),
                        // DAI's expiry 0 means it never expires.
                        signatureDeadline = seconds(num(m, "expiry")),
                        permit2 = false,
                    )
                }
                else -> null
            }
            "PermitSingle", "PermitBatch" -> {
                if (!permit2 || shape("PermitDetails") != PERMIT_DETAILS) return null
                val batch = data.primaryType == "PermitBatch"
                if (shape(data.primaryType) != (if (batch) PERMIT_BATCH else PERMIT_SINGLE)) return null
                fun grant(d: JSONObject) = PermitGrant(
                    TokenRef.of(chainId, addr(d, "token")),
                    num(d, "amount"),
                    // Permit2's expiration 0 means "this block only", not "never": treat as now.
                    expires = num(d, "expiration").let { if (it.signum() == 0) 0L else seconds(it) },
                )
                val grants = if (batch) {
                    val a = arr(m, "details")
                    (0 until a.length()).map { grant(a.get(it) as? JSONObject ?: return null) }
                } else {
                    listOf(grant(obj(m, "details")))
                }
                if (grants.isEmpty()) return null
                DecodedPermit(DecodedPermit.Kind.Allowance, addr(m, "spender"), grants, seconds(num(m, "sigDeadline")), permit2 = true)
            }
            "PermitTransferFrom", "PermitBatchTransferFrom" -> {
                if (!permit2 || shape("TokenPermissions") != TOKEN_PERMISSIONS) return null
                val batch = data.primaryType == "PermitBatchTransferFrom"
                if (shape(data.primaryType) != (if (batch) PERMIT_BATCH_TRANSFER else PERMIT_TRANSFER)) return null
                fun grant(p: JSONObject) = PermitGrant(TokenRef.of(chainId, addr(p, "token")), num(p, "amount"), expires = null)
                val grants = if (batch) {
                    val a = arr(m, "permitted")
                    (0 until a.length()).map { grant(a.get(it) as? JSONObject ?: return null) }
                } else {
                    listOf(grant(obj(m, "permitted")))
                }
                if (grants.isEmpty()) return null
                num(m, "nonce")
                DecodedPermit(DecodedPermit.Kind.OneTime, addr(m, "spender"), grants, seconds(num(m, "deadline")), permit2 = true)
            }
            else -> null
        }
    }

    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    private val EIP2612 = listOf("owner" to "address", "spender" to "address", "value" to "uint256", "nonce" to "uint256", "deadline" to "uint256")
    private val DAI = listOf("holder" to "address", "spender" to "address", "nonce" to "uint256", "expiry" to "uint256", "allowed" to "bool")
    private val PERMIT_DETAILS = listOf("token" to "address", "amount" to "uint160", "expiration" to "uint48", "nonce" to "uint48")
    private val PERMIT_SINGLE = listOf("details" to "PermitDetails", "spender" to "address", "sigDeadline" to "uint256")
    private val PERMIT_BATCH = listOf("details" to "PermitDetails[]", "spender" to "address", "sigDeadline" to "uint256")
    private val TOKEN_PERMISSIONS = listOf("token" to "address", "amount" to "uint256")
    private val PERMIT_TRANSFER = listOf("permitted" to "TokenPermissions", "spender" to "address", "nonce" to "uint256", "deadline" to "uint256")
    private val PERMIT_BATCH_TRANSFER =
        listOf("permitted" to "TokenPermissions[]", "spender" to "address", "nonce" to "uint256", "deadline" to "uint256")
}

/** "20 USDC", "UNLIMITED USDC", or "20000000 units of token 0xA0b8…eB48" for one the wallet doesn't list. */
internal fun tokenAmountText(token: TokenRef, amount: BigInteger): String = when {
    TxDecode.unlimited(amount) -> Strings.get(R.string.send_decode_unlimited, token.label)
    token.listed -> "${SendAmounts.exact(amount, token.decimals!!)} ${token.symbol}"
    else -> Strings.get(R.string.send_decode_units, amount.toString(), shortAddress(token.address))
}

/** [seconds] (Unix) as a date and time; "no end date" for null, "right away" for 0. */
internal fun deadlineText(seconds: Long?, format: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)): String =
    when (seconds) {
        null -> Strings.get(R.string.send_decode_no_end)
        // 0: already over (a permit's deadline), or Permit2's "this block only".
        0L -> Strings.get(R.string.send_decode_ended)
        else -> format.format(Date(seconds * 1000))
    }

/** The hero line for a decoded call: "Send 20 USDC to 0xd8dA…6045", "Allow 0x1111…0582 to spend UNLIMITED USDC". */
internal fun callHeadline(call: DecodedCall): String = when (call) {
    is DecodedCall.Transfer -> if (call.from != null) {
        Strings.get(R.string.send_decode_transfer_from, tokenAmountText(call.token, call.amount), shortAddress(call.from), shortAddress(call.to))
    } else {
        Strings.get(R.string.send_decode_transfer, tokenAmountText(call.token, call.amount), shortAddress(call.to))
    }
    is DecodedCall.Approve -> if (call.amount.signum() == 0) {
        Strings.get(R.string.send_decode_approve_revoke, shortAddress(call.spender), call.token.label)
    } else if (call.unlimited) {
        Strings.get(R.string.send_decode_approve, shortAddress(call.spender), tokenAmountText(call.token, call.amount))
    } else {
        Strings.get(R.string.send_decode_approve_up_to, shortAddress(call.spender), tokenAmountText(call.token, call.amount))
    }
    is DecodedCall.ApproveAll -> if (call.approved) {
        Strings.get(R.string.send_decode_approve_all, shortAddress(call.operator), shortAddress(call.collection))
    } else {
        Strings.get(R.string.send_decode_approve_all_revoke, shortAddress(call.operator), shortAddress(call.collection))
    }
}

/** The warning a decoded call carries, if any: a token approval is a standing permission. */
internal fun callWarning(call: DecodedCall): SheetWarning? = when (call) {
    // Only ever a listed token's (an unlisted contract's transfer may be an NFT's, R5-M2).
    is DecodedCall.Transfer -> null
    is DecodedCall.Approve -> when {
        call.amount.signum() == 0 -> null
        call.unlimited -> SheetWarning(
            WarningLevel.Danger,
            Strings.get(R.string.send_decode_approve_unlimited_danger, shortAddress(call.spender), call.token.label),
            "approve-unlimited",
        )
        else -> SheetWarning(
            WarningLevel.Caution,
            Strings.get(R.string.send_decode_approve_caution, shortAddress(call.spender), tokenAmountText(call.token, call.amount)),
            "approve",
        )
    }
    is DecodedCall.ApproveAll -> if (!call.approved) null else {
        SheetWarning(WarningLevel.Danger, Strings.get(R.string.send_decode_approve_all_danger, shortAddress(call.operator)), "approve-all")
    }
}

/** The hero line for a permit: "Allow 0x1111…0582 to spend UNLIMITED USDC". */
internal fun permitHeadline(p: DecodedPermit): String {
    val spender = shortAddress(p.spender)
    val one = p.grants.singleOrNull()
    return when (p.kind) {
        DecodedPermit.Kind.Allowance -> when {
            one == null -> Strings.plural(R.plurals.send_decode_permit_tokens, p.grants.size, spender, p.grants.size)
            one.amount.signum() == 0 -> Strings.get(R.string.send_decode_approve_revoke, spender, one.token.label)
            one.unlimited -> Strings.get(R.string.send_decode_approve, spender, tokenAmountText(one.token, one.amount))
            else -> Strings.get(R.string.send_decode_approve_up_to, spender, tokenAmountText(one.token, one.amount))
        }
        DecodedPermit.Kind.OneTime -> if (one == null) {
            Strings.plural(R.plurals.send_decode_permit_transfer_tokens, p.grants.size, spender, p.grants.size)
        } else {
            Strings.get(R.string.send_decode_permit_transfer, spender, tokenAmountText(one.token, one.amount))
        }
    }
}

/** A permit's lines under the headline: each token's amount and, for an allowance, until when; then the signature's own deadline. */
internal fun permitLines(p: DecodedPermit): List<String> {
    val lines = ArrayList<String>()
    for (g in p.grants) {
        val amount = tokenAmountText(g.token, g.amount)
        lines += when {
            p.kind == DecodedPermit.Kind.OneTime -> amount
            !p.permit2 -> Strings.get(R.string.send_decode_until_changed, amount)
            else -> Strings.get(R.string.send_decode_until, amount, deadlineText(g.expires))
        }
    }
    lines += Strings.get(R.string.send_decode_signature_until, deadlineText(p.signatureDeadline))
    if (p.permit2) lines += Strings.get(R.string.send_decode_via_permit2)
    return lines
}

/**
 * How loud a permit's warning is (W21): Danger for an unlimited amount, or
 * a long-lived one — the signature usable (or a Permit2 allowance lasting)
 * more than [TxDecode.LONG_LIVED_SECONDS] — Caution for any other; a
 * revoke (amount 0) only Info.
 */
internal fun permitLevel(p: DecodedPermit, nowSeconds: Long): WarningLevel = when {
    p.grants.all { it.amount.signum() == 0 } -> WarningLevel.Info
    p.grants.any { it.unlimited } -> WarningLevel.Danger
    TxDecode.longLived(p.signatureDeadline, nowSeconds) -> WarningLevel.Danger
    p.kind == DecodedPermit.Kind.Allowance && p.permit2 && p.grants.any { TxDecode.longLived(it.expires, nowSeconds) } -> WarningLevel.Danger
    else -> WarningLevel.Caution
}

/** The card under a permit's summary, at [permitLevel]. */
internal fun permitWarning(p: DecodedPermit, nowSeconds: Long): SheetWarning {
    val spender = shortAddress(p.spender)
    val level = permitLevel(p, nowSeconds)
    val text = when {
        level == WarningLevel.Info -> Strings.get(R.string.send_decode_permit_revoke_note)
        p.grants.any { it.unlimited } -> Strings.get(R.string.send_decode_permit_unlimited_danger, spender)
        level == WarningLevel.Danger -> Strings.get(R.string.send_decode_permit_long_danger, spender)
        p.kind == DecodedPermit.Kind.OneTime -> Strings.get(R.string.send_decode_permit_transfer_caution, spender)
        else -> Strings.get(R.string.send_decode_permit_caution, spender)
    }
    return SheetWarning(level, text, "permit-${level.name.lowercase()}")
}
