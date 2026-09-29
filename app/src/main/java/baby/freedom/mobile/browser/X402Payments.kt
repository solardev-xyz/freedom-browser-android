package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.X402Store
import baby.freedom.mobile.wallet.Erc20
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.X402
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
    /** The token is one the wallet lists; false: its symbol and decimals were read from the contract. */
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
 * request the site answers with another 402 is not paid again (desktop's
 * loop guard): the history says it was refused, and the user can reload
 * to be asked.
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

    /** A paid request in flight on a tab: [recordId] in the history, for [url]. */
    private class Retry(val url: String, val recordId: String)

    private val detections = HashMap<Long, Detection>()
    private val retries = HashMap<Long, Retry>()
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
     * request settles its payment as refused.
     */
    fun onHttpError(tab: BrowserState, request: WebResourceRequest, response: WebResourceResponse?) {
        if (tab.private || !request.isForMainFrame) return
        val url = request.url?.toString() ?: return
        val status = response?.statusCode ?: return
        val retry = retries[tab.id]
        if (retry != null && retry.url == url) {
            retries.remove(tab.id)
            Log.i(TAG, "paid request answered HTTP $status")
            settle(retry.recordId, X402Store.Status.REFUSED, status)
            // Not paid again: the loop guard.
            detections.remove(tab.id)
            return
        }
        if (status != 402) return
        if (!request.method.equals("GET", ignoreCase = true)) return
        if (isDwebPageUrl(url)) return
        val origin = providerOriginKey(url) ?: return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val required = X402.requiredFrom(response.responseHeaders) ?: return
        Log.i(TAG, "402 with x402 v${required.version} terms: ${required.offers.size} payable offer(s)")
        detections[tab.id] = Detection(url, origin, required)
    }

    /** `onReceivedError` for [tab]'s main frame: a paid request got no answer. */
    fun onMainFrameFailed(tab: BrowserState, url: String?) {
        val retry = retries[tab.id] ?: return
        if (url != null && url != retry.url) return
        retries.remove(tab.id)
        settle(retry.recordId, X402Store.Status.UNCONFIRMED)
    }

    /**
     * [tab] committed a new document on [url] (null: it's being torn
     * down). Called after [EthereumProviders.onDocumentStarted], so the
     * document number is the new page's.
     */
    fun onDocumentStarted(tab: BrowserState, view: WebView?, url: String?) {
        retries.remove(tab.id)?.let { retry ->
            val status = if (url == retry.url) X402Store.Status.PAID else X402Store.Status.UNCONFIRMED
            settle(retry.recordId, status)
        }
        val detection = detections.remove(tab.id) ?: return
        if (url == null || url != detection.url || view == null || tab.private) return
        val doc = EthereumProviders.currentDocument(tab.id)
        val webView = WeakReference(view)
        scope.launch {
            try {
                handle(tab, doc, webView, detection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "x402 payment failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun onTabClosed(tabId: Long) {
        detections.remove(tabId)
        retries.remove(tabId)?.let { settle(it.recordId, X402Store.Status.UNCONFIRMED) }
    }

    private fun settle(id: String, status: X402Store.Status, httpStatus: Int? = null) {
        val app = context ?: return
        scope.launch { X402Store.get(app).settle(id, status, httpStatus) }
    }

    private suspend fun handle(tab: BrowserState, doc: Int, webView: WeakReference<WebView>, d: Detection) {
        val app = context ?: return
        val store = X402Store.get(app)
        val vault = Vault.get(app)
        val walletAccounts = WalletAccounts.get(app)
        val account = activeAccount(vault, walletAccounts)
        val chains = ChainStore.get(app).chainsOrUnreadable.first().orEmpty()
        val rpc = WalletRpc(ChainDataRouter.get(app))
        val (options, unreadable) = options(d.required, chains, rpc, account?.address)
        val unusable = d.required.unusable + unreadable
        val allowances = store.allowances.first()

        // An allowance pays silently — only with the wallet open, never unlocking it for a site.
        val covered = options.firstOrNull { o ->
            store.covering(allowances, d.origin, o.offer.chainId, o.offer.asset, o.offer.amount) != null
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
                options = options,
                unusable = unusable,
                allowanceWaitingOnUnlock = covered != null && account != null,
            ),
        )
        val answer = EthereumProviders.askOnDocument(tab, doc, ask)
        val choice = (answer as? EthAnswer.Approved)?.payment ?: return
        val option = options.getOrNull(choice.option) ?: return
        val payer = activeAccount(vault, walletAccounts) ?: return
        if (!stillOn(tab, doc, webView, d.url)) return
        pay(tab, doc, webView, d, option, payer, auto = false, grant = choice.allowance)
    }

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
     * payment is never made that the history doesn't show. An [auto]
     * payment is counted against the site's allowance once it's signed,
     * in one write with the check that it's covered ([Paid.NOT_COVERED]
     * if it no longer is: nothing is sent).
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
        val now = System.currentTimeMillis() / 1000
        val authorization = X402.authorize(d.required.version, offer, account.address, now, nonce)
        val signature = withContext(Dispatchers.Default) {
            MessageSigning.sign(vault, account, X402.digest(offer, authorization))
        }
        if (X402.runway(authorization, System.currentTimeMillis() / 1000) < X402.MIN_RUNWAY_SECONDS) {
            Log.w(TAG, "the authorization ran out while it was signed; not sending it")
            return Paid.NOT_SENT
        }
        if (auto && !store.consume(d.origin, offer.chainId, offer.asset, offer.amount)) return Paid.NOT_COVERED
        val (header, value) = X402.paymentHeader(d.required, offer, authorization, signature)
        if (grant != null) {
            store.grant(
                origin = d.origin,
                chainId = offer.chainId,
                asset = offer.asset,
                symbol = option.symbol,
                decimals = option.decimals,
                cap = grant.cap,
                windowMs = grant.windowMs,
                spentNow = offer.amount,
            )
        }
        val id = UUID.randomUUID().toString()
        val recorded = store.record(
            X402Store.Payment(
                id = id,
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
            ),
        )
        if (!recorded) {
            Log.w(TAG, "couldn't record the payment; not sending it")
            return Paid.NOT_SENT
        }
        val view = webView.get()
        if (view == null || !stillOn(tab, doc, webView, d.url)) {
            // Never sent: the page it was for is gone.
            store.settle(id, X402Store.Status.UNCONFIRMED)
            return Paid.NOT_SENT
        }
        vault.noteActivity()
        retries[tab.id] = Retry(d.url, id)
        view.loadUrl(d.url, mapOf(header to value))
        return Paid.SENT
    }

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
                val (symbol, decimals) = known ?: (readToken(rpc, offer.chainId, offer.asset)
                    ?: return@async null to "Offer ${offer.index + 1}: its token on ${chain.name} couldn't be read")
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

    private suspend fun readToken(rpc: WalletRpc, chainId: Long, asset: String): Pair<String, Int>? = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            val decimals = Erc20.decodeUint256(rpc.call(chainId, JSONObject().put("to", asset).put("data", DECIMALS)).value)
                ?.takeIf { it <= BigInteger.valueOf(36) }?.toInt() ?: return@withTimeoutOrNull null
            val symbol = abiSymbol(rpc.call(chainId, JSONObject().put("to", asset).put("data", SYMBOL)).value)
                ?: return@withTimeoutOrNull null
            symbol to decimals
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
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
