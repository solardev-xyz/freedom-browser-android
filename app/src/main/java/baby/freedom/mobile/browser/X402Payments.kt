package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.X402Store
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.Erc20
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.X402
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One offer of an x402 payment sheet, with what the wallet knows about its token. */
class X402Option internal constructor(
    val offer: X402.Offer,
    val chain: Chain,
    val symbol: String,
    val decimals: Int,
    /**
     * The token is one the wallet lists; false: its symbol and decimals
     * were read from the contract, on verified answers on a built-in
     * chain ([X402Payments.tokenReadTrusted]).
     */
    val listed: Boolean,
    /** The paying account's balance of the token, or null if it couldn't be read (or there's no account). */
    val balance: BigInteger?,
) {
    /** False only when the balance is known and short of the amount. */
    val fundable: Boolean get() = balance == null || balance >= offer.amount
}

/** What an x402 payment sheet shows ([EthAsk.Payment]). */
class X402Ask internal constructor(
    /** The page that asked to be paid for. */
    val url: String,
    val description: String?,
    /**
     * The account that pays, and that every figure here — balances,
     * [allowanceWaitingOnUnlock] — was worked out for; null: no wallet.
     * The sheet names this one, never whichever is active when it's drawn
     * (#218 R4-F1).
     */
    val account: WalletAccount?,
    /** The offers the wallet can pay, in the server's order. */
    val options: List<X402Option>,
    /** Why each other offer can't be paid. */
    val unusable: List<String>,
    /** An allowance of the site's covers this, but it was left to the user: the wallet was locked. */
    val allowanceWaitingOnUnlock: Boolean,
)

/** The user's yes on an x402 sheet: which offer ([X402Option]'s index in [X402Ask.options]), and an allowance to grant. */
data class X402Choice(val option: Int, val allowance: X402Grant?)

/** Pay the site without asking up to [cap] base units (this payment included) for [windowMs]. */
data class X402Grant(val cap: BigInteger, val windowMs: Long)

/**
 * x402 pay-per-request (#140) on a tab's pages: desktop's
 * `x402/intercept.js` and `sign-flow.js`.
 *
 * A top-level `GET` of an http(s) page — https, or http on loopback, as
 * for `window.ethereum` — answered `402` with x402 terms in its headers
 * ([X402.requiredFrom], seen in `onReceivedHttpError`) is noted for its
 * tab. When that 402 page commits ([onDocumentStarted]) it's either paid
 * from an allowance of the site's, silently, or put to the user on the
 * wallet's approval sheet ([EthAsk.Payment], through [EthereumProviders]'
 * per-tab queue, so it takes its turn with the provider's sheets, goes
 * away with the page, and a rejection pauses the tab's sheets until the
 * user navigates it). On a yes, the payment is signed from the wallet's
 * active account and the page is loaded again with it in the payment
 * header; the answer to that request is the payment's outcome in the
 * history ([X402Store.Status]).
 *
 * Never in a private tab, and never automatically while the wallet is
 * locked: an allowance only pays when the wallet is open already; a
 * locked wallet gets the sheet, which asks for the screen lock. A paid
 * request the site answers with another 402 — at its URL or one it was
 * redirected to — is not paid again (desktop's loop guard): the history
 * says it was refused, and the user can reload to be asked. Which
 * navigation a 402 or an answer belongs to is [X402Flow]'s. A rejected sheet pauses the tab's sheets (the provider's
 * anti-loop rule) until the user navigates the tab themselves — an
 * address, Reload or pull-to-refresh — so reloading the page asks again.
 *
 * Subresource 402s (a page's `fetch`) aren't seen here: WebView doesn't
 * let a native handler hold one open and retry it. A site's own x402
 * client pays those through `window.ethereum` (`eth_signTypedData_v4`).
 *
 * Main thread only, like the WebView callbacks that drive it.
 */
object X402Payments {
    private val scope = MainScope()
    private var context: Context? = null

    /** A 402 with terms, waiting for its page to commit on [tab]. */
    private class Detection(val url: String, val origin: String, val required: X402.Required)

