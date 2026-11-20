package baby.freedom.mobile.agent

import android.content.Context
import baby.freedom.mobile.browser.Gateways
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.mobile.wallet.Recipients
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccountList
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * `wallet_transfer`'s arguments, as desktop's `operations.js` checks them
 * (`WALLET_TRANSFER`): [recipient], [amount] and [asset] trimmed and
 * non-empty, at most 255, 80 and 80 characters; [chainId] a positive
 * integer and [walletIndex] a non-negative one, when given. Nothing else.
 */
internal data class TransferArgs(
    val recipient: String,
    val amount: String,
    val asset: String,
    val chainId: Long? = null,
    val walletIndex: Int? = null,
) {
    companion object {
        private val KEYS = setOf("recipient", "amount", "asset", "chainId", "walletIndex")

        /** [args] checked, or the [ToolError] saying what to correct. */
        fun parse(args: JSONObject): TransferArgs {
            args.keys().forEach { if (it !in KEYS) throw invalid("$it is not a wallet_transfer argument") }
            val recipient = text(args, "recipient", 255)
            val amount = text(args, "amount", 80)
            val asset = text(args, "asset", 80)
            val chainId = integer(args, "chainId")?.also { if (it < 1) throw invalid("chainId must be a positive integer") }
            val walletIndex = integer(args, "walletIndex")?.also {
                if (it < 0 || it > Int.MAX_VALUE) throw invalid("walletIndex must be a non-negative integer")
            }
            return TransferArgs(recipient, amount, asset, chainId, walletIndex?.toInt())
        }

        private fun text(args: JSONObject, key: String, max: Int): String {
            val value = args.opt(key) as? String ?: throw invalid("$key must be a non-empty string")
            val t = value.trim()
            if (t.isEmpty()) throw invalid("$key must be a non-empty string")
            if (t.length > max) throw invalid("$key cannot exceed $max characters")
            return t
        }

        /** A whole number in JavaScript's safe range (desktop's `Number.isSafeInteger`), or null when absent. */
        private fun integer(args: JSONObject, key: String): Long? {
            val value = args.opt(key) ?: return null
            if (value == JSONObject.NULL) return null
            val n = when (value) {
                is Int -> value.toLong()
                is Long -> value
                is Double -> value.takeIf { it == Math.rint(it) && !it.isInfinite() }?.toLong()
                else -> null
            } ?: throw invalid("$key must be an integer")
            if (n !in -MAX_SAFE..MAX_SAFE) throw invalid("$key must be an integer")
            return n
        }

        private const val MAX_SAFE = 9_007_199_254_740_991L
    }
}

private fun invalid(message: String) = ToolError(ToolError.INVALID_ARGUMENT, message)

private fun unavailable(message: String) = ToolError(ToolError.CAPABILITY_UNAVAILABLE, message)

/**
 * The asset a transfer names (desktop's `#resolveTransferToken`): a
 * wallet asset ([TokenRegistry], on the chains the wallet's Send page
 * offers) by its symbol or contract address, any case — never an
 * arbitrary contract, whose decimals and symbol only an RPC would vouch
 * for. More than one match (the same symbol on two networks) asks for
 * the [chainId].
 */
internal object TransferAssets {
    /** The wallet's assets on [chains]: those in [TokenRegistry.WALLET_CHAIN_IDS], native first. */
    fun of(chains: List<Chain>): List<Pair<Chain, Token>> =
        TokenRegistry.WALLET_CHAIN_IDS.mapNotNull { id -> chains.firstOrNull { it.id == id } }
            .flatMap { chain -> TokenRegistry.tokens(chain).map { chain to it } }

    fun pick(asset: String, chainId: Long?, assets: List<Pair<Chain, Token>>): Pair<Chain, Token> {
        val candidates = assets.filter { (chain, token) ->
            (chainId == null || chain.id == chainId) &&
                (token.symbol.equals(asset, ignoreCase = true) || token.address?.equals(asset, ignoreCase = true) == true)
        }
        if (candidates.isEmpty()) {
            throw ToolError(
                ToolError.INVALID_ARGUMENT,
                if (chainId != null) {
                    "$asset is not a configured asset on chain $chainId. Ask the user to choose a configured asset and network."
                } else {
                    "$asset is not a configured Freedom wallet asset. Ask the user to choose a configured asset and network."
                },
            )
        }
        if (candidates.size > 1) {
            val networks = candidates.joinToString(", ") { "${it.first.name} (chainId ${it.first.id})" }
            throw invalid("$asset is available on multiple networks: $networks. Ask the user which network to use, then provide its chainId.")
        }
        return candidates.single()
    }
}

