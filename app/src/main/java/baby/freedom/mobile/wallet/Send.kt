package baby.freedom.mobile.wallet

import android.util.Log
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A send the user can't make as asked, with what to tell them. */
class SendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Typed amounts: `1.5` of a token with [decimals] decimals → base units. */
object SendAmounts {
    /**
     * [input] in base units, or null when it isn't a plain positive
     * decimal with at most [decimals] digits after the point. Either `.`
     * or `,` is the decimal point (a keyboard's decimal key types the
     * locale's); no grouping, no sign, no exponent.
     */
    fun parse(input: String, decimals: Int): BigInteger? {
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
}

/** The recipient field. */
object Recipients {
    sealed interface Parsed {
        /** [address] EIP-55 checksummed. */
        data class Ok(val address: String) : Parsed

        data class Invalid(val reason: String) : Parsed
    }

    /**
     * [input] as a recipient for [token]: a `0x` address of 40 hex
     * digits. Mixed case must be a correct EIP-55 checksum (a typo in a
     * checksummed address is caught, not sent); all-lower or all-upper
     * carries no checksum and is taken as typed. The zero address, and a
     * token's own contract for that token, are refused: what's sent there
     * is gone.
     */
    fun parse(input: String, token: Token): Parsed {
        val t = input.trim()
        if (t.isEmpty()) return Parsed.Invalid("Enter the address to send to")
        if (!EthTransaction.ADDRESS.matches(t)) {
            return Parsed.Invalid("Not an address: it’s 0x and 40 hex digits (names aren’t supported here yet)")
        }
        val digits = t.substring(2)
        val checksummed = checksum(digits.lowercase())
        val mixed = digits.any { it in 'a'..'f' } && digits.any { it in 'A'..'F' }
        if (mixed && checksummed != t) return Parsed.Invalid("This address has a typo: its capital letters don’t match its checksum")
        if (digits.all { it == '0' }) return Parsed.Invalid("That’s the zero address: anything sent there is lost")
        if (token.address != null && token.address.equals(checksummed, ignoreCase = true)) {
            return Parsed.Invalid("That’s the ${token.symbol} contract itself, not an account: tokens sent there are lost")
        }
        return Parsed.Ok(checksummed)
    }