    /** Which navigation a 402 or a paid request's answer is (#218 R2). */
    private val flow = X402Flow<Detection>(
        settle = { id, status, httpStatus -> settle(id, status, httpStatus) },
        originOf = ::providerOriginKey,
    )
    private val random = SecureRandom()

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        // Paid requests an earlier run never saw answered.
        scope.launch { X402Store.get(app).settleStale() }
    }

    /**
     * `onReceivedHttpError` for a main-frame request on [tab]: a 402 with
     * terms is noted for when its page commits; the answer to a paid
     * request (at its URL, or one it was redirected to) settles its
     * payment as refused, and isn't paid again.
     */
    fun onHttpError(tab: BrowserState, request: WebResourceRequest, response: WebResourceResponse?) {
        if (tab.private || !request.isForMainFrame) return
        val url = request.url?.toString() ?: return
        val status = response?.statusCode ?: return
        if (flow.httpError(tab.id, url, request.method, status)) {
            // Not paid again: the loop guard.
            Log.i(TAG, "paid request answered HTTP $status")
            return
        }
        if (status != 402) return
        if (!request.method.equals("GET", ignoreCase = true)) return
        if (isDwebPageUrl(url)) return
        val origin = providerOriginKey(url) ?: return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val required = X402.requiredFrom(response.responseHeaders) ?: return
        Log.i(TAG, "402 with x402 v${required.version} terms: ${required.offers.size} payable offer(s)")
        flow.detected(tab.id, url, Detection(url, origin, required))
    }

    /** A main-frame server redirect on [tab] to [target]: a paid request's answer may be its. */
    fun onRedirect(tab: BrowserState, target: String) = flow.redirected(tab.id, target)

    /**
     * [tab] began a navigation of its own (the browser's load, the page's
     * link or script) or ended one without a commit (Stop, a download):
     * a 402 noted earlier won't commit, and a paid request in flight
     * won't be answered — whatever answers next is judged on its own.
     */
    fun onNavigationSuperseded(tab: BrowserState) = flow.superseded(tab.id)

    /**
     * [tab] began a navigation to [url] (null: Reload or Back/Forward)
     * (after [onNavigationSuperseded]): [byUser] — the address they
     * named, their Reload or Back/Forward — or the page on screen's, at
     * [pageUrl]. Only these may let a site's allowance pay without asking
     * (#218 R4-M3), and only while its redirects stay on that site
     * (#218 R5-M1).
     */
    fun onNavigationStarted(tab: BrowserState, byUser: Boolean, pageUrl: String?, url: String?) =
        flow.navigationStarted(tab.id, byUser, if (byUser) null else pageUrl?.let(::providerOriginKey), url)

    /**
     * [tab]'s payment epoch, read on the interceptor's thread as a
     * main-frame request goes out and handed to [onMainFrameRequested]
     * (#218 R4-M1).
     */
    fun requestEpoch(tab: BrowserState): Long = flow.epoch(tab.id)

    /**
     * `shouldInterceptRequest` for a main-frame request for [url] on [tab]
     * with [method], seen in payment [epoch] ([requestEpoch]), posted to
     * the main thread while [pageUrl] is still on screen: a form POST
     * (never seen by `shouldOverrideUrlLoading`) supersedes a paid
     * request as the page's other navigations do (#218 R3-M1) — unless it
     * went out before that paid request did (#218 R4-M1); the paid GET
     * itself is noted as seen by the interceptor (#218 R4-M2).
     */
    fun onMainFrameRequested(tab: BrowserState, url: String?, method: String?, epoch: Long, pageUrl: String?) =
        flow.mainFrameRequested(tab.id, url, method, epoch, pageUrl?.let(::providerOriginKey))

    /** `onPageFinished` for [url] on [tab]. */
    fun onLoadFinished(tab: BrowserState, url: String?) = flow.loadFinished(tab.id, url)

    /** `onReceivedError` for [tab]'s main frame: a paid request got no answer. */
    fun onMainFrameFailed(tab: BrowserState, url: String?) {
        flow.failed(tab.id, url)
    }

    /**
     * [tab] committed a new document on [url] (null: it's being torn
     * down). Called after [EthereumProviders.onDocumentStarted], so the
     * document number is the new page's.
     */
    fun onDocumentStarted(tab: BrowserState, view: WebView?, url: String?) {
        val committed = flow.committed(tab.id, url) ?: return
        if (url == null || view == null || tab.private) return
        val detection = committed.value
        // Another site's link, script or popup can't spend this site's allowance (#218 R4-M3).
        val allowanceMayPay = committed.allowanceMayPay(detection.origin)
        val doc = EthereumProviders.currentDocument(tab.id)
        val webView = WeakReference(view)
        scope.launch {
            try {
                handle(tab, doc, webView, detection, allowanceMayPay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "x402 payment failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun onTabClosed(tabId: Long) {
        flow.closed(tabId)
    }

    private fun settle(id: String, status: X402Store.Status, httpStatus: Int? = null) {
        val app = context ?: return
        scope.launch { X402Store.get(app).settle(id, status, httpStatus) }
    }

    private suspend fun handle(
        tab: BrowserState,
        doc: Int,
        webView: WeakReference<WebView>,
        d: Detection,
        allowanceMayPay: Boolean,
    ) {
        val app = context ?: return
        val store = X402Store.get(app)
        val vault = Vault.get(app)
        val walletAccounts = WalletAccounts.get(app)
        // Everything on the sheet — balances, what Pay allows, the allowance note — is worked
        // out for one account, and only that account pays. If the user switches accounts
        // while the sheet waits (or is up), it's taken down and worked out again for the
        // new one (#218 R4-F1) — but a switch never pays: after one, an allowance of the newly
        // active account doesn't pay silently, the sheet goes up for it (#218 R5-M2).
        var switched = false
        while (true) {
            val account = activeAccount(vault, walletAccounts)
            val chains = ChainStore.get(app).chainsOrUnreadable.first().orEmpty()
            val rpc = WalletRpc(ChainDataRouter.get(app))
            val (options, unreadable) = options(d.required, chains, rpc, account?.address)
            val unusable = d.required.unusable + unreadable
            val allowances = store.allowances.first()

            // An allowance pays silently — only with the wallet open, never unlocking it for a site,
            // only for a navigation the user or the site itself started (#218 R4-M3), only from
            // the account it was granted for (#218 R2-F1), and only when the balance isn't known
            // to be short: otherwise the sheet says why (#218 R2-M1).
            val payer = account?.address
            val covered = silentPayOption(allowanceMayPay, switched, payer, account?.isLedger == true, options) { o ->
                store.covering(allowances, d.origin, o.chainId, o.asset, payer!!, o.amount) != null
            }
            // Switched while the figures were read: read them again for the account now active.
            if (activeAccount(vault, walletAccounts)?.address != payer) {
                switched = true
                continue
            }
            if (covered != null && account != null && vault.unlockedNow()) {
                if (!stillOn(tab, doc, webView, d.url)) return
                Log.i(TAG, "paying from the site's allowance")
                if (pay(tab, doc, webView, d, covered, account, auto = true, grant = null) != Paid.NOT_COVERED) return
            }
            if (!stillOn(tab, doc, webView, d.url)) return
            val ask = EthAsk.Payment(
                origin = d.origin,
                payment = X402Ask(
                    url = d.url,
                    description = d.required.description,
                    account = account,
                    options = options,
                    unusable = unusable,
                    allowanceWaitingOnUnlock = covered != null && account != null,
                ),
            )
            val answer = askWhileActive(walletAccounts, payer) { EthereumProviders.askOnDocument(tab, doc, ask) }
            if (answer == null) {
                switched = true
                continue
            }
            val choice = (answer as? EthAnswer.Approved)?.payment ?: return
            val option = options.getOrNull(choice.option) ?: return
            if (account == null || activeAccount(vault, walletAccounts)?.address != payer) {
                switched = true
                continue
            }
            if (!stillOn(tab, doc, webView, d.url)) return
            pay(tab, doc, webView, d, option, account, auto = false, grant = choice.allowance)
            return
        }
    }

    /**
     * [ask], or null if the wallet's active account stops being [address]
     * before it's answered: the sheet is taken down (not as a rejection —
     * the tab's sheets aren't paused) so it can be put up again with
     * figures for the account now active (#218 R4-F1).
     */
    private suspend fun askWhileActive(
        accounts: WalletAccounts,
        address: String?,
        ask: suspend () -> EthAnswer,
    ): EthAnswer? = coroutineScope {
        var switched = false
        val asking = async { ask() }
        val watch = launch {
            accounts.accounts.first { it != null && it.active.address != address }
            switched = true
            asking.cancel()
        }
        try {
            asking.await()
        } catch (e: CancellationException) {
            if (!switched) throw e
            null
        } finally {
            watch.cancel()
        }
    }

    /**
     * The offer an allowance pays without a sheet: none unless the
     * navigation itself [allowanceMayPay] (#218 R4-M3) and the active
     * account hasn't been [switched] since — a 402 left waiting in a tab
     * is never paid silently by a later account switch, from the new
     * account's allowance and on the old navigation's terms; the sheet
     * goes up for it instead (#218 R5-M2). Never from a [ledger]
     * account (#142): the Ledger confirms every payment, as it does every
     * transaction an auto-approve rule would otherwise cover.
     */
    internal fun silentPayOption(
        allowanceMayPay: Boolean,
        switched: Boolean,
        payer: String?,
        ledger: Boolean,
        options: List<X402Option>,
        covered: (X402.Offer) -> Boolean,
    ): X402Option? = if (!allowanceMayPay || switched || payer == null || ledger) null else autoPayOption(options, covered)

    /**
     * The offer an allowance pays without asking: the first one [covered]
     * by an allowance whose balance isn't known to be short — a transfer
     * that can't settle would only be refused and still count against the
     * allowance, so a short balance goes to the sheet, which says why
     * (#218 R2-M1).
     */
    internal fun autoPayOption(options: List<X402Option>, covered: (X402.Offer) -> Boolean): X402Option? =
        options.firstOrNull { it.fundable && covered(it.offer) }

    private suspend fun activeAccount(vault: Vault, accounts: WalletAccounts): WalletAccount? {
        accounts.accounts.value?.let { return it.active }
        if (vault.state.value !is Vault.State.Locked && vault.state.value !is Vault.State.Unlocked) return null
        accounts.start()
        return withTimeoutOrNull(ACCOUNTS_WAIT_MS) { accounts.accounts.first { it != null } }?.active
    }

    /** The document that asked is still [tab]'s, on [url], in [webView]. */
    private fun stillOn(tab: BrowserState, doc: Int, webView: WeakReference<WebView>, url: String): Boolean {
        val view = webView.get() ?: return false
        return EthereumProviders.currentDocument(tab.id) == doc && view.url == url
    }

    private enum class Paid { SENT, NOT_SENT, NOT_COVERED }

    /**
     * Sign [option] from [account] and load the page again with the
     * payment. The history records it before the request goes out, so a
     * payment is never made that the history doesn't show. The record,
     * and the allowance change that goes with it — an [auto] payment
     * counted against the site's allowance ([Paid.NOT_COVERED] if it no
     * longer covers it: nothing is sent), or the allowance [grant]ed with
     * a manual one — are one write ([X402Store.commit]), made only once
     * the payment is signed and its page is still on; if the page is gone
     * by the time that write is done, it's undone ([X402Store.withdraw]):
     * nothing was sent, so nothing is spent, granted or listed (#218
     * R1-M2, R1-M4).
     */
    private suspend fun pay(
        tab: BrowserState,
        doc: Int,
        webView: WeakReference<WebView>,
        d: Detection,
        option: X402Option,
        account: WalletAccount,
        auto: Boolean,
        grant: X402Grant?,
    ): Paid {
        val app = context ?: return Paid.NOT_SENT
        val store = X402Store.get(app)
        val vault = Vault.get(app)
        val offer = option.offer
        val nonce = ByteArray(32).also(random::nextBytes)
        fun authorize() = X402.authorize(d.required.version, offer, account.address, System.currentTimeMillis() / 1000, nonce)
        val authorization: X402.Authorization
        val signature = if (account.isLedger) {
            // Signed on the Ledger (#142), which shows the transfer; its dialog carries Cancel.
            if (auto) return Paid.NOT_SENT
            // Made once the Ledger is connected and ready to show it, so its
            // time limit isn't spent on the connect and the unlock (#218 R1-F1).
            var made: X402.Authorization? = null
            val sig = try {
                Ledger.get(app).signTypedData(account) {
                    val a = authorize().also { made = it }
                    Eip712.parse(X402.typedData(offer, a)) to X402.digest(offer, a)
                }
            } catch (e: LedgerException) {
                Log.i(TAG, "not signed on the Ledger: ${e.kind}")
                // Refused or cancelled there is the user's answer; anything else they're told.
                if (e.kind != LedgerException.Kind.REJECTED && e.kind != LedgerException.Kind.CANCELLED) {
                    Toast.makeText(app, "Not paid: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return Paid.NOT_SENT
            }
            authorization = made ?: return Paid.NOT_SENT
            sig
        } else {
            authorization = authorize()
            val digest = X402.digest(offer, authorization)
            withContext(Dispatchers.Default) { MessageSigning.sign(vault, account, digest) }
        }
        if (X402.runway(authorization, System.currentTimeMillis() / 1000) < X402.MIN_RUNWAY_SECONDS) {
            Log.w(TAG, "the authorization ran out while it was signed; not sending it")
            // The user approved it: say why nothing happened, and what to do.
            if (stillOn(tab, doc, webView, d.url)) Toast.makeText(app, timedOutMessage(account.isLedger, offer), Toast.LENGTH_LONG).show()
            return Paid.NOT_SENT
        }
        // The page left while it was signed: nothing to pay for.
        if (!stillOn(tab, doc, webView, d.url)) return Paid.NOT_SENT
        val (header, value) = X402.paymentHeader(d.required, offer, authorization, signature)
        val payment = X402Store.Payment(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            origin = d.origin,
            url = d.url,
            chainId = offer.chainId,
            asset = offer.asset,
            symbol = option.symbol,
            decimals = option.decimals,
            amount = offer.amount,
            payTo = offer.payTo,
            from = authorization.from,
            auto = auto,
            nonce = authorization.nonce,
            status = X402Store.Status.PENDING,
        )
        val newAllowance = if (auto || account.isLedger) null else grant?.let { X402Store.NewAllowance(option.symbol, option.decimals, it.cap, it.windowMs) }
        val committed = when (val c = store.commit(payment, newAllowance)) {
            is X402Store.Commit.Done -> c
            X402Store.Commit.NotCovered -> return Paid.NOT_COVERED
            X402Store.Commit.Failed -> {
                Log.w(TAG, "couldn't record the payment; not sending it")
                return Paid.NOT_SENT
            }
        }
        val view = webView.get()
        if (view == null || !stillOn(tab, doc, webView, d.url)) {
            // Never sent: the page it was for went while it was written.
            store.withdraw(payment, committed.allowanceCreated)
            return Paid.NOT_SENT
        }
        // No suspension from the check above to here: the request goes out on the page it was for.
        vault.noteActivity()
        // The load first: it's a browser load, which ends any earlier
        // navigation's bookkeeping ([onNavigationSuperseded]) — not this one's.
        // Requests the interceptor saw before this one are the old page's (#218 R4-M1).
        flow.sending(tab.id)
        view.loadUrl(d.url, mapOf(header to value))
        flow.paid(tab.id, d.url, payment.id)
        return Paid.SENT
    }

    /**
     * What the user is told when a payment they approved ran out before it
     * could be sent (#218 R1-F1): nothing was paid, and reloading asks again.
     */
    internal fun timedOutMessage(ledger: Boolean, offer: X402.Offer): String =
        "Not paid: the site allows ${X402.confirmSeconds(offer)} s to " +
            (if (ledger) "confirm a payment on the Ledger" else "sign a payment") +
            ", and that ran out. Reload the page to try again."

    /**
     * The payable offers of [required] with their chain, token and
     * balance, and why each one that can't be shown honestly is left out
     * (a chain the wallet doesn't have, a token it can't read).
     */
    private suspend fun options(
        required: X402.Required,
        chains: List<Chain>,
        rpc: WalletRpc,
        holder: String?,
    ): Pair<List<X402Option>, List<String>> = coroutineScope {
        val results = required.offers.map { offer ->
            async {
                val chain = chains.firstOrNull { it.id == offer.chainId }
                    ?: return@async null to "Offer ${offer.index + 1}: pays on chain ${offer.chainId}, which isn't in Settings → Chains"
                val known = knownToken(offer.chainId, offer.asset)
                val (symbol, decimals) = known ?: when (val read = readToken(rpc, chain, offer.asset)) {
                    is TokenRead.Ok -> read.symbol to read.decimals
                    TokenRead.Unverified -> return@async null to
                        "Offer ${offer.index + 1}: its token on ${chain.name} isn't in the wallet's token list, " +
                        "and its decimals couldn't be verified, so the amount can't be shown"
                    TokenRead.Unreadable -> return@async null to
                        "Offer ${offer.index + 1}: its token on ${chain.name} couldn't be read"
                }
                val balance = holder?.let { readBalance(rpc, offer.chainId, offer.asset, it) }
                X402Option(offer, chain, symbol, decimals, listed = known != null, balance = balance) to null
            }
        }.awaitAll()
        results.mapNotNull { it.first } to results.mapNotNull { it.second }
    }

    /** A token the wallet lists: its symbol and decimals. */
    internal fun knownToken(chainId: Long, asset: String): Pair<String, Int>? =
        (TokenRegistry.builtins + EXTRA_TOKENS).firstOrNull {
            it.chainId == chainId && it.address.equals(asset, ignoreCase = true)
        }?.let { it.symbol to it.decimals }

    /** USDC where x402 is used most, which the wallet's balance list doesn't carry. */
    private val EXTRA_TOKENS = listOf(
        baby.freedom.mobile.wallet.Token(8453L, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", "USD Coin", 6),
        baby.freedom.mobile.wallet.Token(100L, "0x2a22f9c3b484c3629090FeED35F17Ff8F88f76F0", "USDC.e", "Bridged USDC", 6),
    )

    private sealed interface TokenRead {
        class Ok(val symbol: String, val decimals: Int) : TokenRead

        /** Read, but not on answers the wallet can stand behind ([tokenReadTrusted]). */
        data object Unverified : TokenRead
        data object Unreadable : TokenRead
    }

    /**
     * Whether an unlisted token's `decimals()`/`symbol()`, read on [chain]
     * with [trust], may be shown on the sheet as the payment's amount
     * (#218 R1-F1). The decimals turn the base units the site asks for
     * into the amount the user approves, so one RPC's word isn't enough:
     * a site can add a chain with its own RPC (`wallet_addEthereumChain`)
     * and have it say 18 decimals for a 6-decimal token, showing
     * 1,000,000 USDC as 0.000001. Only a verified answer (a proof, or a
     * quorum agreeing) on a built-in chain counts: a custom chain's RPCs
     * came from whoever added it, a site included, so even a quorum of
     * them can be one party.
     */
    internal fun tokenReadTrusted(chain: Chain, trust: List<ChainTrust>): Boolean =
        chain.builtIn && trust.isNotEmpty() && trust.all { it.level == ChainTrust.Level.VERIFIED }

    private suspend fun readToken(rpc: WalletRpc, chain: Chain, asset: String): TokenRead = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            val d = rpc.call(chain.id, JSONObject().put("to", asset).put("data", DECIMALS))
            val decimals = Erc20.decodeUint256(d.value)
                ?.takeIf { it <= BigInteger.valueOf(36) }?.toInt() ?: return@withTimeoutOrNull TokenRead.Unreadable
            val sym = rpc.call(chain.id, JSONObject().put("to", asset).put("data", SYMBOL))
            val symbol = abiSymbol(sym.value) ?: return@withTimeoutOrNull TokenRead.Unreadable
            if (!tokenReadTrusted(chain, listOf(d.trust, sym.trust))) {
                Log.i(TAG, "unlisted token's decimals not verified (${d.trust.level.name.lowercase()}, built-in ${chain.builtIn})")
                return@withTimeoutOrNull TokenRead.Unverified
            }
            TokenRead.Ok(symbol, decimals)
        } ?: TokenRead.Unreadable
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TokenRead.Unreadable
    }

    private suspend fun readBalance(rpc: WalletRpc, chainId: Long, asset: String, holder: String): BigInteger? = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            Erc20.decodeUint256(rpc.call(chainId, JSONObject().put("to", asset).put("data", Erc20.balanceOfData(holder))).value)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * A `symbol()` answer — an ABI `string`, or a `bytes32` from older
     * tokens — as a short run of letters, digits and `.`/`-`/`_`, or null:
     * the sheet shows it, so it can't be arbitrary text.
     */
    internal fun abiSymbol(hex: String): String? {
        val data = hex.removePrefix("0x")
        if (data.length % 2 != 0) return null
        val bytes = try {
            ByteArray(data.length / 2) { i -> data.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            return null
        }
        val raw: ByteArray = if (bytes.size == 32) {
            bytes.takeWhile { it != 0.toByte() }.toByteArray()
        } else {
            if (bytes.size < 64) return null
            val offset = BigInteger(1, bytes.copyOfRange(0, 32))
            if (offset != BigInteger.valueOf(32)) return null
            val length = BigInteger(1, bytes.copyOfRange(32, 64))
            if (length > BigInteger.valueOf(32) || 64 + length.toInt() > bytes.size) return null
            bytes.copyOfRange(64, 64 + length.toInt())
        }
        val s = String(raw, Charsets.US_ASCII)
        return s.takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in ".-_" } }
    }

    private const val DECIMALS = "0x313ce567"
    private const val SYMBOL = "0x95d89b41"
    private const val READ_TIMEOUT_MS = 8_000L
    private const val ACCOUNTS_WAIT_MS = 3_000L
    private const val TAG = "X402"
}