/**
 * The amount as the model wrote it, in [token]'s base units: digits with
 * at most one `.` — no comma, which is a decimal point on some keyboards
 * and a thousands separator on others ([SendAmounts.parse] takes either
 * for a typed field; a model's `1,000` must not become 1) — and no more
 * decimals than the token has. Zero is refused (desktop: "must be positive").
 */
internal fun transferAmount(amount: String, token: Token): BigInteger {
    if (!Regex("^(\\d+\\.?\\d*|\\.\\d+)$").matches(amount)) {
        throw invalid("$amount is not a valid ${token.symbol} amount: write a plain decimal number with a point, like 0.5")
    }
    if (amount.all { it == '0' || it == '.' }) throw invalid("Transfer amount must be positive")
    return SendAmounts.parse(amount, token.decimals)
        ?: throw invalid("$amount is not a valid ${token.symbol} amount: ${token.symbol} has ${token.decimals} decimals")
}

/**
 * One transfer waiting on the user: [quote] exactly as it would be
 * signed, the name the recipient was given as and how that name's
 * address was vouched for ([nameTrust], null for an address), and
 * whether it was priced again after an earlier sheet ([repriced]).
 */
internal data class TransferAsk(
    val id: Long,
    val sessionId: String,
    val quote: SendQuote,
    val nameTrust: EnsTrust?,
    val repriced: Boolean,
) {
    /** The recipient is a name only one server vouched for: Confirm needs the user's explicit OK of that address. */
    val needsAcceptance: Boolean get() = quote.request.toName != null && nameTrust?.verified != true
}

/** How a transfer sheet ended. Only [Declined] is the user saying no. */
internal sealed interface TransferAnswer {
    /** Confirmed (the wallet opened, if it was locked); [acceptedUnverified]: the user ticked the one-server name's checkbox. */
    data class Approved(val acceptedUnverified: Boolean) : TransferAnswer
    data object Declined : TransferAnswer

    /** Taken down without the user's answer: Stop, the chat deleted, the browser screen gone. */
    data object Withdrawn : TransferAnswer
}

/**
 * What `wallet_transfer` needs from the wallet: a seam, so its rules
 * are tested against fakes and never against a real send.
 */
internal interface TransferWallet {
    /** A wallet exists on this phone (it may be locked: the sheet's Confirm opens it). */
    fun hasWallet(): Boolean

    suspend fun accounts(): WalletAccountList?

    /** The chains as Settings has them; null when they can't be read. */
    suspend fun chains(): List<Chain>?

    suspend fun resolveAddress(name: String, chainId: Long, fresh: Boolean): EnsAddressResult

    fun busy(): Boolean

    /** Priced for review ([WalletSender.prepare]); throws [SendException] when it can't be sent. */
    suspend fun prepare(request: SendRequest): SendQuote

    fun isStale(quote: SendQuote): Boolean

    /** Signs [quote] and waits until it went out ([WalletSender.submitAndAwaitBroadcast]). */
    suspend fun send(quote: SendQuote): WalletSender.Broadcast
}

/** The app's own wallet, send flow and name resolver, from the application context only. */
internal class AppTransferWallet(context: Context) : TransferWallet {
    private val app = context.applicationContext
    private val vault = Vault.get(app)
    private val sender = WalletSender.get(app)

    override fun hasWallet(): Boolean = vault.state.value.let { it is Vault.State.Locked || it is Vault.State.Unlocked }

    override suspend fun accounts(): WalletAccountList? =
        withTimeoutOrNull(5_000) { WalletAccounts.get(app).accounts.first { it != null } }

    override suspend fun chains(): List<Chain>? = ChainStore.get(app).chainsOrUnreadable.first()

    override suspend fun resolveAddress(name: String, chainId: Long, fresh: Boolean): EnsAddressResult =
        Gateways.ensResolver.resolveAddress(name, chainId, fresh = fresh)