    private fun checksum(lowerHex: String): String =
        NodeIdentity.checksum(ByteArray(20) { i -> lowerHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
}

/** What the user asked for: [amount] base units of [token] from [from] to [to] on [chain]. */
data class SendRequest(
    val chain: Chain,
    val token: Token,
    val from: WalletAccount,
    /** EIP-55 checksummed ([Recipients.parse]). */
    val to: String,
    val amount: BigInteger,
) {
    init {
        require(token.chainId == chain.id) { "the token is on another chain" }
        require(amount.signum() > 0) { "nothing to send" }
    }

    /**
     * What goes on chain: native passes straight through; an ERC-20 is a
     * call to its contract with `transfer(to, amount)` and no value.
     */
    fun call(): Triple<String, BigInteger, ByteArray> = if (token.address == null) {
        Triple(to, amount, ByteArray(0))
    } else {
        Triple(token.address, BigInteger.ZERO, Erc20.transferData(to, amount))
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
) {
    /** For a native send, the amount plus the most the fee can be; null for a token (two currencies). */
    val nativeTotal: BigInteger? get() = if (request.token.isNative) request.amount + tx.maxFee else null
}

/**
 * Fees for the next transaction (#105). With a base fee (EIP-1559):
 * the node's suggested tip, but at least [MIN_TIP_WEI] — iOS's
 * `GasOracle` floor; one lowballing RPC must not leave the send, and
 * every one queued behind its nonce, stuck — and a cap of twice the
 * latest base fee plus the tip, desktop's market preset: headroom for
 * the base fee to keep rising for a few blocks between quote and
 * inclusion. Unused headroom is never charged. Without a base fee:
 * legacy, at the node's gas price.
 */
class GasOracle(private val rpc: WalletRpc) {
    suspend fun fees(chainId: Long): EthTransaction.Fees {
        val baseFee = rpc.latestBaseFee(chainId).value
        if (baseFee == null) return legacy(rpc.gasPrice(chainId).value)
        val tip = try {
            rpc.maxPriorityFeePerGas(chainId).value
        } catch (e: ChainRpcException) {
            // An RPC without the method (it's not standard JSON-RPC): the floor is a fine tip.
            null
        }
        return eip1559(baseFee, tip)
    }

    companion object {
        /** 1 gwei, the tip most wallets bid by default. */
        val MIN_TIP_WEI: BigInteger = BigInteger.valueOf(1_000_000_000L)

        internal fun eip1559(baseFee: BigInteger, suggestedTip: BigInteger?): EthTransaction.Fees.Eip1559 {
            val tip = (suggestedTip ?: MIN_TIP_WEI).max(MIN_TIP_WEI)
            return EthTransaction.Fees.Eip1559(maxFeePerGas = baseFee.shiftLeft(1) + tip, maxPriorityFeePerGas = tip)
        }

        internal fun legacy(gasPrice: BigInteger): EthTransaction.Fees.Legacy {
            // A zero price would sit in the mempool forever and hold every later nonce behind it.
            if (gasPrice.signum() <= 0) throw SendException("The network gave no usable gas price. Try again.")
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
 */
class NonceTracker(private val rpc: WalletRpc) {
    private val sent = HashMap<String, BigInteger>()

    suspend fun next(address: String, chainId: Long): WalletRpc.Reading<BigInteger> {
        val fromChain = rpc.transactionCount(chainId, address)
        val local = synchronized(sent) { sent[key(address, chainId)] }
        return if (local != null && local > fromChain.value) fromChain.copy(value = local) else fromChain
    }

    fun markSent(address: String, chainId: Long, nonce: BigInteger) {
        synchronized(sent) {
            val k = key(address, chainId)
            val next = nonce + BigInteger.ONE
            if (sent[k]?.let { it >= next } != true) sent[k] = next
        }
    }

    fun forget(address: String, chainId: Long) {
        synchronized(sent) { sent.remove(key(address, chainId)) }
    }

    private fun key(address: String, chainId: Long) = "$chainId:${address.lowercase()}"
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
         */
        data class Failed(val message: String, val mayHaveGone: Boolean) : Stage

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
) {
    private val nonces = NonceTracker(rpc)
    private val gas = GasOracle(rpc)

    private val _status = MutableStateFlow<SendStatus?>(null)

    /** The current (or last) confirmed send; null once acknowledged. */
    val status: StateFlow<SendStatus?> = _status.asStateFlow()

    /** The signed bytes of the current send, kept so Try again resends exactly them. */
    private var signed: EthTransaction.Signed? = null
    private var job: Job? = null

    /**
     * Prices [request] for the review; with [all], for the account's
     * whole balance of the token (of the native currency: all of it
     * less the most the fee can be, so the amount is [SendQuote.request]'s). Throws [SendException] with what
     * to tell the user: not enough of the token, or of the native
     * currency for the fee; a transfer the chain would refuse; no RPC
     * answering.
     */
    suspend fun prepare(request: SendRequest, all: Boolean = false): SendQuote = try {
        coroutineScope {
            val chainId = request.chain.id
            val from = request.from.address
            val token = request.token
            val native = async { rpc.balance(chainId, from).value }
            val tokenBalance = async {
                token.address?.let { contract ->
                    val r = rpc.call(chainId, JSONObject().put("to", contract).put("data", Erc20.balanceOfData(from)))
                    Erc20.decodeUint256(r.value) ?: throw SendException("The ${token.symbol} contract gave no balance for this account")
                }
            }
            val nonce = async { nonces.next(from, chainId) }
            val fees = async { gas.fees(chainId) }
            val held = tokenBalance.await() ?: native.await()
            if (held.signum() == 0) throw SendException("This account has no ${token.symbol}")
            if (!all && request.amount > held) {
                throw SendException(
                    "Not enough ${token.symbol}: this account has ${TokenAmounts.format(held, token.decimals)}",
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
            var tx = EthTransaction(
                chainId = chainId,
                nonce = nonce.await().value,
                gasLimit = gasLimit(estimate, data.isNotEmpty()),
                to = to,
                value = value,
                data = data,
                fees = fees.await(),
            )
            val nativeBalance = native.await()
            val symbol = request.chain.symbol
            val fee = "${TokenAmounts.format(tx.maxFee, request.chain.decimals)} $symbol"
            val has = "this account has ${TokenAmounts.format(nativeBalance, request.chain.decimals)} $symbol"
            if (all && token.isNative) {
                val rest = nativeBalance - tx.maxFee
                if (rest.signum() <= 0) throw SendException("Not enough $symbol to pay the network fee (up to $fee): $has")
                sending = sending.copy(amount = rest)
                tx = tx.copy(value = rest)
            }
            if (tx.maxFee + tx.value > nativeBalance) {
                val what = if (token.isNative) "the amount and the network fee" else "the network fee"
                throw SendException("Not enough $symbol for $what (up to $fee): $has")
            }
            SendQuote(sending, tx, nativeBalance, tokenBalance.await(), clock(), nonce.await().trust)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SendException) {
        throw e
    } catch (e: ChainRpcException) {
        throw SendException(readFailure(e), e)
    }

    /** Whether [quote] is too old to sign as is (its fees may no longer get it mined). */
    fun isStale(quote: SendQuote): Boolean = clock() - quote.preparedAt !in 0 until QUOTE_TTL_MS

    /**
     * Signs [quote]'s transaction with [sign] (the account's key, which
     * it zeroes) and broadcasts it, then follows it to a receipt. False
     * if another send is still being signed or broadcast.
     */
    fun submit(quote: SendQuote, sign: (EthTransaction) -> EthTransaction.Signed): Boolean {
        synchronized(this) {
            val current = _status.value?.stage
            if (current == SendStatus.Stage.Signing || current == SendStatus.Stage.Broadcasting) return false
            job?.cancel()
            signed = null
            _status.value = SendStatus(quote, SendStatus.Stage.Signing)
            job = scope.launch {
                val s = try {
                    sign(quote.tx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VaultLockedException) {
                    fail(quote, "The wallet locked before the transaction was signed. Nothing was sent; confirm again to unlock it.", false)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "signing failed: ${e.javaClass.simpleName}")
                    fail(quote, "Couldn’t sign the transaction. Nothing was sent.", false)
                    return@launch
                }
                synchronized(this@WalletSender) { signed = s }
                broadcast(quote, s)
            }
        }
        return true
    }

    /** After a [SendStatus.Stage.Failed] that [SendStatus.Stage.Failed.mayHaveGone]: the same bytes again. */
    fun retry() {
        synchronized(this) {
            val status = _status.value ?: return
            val s = signed ?: return
            if ((status.stage as? SendStatus.Stage.Failed)?.mayHaveGone != true) return
            job?.cancel()
            _status.value = status.copy(stage = SendStatus.Stage.Broadcasting, hash = s.hash)
            job = scope.launch { broadcast(status.quote, s) }
        }
    }

    /** After [SendStatus.Stage.Unconfirmed]: keep waiting for the receipt. */
    fun checkAgain() {
        synchronized(this) {
            val status = _status.value ?: return
            val hash = status.hash ?: return
            if (status.stage != SendStatus.Stage.Unconfirmed) return
            job?.cancel()
            _status.value = status.copy(stage = SendStatus.Stage.Pending)
            job = scope.launch { follow(status.quote, hash) }
        }
    }

    /** The page is done with the outcome. A send still being signed or broadcast stays. */
    fun acknowledge() {
        synchronized(this) {
            val stage = _status.value?.stage ?: return
            if (stage == SendStatus.Stage.Signing || stage == SendStatus.Stage.Broadcasting) return
            job?.cancel()
            job = null
            signed = null
            _status.value = null
        }
    }

    private suspend fun broadcast(quote: SendQuote, s: EthTransaction.Signed) {
        val from = quote.request.from.address
        val chainId = quote.tx.chainId
        set(quote, SendStatus.Stage.Broadcasting, s.hash)
        try {
            rpc.sendRawTransaction(chainId, s.raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChainRpcException) {
            // A node that timed out answering may have taken it, and a node
            // asked after it then says the nonce is used: if this very
            // transaction is on chain, it went out.
            if (landed(chainId, s.hash)) {
                nonces.markSent(from, chainId, quote.tx.nonce)
                set(quote, SendStatus.Stage.Pending, s.hash)
                follow(quote, s.hash)
                return
            }
            nonces.forget(from, chainId)
            val (message, uncertain) = broadcastFailure(e, quote)
            Log.i(TAG, "broadcast chain=$chainId nonce=${quote.tx.nonce} failed (uncertain=$uncertain)")
            fail(quote, message, uncertain)
            return
        }
        nonces.markSent(from, chainId, quote.tx.nonce)
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
                    set(quote, outcome, hash)
                    return
                }
            }
            if (clock() >= deadline) break
            delay(pollMs)
        }
        set(quote, SendStatus.Stage.Unconfirmed, hash)
    }

    private fun set(quote: SendQuote, stage: SendStatus.Stage, hash: String?) {
        synchronized(this) {
            // A newer send (or an acknowledge) took over: this one's news is stale.
            if (_status.value?.quote !== quote) return
            _status.value = SendStatus(quote, stage, hash)
        }
    }

    private fun fail(quote: SendQuote, message: String, mayHaveGone: Boolean) {
        synchronized(this) {
            if (_status.value?.quote !== quote) return
            _status.value = _status.value?.copy(stage = SendStatus.Stage.Failed(message, mayHaveGone))
        }
    }

    companion object {
        private const val TAG = "WalletSend"

        /** A quote older than this is priced again before it's signed. */
        const val QUOTE_TTL_MS = 60_000L

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

        /** A receipt's outcome, or null if it's not a receipt this can read. */
        internal fun outcomeOf(receipt: JSONObject): SendStatus.Stage? {
            val block = receipt.optString("blockNumber").hexOrNull()?.toLong() ?: return null
            val gasUsed = receipt.optString("gasUsed").hexOrNull()
            val price = receipt.optString("effectiveGasPrice").hexOrNull()
            val fee = if (gasUsed != null && price != null) gasUsed * price else null
            return when (receipt.optString("status").hexOrNull()) {
                BigInteger.ONE -> SendStatus.Stage.Confirmed(block, fee)
                BigInteger.ZERO -> SendStatus.Stage.Reverted(block, fee)
                else -> null
            }
        }

        private fun String.hexOrNull(): BigInteger? =
            takeIf { it.startsWith("0x") && it.length in 3..66 }?.let { runCatching { BigInteger(it.substring(2), 16) }.getOrNull() }

        /** A read (balance, nonce, fee, estimate) that failed, for the user. */
        internal fun readFailure(e: ChainRpcException): String = when (e) {
            is ChainRpcException.UnknownChain -> "This chain isn’t set up in Settings → Chains."
            is ChainRpcException.AllSourcesFailed -> "No RPC answered for this chain. Check the connection and try again."
            is ChainRpcException.Rpc -> "The RPC answered with an error: ${clip(e.rpcMessage)}"
            else -> "The RPC’s answer made no sense. Try again."
        }

        /** The gas estimate failed: the chain would refuse the transaction as it stands. */
        internal fun estimateFailure(e: ChainRpcException.Rpc, request: SendRequest): SendException {
            val symbol = request.chain.symbol
            val message = when {
                e.insufficientFunds -> "Not enough $symbol to pay for this transaction."
                e.data != null || e.code == ChainRpcException.EXECUTION_REVERTED || REVERTED.containsMatchIn(e.rpcMessage) -> {
                    val reason = e.data?.let(::revertReason) ?: REVERTED.find(e.rpcMessage)?.let { e.rpcMessage.substring(it.range.last + 1).trim(' ', ':') }
                    val who = if (request.token.isNative) "The recipient" else "The ${request.token.symbol} contract"
                    "$who would refuse this transfer" + (reason?.takeIf { it.isNotBlank() }?.let { ": ${clip(it)}" } ?: ".")
                }
                else -> "The network couldn’t price this transaction: ${clip(e.rpcMessage)}"
            }
            return SendException(message, e)
        }

        /**
         * A broadcast that failed: what to tell the user, and whether it
         * may have gone out anyway. A node's own verdict (insufficient
         * funds, a used nonce, a fee too low) means it wasn't taken; no
         * answer at all means it can't be told.
         */
        internal fun broadcastFailure(e: ChainRpcException, quote: SendQuote): Pair<String, Boolean> {
            val node = (e as? ChainRpcException.Rpc) ?: (e as? ChainRpcException.AllSourcesFailed)?.nodeError
            val symbol = quote.request.chain.symbol
            val m = node?.rpcMessage?.lowercase().orEmpty()
            return when {
                node == null -> (
                    "No RPC confirmed it took the transaction, so it may or may not have gone out. " +
                        "Try again sends the very same transaction, so it can’t be paid twice."
                    ) to true
                node.insufficientFunds -> "Not sent: not enough $symbol for the amount and the fee any more." to false
                "nonce too low" in m || "already been used" in m || "invalid nonce" in m || "nonce too high" in m ->
                    "Not sent: nonce ${quote.tx.nonce} was already used — maybe by another wallet with this account. Review it again." to false
                "replacement" in m && "underpriced" in m ->
                    "Not sent: another transaction with nonce ${quote.tx.nonce} is still waiting to be mined. Review it again." to false
                "underpriced" in m || "fee cap" in m || "base fee" in m || "too low" in m ->
                    "Not sent: its fee is below what the network takes now. Review it again for a fresh fee." to false
                else -> "Not sent: the network refused it (${clip(node.rpcMessage)})." to false
            }
        }

        /** `Error(string)` revert data → the string; null for anything else. */
        internal fun revertReason(data: String): String? {
            val hex = data.removePrefix("0x").lowercase()
            if (!hex.startsWith("08c379a0") || hex.length < 8 + 128) return null
            return runCatching {
                val body = hex.substring(8)
                val offset = BigInteger(body.substring(0, 64), 16).toInt()
                val len = BigInteger(body.substring(offset * 2, offset * 2 + 64), 16).toInt()
                val start = offset * 2 + 64
                val bytes = ByteArray(len) { i -> body.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte() }
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
            ).also { instance = it }
        }

        /**
         * Signs with [account]'s key from [vault]'s seed: derived for this
         * one signature and zeroed after. Throws [VaultLockedException] if
         * the wallet isn't open.
         */
        fun vaultSigner(vault: Vault, account: WalletAccount): (EthTransaction) -> EthTransaction.Signed = { tx ->
            val key = vault.withSeed { seed -> HdKeys.secp256k1(seed, account.path) }
            try {
                tx.sign(key, account.address)
            } finally {
                key.fill(0)
            }
        }
    }
}
