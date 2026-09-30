package baby.freedom.mobile.wallet

import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.NameSystem
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Said
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A send the user can't make as asked, with what to tell them ([message],
 * in the app language) and what to tell a site or a peer that asked for
 * it ([english]: developer-facing, never the user's language, #280).
 */
class SendException private constructor(message: String, cause: Throwable?, val english: String) : Exception(message, cause) {
    constructor(said: Said, cause: Throwable? = null) : this(said.text, cause, said.english)

    companion object {
        /**
         * One whose [message] is already English (a literal, a node's own
         * words): the same text for the user and the page. There is no
         * plain `String` constructor, so an app-language text can't reach
         * a page by default (#280): pass a [Said] for one.
         */
        fun ofEnglish(text: String, cause: Throwable? = null) = SendException(text, cause, text)
    }
}

/** Typed amounts: `1.5` of a token with [decimals] decimals → base units. */
object SendAmounts {
    /**
     * [input] in base units, or null when it isn't a plain positive
     * decimal with at most [decimals] digits after the point. Either `.`
     * or `,` is the decimal point (a keyboard's decimal key types the
     * locale's); no grouping, no sign, no exponent. [ambiguous] input is
     * refused too.
     */
    fun parse(input: String, decimals: Int): BigInteger? {
        if (ambiguous(input)) return null
        val t = input.trim().replace(',', '.')
        if (!AMOUNT.matches(t)) return null
        val whole = t.substringBefore('.').ifEmpty { "0" }
        val fraction = t.substringAfter('.', "")
        if (fraction.length > decimals) return null
        val raw = BigInteger(whole + fraction.padEnd(decimals, '0'))
        return raw.takeIf { it.signum() > 0 && it.bitLength() <= 256 }
    }

    /** [raw] base units exactly, every significant digit kept (for Max and the review). */
    fun exact(raw: BigInteger, decimals: Int): String {
        require(raw.signum() >= 0)
        if (decimals == 0) return raw.toString()
        val (whole, rest) = raw.divideAndRemainder(BigInteger.TEN.pow(decimals))
        val fraction = rest.toString().padStart(decimals, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    /** Digits, at most one point, at least one digit. */
    private val AMOUNT = Regex("^(\\d+\\.?\\d*|\\.\\d+)$")

    /**
     * Whether [input] reads as both a decimal comma and a thousands
     * separator: `1,234` is 1.234 on a German keyboard, but it's also how
     * the balance just below the field writes 1234 ([TokenAmounts.format]).
     * Taken as either, some user sends 1000 times more or less than they
     * meant, so it's neither: the field asks for a point or no comma.
     */
    fun ambiguous(input: String): Boolean = GROUPED.matches(input.trim())

    private val GROUPED = Regex("^[1-9]\\d{0,2},\\d{3}$")
}

/** The recipient field. */
object Recipients {
    sealed interface Parsed {
        /** [address] EIP-55 checksummed. */
        data class Ok(val address: String) : Parsed

        /**
         * A name to look up (#277): [name] in its ENSIP-15 form. Not a
         * recipient until [EnsResolver.resolveAddress] answers and
         * [resolved] accepts the address it gives
     * ([baby.freedom.mobile.ens.EnsResolver.resolveAddress]).
         */
        data class Name(val name: String) : Parsed

        data class Invalid(val reason: String) : Parsed
    }

    /**
     * [input] as a recipient for [token]: a `0x` address of 40 hex
     * digits. Mixed case must be a correct EIP-55 checksum (a typo in a
     * checksummed address is caught, not sent); all-lower or all-upper
     * carries no checksum and is taken as typed. The zero address, and a
     * token's own contract for that token, are refused: what's sent there
     * is gone.
     *
     * With [names] (Send's own field, #277), anything else shaped like a
     * dotted name — `alice.eth`, `alice.box`, `alice.wei`, `alice.gwei`,
     * a DNS name imported into ENS like `gregskril.com` — is a [Parsed.Name]
     * to look up; a `.tez` name isn't an Ethereum account and is refused.
     */
    fun parse(input: String, token: Token, names: Boolean = false): Parsed {
        val t = input.trim()
        if (t.isEmpty()) return Parsed.Invalid(Strings.get(if (names) R.string.send_recipient_empty_names else R.string.send_recipient_empty))
        if (!EthTransaction.ADDRESS.matches(t)) {
            if (names && looksLikeName(t)) {
                // The resolver's own normalization, so a name refused here is
                // exactly one it would refuse (`a_b.eth`), said up front.
                val name = try {
                    EnsNormalize.fastNormalize(t)
                } catch (_: EnsNormalize.InvalidNameException) {
                    null
                } ?: return Parsed.Invalid(Strings.get(R.string.send_recipient_bad_name))
                if (NameSystem.forName(name) == NameSystem.TEZOS) {
                    return Parsed.Invalid(Strings.get(R.string.send_recipient_tezos))
                }
                return Parsed.Name(name)
            }
            return Parsed.Invalid(
                Strings.get(if (names) R.string.send_recipient_not_address_or_name else R.string.send_recipient_not_address),
            )
        }
        val digits = t.substring(2)
        val checksummed = checksum(digits.lowercase())
        val mixed = digits.any { it in 'a'..'f' } && digits.any { it in 'A'..'F' }
        if (mixed && checksummed != t) return Parsed.Invalid(Strings.get(R.string.send_recipient_checksum_typo))
        return accept(checksummed, token)
    }

    /**
     * The address a name resolved to (#277, lowercase from the resolver)
     * as a recipient for [token]: checksummed, and held to the same
     * refusals as a typed one — a name can point at a token's contract too.
     */
    fun resolved(address: String, token: Token): Parsed {
        if (!EthTransaction.ADDRESS.matches(address)) return Parsed.Invalid(Strings.get(R.string.send_recipient_record_not_address))
        return accept(checksum(address.substring(2).lowercase()), token)
    }

    private fun accept(checksummed: String, token: Token): Parsed {
        if (checksummed.substring(2).all { it == '0' }) return Parsed.Invalid(Strings.get(R.string.send_recipient_zero))
        if (token.address != null && token.address.equals(checksummed, ignoreCase = true)) {
            return Parsed.Invalid(Strings.get(R.string.send_recipient_token_contract, token.symbol))
        }
        return Parsed.Ok(checksummed)
    }

    /**
     * Shaped like a name: dotted, every label non-empty, nothing a name
     * can't hold (spaces, URL punctuation, control characters) —
     * desktop's `isPotentialEnsName`.
     */
    internal fun looksLikeName(value: String): Boolean =
        '.' in value &&
            value.none { it.isWhitespace() || it in "/:@?#%\\" || it.code < 32 || it.code == 127 } &&
            value.split('.').all { it.isNotEmpty() }

    /**
     * Why a name's answer (#277) gives nothing to send to on [chainName],
     * for the field to say; `null` for an address (which [resolved] then
     * judges).
     */
    fun lookupProblem(result: EnsAddressResult, chainName: String): String? = when (result) {
        is EnsAddressResult.Ok -> null
        is EnsAddressResult.NoAddress -> when (result.reason) {
            "NO_RESOLVER" -> Strings.get(R.string.send_name_no_resolver, result.name)
            "NO_ADDRESS" -> Strings.get(R.string.send_name_no_address, result.name, chainName)
            "CHAIN_UNSUPPORTED" -> Strings.get(R.string.send_name_chain_unsupported, NameSystem.forName(result.name).label, chainName)
            "CHAIN_ID_UNSUPPORTED" -> Strings.get(R.string.send_name_chain_id_unsupported, chainName)
            else -> Strings.get(R.string.send_name_not_account, result.name)
        }
        is EnsAddressResult.Conflict -> if (result.subject == EnsResult.Conflict.Subject.RECORD) {
            Strings.get(
                R.string.send_name_conflict_record,
                result.name,
                result.groups.joinToString("; ") { g -> "${g.answer} (${g.hosts.joinToString(", ")})" },
            )
        } else {
            Strings.get(R.string.send_name_conflict_block, result.name)
        }
        is EnsAddressResult.Error -> Strings.get(R.string.send_name_lookup_error, result.name, result.error)
    }

    /**
     * Whether the re-check just before signing (#277) still backs the
     * send the user reviewed: [name] must still resolve to [address]
     * (`null`: it does), and an answer only one server vouches for only
     * if the user accepted exactly that one ([unverifiedAccepted]). Else
     * why not, for the review to say.
     */
    fun recheck(name: String, address: String, after: EnsAddressResult, unverifiedAccepted: Boolean): String? = when (after) {
        is EnsAddressResult.Ok -> when {
            !after.address.equals(address, ignoreCase = true) ->
                Strings.get(R.string.send_recheck_moved, name, checksum(after.address.substring(2).lowercase()))
            !after.trust.verified && !unverifiedAccepted -> Strings.get(R.string.send_recheck_unverified, name)
            else -> null
        }
        is EnsAddressResult.NoAddress -> Strings.get(R.string.send_recheck_no_address, name)
        is EnsAddressResult.Conflict -> if (after.subject == EnsResult.Conflict.Subject.RECORD) {
            Strings.get(R.string.send_recheck_conflict_record, name)
        } else {
            Strings.get(R.string.send_recheck_conflict_block, name)
        }
        is EnsAddressResult.Error -> if (after.retryable) {
            Strings.get(R.string.send_recheck_error_retry, name, after.error)
        } else {
            Strings.get(R.string.send_recheck_error, name, after.error)
        }
    }

    /**
     * Whether asking again could give a different answer for [result]:
     * a transport failure marked retryable, or servers that disagreed.
     * Not a malformed name, a record that isn't an address, or an answer
     * that says there's nothing to send to — the same ask fails the same
     * way, so no Try again is offered for it.
     */
    fun retryable(result: EnsAddressResult): Boolean = when (result) {
        is EnsAddressResult.Error -> result.retryable
        is EnsAddressResult.Conflict -> true
        is EnsAddressResult.Ok, is EnsAddressResult.NoAddress -> false
    }

    private fun checksum(lowerHex: String): String =
        NodeIdentity.checksum(ByteArray(20) { i -> lowerHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
}

/**
 * A transaction someone else composed, for the wallet to send: a site's
 * `eth_sendTransaction` (#110), with the site that asked ([origin], its
 * provider origin key) — or, with a null [origin], desktop Freedom's over
 * a scanned OpenLV pairing code (#113), which can't say who made the code.
 * [data] is the call data exactly as asked, and [gasLimit] the gas limit
 * it named, if any.
 *
 * With a [safe], the wallet composed it itself for one of its Safe
 * accounts (#141): the Safe's activation or an `execTransaction` its
 * owners signed, sent and paid for by one of this wallet's owner
 * accounts. [origin] is null then — no site or pairing code asked.
 * Likewise with a [swarm]: funding the Swarm node and buying its stamp
 * (#115).
 */
class DappCall(
    val origin: String?,
    val data: ByteArray,
    val gasLimit: BigInteger?,
    val safe: SafeCallLabel? = null,
    val swarm: SwarmFundLabel? = null,
) {
    init {
        require(gasLimit == null || gasLimit.signum() > 0) { "gas limit" }
        require(safe == null || origin == null) { "a Safe's own call has no site" }
        require(swarm == null || (origin == null && safe == null)) { "the node's funding is the wallet's own call" }
    }

    // By content, so a send read back from the journal equals the one that was written.
    override fun equals(other: Any?): Boolean =
        other is DappCall && origin == other.origin && data.contentEquals(other.data) && gasLimit == other.gasLimit &&
            safe == other.safe && swarm == other.swarm

    override fun hashCode(): Int =
        (((origin.hashCode() * 31 + data.contentHashCode()) * 31 + gasLimit.hashCode()) * 31 + safe.hashCode()) * 31 + swarm.hashCode()
}

/**
 * The wallet's own `SwarmNodeFunder` call (#115, [SwarmFunder]): it funds
 * the Swarm node [node] (EIP-55) and buys it batch [batchId] (64 lowercase
 * hex), [depth] deep for about [days] days — which the node connects once
 * the call is mined.
 */
data class SwarmFundLabel(val node: String, val batchId: String, val depth: Int, val days: Long)

/** Which Safe account (#141) a wallet-composed call is for ([address], shown by [name]), and whether it [activates] it or executes a transaction its owners signed. */
data class SafeCallLabel(val address: String, val name: String, val activates: Boolean)

/**
 * What the user asked for: [amount] base units of [token] from [from] to
 * [to] on [chain] — or, with [dapp], a transaction someone else composed:
 * [amount] of the native currency (which may be none) and its call data
 * to [to].
 */
data class SendRequest(
    val chain: Chain,
    val token: Token,
    val from: WalletAccount,
    /** EIP-55 checksummed ([Recipients.parse]). */
    val to: String,
    val amount: BigInteger,
    val dapp: DappCall? = null,
    /**
     * The name the user typed for [to] (#277), shown beside it on the
     * review, the status and in history. A label only: [to] is what's
     * signed and journalled.
     */
    val toName: String? = null,
    /**
     * The user said, for [toName], to send to [to] though only one
     * server vouched for it (#277). Carried with the request — and
     * journalled — so a Review again, even from a reopened page, re-checks
     * against the acceptance actually given: an answer still that one
     * server's, for this same [to], still counts as accepted.
     */
    val toNameAccepted: Boolean = false,
) {
    init {
        require(token.chainId == chain.id) { "the token is on another chain" }
        if (dapp == null) {
            require(amount.signum() > 0) { "nothing to send" }
        } else {
            require(token.isNative) { "a composed transaction carries the native currency" }
            require(amount.signum() >= 0 && amount.bitLength() <= 256) { "a value out of range" }
        }
    }

    /**
     * What goes on chain: native passes straight through; an ERC-20 is a
     * call to its contract with `transfer(to, amount)` and no value; a
     * composed one ([dapp]) is its own call data, as it asked.
     */
    fun call(): Triple<String, BigInteger, ByteArray> = when {
        dapp != null -> Triple(to, amount, dapp.data)
        token.address == null -> Triple(to, amount, ByteArray(0))
        else -> Triple(token.address, BigInteger.ZERO, Erc20.transferData(to, amount))
    }
}

/**
 * A priced, nonce'd transaction for one [SendRequest], ready for the
 * review. [nativeBalance] / [tokenBalance] are what the account held
 * when it was prepared.
 */
data class SendQuote(
    val request: SendRequest,
    val tx: EthTransaction,
    val nativeBalance: BigInteger,
    val tokenBalance: BigInteger?,
    /** Wall-clock millis; a quote older than [WalletSender.QUOTE_TTL_MS] is priced again before signing. */
    val preparedAt: Long,
    /** How the nonce was read (the balances are read the same way). */
    val nonceTrust: ChainTrust,
    /**
     * The hash of a send the user stopped tracking while it could still
     * land, which this one replaces: same nonce, a higher fee, so only
     * one of the two can ever be mined ([NonceTracker.abandon]).
     */
    val replaces: String? = null,
    /**
     * How many sends from this account on this chain [WalletSender.submit]
     * had started when this was priced: once another has started since,
     * it may have gone out on this one's nonce, and this is priced again
     * rather than signed beside it.
     */
    val sendsBefore: Long = 0,
    /**
     * Priced as Max ([WalletSender.prepare]'s `all`): [WalletSender.reprice]
     * prices it as Max again, so a native send whose fee rose meanwhile
     * is the balance less the new fee, not the old amount plus it (more
     * than the account holds).
     */
    val all: Boolean = false,
    /**
     * On an OP Stack rollup (Base), what posting the transaction to L1
     * may cost on top of its gas ([WalletSender.l1Fee]), with headroom;
     * zero elsewhere. The chain takes it from the sender's balance too,
     * and refuses a send the balance can't cover it for.
     */
    val l1Fee: BigInteger = BigInteger.ZERO,
) {
    /** The most the send can cost in fees: its gas at the fee cap, plus [l1Fee]. */
    val maxFee: BigInteger get() = tx.maxFee + l1Fee

    /** For a native send, the amount plus the most the fee can be; null for a token (two currencies). */
    val nativeTotal: BigInteger? get() = if (request.token.isNative) request.amount + maxFee else null
}

/**
 * Fees for the next transaction (#105). With a base fee (EIP-1559):
 * the node's suggested tip, but at least [MIN_TIP_WEI] — iOS's
 * `GasOracle` floor; one lowballing RPC must not leave the send, and
 * every one queued behind its nonce, stuck — and at most [tipCeiling]
 * (#233): the tip goes to the block proposer, so an RPC that reports
 * 5000 gwei would otherwise burn the balance. The fee cap is twice the
 * latest base fee plus the tip, desktop's market preset: headroom for
 * the base fee to keep rising for a few blocks between quote and
 * inclusion. Unused headroom is never charged. Without a base fee:
 * legacy, at the node's gas price — which can't be clamped (too low
 * and the send never mines), so one above [legacyCap] is priced but
 * never sent without a sheet ([quiet]).
 */
class GasOracle(private val rpc: WalletRpc) {
    suspend fun fees(chainId: Long): EthTransaction.Fees {
        val baseFee = rpc.latestBaseFee(chainId).value ?: return legacy(rpc.gasPrice(chainId).value)
        val tip = try {
            rpc.maxPriorityFeePerGas(chainId).value
        } catch (e: ChainRpcException) {
            // An RPC without the method (it's not standard JSON-RPC): the floor is a fine tip.
            null
        }
        // Only a tip past the cap needs a base fee it can trust; the usual one needs no extra read.
        val trusted = if (tip != null && tip > tipCap(chainId)) trustedBaseFee(chainId) else null
        return eip1559(baseFee, tip, chainId, trustedBaseFee = trusted)
    }

    /**
     * A base fee that isn't one public RPC's word (#233): a quorum's
     * agreement or the user's own RPC. The latest block can't be
     * agreed on — RPCs are a block apart, and clients put different
     * fields in a block — so this reads `eth_feeHistory` for one block
     * pinned a couple behind the head: a small answer that every RPC
     * gives the same way for the same block.
     *
     * The head that block is pinned from is one RPC's word (a quorum on
     * `eth_blockNumber` rarely forms), and an RPC that picks an old,
     * congested block would have every honest RPC verify a base fee the
     * chain paid years ago, lifting the tip's ceiling to it (R2-F1). So
     * the block must also be recent: the RPCs must agree, the same
     * trusted way, that block pinned + [RECENT_WINDOW] doesn't exist
     * yet. A lying head can then move the pin back by no more than that
     * window, onto a base fee the chain really had moments ago; a head
     * in the future pins a block no honest RPC has, and nothing agrees.
     * Null when nothing agreed, the read failed, or the pin isn't recent.
     */
    private suspend fun trustedBaseFee(chainId: Long): BigInteger? = try {
        val head = rpc.blockNumber(chainId).value
        val pinned = maxOf(0L, head - PINNED_BEHIND)
        val read = rpc.baseFeeAt(chainId, pinned)
        read.value?.takeIf { trusted(read.trust) }?.takeIf {
            val later = rpc.blockExists(chainId, pinned + RECENT_WINDOW)
            !later.value && trusted(later.trust)
        }
    } catch (e: ChainRpcException) {
        null
    }

    companion object {
        /** 1 gwei, the tip most wallets bid by default. */
        val MIN_TIP_WEI: BigInteger = BigInteger.valueOf(1_000_000_000L)

        private fun gwei(n: Long): BigInteger = BigInteger.valueOf(n) * BigInteger.valueOf(1_000_000_000L)

        /**
         * The most tip per gas bid on no other evidence (#233): 5 gwei on
         * the built-in chains (Ethereum, Gnosis, Base), where wallets bid
         * 0.01–2; 50 gwei elsewhere, where a chain may ask for more
         * (Polygon won't take under 25–30).
         */
        internal fun tipCap(chainId: Long): BigInteger = if (BuiltInChains.isBuiltIn(chainId)) gwei(5) else gwei(50)

        /**
         * The most legacy gas price taken on one RPC's word (#233): 100
         * gwei on the built-in chains (all EIP-1559, so a legacy read
         * there is itself odd), 500 elsewhere.
         */
        internal fun legacyCap(chainId: Long): BigInteger = if (BuiltInChains.isBuiltIn(chainId)) gwei(100) else gwei(500)

        /** How far behind the head [trustedBaseFee] reads, so RPCs a block or two behind have it too. */
        private const val PINNED_BEHIND = 2L

        /**
         * How many blocks past the pinned one must not exist yet for
         * [trustedBaseFee] to take its base fee: room for honest RPCs a
         * few blocks apart, and little enough that a lying head can only
         * pin a base fee from moments ago. A chain whose blocks come
         * faster than the reads finish just keeps the cap.
         */
        private const val RECENT_WINDOW = 16L

        /** A read that is more than one public RPC's word: verified, or from an RPC the user added. */
        internal fun trusted(trust: ChainTrust): Boolean = trust.level != ChainTrust.Level.UNVERIFIED

        /**
         * The highest tip [eip1559] bids: [tipCap], or a trusted base fee
         * when that is higher — a tip as high as the base fee is what a
         * congested chain can call for, but an RPC's own word for the
         * base fee mustn't be what lets its tip through.
         */
        internal fun tipCeiling(chainId: Long, trustedBaseFee: BigInteger?): BigInteger =
            trustedBaseFee?.let { tipCap(chainId).max(it) } ?: tipCap(chainId)

        internal fun eip1559(
            baseFee: BigInteger,
            suggestedTip: BigInteger?,
            chainId: Long,
            trustedBaseFee: BigInteger? = null,
        ): EthTransaction.Fees.Eip1559 {
            val tip = (suggestedTip ?: MIN_TIP_WEI).max(MIN_TIP_WEI).min(tipCeiling(chainId, trustedBaseFee))
            return EthTransaction.Fees.Eip1559(maxFeePerGas = baseFee.shiftLeft(1) + tip, maxPriorityFeePerGas = tip)
        }

        /**
         * Whether [fees] may go out with no sheet under an auto-approve
         * rule (#112, #233): a tip, or a legacy gas price, no higher than
         * one RPC's word may set it. Anything above — a trusted high base
         * fee letting the tip past [tipCap], a legacy price past
         * [legacyCap], a replacement's bump — is the user's to see; the
         * review then says the fee is unusually high (the review's `feeFootnote`).
         */
        fun quiet(fees: EthTransaction.Fees, chainId: Long): Boolean = when (fees) {
            is EthTransaction.Fees.Eip1559 -> fees.maxPriorityFeePerGas <= tipCap(chainId)
            is EthTransaction.Fees.Legacy -> fees.gasPrice <= legacyCap(chainId)
        }

        /**
         * [fees], raised where needed to replace a transaction priced at
         * [over]: nodes take a replacement only at a fee (and tip) at
         * least 10% above the one it replaces — this bids 12.5% plus one wei.
         * Across fee types too (one RPC may report a base fee and another
         * not): a legacy gas price counts as both fee cap and tip, as geth
         * compares them, so the replacement is never left unbumped.
         */
        internal fun replacing(fees: EthTransaction.Fees, over: EthTransaction.Fees): EthTransaction.Fees {
            fun bump(v: BigInteger) = v * BigInteger.valueOf(9) / BigInteger.valueOf(8) + BigInteger.ONE
            val (overCap, overTip) = when (over) {
                is EthTransaction.Fees.Eip1559 -> over.maxFeePerGas to over.maxPriorityFeePerGas
                is EthTransaction.Fees.Legacy -> over.gasPrice to over.gasPrice
            }
            return when (fees) {
                is EthTransaction.Fees.Eip1559 -> EthTransaction.Fees.Eip1559(
                    maxFeePerGas = fees.maxFeePerGas.max(bump(overCap)),
                    maxPriorityFeePerGas = fees.maxPriorityFeePerGas.max(bump(overTip)),
                )
                // The tip never exceeds the cap, so outbidding the cap outbids both.
                is EthTransaction.Fees.Legacy -> EthTransaction.Fees.Legacy(fees.gasPrice.max(bump(overCap)))
            }
        }

        internal fun legacy(gasPrice: BigInteger): EthTransaction.Fees.Legacy {
            // A zero price would sit in the mempool forever and hold every later nonce behind it.
            if (gasPrice.signum() <= 0) throw SendException(Strings.said(R.string.send_no_gas_price))
            // Not refused when high (#233 R1-F1): a legacy price can't be clamped, and a chain
            // may just be priced that way (IoTeX 1000 gwei, Theta 4000). It's shown on a sheet
            // that says it's unusually high, and never sent without one ([quiet]).
            return EthTransaction.Fees.Legacy(gasPrice)
        }
    }
}

/**
 * The next nonce per (account, chain), iOS's `NonceTracker`: the chain's
 * pending count, unless a transaction this app just sent isn't in it
 * yet — then one past that. A failed or uncertain broadcast forgets the
 * local value, so the next send reads the chain again rather than leave
 * a gap that would hold every later transaction back.
 *
 * The local value only bridges the moments before every RPC has seen
 * the transaction in its pool: it's honoured for [ttlMs] after the send,
 * and [forgetSent] drops it once the send ends without a receipt (it may
 * have been evicted). Past that, the chain's count is the answer — a
 * reused nonce can at worst replace a transaction (one of the two
 * mines), while a nonce past a dropped one never mines at all.
 *
 * A send the user stops tracking while it may still land is
 * [abandoned][abandon]: until the chain has mined its nonce, the next
 * send from that account reuses it (with [GasOracle.replacing]'s higher
 * fee), so the two can't both go through — rather than go out one past
 * it, beside it in a mempool. Those records outlive the process
 * ([SendJournal]): [onAbandonedChange] is told whenever they change, never
 * while this tracker's own lock is held.
 *
 * A record goes once the chain's mined (`latest`) count is past its
 * nonce — on a trusted read ([GasOracle.trusted]: a quorum, or the
 * user's own RPC) at once, on one public RPC's word only once a second
 * read at least [confirmAfterMs] later ([now], monotonic) agrees (#238).
 * One lying RPC, with the quorum defeated, can't then drop the guard
 * with a single answer and have the next send go out beside the
 * abandoned one. A chain with no trusted read at all isn't stranded:
 * the second read settles it, so a nonce the chain really mined isn't
 * reused for longer than that.
 */
class NonceTracker(
    private val rpc: WalletRpc,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = LOCAL_TTL_MS,
    private val onAbandonedChange: () -> Unit = {},
    private val confirmAfterMs: Long = CONFIRM_AFTER_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Sent(val next: BigInteger, val at: Long)

    private val sent = HashMap<String, Sent>()

    /**
     * A send stopped being tracked while it could still land: its nonce,
     * fees and hash. [heldBy] is the hash of this app's own send that took
     * its place and was then found mined ([noteMined]) — the record stays
     * until the mined count settles it (#238), but a later send refused
     * for its nonce then names that one, not the abandoned one, as what
     * went through (#257 R2-F1). Kept in memory only (a restart forgets
     * it, and the copy names the abandoned send again).
     */
    class Abandoned(val nonce: BigInteger, val fees: EthTransaction.Fees, val hash: String) {
        @Volatile
        var heldBy: String? = null
            internal set
    }

    private val abandoned = HashMap<String, Abandoned>()

    /** An untrusted read that found [record]'s nonce mined, at [at] ([now]), not yet confirmed. */
    private class MinedSeen(val record: Abandoned, val at: Long)

    /** Under the same lock as [abandoned]; per key, only for the record it names. */
    private val minedSeen = HashMap<String, MinedSeen>()

    /**
     * Whether a `latest` [read] of the count settles [record] (under key
     * [k]) as mined: past its nonce, and trusted — or untrusted and agreeing
     * with an earlier untrusted read at least [confirmAfterMs] before. A
     * read that finds it not mined starts over. If it's settled, the
     * record is dropped here; true if it was. False for a record no longer
     * on file.
     */
    private fun settleMined(k: String, record: Abandoned, read: WalletRpc.Reading<BigInteger>): Boolean = synchronized(sent) {
        if (abandoned[k] !== record) return@synchronized false
        if (read.value <= record.nonce) {
            if (minedSeen[k]?.record === record) minedSeen.remove(k)
            return@synchronized false
        }
        val t = now()
        val first = minedSeen[k]?.takeIf { it.record === record }
        val settled = GasOracle.trusted(read.trust) || (first != null && t - first.at >= confirmAfterMs)
        if (!settled) {
            if (first == null) minedSeen[k] = MinedSeen(record, t)
            return@synchronized false
        }
        minedSeen.remove(k)
        abandoned.remove(k)
        true
    }

    suspend fun next(address: String, chainId: Long): WalletRpc.Reading<BigInteger> {
        val fromChain = rpc.transactionCount(chainId, address)
        val k = key(address, chainId)
        val stood = synchronized(sent) { abandoned[k] }
        if (stood != null) {
            val latest = rpc.transactionCount(chainId, address, "latest")
            // Mined (it or another with its nonce): nothing left to replace.
            if (settleMined(k, stood, latest)) {
                onAbandonedChange()
            } else if (latest.value > stood.nonce || fromChain.value > stood.nonce) {
                // Still waiting in a pool — or said mined only on one RPC's
                // word, not yet confirmed (#238): take its place rather than
                // queue behind it. Were it mined after all, this one is
                // refused as a used nonce, never sent beside it: "Not sent"
                // when every RPC asked says so, "may have gone" (Try again,
                // then followed to Unconfirmed) when one didn't answer.
                return fromChain.copy(value = stood.nonce)
            }
        }
        val now = clock()
        val local = synchronized(sent) {
            val k = key(address, chainId)
            val s = sent[k]
            if (s != null && now - s.at !in 0 until ttlMs) sent.remove(k)
            sent[k]?.next
        }
        return if (local != null && local > fromChain.value) fromChain.copy(value = local) else fromChain
    }

    /**
     * The send with [nonce] went out. [hash] is that transaction's own, when
     * it's known to be in a pool rather than mined: if a Stop tracking
     * filed that very transaction as abandoned between its broadcast and
     * this call, the record stays — it's still the one a next send must
     * replace, not a later send that makes it unable to land (#229).
     */
    fun markSent(address: String, chainId: Long, nonce: BigInteger, hash: String? = null) {
        val dropped = synchronized(sent) {
            val k = key(address, chainId)
            noteUsed(k, nonce)
            // Its replacement (or a later one) went out: the abandoned one can't land any more.
            (abandoned[k]?.let { nonce >= it.nonce && !it.hash.equals(hash, ignoreCase = true) } == true &&
                abandoned.remove(k) != null).also { if (it) minedSeen.remove(k) }
        }
        if (dropped) onAbandonedChange()
    }

    /**
     * A node said [nonce] is already used, but not by what: the next send
     * goes past it, while an abandoned send's guard stays. That refusal may
     * be one lying RPC's (the quorum defeated), or the abandoned send itself
     * having mined; only [settleMined]'s reads of the mined count drop the
     * guard, never one broadcast answer (#238).
     */
    fun markUsed(address: String, chainId: Long, nonce: BigInteger) {
        synchronized(sent) { noteUsed(key(address, chainId), nonce) }
    }

    /**
     * This app's own send [hash] with [nonce] was found mined (one RPC's
     * receipt): if it took an abandoned send's place, that record still
     * stays — one receipt doesn't drop the guard (#238) — but it notes
     * [hash] as what holds the nonce, for [WalletSender.broadcastFailure]'s
     * copy (#257 R2-F1).
     */
    fun noteMined(address: String, chainId: Long, nonce: BigInteger, hash: String) {
        synchronized(sent) {
            val a = abandoned[key(address, chainId)] ?: return
            if (a.nonce == nonce && !a.hash.equals(hash, ignoreCase = true)) a.heldBy = hash
        }
    }

    /** Under the lock: [nonce] is taken, so the local next is at least one past it. */
    private fun noteUsed(k: String, nonce: BigInteger) {
        val next = nonce + BigInteger.ONE
        if (sent[k]?.let { it.next >= next } != true) sent[k] = Sent(next, clock())
    }

    /**
     * The send with [nonce] ([fees], [hash]) stopped being tracked while
     * it may still land: the next send reuses its nonce until the chain
     * has mined it.
     */
    fun abandon(address: String, chainId: Long, nonce: BigInteger, fees: EthTransaction.Fees, hash: String) {
        synchronized(sent) {
            val k = key(address, chainId)
            sent.remove(k)
            minedSeen.remove(k)
            abandoned[k] = Abandoned(nonce, fees, hash)
        }
        onAbandonedChange()
    }

    /** Every abandoned send, keyed `chainId:address` (for [SendJournal]). */
    fun abandonedSnapshot(): Map<String, Abandoned> = synchronized(sent) { HashMap(abandoned) }

    /** Takes back what [abandonedSnapshot] gave before the process was restarted (a newer record for a key wins). */
    fun restoreAbandoned(records: Map<String, Abandoned>) {
        synchronized(sent) { records.forEach { (k, a) -> abandoned.putIfAbsent(k, a) } }
    }

    /**
     * Drops every abandoned send whose nonce the chain has mined, for
     * any account — [next] does this only for the account it's asked
     * about, and one no send is ever prepared from again (a deleted
     * wallet's) would otherwise keep its address and hash on disk for
     * good. One that can't be read (no RPC answering, the chain gone
     * from the list) stays for the next sweep. One found mined only on
     * one public RPC's word is read again [confirmAfterMs] later (#238),
     * and goes if that read agrees: the first sighting isn't kept across
     * a restart, so waiting for the next launch would never settle it.
     */
    suspend fun sweepMined() {
        var pass = 0
        while (true) {
            var dropped = false
            var unsettled = false
            for ((k, a) in abandonedSnapshot()) {
                val chainId = k.substringBefore(':').toLongOrNull() ?: continue
                val address = k.substringAfter(':')
                val read = try {
                    rpc.transactionCount(chainId, address, "latest")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                when {
                    settleMined(k, a, read) -> dropped = true
                    read.value > a.nonce -> unsettled = true
                }
            }
            if (dropped) onAbandonedChange()
            if (!unsettled || ++pass > SWEEP_RECHECKS) return
            delay(confirmAfterMs)
        }
    }

    /** The abandoned send a transaction with [nonce] would replace, if any. */
    fun replacing(address: String, chainId: Long, nonce: BigInteger): Abandoned? =
        synchronized(sent) { abandoned[key(address, chainId)]?.takeIf { it.nonce == nonce } }

    fun forget(address: String, chainId: Long) {
        synchronized(sent) { sent.remove(key(address, chainId)) }
    }

    /** The send with [nonce] ended with no receipt: unless a later send has been marked since, read the chain again. */
    fun forgetSent(address: String, chainId: Long, nonce: BigInteger) {
        synchronized(sent) {
            val k = key(address, chainId)
            if (sent[k]?.next == nonce + BigInteger.ONE) sent.remove(k)
        }
    }

    private fun key(address: String, chainId: Long) = "$chainId:${address.lowercase()}"

    companion object {
        /** As long as a page waits for a receipt: past that, a transaction not in the chain's count may be gone. */
        const val LOCAL_TTL_MS = 3 * 60_000L

        /**
         * How far apart two untrusted reads of the mined count must be to
         * drop an abandoned send (#238): long enough for a node a block or
         * two ahead (or one serving a block later reorged away) to be
         * caught up with.
         */
        const val CONFIRM_AFTER_MS = 30_000L

        /** How many times [sweepMined] reads again for untrusted sightings before leaving them to the next launch. */
        private const val SWEEP_RECHECKS = 2
    }
}

/** Where one confirmed send stands (#105). */
data class SendStatus(val quote: SendQuote, val stage: Stage, val hash: String? = null) {
    sealed interface Stage {
        data object Signing : Stage
        data object Broadcasting : Stage

        /**
         * Didn't go out, or can't be told. [mayHaveGone]: no node said
         * it took the transaction, but one may have — Try again resends
         * the very same signed bytes, so it can't be sent twice.
         * Otherwise it certainly wasn't sent, and the send is reviewed again.
         * [stale]: it wasn't sent because its quote got older than
         * [WalletSender.SIGNED_TTL_MS] while it was being signed (a Ledger
         * waited on): priced again, it can be confirmed again.
         * [droppedSigned]: of those, it was signed — approved on the
         * Ledger — and only then found older than
         * [WalletSender.SIGNED_TTL_MS]; otherwise the Ledger found it
         * over [WalletSender.QUOTE_TTL_MS] old before showing it.
         * [rejected]: the user refused it on their Ledger, or cancelled
         * waiting for it — a site that asked for it hears a rejection (4001).
         */
        data class Failed(
            val message: String,
            val mayHaveGone: Boolean,
            val stale: Boolean = false,
            val rejected: Boolean = false,
            val droppedSigned: Boolean = false,
            /**
             * [message] in English, for the site or peer waiting on this
             * send (#280); null once read back from the journal, where
             * nobody waits on it any more. Part of equality, so a live
             * failure published over an equal restored one (null here)
             * still replaces it and the page hears the English reason.
             */
            val english: String? = null,
        ) : Stage

        data object Pending : Stage
        data class Confirmed(val block: Long, val feePaid: BigInteger?) : Stage

        /** Mined, but the transfer itself failed (the token refused it); the fee was still paid. */
        data class Reverted(val block: Long, val feePaid: BigInteger?) : Stage

        /** Still no receipt after [WalletSender.CONFIRM_TIMEOUT_MS]: it may yet land. */
        data object Unconfirmed : Stage
    }

    val done: Boolean get() = stage is Stage.Confirmed || stage is Stage.Reverted

    /** Signing, broadcasting or waiting for its receipt: it's still going. */
    val inFlight: Boolean get() = stage == Stage.Signing || stage == Stage.Broadcasting || stage == Stage.Pending

    /**
     * Failed, but it may have gone out: only Try again (the very same
     * bytes) settles it, so it stays until then — dropping it would let
     * the next send sign a second payment next to one in a mempool.
     */
    val mayHaveGone: Boolean get() = (stage as? Stage.Failed)?.mayHaveGone == true

    /**
     * Still going, or not yet known not to have gone (including a send
     * no receipt came for): the page leaves it for the next visit, and
     * only Try again / Keep waiting or an explicit [WalletSender.discard]
     * ends it.
     */
    val unresolved: Boolean get() = inFlight || mayHaveGone || stage == Stage.Unconfirmed
}

/**
 * The wallet's send flow (#105), after desktop's `transaction-service.js`
 * and iOS's `TransactionService`: [prepare] prices a [SendRequest] —
 * fresh balances, nonce ([NonceTracker]), fees ([GasOracle]) and a gas
 * estimate, all through the chain-data router — for the review; [submit]
 * signs what the user confirmed, broadcasts it and follows it to a
 * receipt.
 *
 * A confirmed send runs in this object's own scope, not the page's, so
 * leaving the page can't cut it between signing and broadcasting, and
 * [status] still has the outcome when the page is back. One at a time.
 */
class WalletSender internal constructor(
    private val rpc: WalletRpc,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    private val journal: SendJournal = SendJournal.None,
    private val history: TxHistory? = null,
) {
    private val nonces = NonceTracker(rpc, clock, onAbandonedChange = { persistLater() })
    private val gas = GasOracle(rpc)

    private val _status = MutableStateFlow<SendStatus?>(null)

    /** The current (or last) confirmed send; null once acknowledged. */
    val status: StateFlow<SendStatus?> = _status.asStateFlow()

    private val _changes = MutableSharedFlow<SendStatus?>(replay = 1, extraBufferCapacity = Channel.UNLIMITED)

    /**
     * Every value [status] takes, in order, none skipped (the latest is
     * replayed to a new collector): [status] conflates, so a collector
     * busy with a Pending could see it followed straight by the null of
     * Done and never see the Confirmed between. For a follower that must
     * not miss an outcome ([SafeAccounts]).
     */
    val changes: SharedFlow<SendStatus?> = _changes.asSharedFlow()

    /** Sets [status] and hands [value] to [changes]; under this object's lock. */
    private fun setStatus(value: SendStatus?) {
        _status.value = value
        _changes.tryEmit(value)
    }

    /**
     * Shows [value] and reports it to the [history] (#109), which records
     * a send once it went out or may have. Under this object's lock; the
     * history never blocks on storage there. The history hears first, so
     * whoever sees [value] in [status] finds the history already has it:
     * shown first, a collector woken by it (the wallet page reading the
     * history list) could run before the history had heard, and find the
     * send there a stage behind.
     */
    private fun show(value: SendStatus?) {
        value?.let { history?.note(it) }
        setStatus(value)
    }

    /** The signed bytes of the current send, kept so Try again resends exactly them. */
    private var signed: EthTransaction.Signed? = null
    private var job: Job? = null

    /**
     * Held while the journal is written, and taken before this object's
     * lock, never under it: storage is only ever touched off the main
     * thread, and the main thread (which takes only this object's lock,
     * for moments) never waits on a write.
     */
    private val writing = Any()

    /** The journal has been read back (under this object's lock); until then nothing is signed. */
    private var restoredYet = false
    private val restored = CompletableDeferred<Unit>()

    /** [discard] was asked for before the journal was read back: it applies to what that brings. */
    private var discardOnRestore = false

    init {
        scope.launch(Dispatchers.IO) {
            try {
                restore()
            } finally {
                restored.complete(Unit)
            }
            // A deleted wallet's abandoned sends are never looked at by a send again: drop the mined ones
            // here (at each launch that left a journal: MainActivity calls resumeAtLaunch).
            nonces.sweepMined()
        }
    }

    /** Until the journal has been read back (off the main thread, from [init]). */
    internal suspend fun awaitRestored() = restored.await()

    /**
     * Picks up where the last process left off ([SendJournal]): the
     * abandoned nonces, and an unresolved send as it stood — one that
     * was waiting for its receipt goes on waiting, one the process died
     * sending is "may have gone out" (Try again resends the same bytes).
     */
    private fun restore() {
        val state = journal.load()
        synchronized(this) {
            restoredYet = true
            if (state != null) {
                nonces.restoreAbandoned(state.abandoned)
                state.send?.let { send ->
                    signed = send.signed
                    val status = send.status
                    val shown = if (status.stage == SendStatus.Stage.Broadcasting) {
                        status.copy(stage = SendStatus.Stage.Failed(INTERRUPTED, true))
                    } else {
                        status
                    }
                    if (discardOnRestore) {
                        // The wallet it came from was removed before this was read back, and
                        // its history wiped with it: the discard below gives the send up
                        // (abandoning its nonce) without recording it in the emptied history
                        // or following it.
                        setStatus(shown)
                    } else {
                        show(shown)
                        if (status.stage == SendStatus.Stage.Pending) job = scope.launch { follow(status.quote, send.signed.hash) }
                    }
                    Log.i(TAG, "restored ${send.signed.hash} chain=${send.signed.tx.chainId} nonce=${send.signed.tx.nonce}")
                }
            }
            if (discardOnRestore) discard()
        }
    }

    /**
     * What must survive the process ([SendJournal]) with [current] shown
     * and [s] its signed bytes: the send if it's
     * [unresolved][SendStatus.unresolved] and signed, and the abandoned
     * nonces. Under this object's lock.
     */
    private fun snapshot(current: SendStatus?, s: EthTransaction.Signed?): SendJournal.State {
        val send = if (current != null && s != null && current.unresolved && current.stage != SendStatus.Stage.Signing) {
            SendJournal.Send(current, s)
        } else {
            null
        }
        return SendJournal.State(send, nonces.abandonedSnapshot())
    }

    /**
     * Writes the state as it is now, on the calling thread (never the
     * main one). The snapshot is taken under [writing], so writes land
     * in order and the last one is always the latest state; false if the
     * journal couldn't be written.
     */
    internal fun persistNow(): Boolean = synchronized(writing) {
        journal.save(synchronized(this) { snapshot(_status.value, signed) })
    }

    /** [persistNow], off the calling thread: for changes made on the main thread. */
    private fun persistLater() {
        scope.launch(Dispatchers.IO) { persistNow() }
    }

    /**
     * From the main thread ([retry], [checkAgain], [discard],
     * [acknowledge]): [status] is shown now and journalled just after,
     * off the main thread. Safe to show first: each of these moves to a
     * state a restart may miss without harm — the journal still holds
     * the same signed bytes (Try again, Keep waiting), or a send given
     * up on that a restart brings back to be given up on again.
     */
    private fun publish(status: SendStatus?) {
        synchronized(this) { show(status) }
        persistLater()
    }

    /**
     * From a send's own coroutine: [quote]'s status becomes what [next]
     * makes of it — journalled first (on the IO pool: the save fsyncs),
     * then shown, so nothing is on screen that a restart wouldn't bring
     * back. Nothing if another send (or a discard) took over, before or
     * while it was written; true if it was shown.
     */
    private suspend fun journalThenShow(quote: SendQuote, next: (SendStatus) -> SendStatus): Boolean = withContext(Dispatchers.IO) {
        synchronized(writing) {
            val (was, now, state) = synchronized(this@WalletSender) {
                val was = _status.value?.takeIf { it.quote === quote } ?: return@withContext false
                val now = next(was)
                Triple(was, now, snapshot(now, signed))
            }
            journal.save(state)
            synchronized(this@WalletSender) {
                if (_status.value !== was) return@withContext false
                show(now)
                true
            }
        }
    }

    /**
     * [s], about to go out for [quote], written to the journal (on the IO
     * pool) and then shown as Broadcasting; false if nothing may go out —
     * discarded meanwhile, or the journal couldn't be written (shown as
     * a failure that certainly didn't go out, and written over whatever
     * part of the failed save did land, so a restart agrees).
     */
    private suspend fun journalBeforeBroadcast(
        quote: SendQuote,
        s: EthTransaction.Signed,
        start: Started,
    ): Boolean = withContext(Dispatchers.IO) {
        val broadcasting = SendStatus(quote, SendStatus.Stage.Broadcasting, s.hash)
        synchronized(writing) {
            val state = synchronized(this@WalletSender) {
                // Discarded while the key was at work: nothing goes out.
                if (_status.value?.quote !== quote) return@withContext false
                snapshot(broadcasting, s)
            }
            val saved = journal.save(state)
            var failedStatus: SendStatus? = null
            val unsaved = synchronized(this@WalletSender) {
                // Discarded while it was written: nothing goes out (the discard's own write follows this one).
                if (_status.value?.quote !== quote) return@withContext false
                if (saved) {
                    signed = s
                    show(broadcasting)
                    return@withContext true
                }
                val notSaved = Strings.said(R.string.send_save_failed)
                val failed = SendStatus(
                    quote,
                    SendStatus.Stage.Failed(notSaved.text, false, english = notSaved.english),
                )
                failedStatus = failed
                snapshot(failed, null)
            }
            // A failed save may still have landed (renamed, just not known
            // to be on flash): write over it, still under [writing], so a
            // restart can't bring back as maybe-sent the bytes the user
            // is about to be told were never sent. Written *before* the
            // failure is shown, so there's no window where the page says
            // "nothing was sent" while the journal still holds the signed
            // send (a kill in that window would resurrect it).
            journal.save(unsaved)
            // Nothing went out, so no nonce was taken: settled before it's shown.
            settle(start)
            synchronized(this@WalletSender) {
                if (_status.value?.quote === quote) failedStatus?.let { show(it) }
            }
            false
        }
    }

    /**
     * Prices [request] for the review. With [all], for the account's
     * whole balance of the token — of the native currency, all of it
     * less the most the fee can be — so the amount to show is
     * [SendQuote.request]'s. Throws [SendException] with what to tell
     * the user: not enough of the token, or of the native currency for
     * the fee; a transfer the chain would refuse; no RPC answering.
     */
    suspend fun prepare(request: SendRequest, all: Boolean = false): SendQuote = try {
        // The nonces of sends given up on before the last restart decide this one's.
        restored.await()
        coroutineScope {
            val chainId = request.chain.id
            val from = request.from.address
            // Before the nonce is read: a send started from here on may take
            // it, and so may one started already whose nonce isn't yet in
            // [nonces] (still signing or broadcasting), so that one isn't
            // counted as before this quote.
            val sendsBefore = synchronized(this@WalletSender) {
                started[startKey(from, chainId)]?.let { if (it.settled) it.count else it.count - 1 } ?: 0L
            }
            val token = request.token
            val native = async { rpc.balance(chainId, from).value }
            val tokenBalance = async {
                token.address?.let { contract ->
                    val r = rpc.call(chainId, JSONObject().put("to", contract).put("data", Erc20.balanceOfData(from)))
                    Erc20.decodeUint256(r.value) ?: throw SendException(Strings.said(R.string.send_token_no_balance, token.symbol))
                }
            }
            val nonce = async { nonces.next(from, chainId) }
            val fees = async { gas.fees(chainId) }
            val held = tokenBalance.await() ?: native.await()
            // A site's call may carry no value: the fee check below says what's missing then.
            if (held.signum() == 0 && request.dapp == null) throw SendException(Strings.said(R.string.send_no_token, token.symbol))
            if (!all && request.amount > held) {
                throw SendException(
                    Strings.said(R.string.send_not_enough_token, token.symbol, SendAmounts.exact(held, token.decimals)),
                )
            }
            // Max: all of a token; all of the native currency is priced first, then less the fee.
            var sending = if (all) request.copy(amount = held) else request
            val (to, value, data) = sending.call()
            val estimate = try {
                val call = JSONObject().put("from", from).put("to", to).put("value", "0x" + value.toString(16))
                if (data.isNotEmpty()) call.put("data", "0x" + data.toHex())
                rpc.estimateGas(chainId, call).value
            } catch (e: ChainRpcException.Rpc) {
                throw estimateFailure(e, sending)
            }
            // Taking the place of a send the user stopped tracking: outbid it, or no node swaps it.
            val replacing = nonces.replacing(from, chainId, nonce.await().value)
            var tx = EthTransaction(
                chainId = chainId,
                nonce = nonce.await().value,
                gasLimit = gasLimit(estimate, data.isNotEmpty(), site = request.dapp?.gasLimit),
                to = to,
                value = value,
                data = data,
                fees = replacing?.let { GasOracle.replacing(fees.await(), it.fees) } ?: fees.await(),
            )
            val nativeBalance = native.await()
            // Priced on the bytes as they stand — for Max, with the whole balance, which is never shorter than the rest.
            val l1Fee = l1Fee(tx)
            val maxFee = tx.maxFee + l1Fee
            val symbol = request.chain.symbol
            val fee = SendAmounts.exact(maxFee, request.chain.decimals)
            val has = SendAmounts.exact(nativeBalance, request.chain.decimals)
            if (all && token.isNative) {
                val rest = nativeBalance - maxFee
                if (rest.signum() <= 0) throw SendException(Strings.said(R.string.send_not_enough_for_fee_all, symbol, fee, has))
                sending = sending.copy(amount = rest)
                tx = tx.copy(value = rest)
            }
            if (maxFee + tx.value > nativeBalance) {
                val what = if (token.isNative && tx.value.signum() > 0) R.string.send_not_enough_for_amount_and_fee else R.string.send_not_enough_for_fee
                throw SendException(Strings.said(what, symbol, fee, has))
            }
            SendQuote(sending, tx, nativeBalance, tokenBalance.await(), clock(), nonce.await().trust, replacing?.hash, sendsBefore, all, l1Fee)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SendException) {
        throw e
    } catch (e: ChainRpcException) {
        throw SendException(readFailureSaid(e), e)
    }

    /**
     * What [tx] may cost to post to L1 on an OP Stack rollup
     * ([OP_STACK_CHAINS]; zero on any other chain): the chain's own
     * `GasPriceOracle.getL1Fee` of the unsigned transaction — which adds
     * the signature's bytes itself, as viem's `estimateL1Fee` relies on —
     * doubled, headroom for L1's fee to rise before the send is mined,
     * like the gas fee cap's. op-geth refuses a send whose balance
     * can't cover this on top of value and gas ("insufficient funds for
     * gas * price + value"), so Max and the balance check must count it.
     */
    private suspend fun l1Fee(tx: EthTransaction): BigInteger {
        if (tx.chainId !in OP_STACK_CHAINS) return BigInteger.ZERO
        val call = JSONObject().put("to", GAS_PRICE_ORACLE).put("data", getL1FeeData(tx.signingPayload()))
        val fee = Erc20.decodeUint256(rpc.call(tx.chainId, call).value)
            ?: throw SendException(Strings.said(R.string.send_read_nonsense))
        return fee.shiftLeft(1)
    }

    /** [quote] priced afresh, as it was asked for: Max stays Max. */
    suspend fun reprice(quote: SendQuote): SendQuote = prepare(quote.request, quote.all)

    /** Whether [quote] is too old to sign as is (its fees may no longer get it mined). */
    fun isStale(quote: SendQuote): Boolean = clock() - quote.preparedAt !in 0 until QUOTE_TTL_MS

    /**
     * Whether [quote], signed just now, is too old to broadcast: priced
     * over [SIGNED_TTL_MS] ago. Longer than [QUOTE_TTL_MS], which was
     * already checked as the signing began (and, on a Ledger, again once
     * it was connected and unlocked, just before it showed the
     * transaction), so the time the user spends reviewing it on the
     * device doesn't throw away what they just approved.
     */
    private fun tooOldToSend(quote: SendQuote): Boolean = clock() - quote.preparedAt !in 0 until SIGNED_TTL_MS

    /**
     * Sends [submit] started per (account, chain), and the quote that
     * started the last one. [settled] once that send's nonce is in
     * [nonces] (marked sent, or forgotten) or it ended without one: until
     * then a quote priced beside it reads the nonce it is taking.
     */
    private class Started(val count: Long, val by: SendQuote) {
        /** Under [WalletSender]'s lock. */
        var settled = false
    }

    /** [start]'s nonce is now what [NonceTracker.next] answers from (called after it got there). */
    private fun settle(start: Started?) {
        if (start != null) synchronized(this) { start.settled = true }
    }

    /** Under this object's lock. */
    private val started = HashMap<String, Started>()

    private fun startKey(from: String, chainId: Long) = "${from.lowercase()}|$chainId"

    /**
     * Whether another send from [quote]'s account on its chain has
     * started since [quote] was priced, or had started but not yet
     * recorded its nonce when it was, so its nonce may be taken: two
     * quotes priced side by side (a site's sheet still up while a send an
     * auto-approve rule covers goes out, or the wallet page's own) hold
     * the same one. Its own earlier start (a retry after it failed
     * without going out) doesn't count. Under this object's lock.
     */
    private fun overtakenLocked(quote: SendQuote): Boolean {
        val last = started[startKey(quote.request.from.address, quote.tx.chainId)] ?: return false
        return last.count != quote.sendsBefore && last.by !== quote
    }

    /** What [submit] did with a quote. */
    enum class Submit {
        /** Signing and broadcasting it; [status] follows it. */
        STARTED,

        /**
         * Another send is still being signed or broadcast, or may have
         * gone out and awaits Try again / Keep waiting or [discard].
         */
        BUSY,

        /**
         * Older than [QUOTE_TTL_MS], or another send from its account on
         * its chain started after it was priced (its nonce may be taken):
         * nothing signed, price it again.
         */
        STALE,
    }

    /**
     * Signs [quote]'s transaction with [sign] (the account's key, which
     * it zeroes) and broadcasts it, then follows it to a receipt —
     * unless another send is still being signed or broadcast, or the
     * quote has gone [stale][isStale] or another send from its account
     * on its chain started after it was priced (checked here, at the
     * moment of signing, however long an unlock prompt kept the user
     * before it — and again once it's signed, since a Ledger's connect,
     * unlock and on-device review all happen inside [sign]: a quote
     * priced over [SIGNED_TTL_MS] ago by then is thrown away unsent and
     * ends [SendStatus.Stage.Failed.stale], to be priced again).
     */
    fun submit(quote: SendQuote, sign: suspend (EthTransaction) -> EthTransaction.Signed): Submit {
        synchronized(this) {
            if (busyLocked()) return Submit.BUSY
            if (isStale(quote) || overtakenLocked(quote)) return Submit.STALE
            val k = startKey(quote.request.from.address, quote.tx.chainId)
            val start = Started((started[k]?.count ?: 0L) + 1, quote)
            started[k] = start
            job?.cancel()
            signed = null
            show(SendStatus(quote, SendStatus.Stage.Signing))
            job = scope.launch {
                // Every way this ends without broadcast settling it (signing
                // failed, the save failed, discarded or cancelled) leaves the
                // nonce as it was, or abandoned by [discard] before the cancel.
                try {
                    signAndBroadcast(quote, sign, start)
                } finally {
                    settle(start)
                }
            }
        }
        return Submit.STARTED
    }

    private suspend fun signAndBroadcast(quote: SendQuote, sign: suspend (EthTransaction) -> EthTransaction.Signed, start: Started) {
        // Every ending below that takes no nonce settles [start] before its
        // outcome is shown, as [broadcast] does: a page that sees the failure
        // and prices again at once must read this send as over, not as one
        // still taking its nonce (which would make that fresh quote STALE).
        val s = try {
            sign(quote.tx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settle(start)
            signingFailed(quote, e)
            return
        }
        // Signing may have taken minutes (a Ledger unlocked, opened, reviewed on):
        // bytes whose fee cap was priced too long ago are dropped, never sent.
        if (tooOldToSend(quote)) {
            settle(start)
            failStale(quote, droppedSigned = true)
            return
        }
        // On disk before it goes out: a process killed mid-broadcast
        // must come back to these bytes, never to an empty form that
        // would sign a second payment next to them.
        if (journalBeforeBroadcast(quote, s, start)) broadcast(quote, s, start = start)
    }

    /** Signing [quote] threw [e]: nothing was sent, and the page is told why. */
    private suspend fun signingFailed(quote: SendQuote, e: Exception) {
        when (e) {
            is QuoteStaleException -> failStale(quote, droppedSigned = false)
            is SigningHeldException -> fail(quote, Strings.said(R.string.send_signing_held, Said.of(e.message.orEmpty())), false)
            is VaultLockedException ->
                fail(quote, Strings.said(R.string.send_signing_locked), false)
            is LedgerException -> {
                // The Ledger's own words: rejected, locked, disconnected, timed out… (#142)
                val said = e.said
                val rejected = e.kind == LedgerException.Kind.REJECTED || e.kind == LedgerException.Kind.CANCELLED
                fail(quote, if (e.kind.saysNothingSent && !e.ownWords) said else Strings.said(R.string.send_ledger_failed, said), false, rejected)
            }
            else -> {
                Log.w(TAG, "signing failed: ${e.javaClass.simpleName}")
                fail(quote, Strings.said(R.string.send_signing_failed), false)
            }
        }
    }

    /** How far a [submitAndAwaitBroadcast] got. */
    sealed interface Broadcast {
        /** A node took it: [hash] is on its way (the wallet page follows it to a receipt). */
        data class Sent(val hash: String) : Broadcast

        /**
         * It didn't go out — or, with [mayHaveGone], can't be told (the wallet page offers Try again).
         * [english]: [message] for the peer that asked (#280).
         */
        data class Failed(val message: String, val mayHaveGone: Boolean, val english: String) : Broadcast {
            constructor(said: Said, mayHaveGone: Boolean) : this(said.text, mayHaveGone, said.english)
        }

        /** [Submit.BUSY]: another send is still unresolved; nothing signed. */
        data object Busy : Broadcast

        /**
         * [Submit.STALE], or it went stale while a Ledger signed it
         * ([SendStatus.Stage.Failed.stale]): nothing sent, price it again.
         * [droppedSigned]: it was approved on the Ledger and dropped
         * only then, priced over [SIGNED_TTL_MS] ago
         * ([SendStatus.Stage.Failed.droppedSigned]); otherwise it was
         * over [QUOTE_TTL_MS] old (or overtaken) before anything was signed.
         */
        data class Stale(val droppedSigned: Boolean) : Broadcast

        /** Refused on the Ledger, or its wait cancelled ([SendStatus.Stage.Failed.rejected]): nothing sent. */
        data object Rejected : Broadcast
    }

    /**
     * [submit], then wait until the transaction has gone out (or hasn't):
     * for a transaction someone else asked for and waits on the hash of
     * (desktop Freedom over OpenLV, #113). The send is the wallet's own
     * from here on, followed to its receipt and journalled like any other.
     */
    suspend fun submitAndAwaitBroadcast(quote: SendQuote, sign: suspend (EthTransaction) -> EthTransaction.Signed): Broadcast {
        when (submit(quote, sign)) {
            Submit.BUSY -> return Broadcast.Busy
            Submit.STALE -> return Broadcast.Stale(droppedSigned = false)
            Submit.STARTED -> Unit
        }
        val s = status.first { it?.quote !== quote || (it.stage != SendStatus.Stage.Signing && it.stage != SendStatus.Stage.Broadcasting) }
        if (s?.quote !== quote) {
            // Stopped on the phone (Stop tracking, or the wallet removed) while it was going out.
            return Broadcast.Failed(Strings.said(R.string.send_stopped_on_phone), true)
        }
        return when (val stage = s.stage) {
            is SendStatus.Stage.Failed -> when {
                stage.stale -> Broadcast.Stale(stage.droppedSigned)
                stage.rejected -> Broadcast.Rejected
                else -> Broadcast.Failed(stage.message, stage.mayHaveGone, stage.english ?: NOT_SENT_ENGLISH)
            }
            else -> s.hash?.let { Broadcast.Sent(it) } ?: Broadcast.Failed(Strings.said(R.string.send_no_hash), true)
        }
    }

    /** Whether [submit] would answer [Submit.BUSY] to any quote right now. */
    fun busy(): Boolean = synchronized(this) { busyLocked() }

    private fun busyLocked(): Boolean {
        // The last process's send isn't read back yet: it may be one this would sign beside.
        if (!restoredYet) return true
        val current = _status.value
        if (current?.stage == SendStatus.Stage.Signing || current?.stage == SendStatus.Stage.Broadcasting) return true
        // One that may have gone out is settled by Try again (or given up on with
        // discard, which makes the next send replace it), never by signing another beside it.
        return current?.unresolved == true
    }

    /** After a [SendStatus.Stage.Failed] that [SendStatus.Stage.Failed.mayHaveGone]: the same bytes again. */
    fun retry() {
        synchronized(this) {
            val status = _status.value ?: return
            val s = signed ?: return
            if ((status.stage as? SendStatus.Stage.Failed)?.mayHaveGone != true) return
            job?.cancel()
            publish(status.copy(stage = SendStatus.Stage.Broadcasting, hash = s.hash))
            job = scope.launch { broadcast(status.quote, s, resend = true) }
        }
    }

    /** After [SendStatus.Stage.Unconfirmed]: keep waiting for the receipt. */
    fun checkAgain() {
        synchronized(this) {
            val status = _status.value ?: return
            val hash = status.hash ?: return
            if (status.stage != SendStatus.Stage.Unconfirmed) return
            job?.cancel()
            publish(status.copy(stage = SendStatus.Stage.Pending))
            job = scope.launch { follow(status.quote, hash) }
        }
    }

    /**
     * The page is done with the outcome. A send still going stays, and
     * so does one that [may have gone out][SendStatus.mayHaveGone] or
     * got no receipt ([SendStatus.unresolved]): its signed bytes are what
     * Try again resends, and without them a fresh send could pay a
     * second time. Only [discard] drops those.
     */
    fun acknowledge() {
        synchronized(this) {
            val current = _status.value ?: return
            if (current.unresolved) return
            clear()
        }
    }

    /**
     * Stops tracking the current send whatever its stage: the user gave
     * up on one that may yet land (Stop tracking), or the wallet it came
     * from was deleted. If it was signed and may be out there, its nonce
     * is [abandoned][NonceTracker.abandon]: the next send from that
     * account on that chain reuses it at a higher fee and so replaces it
     * — only one of the two can go through, never both.
     */
    fun discard() {
        synchronized(this) {
            if (!restoredYet) {
                discardOnRestore = true
                return
            }
            val current = _status.value ?: return
            val s = signed
            val certainlyNotSent = (current.stage as? SendStatus.Stage.Failed)?.mayHaveGone == false
            if (s != null && !current.done && !certainlyNotSent) {
                val tx = current.quote.tx
                nonces.abandon(current.quote.request.from.address, tx.chainId, tx.nonce, tx.fees, s.hash)
                Log.i(TAG, "stopped tracking ${s.hash} chain=${tx.chainId} nonce=${tx.nonce}")
            }
            clear()
        }
    }

    private fun clear() {
        job?.cancel()
        job = null
        signed = null
        publish(null)
    }

    private fun current(quote: SendQuote): Boolean = synchronized(this) { _status.value?.quote === quote }

    /**
     * [resend]: these bytes were broadcast before and may already be out
     * (Try again), so a node refusing them (the nonce is used, the
     * balance is too low) may be saying it about this very transaction.
     */
    private suspend fun broadcast(
        quote: SendQuote,
        s: EthTransaction.Signed,
        resend: Boolean = false,
        start: Started? = null,
    ) {
        val from = quote.request.from.address
        val chainId = quote.tx.chainId
        // Discarded meanwhile (signing isn't a suspension point, so cancelling alone can't stop this).
        if (!current(quote)) return
        // The write blocks (lock, fsync): a discard landing during it wins, and nothing goes out.
        if (!set(quote, SendStatus.Stage.Broadcasting, s.hash)) return
        try {
            rpc.sendRawTransaction(chainId, s.raw)
            // Discarded while it went out: discard filed it as abandoned; don't undo that.
            if (!current(quote)) return
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChainRpcException) {
            // A node that timed out answering may have taken it, and a node
            // asked after it then says the nonce is used: if this very
            // transaction is on chain, it went out.
            if (landed(chainId, s.hash)) {
                // One RPC's receipt: an abandoned send's guard stays until
                // the mined count settles it (#238).
                nonces.markUsed(from, chainId, quote.tx.nonce)
                nonces.noteMined(from, chainId, quote.tx.nonce, s.hash)
                settle(start)
                set(quote, SendStatus.Stage.Pending, s.hash)
                follow(quote, s.hash)
                return
            }
            val heldBy = nonces.replacing(from, chainId, quote.tx.nonce)?.heldBy
            val (message, uncertain) = broadcastFailure(e, quote, heldBy)
            // On a resend, a refusal is what a node says once the earlier
            // try's transaction is mined: "nonce used", or — from a client
            // that checks the balance or the fee before the nonce
            // (Nethermind's BalanceTooLowFilter runs before its nonce
            // filter) — "insufficient funds" for a balance the payment
            // itself just spent. One receipt read (rate limited, or from a
            // node a block behind) can't rule that out, and "Not sent"
            // would let Review again pay twice, so no refusal is read as
            // "Not sent" here: follow its receipt instead. It lands, or
            // ends Unconfirmed (Keep waiting, the explorer) — never a
            // fresh signature.
            if (resend && !uncertain) {
                Log.i(TAG, "resend chain=$chainId nonce=${quote.tx.nonce} refused, following ${s.hash}")
                // A used nonce is used whoever used it; for any other
                // refusal the chain's own count tells the next send.
                // Not markSent: a refusal names no transaction, so it keeps
                // an abandoned send's guard (one lying RPC can't drop it).
                if (nonceUsed(e)) nonces.markUsed(from, chainId, quote.tx.nonce) else nonces.forget(from, chainId)
                settle(start)
                set(quote, SendStatus.Stage.Pending, s.hash)
                follow(quote, s.hash)
                return
            }
            nonces.forget(from, chainId)
            settle(start)
            Log.i(TAG, "broadcast chain=$chainId nonce=${quote.tx.nonce} failed (uncertain=$uncertain)")
            fail(quote, message, uncertain)
            return
        }
        nonces.markSent(from, chainId, quote.tx.nonce, s.hash)
        settle(start)
        Log.i(TAG, "sent ${s.hash} chain=$chainId nonce=${quote.tx.nonce}")
        set(quote, SendStatus.Stage.Pending, s.hash)
        follow(quote, s.hash)
    }

    private suspend fun landed(chainId: Long, hash: String): Boolean = try {
        rpc.receipt(chainId, hash).value != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChainRpcException) {
        false
    }

    private suspend fun follow(quote: SendQuote, hash: String) {
        val deadline = clock() + confirmTimeoutMs
        while (true) {
            val receipt = try {
                rpc.receipt(quote.tx.chainId, hash).value
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChainRpcException) {
                null
            }
            if (receipt != null) {
                val outcome = outcomeOf(receipt)
                if (outcome != null) {
                    // Mined, so it holds its nonce: if it took an abandoned send's place, a later refusal names it.
                    nonces.noteMined(quote.request.from.address, quote.tx.chainId, quote.tx.nonce, hash)
                    set(quote, outcome, hash)
                    return
                }
            }
            if (clock() >= deadline) break
            delay(pollMs)
        }
        // Not mined in all that time: it may have been dropped, so the next
        // send reads the chain's count rather than sign past a gap.
        nonces.forgetSent(quote.request.from.address, quote.tx.chainId, quote.tx.nonce)
        set(quote, SendStatus.Stage.Unconfirmed, hash)
    }

    // A newer send (or an acknowledge) took over: this one's news is stale, and journalThenShow drops it.
    private suspend fun set(quote: SendQuote, stage: SendStatus.Stage, hash: String?): Boolean =
        journalThenShow(quote) { SendStatus(quote, stage, hash) }

    private suspend fun fail(quote: SendQuote, message: Said, mayHaveGone: Boolean, rejected: Boolean = false) =
        journalThenShow(quote) {
            it.copy(stage = SendStatus.Stage.Failed(message.text, mayHaveGone, rejected = rejected, english = message.english))
        }

    private suspend fun failStale(quote: SendQuote, droppedSigned: Boolean) = journalThenShow(quote) {
        val message = Strings.said(if (droppedSigned) R.string.send_stale_while_signing else R.string.send_stale_before_signing)
        it.copy(stage = SendStatus.Stage.Failed(message.text, mayHaveGone = false, stale = true, droppedSigned = droppedSigned, english = message.english))
    }

    companion object {
        /**
         * Signs as [account]: on its Ledger if it's a Ledger's (#142),
         * which the user confirms there; else [vaultSigner]. [fresh] is
         * asked once the Ledger is ready, before it shows the transaction:
         * false (the quote aged while it was unlocked) ends it with
         * [QuoteStaleException], so nothing is reviewed that would be dropped;
         * it may throw [SigningHeldException] to end it for a reason of its own.
         */
        fun signerFor(
            context: android.content.Context,
            vault: Vault,
            account: WalletAccount,
            fresh: () -> Boolean = { true },
        ): suspend (EthTransaction) -> EthTransaction.Signed = if (account.ledger != null) {
            val ledger = Ledger.get(context);
            { tx -> ledger.signTransaction(account, tx, fresh) }
        } else {
            vaultSigner(vault, account)
        }

        private const val TAG = "WalletSend"

        /** A send the last process died broadcasting, as the next one finds it. */
        internal val INTERRUPTED: String get() = Strings.get(R.string.send_interrupted)

        /**
         * What a site or a peer hears for a failure with no English
         * words of its own ([SendStatus.Stage.Failed.english] null):
         * developer-facing, so never in the user's language (#280).
         */
        const val NOT_SENT_ENGLISH = "The transaction didn't go out."

        /** Why a send was dropped unsent: its quote aged past [SIGNED_TTL_MS] while it was signed. */
        internal val STALE_WHILE_SIGNING: String get() = Strings.get(R.string.send_stale_while_signing)

        /** Why a send was dropped unsigned: the Ledger found its quote over [QUOTE_TTL_MS] old before showing it. */
        internal val STALE_BEFORE_SIGNING: String get() = Strings.get(R.string.send_stale_before_signing)

        /** A quote older than this is priced again before it's signed (or shown on a Ledger). */
        const val QUOTE_TTL_MS = 60_000L

        /**
         * Signed bytes whose quote is older than this are dropped, not
         * broadcast: [QUOTE_TTL_MS] to confirm, plus time to review it
         * on a Ledger (#220 R2-M1).
         */
        const val SIGNED_TTL_MS = 3 * 60_000L

        /** Between receipt reads: about a Gnosis block, under an Ethereum one. */
        const val POLL_MS = 4_000L

        /** How long a page waits for the receipt before saying it's still pending. */
        const val CONFIRM_TIMEOUT_MS = 3 * 60_000L

        /** A plain transfer to an account costs exactly this; nothing can make it cost more. */
        private val TRANSFER_GAS = BigInteger.valueOf(21_000)

        /**
         * The gas limit for an estimate: a plain 21 000 transfer as is,
         * anything with code involved with desktop's 20% headroom (a
         * contract's gas use can shift between estimate and inclusion).
         */
        internal fun gasLimit(estimate: BigInteger, hasData: Boolean): BigInteger =
            if (!hasData && estimate == TRANSFER_GAS) estimate else estimate * BigInteger.valueOf(120) / BigInteger.valueOf(100)

        /**
         * The gas limit for a send with [site]'s `gas` (a dApp's
         * `eth_sendTransaction`, #110), if it named one: taken as long as it
         * covers the estimate, but never more than [SITE_GAS_CEILING] times
         * it — a site's `0xffffffffffff` would otherwise price the "up to"
         * fee past any balance, or past the block gas limit so no node
         * takes it (#215 R6-M2). Below the estimate it's ignored, as the
         * send would run out of gas.
         */
        internal fun gasLimit(estimate: BigInteger, hasData: Boolean, site: BigInteger?): BigInteger =
            site?.takeIf { it >= estimate }?.min(estimate * SITE_GAS_CEILING) ?: gasLimit(estimate, hasData)

        /**
         * OP Stack rollups, whose sends also pay an L1 data fee ([l1Fee]):
         * OP Mainnet, Base, their Sepolia testnets, and other Superchain
         * members (Zora, Mode, Unichain, World Chain, Ink, Soneium, Lisk,
         * Fraxtal, BOB).
         */
        internal val OP_STACK_CHAINS = setOf(
            10L, 8453L, 11155420L, 84532L, 7777777L, 34443L, 130L, 480L, 57073L, 1868L, 1135L, 252L, 60808L,
        )

        /** The OP Stack's `GasPriceOracle` predeploy. */
        internal const val GAS_PRICE_ORACLE = "0x420000000000000000000000000000000000000F"

        /** `getL1Fee(bytes)`'s selector. */
        internal const val GET_L1_FEE = "0x49948e0e"

        /** `getL1Fee(unsignedTx)` call data: the selector, the offset of the bytes, their length, the bytes padded to a word. */
        internal fun getL1FeeData(unsignedTx: ByteArray): String {
            val padded = (unsignedTx.size + 31) / 32 * 32
            return GET_L1_FEE + "20".padStart(64, '0') + unsignedTx.size.toString(16).padStart(64, '0') +
                unsignedTx.toHex() + "00".repeat(padded - unsignedTx.size)
        }

        /** How many times the estimate a site's own `gas` may be ([gasLimit]). */
        private val SITE_GAS_CEILING = BigInteger.valueOf(3)

        /** A receipt's outcome, or null if it's not a receipt this can read. */
        internal fun outcomeOf(receipt: JSONObject): SendStatus.Stage? {
            val block = receipt.optString("blockNumber").hexOrNull()?.toLong() ?: return null
            val gasUsed = receipt.optString("gasUsed").hexOrNull()
            val price = receipt.optString("effectiveGasPrice").hexOrNull()
            // An OP Stack rollup (Base) also charges for posting the transaction to L1, as its own receipt field.
            val l1Fee = receipt.optString("l1Fee").hexOrNull() ?: BigInteger.ZERO
            val fee = if (gasUsed != null && price != null) gasUsed * price + l1Fee else null
            return when (receipt.optString("status").hexOrNull()) {
                BigInteger.ONE -> SendStatus.Stage.Confirmed(block, fee)
                BigInteger.ZERO -> SendStatus.Stage.Reverted(block, fee)
                else -> null
            }
        }

        private fun String.hexOrNull(): BigInteger? =
            // Hex digits only: BigInteger would also take a sign (`0x-5208`), a negative fee from one RPC.
            takeIf { it.startsWith("0x") && it.length in 3..66 && it.drop(2).all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                ?.let { BigInteger(it.substring(2), 16) }

        /** A read (balance, nonce, fee, estimate) that failed, for the user. */
        internal fun readFailure(e: ChainRpcException): String = readFailureSaid(e).text

        /** [readFailure], and the same in English for a site or a peer (#280). */
        internal fun readFailureSaid(e: ChainRpcException): Said = when (e) {
            is ChainRpcException.UnknownChain -> Strings.said(R.string.send_read_unknown_chain)
            is ChainRpcException.AllSourcesFailed -> Strings.said(R.string.send_read_no_rpc)
            is ChainRpcException.Rpc -> Strings.said(R.string.send_read_rpc_error, clip(e.rpcMessage))
            else -> Strings.said(R.string.send_read_nonsense)
        }

        /** The gas estimate failed: the chain would refuse the transaction as it stands. */
        internal fun estimateFailure(e: ChainRpcException.Rpc, request: SendRequest): SendException {
            val symbol = request.chain.symbol
            val message = when {
                e.insufficientFunds -> Strings.said(R.string.send_estimate_insufficient, symbol)
                e.data != null || e.code == ChainRpcException.EXECUTION_REVERTED || REVERTED.containsMatchIn(e.rpcMessage) -> {
                    val reason = e.data?.let(::revertReason) ?: REVERTED.find(e.rpcMessage)?.let { e.rpcMessage.substring(it.range.last + 1).trim(' ', ':') }
                    val why = reason?.takeIf { it.isNotBlank() }?.let(::clip)
                    when {
                        request.dapp != null -> why?.let { Strings.said(R.string.send_estimate_contract_refuses_reason, it) }
                            ?: Strings.said(R.string.send_estimate_contract_refuses)
                        request.token.isNative -> why?.let { Strings.said(R.string.send_estimate_recipient_refuses_reason, it) }
                            ?: Strings.said(R.string.send_estimate_recipient_refuses)
                        else -> why?.let { Strings.said(R.string.send_estimate_token_refuses_reason, request.token.symbol, it) }
                            ?: Strings.said(R.string.send_estimate_token_refuses, request.token.symbol)
                    }
                }
                else -> Strings.said(R.string.send_estimate_failed, clip(e.rpcMessage))
            }
            return SendException(message, e)
        }

        /**
         * A broadcast that failed: what to tell the user, and whether it
         * may have gone out anyway. Only a verdict this recognises as a
         * refusal (insufficient funds, a nonce, a fee too low), from a
         * walk where every source asked gave one, means it wasn't taken.
         * A source that never answered may have taken it whatever the
         * others said, and an error this can't read (a rate limit, a
         * client's own "already have it" wording) proves nothing either —
         * both are "may have gone", where Try again resends the same bytes.
         */
        private fun nodeErrorOf(e: ChainRpcException): ChainRpcException.Rpc? =
            (e as? ChainRpcException.Rpc) ?: (e as? ChainRpcException.AllSourcesFailed)?.nodeError

        /** A node's answer that the transaction's nonce is already taken (by whichever transaction). */
        internal fun nonceUsed(e: ChainRpcException): Boolean {
            val m = nodeErrorOf(e)?.rpcMessage?.lowercase() ?: return false
            return "nonce too low" in m || "already been used" in m || "oldnonce" in m
        }

        /**
         * [heldBy]: this app's own send found mined on the abandoned
         * send's nonce ([NonceTracker.Abandoned.heldBy]), named instead of
         * [SendQuote.replaces] when the nonce is refused as used.
         */
        internal fun broadcastFailure(e: ChainRpcException, quote: SendQuote, heldBy: String? = null): Pair<Said, Boolean> {
            val node = nodeErrorOf(e)
            val unanswered = (e as? ChainRpcException.AllSourcesFailed)?.unanswered == true
            val symbol = quote.request.chain.symbol
            val m = node?.rpcMessage?.lowercase().orEmpty()
            val nonce = quote.tx.nonce.toString()
            return when {
                node == null -> Strings.said(R.string.send_broadcast_uncertain) to true
                unanswered -> Strings.said(R.string.send_broadcast_one_unanswered, clip(node.rpcMessage)) to true
                node.insufficientFunds -> Strings.said(R.string.send_broadcast_insufficient, symbol) to false
                "nonce too high" in m -> Strings.said(R.string.send_broadcast_nonce_too_high, nonce) to false
                nonceUsed(e) && quote.replaces != null && heldBy != null ->
                    Strings.said(R.string.send_broadcast_nonce_used_by_replacement, nonce, heldBy) to false
                nonceUsed(e) && quote.replaces != null ->
                    Strings.said(R.string.send_broadcast_nonce_used_by_stopped, nonce, quote.replaces) to false
                nonceUsed(e) -> Strings.said(R.string.send_broadcast_nonce_used, nonce) to false
                "invalid nonce" in m -> Strings.said(R.string.send_broadcast_invalid_nonce, nonce) to false
                "replacement" in m && "underpriced" in m && quote.replaces != null ->
                    Strings.said(R.string.send_broadcast_stopped_holds_nonce, nonce) to false
                "replacement" in m && "underpriced" in m -> Strings.said(R.string.send_broadcast_nonce_waiting, nonce) to false
                "underpriced" in m || "fee cap" in m || "base fee" in m || "too low" in m ->
                    Strings.said(R.string.send_broadcast_underpriced) to false
                else -> Strings.said(R.string.send_broadcast_rpc_error, clip(node.rpcMessage)) to true
            }
        }

        /** `Error(string)` revert data → the string; null for anything else. */
        internal fun revertReason(data: String): String? {
            val hex = data.removePrefix("0x").lowercase()
            if (!hex.startsWith("08c379a0") || hex.length < 8 + 128) return null
            return runCatching {
                val body = hex.substring(8)
                // Untrusted node data: bound every length by what's actually there before using it.
                val offset = BigInteger(body.substring(0, 64), 16)
                if (offset > BigInteger.valueOf((body.length / 2 - 32).toLong())) return null
                val lenAt = offset.toInt() * 2
                val len = BigInteger(body.substring(lenAt, lenAt + 64), 16)
                val start = lenAt + 64
                if (len > BigInteger.valueOf(((body.length - start) / 2).toLong())) return null
                val bytes = ByteArray(len.toInt()) { i -> body.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte() }
                String(bytes, Charsets.UTF_8)
            }.getOrNull()
        }

        /** A node's or contract's words, for one line of the page: no control or bidi characters, at most 160 characters. */
        internal fun clip(text: String): String {
            val clean = text.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.trim()
            return if (clean.length > 160) clean.take(159) + "…" else clean
        }

        private val REVERTED = Regex("execution reverted", RegexOption.IGNORE_CASE)

        @Volatile
        private var instance: WalletSender? = null

        fun get(context: android.content.Context): WalletSender = instance ?: synchronized(this) {
            instance ?: WalletSender(
                rpc = WalletRpc(ChainDataRouter.get(context.applicationContext)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                journal = FileSendJournal(journalFile(context)),
                history = TxHistory.get(context),
            ).also { instance = it }
        }

        private fun journalFile(context: android.content.Context) =
            java.io.File(context.applicationContext.noBackupFilesDir, "wallet/send.json")

        /**
         * At each app launch, off the main thread (it touches storage):
         * if the last process left a journal, the sender comes up now
         * rather than at the first visit to the wallet — a send still
         * waiting for its receipt goes on being followed, and abandoned
         * sends whose nonce is mined are swept, a deleted wallet's among
         * them. With no journal there is nothing to resume or sweep, and
         * nothing is started.
         */
        fun resumeAtLaunch(context: android.content.Context) {
            if (journalFile(context).exists()) get(context)
        }

        /**
         * Signs with [account]'s key from [vault]'s seed: derived for this
         * one signature and zeroed after. Throws [VaultLockedException] if
         * the wallet isn't open.
         */
        fun vaultSigner(vault: Vault, account: WalletAccount): suspend (EthTransaction) -> EthTransaction.Signed = { tx ->
            val key = vault.withSeed { seed -> HdKeys.secp256k1(seed, account.path) }
            try {
                tx.sign(key, account.address)
            } finally {
                key.fill(0)
            }
        }
    }
}

/** A signer found the quote it was signing too old to use ([WalletSender.QUOTE_TTL_MS]): nothing was signed. */
class QuoteStaleException : Exception("quote went stale before signing")

/**
 * A signer's [fresh][WalletSender.signerFor] check found the page no longer
 * wants this transaction signed, for the reason in [message] (e.g. Fund node:
 * the node restarted as another account while the Ledger was connecting):
 * nothing was signed, and the page's own reason is what the user sees.
 */
class SigningHeldException(message: String) : Exception(message)