    override fun busy(): Boolean = sender.busy()

    override suspend fun prepare(request: SendRequest): SendQuote = sender.prepare(request)

    override fun isStale(quote: SendQuote): Boolean = sender.isStale(quote)

    // Not wallet activity in itself: the sheet's Confirm already counted as the user's.
    override suspend fun send(quote: SendQuote): WalletSender.Broadcast =
        sender.submitAndAwaitBroadcast(quote, WalletSender.signerFor(app, vault, quote.request.from, activity = false) { !sender.isStale(quote) })
}

/**
 * The transfers Freedom Agent is waiting on the user for (#596): the
 * same shape as [AgentApprovals] — one sheet at a time, oldest first,
 * each answer settling exactly the ask it was shown for, a withdrawal
 * never read as the user's no — but kept apart, since a transfer is
 * bound to no tab. Its ids come from [AgentApprovals.nextId], so the
 * hosts (with [AgentDomains]' name transactions, #599) show whichever was
 * asked first and never two at once ([agentSheetFirst]).
 *
 * [wallet] is installed by the browser screen's host while it's up
 * ([AgentTransferHost]): with no host, nothing could show the sheet,
 * so no transfer is prepared at all.
 */
internal object AgentTransfers {
    private class Pending(val ask: TransferAsk, val result: CompletableDeferred<TransferAnswer>)

    private val lock = Any()
    private val pending = MutableStateFlow<List<Pending>>(emptyList())
    private val _shown = MutableStateFlow<TransferAsk?>(null)

    /** The transfer on screen: the oldest waiting. */
    val shown: StateFlow<TransferAsk?> = _shown.asStateFlow()

    @Volatile
    var wallet: TransferWallet? = null

    suspend fun ask(ask: TransferAsk): TransferAnswer {
        val entry = Pending(ask, CompletableDeferred())
        update { it + entry }
        try {
            return entry.result.await()
        } catch (e: CancellationException) {
            entry.result.complete(TransferAnswer.Withdrawn)
            throw e
        } finally {
            update { list -> list.filter { it !== entry } }
        }
    }

    /** The user's answer to the sheet [id]; nothing for one already settled or gone. */
    fun answer(id: Long, answer: TransferAnswer) {
        val entry = synchronized(lock) { pending.value.firstOrNull { it.ask.id == id } } ?: return
        entry.result.complete(answer)
        update { list -> list.filter { it !== entry } }
    }

    fun withdrawSession(sessionId: String) = withdraw { it.sessionId == sessionId }

    fun withdrawAll() = withdraw { true }

    private fun withdraw(which: (TransferAsk) -> Boolean) {
        val gone = synchronized(lock) { pending.value.filter { which(it.ask) } }
        if (gone.isEmpty()) return
        for (entry in gone) entry.result.complete(TransferAnswer.Withdrawn)
        update { list -> list.filter { it !in gone } }
    }

    private fun update(change: (List<Pending>) -> List<Pending>) = synchronized(lock) {
        pending.update(change)
        _shown.value = pending.value.firstOrNull()?.ask
    }
}

/**
 * Desktop's `wallet_transfer` (`agent-wallet-controller.js`, #596): one
 * exact transfer of a wallet asset, always put to the user on a review
 * sheet built from the priced transaction itself; nothing is signed
 * without their Confirm, in any mode — no rule, allowance or setting
 * answers it. Everything that can be checked is checked before the
 * sheet: the asset, the account, the recipient (an address, or an ENS,
 * WNS or GNS name looked up as the Send page does), the amount, the
 * balance and the fee (a quote with no fee is refused). After Confirm
 * the name is looked up again past every cache, as Send does, and a
 * quote that aged meanwhile is priced again and shown again.
 *
 * A locked wallet is opened by the sheet's Confirm (the screen lock);
 * the signer itself never opens it, so a wallet that locks again before
 * signing fails the send unsigned.
 */
internal class WalletTransferTool(
    private val sessionId: String,
    private val wallet: () -> TransferWallet? = { AgentTransfers.wallet },
    private val ask: suspend (TransferAsk) -> TransferAnswer = AgentTransfers::ask,
    private val nextId: () -> Long = AgentApprovals::nextId,
) : AgentTool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Prepare and send one exact asset transfer from a Freedom wallet. This is a direct Freedom capability, " +
            "not a webpage interaction. Freedom resolves the recipient, verifies balances, estimates the maximum fee, and " +
            "always asks the user to approve the exact transfer before signing. If an asset exists on multiple networks, ask " +
            "the user which network to use and retry with its chainId. If the user declines, do not retry or work around the " +
            "decision unless they explicitly ask again.",
        parameters = JSONObject(
            """
            {"type":"object","properties":{
              "recipient":{"type":"string","minLength":1,"maxLength":255},
              "amount":{"type":"string","minLength":1,"maxLength":80},
              "asset":{"type":"string","minLength":1,"maxLength":80,"description":"Configured wallet asset symbol or token contract address."},
              "chainId":{"type":"integer","minimum":1,"description":"Exact EVM chain ID. Required when the asset is ambiguous."},
              "walletIndex":{"type":"integer","minimum":0,"description":"Freedom wallet account index. Omit to use the active account."}
            },"required":["recipient","amount","asset"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )

    override suspend fun run(arguments: JSONObject): ToolOutcome = try {
        transfer(TransferArgs.parse(arguments))
    } catch (e: ToolError) {
        e.outcome(NAME)
    }

    private suspend fun transfer(args: TransferArgs): ToolOutcome {
        val w = wallet() ?: throw unavailable("Freedom's wallet isn't available right now: open the browser and try again.")
        if (!w.hasWallet()) throw unavailable("There is no Freedom wallet on this phone. Ask the user to set one up on the wallet page.")
        val chains = w.chains() ?: throw unavailable("Freedom couldn't read its network settings. Try again shortly.")
        val (chain, token) = TransferAssets.pick(args.asset, args.chainId, TransferAssets.of(chains))
        val list = w.accounts() ?: throw unavailable("Freedom couldn't read its wallet accounts. Try again shortly.")
        val account = if (args.walletIndex == null) {
            list.active
        } else {
            list.accounts.firstOrNull { it.index == args.walletIndex }
                ?: throw unavailable("The selected wallet account is unavailable")
        }
        val amount = transferAmount(args.amount, token)
        val recipient = resolveRecipient(w, args.recipient, chain, token)
        val request = SendRequest(chain, token, account, recipient.address, amount, toName = recipient.name)
        var repriced = false
        repeat(MAX_SHEETS) {
            currentCoroutineContext().ensureActive()
            // Before the sheet, not after the user confirmed one that can't go.
            if (w.busy()) throw unavailable(BUSY)
            val quote = prepared(w, request)
            val shown = TransferAsk(nextId(), sessionId, quote, recipient.trust, repriced)
            val answer = ask(shown)
            if (answer !is TransferAnswer.Approved) {
                if (answer == TransferAnswer.Declined) {
                    throw ToolError(ToolError.USER_DECLINED, "The user declined the wallet transfer. Nothing was sent.", declined = true)
                }
                throw ToolError(ToolError.APPROVAL_REQUIRED, "The transfer sheet was closed without an answer. Nothing was sent.")
            }
            if (shown.needsAcceptance && !answer.acceptedUnverified) {
                // The sheet keeps Confirm off until it's ticked; this is the tool's own check of that.
                throw ToolError(ToolError.APPROVAL_REQUIRED, "The user didn't accept the recipient name's unverified address. Nothing was sent.")
            }
            currentCoroutineContext().ensureActive()
            // Priced too long ago to trust its fee: priced again, and the user looks again.
            if (w.isStale(quote)) {
                repriced = true
                return@repeat
            }
            val confirmed = quote.copy(request = quote.request.copy(toNameAccepted = answer.acceptedUnverified))
            recheckName(w, confirmed)
            // From here the send is the wallet's own ([WalletSender] runs it in its own
            // scope): a Stop now ends only this wait, and the wallet page follows the send.
            val outcome = w.send(confirmed)
            when (outcome) {
                is WalletSender.Broadcast.Sent -> return sent(confirmed, outcome.hash)
                is WalletSender.Broadcast.Stale -> repriced = true
                WalletSender.Broadcast.Busy -> throw unavailable(BUSY)
                WalletSender.Broadcast.Rejected ->
                    throw ToolError(ToolError.USER_DECLINED, "The user rejected the transfer on their Ledger. Nothing was sent.", declined = true)
                is WalletSender.Broadcast.Failed -> if (outcome.mayHaveGone) {
                    throw ToolError(
                        ToolError.INTERNAL,
                        "outcome_unknown: the transfer may have been sent" + (outcome.hash?.let { " (transaction $it)" } ?: "") +
                            ". ${outcome.english} Do not send it again: the user can follow it on Freedom's wallet page.",
                    )
                } else {
                    throw unavailable("The transfer was not sent: ${outcome.english}")
                }
            }
        }
        throw unavailable("The network fee kept changing before the transfer could be sent. Nothing was sent.")
    }

    private class Recipient(val address: String, val name: String?, val trust: EnsTrust?)

    /** Desktop's `#resolveTransferRecipient`, through the Send page's own rules ([Recipients]). */
    private suspend fun resolveRecipient(w: TransferWallet, input: String, chain: Chain, token: Token): Recipient =
        when (val parsed = Recipients.parse(input, token, names = true)) {
            is Recipients.Parsed.Ok -> Recipient(parsed.address, null, null)
            is Recipients.Parsed.Invalid -> throw invalid("The recipient can't be used: ${parsed.reason}")
            is Recipients.Parsed.Name -> {
                val result = try {
                    w.resolveAddress(parsed.name, chain.id, fresh = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw unavailable("Freedom could not resolve ${parsed.name}. Try again shortly.")
                }
                Recipients.lookupProblem(result, chain.name)?.let {
                    throw if (Recipients.retryable(result)) unavailable("Freedom could not resolve ${parsed.name}: $it") else invalid(it)
                }
                val ok = result as EnsAddressResult.Ok
                when (val r = Recipients.resolved(ok.address, token)) {
                    is Recipients.Parsed.Ok -> Recipient(r.address, parsed.name, ok.trust)
                    is Recipients.Parsed.Invalid -> throw invalid("${parsed.name} can't be sent to: ${r.reason}")
                    is Recipients.Parsed.Name -> throw invalid("${parsed.name} does not resolve to an Ethereum address")
                }
            }
        }

    private suspend fun prepared(w: TransferWallet, request: SendRequest): SendQuote {
        val quote = try {
            w.prepare(request)
        } catch (e: SendException) {
            throw unavailable(e.english)
        }
        // A quote with no fee isn't one Freedom can stand behind: the sheet would say "up to 0".
        if (quote.tx.gasLimit.signum() <= 0 || quote.tx.fees.maxPerGas.signum() <= 0 || quote.maxFee.signum() <= 0) {
            throw unavailable("Freedom could not determine the maximum transfer fee")
        }
        return quote
    }

    /**
     * A name is asked again past every cache right before signing (as
     * Send does, #277): the address on the sheet must still be its
     * answer, as well vouched for as when it was confirmed.
     */
    private suspend fun recheckName(w: TransferWallet, quote: SendQuote) {
        val name = quote.request.toName ?: return
        val after = try {
            w.resolveAddress(name, quote.request.chain.id, fresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnsAddressResult.Error(name, "RESOLUTION_ERROR", e.message ?: e.javaClass.simpleName, retryable = true)
        }
        currentCoroutineContext().ensureActive()
        Recipients.recheck(name, quote.request.to, after, quote.request.toNameAccepted)?.let {
            throw unavailable("Nothing was sent: $it")
        }
    }

    private fun sent(quote: SendQuote, hash: String): ToolOutcome {
        val r = quote.request
        val wallet = JSONObject()
            .put("action", "broadcast")
            .put("chainId", r.chain.id)
            .put("transactionHash", hash)
            .put("recipient", r.to)
            .put("amount", SendAmounts.exact(r.amount, r.token.decimals))
            .put("asset", r.token.symbol)
        return ToolOutcome(JSONObject().put("ok", true).put("result", JSONObject().put("wallet", wallet)).toString())
    }

    companion object {
        const val NAME = "wallet_transfer"

        /** Sheets one call may show: the first, and again after the fee was priced again. */
        const val MAX_SHEETS = 3

        private const val BUSY =
            "Another transaction from this wallet is still going out or waiting on the wallet page. Nothing was sent."
    }
}
