package baby.freedom.mobile.browser

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.ChainlistService
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RoutingContext
import baby.freedom.mobile.data.AutoApproveStore
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import java.io.IOException
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * An approval sheet waiting on its tab (#110): [ask] is what a page on
 * [EthAsk.origin] wants, [respond] the user's answer. Shown by
 * [BrowserScreen] ([EthereumApprovalSheet]) only while its tab is the
 * active one and its page is on screen. [setUpWallet] opens the wallet
 * page to create, import or unlock a wallet (for a connect with no
 * wallet yet), from a scope that outlives the sheet — the sheet leaves
 * composition while the wallet page covers it.
 */
class EthereumPromptRequest internal constructor(val ask: EthAsk, val setUpWallet: () -> Unit) {
    internal val answer = CompletableDeferred<EthAnswer>()

    fun respond(answer: EthAnswer) {
        this.answer.complete(answer)
    }
}

/**
 * The page side of the `window.ethereum` provider (#110) and its
 * channel: a document-start script ([ethereumProviderJs]) defines
 * `window.ethereum` — and announces it over EIP-6963 — in every http(s)
 * top-level document of a normal tab, and sends each request down a
 * `WebMessageListener` channel to [EthereumProvider], which answers back
 * on it. Private tabs get no provider and no channel: a connection would
 * have nowhere to live that the private session could forget, and the
 * wallet is the user's one identity.
 *
 * Who is asking is the platform's `sourceOrigin` for the document that
 * sent the message — never anything the page claims — and only a tab's
 * top-level document may ask: a frame can't act as the page it's
 * embedded in. The origin must be a secure one: https (the dweb and
 * onchain-app origins are https too) or http on loopback.
 *
 * Approval sheets ([EthereumPromptRequest]) are one at a time per tab,
 * taken down (as a rejection) when the tab starts a new document or
 * closes. Once the user rejects one, that tab's pages get no more
 * sheets — every ask is refused at once ([EthAnswer.Paused], a 4001 that
 * says to reload) — until the user navigates the
 * tab themselves ([allowPrompts]), so a page can't hold the browser
 * behind a loop of them. Like `window.radicle` ([RadicleProviders]).
 */
object EthereumProviders {
    @Volatile
    private var provider: EthereumProvider? = null

    private val scope = MainScope()

    /**
     * What each tab may have in flight (#459): plenty for a dApp's
     * parallel reads and a few big calls side by side, not a heap's worth.
     */
    private val budget = BridgeRequestBudget(
        maxRequests = 256,
        smallChars = 2L * 1024 * 1024,
        largeAbove = 128 * 1024,
        largeChars = 8L * 1024 * 1024,
    )

    /** Live bridges, one per WebView; main thread only. */
    private val bridges = WeakHashMap<WebView, Bridge>()

    /** Main thread only, like everything below. */
    private val documents = HashMap<Long, Int>()
    private val committedOrigins = HashMap<Long, String?>()
    private val promptLocks = HashMap<Long, Mutex>()
    private val pending = HashMap<Long, MutableSet<EthereumPromptRequest>>()
    private val blockedTabs = HashSet<Long>()

    /** Tabs whose can't-send sheet the user closed: further ones are skipped (the page still gets the error) until they navigate it. */
    private val cantSendClosed = HashSet<Long>()

    private class Bridge(val tab: BrowserState) {
        /** The origin and channel of the top-level document that last spoke. */
        var origin: String? = null
        var reply: JavaScriptReplyProxy? = null
        var channel: String? = null
        var script: ScriptHandler? = null
    }

    /** The EIP-6963 icon, a `data:` URI (empty if the asset can't be read). */
    private var iconDataUri = ""

    private lateinit var setUpWallet: (String) -> Unit

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /** Wire the provider to the wallet, the grant store and the chain-data router, once per process. */
    fun init(context: Context) {
        if (provider != null) return
        val app = context.applicationContext
        iconDataUri = runCatching {
            val bytes = app.assets.open(ICON_ASSET).use { it.readBytes() }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "no EIP-6963 icon", it)
            ""
        }
        val grantStore = DappGrantStore.get(app)
        val ruleStore = AutoApproveStore.get(app)
        val chainStore = ChainStore.get(app)
        val router = ChainDataRouter.get(app)
        val vault = Vault.get(app)
        val accounts = WalletAccounts.get(app)
        val sender = WalletSender.get(app)
        val ledger = Ledger.get(app)
        setUpWallet = { reason -> scope.launch { vault.requireUnlocked(reason) } }
        val p = EthereumProvider(
            grants = object : EthereumProvider.Grants {
                override suspend fun grantFor(origin: String) =
                    all()[origin]
                override suspend fun grant(origin: String, account: String, chainId: Long) = grantStore.grant(origin, account, chainId)
                override suspend fun setChain(origin: String, chainId: Long) = grantStore.setChain(origin, chainId)
                override suspend fun revoke(origin: String) = grantStore.revoke(origin)
                override suspend fun all() = (grantStore.allOrUnreadable.first() ?: throw EthereumProvider.GrantsUnreadable())
                    .associate { it.origin to EthereumProvider.Grant(it.account, it.chainId) }
                override suspend fun clear() = grantStore.clear()
                override suspend fun addChain(chain: Chain) = when (chainStore.add(chain)) {
                    ChainStore.AddResult.ADDED, ChainStore.AddResult.DUPLICATE, ChainStore.AddResult.BUILT_IN -> true
                    ChainStore.AddResult.FAILED -> false
                }
            },
            wallet = object : EthereumProvider.Wallet {
                override suspend fun accounts(): List<WalletAccount>? {
                    accounts.accounts.value?.let { return it.accounts }
                    // Just launched: the list is read back from disk once the vault's state is known.
                    if (vault.state.value !is Vault.State.Locked && vault.state.value !is Vault.State.Unlocked) return null
                    accounts.start()
                    return withTimeoutOrNull(ACCOUNTS_WAIT_MS) { accounts.accounts.first { it != null } }?.accounts
                }
                override fun unlocked() = vault.unlockedNow()
                override fun noteActivity() = vault.noteActivity()
                override suspend fun signMessage(account: WalletAccount, message: ByteArray) =
                    if (account.isLedger) ledger.signPersonal(account, message)
                    else MessageSigning.sign(vault, account, MessageSigning.personalDigest(message))
                override suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray) =
                    if (account.isLedger) ledger.signTypedData(account, data, digest)
                    else MessageSigning.sign(vault, account, digest)
            },
            chains = { chainStore.chainsOrUnreadable.first() ?: throw IOException("chain list unreadable") },
            reads = { chainId, method, params, origin ->
                router.request(chainId, method, params, RoutingContext.forPage(origin)).result
            },
            sends = object : EthereumProvider.Sends {
                override suspend fun prepare(request: SendRequest) = sender.prepare(request)
                override suspend fun submit(quote: SendQuote) = submitAndWait(app, sender, vault, quote)
                override fun busy() = sender.busy()
            },
            autoApprove = object : EthereumProvider.AutoApprove {
                override suspend fun matches(rule: AutoApproveRule) = ruleStore.matches(rule)
                override suspend fun grant(rule: AutoApproveRule) = ruleStore.grant(rule)
                override suspend fun revokeOrigin(origin: String) = ruleStore.revokeOrigin(origin)
                override suspend fun clear() = ruleStore.clear()
            },
            // The catalog Settings → Chains → Add chain uses, bounded: an Add network sheet doesn't
            // wait on a multi-MB download, it just offers the site's RPCs alone (#423).
            catalog = { id ->
                withTimeoutOrNull(CATALOG_WAIT_MS) { ChainlistService.get(app).entries().firstOrNull { it.id == id } }?.toChain()
            },
        )
        p.events = EthereumProvider.Events { origin, event, data -> scope.launch { emit(origin, event, data) } }
        provider = p
        // A chain removed in Settings → Chains moves the sites on it off it, and tells their pages (#215 R3-F2).
        // A list that couldn't be read (null) moves nobody: that's a read error, not a removal (#215 R4-F1).
        scope.launch { chainStore.chainsOrUnreadable.collect { list -> list?.let { p.chainsChanged(it) } } }
    }

    /**
     * One "<site> switched to <chain>" notice with Undo (#440), on [tabId]:
     * that tab's next no-sheet switch waits until it's [close]d (#446
     * R1-F1) — a sheet the tab's page asks for ends that hold instead and
     * comes up at once, leaving the notice up (R4-F1, R5-M2) — and an Undo
     * pauses the tab's asks as a refused sheet does.
     */
    class SwitchNotice internal constructor(val tabId: Long, val switch: EthereumProvider.ChainSwitched) {
        internal val closed = CompletableDeferred<Boolean>()
        internal val onScreen = CompletableDeferred<Unit>()
        internal val received = CompletableDeferred<Unit>()

        /** Holding the tab's next no-sheet switch: ended by [close], or by a sheet asking. */
        internal val hold = CompletableDeferred<Unit>()

        /**
         * The browser has the notice. From here it owns taking it down
         * ([close]), so it may wait as long as it takes for its tab to be
         * the active one again: the switch is made, and is announced
         * whenever the user comes back (#446 R5-M3).
         */
        fun received() {
            received.complete(Unit)
        }

        /**
         * The notice is actually on screen now. Its hold on the tab is timed
         * from here, not from when it was handed over: one queued behind
         * another snackbar mustn't run out before it's ever seen (#446 R4-M1).
         */
        fun shown() {
            onScreen.complete(Unit)
        }

        /**
         * Something covered the notice after it was on screen — a sheet or
         * prompt of any kind, or a full-screen panel — and the browser took
         * it down until its page is clear again. Like a sheet asking, that ends its hold: the next no-sheet
         * switch can't come up while the page is covered anyway, and the
         * notice is no longer bounded by [NOTICE_MAX_MS], so it isn't timed
         * out unseen behind whatever covers it (#446 R1-M1, round 1007).
         *
         * That includes covers the page raises itself — an `alert()`, a
         * download offer, a permission request — so a page can end the
         * hold early that way. Each of those still needs the user's tap
         * to clear before its next switch's notice can come up, so a page
         * looping switch → `alert()` still gets one switch per user action,
         * not a silent run of them (#446 R2-M2, round 1007).
         */
        fun covered() {
            hold.complete(Unit)
        }

        /** The notice is down: [undo] if the user tapped Undo. Only the first call counts. */
        fun close(undo: Boolean) {
            closed.complete(undo)
            hold.complete(Unit)
        }

        /**
         * Returns once the notice is closed, from here or by the bridge —
         * its tab closed, or [NOTICE_MAX_MS] on screen ran out — so the
         * browser takes its snackbar down with it: an Undo offered past that
         * point would outlast the turn it holds (#446 R2-M1, R2-M2). A new
         * document in the tab doesn't close it: the switch is the site's
         * and stays, so it's announced either way (#446 R4-M2, R5-M1).
         */
        suspend fun awaitClosed() {
            closed.await()
        }
    }

    private val switchedFlow = MutableSharedFlow<SwitchNotice>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * A connected site switched itself to a built-in chain with no sheet
     * (#440), while its tab was on screen: the browser shows "<site>
     * switched to <chain>" with Undo ([undoSwitch]), says it has it
     * ([SwitchNotice.received]) and once it's on screen
     * ([SwitchNotice.shown]), and [SwitchNotice.close]s it when it's down —
     * a newer notice replacing it, the user leaving the tab once it was up,
     * or the notice timing out all count as no Undo.
     */
    val chainSwitches: SharedFlow<SwitchNotice> = switchedFlow.asSharedFlow()

    /**
     * The Undo of a [chainSwitches] notice: closes it as undone (pausing
     * its tab's asks) and puts the site back ([EthereumProvider.undoSwitch]).
     */
    suspend fun undoSwitch(notice: SwitchNotice): EthereumProvider.UndoResult {
        notice.close(undo = true)
        return provider?.undoSwitch(notice.switch) ?: EthereumProvider.UndoResult.FAILED
    }

    /** Notices up, by tab: closed (no Undo) when their tab closes; their hold ends when a sheet asks. */
    private val notices = HashMap<Long, SwitchNotice>()

    /** How many sheet asks (anything but a no-sheet switch) are waiting for each tab's turn (#446 R4-F1). */
    private val sheetsWaiting = HashMap<Long, Int>()

    /**
     * Signs and broadcasts [quote] through the wallet's own send flow and
     * waits until it's out: its hash once a node took it (or it's on
     * chain), or why not.
     */
    private suspend fun submitAndWait(app: Context, sender: WalletSender, vault: Vault, quote: SendQuote): EthereumProvider.Submitted {
        // Not wallet activity in itself (#474): a send an auto-approve rule covers goes out with
        // no sheet, so the site alone could keep the wallet from locking; a confirmed sheet has
        // already counted (EthereumProvider's ask → noteActivity).
        when (sender.submit(quote, WalletSender.signerFor(app, vault, quote.request.from, activity = false) { !sender.isStale(quote) })) {
            WalletSender.Submit.BUSY -> return EthereumProvider.Submitted.Busy
            WalletSender.Submit.STALE -> return EthereumProvider.Submitted.Stale
            WalletSender.Submit.STARTED -> Unit
        }
        val status = sender.status.first { s ->
            s == null || s.quote !== quote || (s.stage != SendStatus.Stage.Signing && s.stage != SendStatus.Stage.Broadcasting)
        }
        if (status == null || status.quote !== quote) {
            return EthereumProvider.Submitted.Failed("The transaction was discarded before it went out.", null)
        }
        return when (val stage = status.stage) {
            // Aged while a Ledger was unlocked or reviewed on: nothing sent; priced again, the site's sheet asks again.
            is SendStatus.Stage.Failed -> if (stage.stale) {
                EthereumProvider.Submitted.Stale
            } else if (stage.rejected) {
                EthereumProvider.Submitted.Rejected
            } else {
                // English, for the page (#280).
                EthereumProvider.Submitted.Failed(stage.english ?: WalletSender.NOT_SENT_ENGLISH, status.hash.takeIf { stage.mayHaveGone })
            }
            else -> status.hash?.let { EthereumProvider.Submitted.Sent(it) }
                ?: EthereumProvider.Submitted.Failed("The transaction didn't go out.", null)
        }
    }

    /**
     * The user disconnected [origin]: the one path for every disconnect
     * control — Settings' ×, the Connected site page and the wallet page.
     * Its open pages see no accounts any more and stay on their chain
     * ([EthereumProvider.disconnect]). Once the provider exists this runs to
     * completion even if the caller is cancelled (the sheet closing, the
     * screen leaving composition), so a revoked grant always comes with its
     * `accountsChanged`; before then there are no pages to tell and only
     * the stores are written — the site's auto-approve rules (#112) first,
     * as [EthereumProvider.disconnect]. False if it couldn't be written.
     */
    suspend fun disconnect(context: Context, origin: String): Boolean =
        provider?.disconnect(origin)
            ?: (AutoApproveStore.get(context).revokeOrigin(origin) && DappGrantStore.get(context).revoke(origin))

    /**
     * Undo the user's Disconnect of [origin] (#423): connected again with
     * [account] on [chainId], with its auto-approve [rules] back
     * ([EthereumProvider.reconnect]: only if nothing changed meanwhile).
     * Before the provider exists there are no pages to tell, and only the
     * stores are written, under the same conditions. False if it wasn't.
     */
    suspend fun reconnect(context: Context, origin: String, account: String, chainId: Long, rules: List<AutoApproveRule>): Boolean =
        provider?.reconnect(origin, account, chainId, rules)
            // Not cancellable, as the provider's (R6-M1): Undo runs on a job a second notice or
            // leaving the page cancels, and a cancel between the grant and its rules would
            // leave the site connected without them.
            ?: withContext(NonCancellable) { reconnectStores(context, origin, account, chainId, rules) }

    private suspend fun reconnectStores(context: Context, origin: String, account: String, chainId: Long, rules: List<AutoApproveRule>): Boolean {
        val grants = DappGrantStore.get(context)
        val now = grants.allOrUnreadable.first() ?: return false
        if (now.any { it.origin == origin }) return false
        val known = WalletAccounts.get(context).accounts.value?.accounts ?: return false
        if (known.none { it.address.equals(account, ignoreCase = true) }) return false
        // Not on a chain removed in Settings meanwhile, as the provider's own check (R1-M3).
        val chains = ChainStore.get(context).chainsOrUnreadable.first() ?: return false
        if (chains.none { it.id == chainId }) return false
        if (!grants.grant(origin, account, chainId)) return false
        val ruleStore = AutoApproveStore.get(context)
        // All the rules back, or none and no connection either (R5-M1), as the provider's.
        if (rules.filter { it.origin == origin }.all { ruleStore.grant(it) }) return true
        ruleStore.revokeOrigin(origin)
        grants.revoke(origin)
        return false
    }

    /**
     * Undo removing [rule] (#423): back on only while its site is still
     * connected with [account] ([EthereumProvider.restoreRule]). False if
     * it isn't.
     */
    suspend fun restoreRule(context: Context, account: String, rule: AutoApproveRule): Boolean =
        provider?.restoreRule(account, rule) ?: run {
            val grant = DappGrantStore.get(context).allOrUnreadable.first()?.firstOrNull { it.origin == rule.origin } ?: return false
            grant.account.equals(account, ignoreCase = true) && AutoApproveStore.get(context).grant(rule)
        }

    /**
     * The Ledger account [address] is being removed: the sites connected
     * with it are disconnected, their auto-approve rules first
     * ([EthereumProvider.accountRemoved]). False
     * if that couldn't be read or written.
     */
    suspend fun accountRemoved(context: Context, address: String): Boolean =
        provider?.accountRemoved(address) ?: DappGrantStore.get(context).let { store ->
            val grants = store.allOrUnreadable.first() ?: return false
            val rules = AutoApproveStore.get(context)
            grants.filter { it.account.equals(address, ignoreCase = true) }
                .map { rules.revokeOrigin(it.origin) && store.revoke(it.origin) }.all { it }
        }

    /**
     * The wallet was removed: every connected site is disconnected
     * ([EthereumProvider.disconnectAll]) and every auto-approve rule
     * dropped, so neither can come back to life if the same phrase is
     * imported again. Each store is cleared even if the other can't be.
     */
    suspend fun walletRemoved(context: Context): Boolean = provider?.disconnectAll() ?: run {
        val rulesCleared = AutoApproveStore.get(context).clear()
        DappGrantStore.get(context).clear() && rulesCleared
    }

    /**
     * Track [webView] (a tab's, before its first load) and register the
     * channel and the script on it. Nothing for a private tab.
     */
    fun install(webView: WebView, tab: BrowserState) {
        if (tab.private || !isSupported()) return
        val bridge = Bridge(tab)
        bridges[webView] = bridge
        try {
            val channel = newBottomUiChannelName()
            WebViewCompat.addWebMessageListener(webView, channel, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                onMessage(bridge, message, sourceOrigin.toString(), isMainFrame, reply)
            }
            bridge.channel = channel
            bridge.script = WebViewCompat.addDocumentStartJavaScript(webView, ethereumProviderJs(channel, iconDataUri), setOf("*"))
        } catch (e: RuntimeException) {
            // A destroyed WebView: nothing to attach to.
            Log.w(TAG, "couldn't attach the provider to tab ${tab.id}", e)
            runCatching { bridge.script?.remove() }
            bridge.channel?.let { channel -> runCatching { WebViewCompat.removeWebMessageListener(webView, channel) } }
            bridges.remove(webView)
        }
    }

    private fun onMessage(
        bridge: Bridge,
        message: WebMessageCompat,
        sourceOrigin: String,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        val tab = bridge.tab
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val data = message.data ?: return
        // Too long ever to be accepted: refused as such, not as "try again" (which a client retries).
        if (data.length > MAX_ETH_REQUEST_CHARS) {
            unparsedRequestId(data)?.let { answer(reply, it, EthereumProvider.Reply.Err(EthereumProvider.INVALID_PARAMS, "Request too large")) }
            return
        }
        // Counted before it's parsed, and answered at once over the tab's share (#459).
        val ticket = budget.reserve(tab.id, data.length) ?: run {
            unparsedRequestId(data)?.let {
                answer(reply, it, EthereumProvider.Reply.Err(BridgeRequestBudget.LIMIT_EXCEEDED, BridgeRequestBudget.LIMIT_MESSAGE))
            }
            return
        }
        val request = parseEthereumRequest(data) ?: run {
            ticket.release()
            // Readable enough to answer: the page learns now, not when its own timer runs out.
            unparsedRequestId(data)?.let {
                val why = tooComplexMessage(data, MAX_ETH_REQUEST_VALUES, MAX_ETH_REQUEST_CONTAINERS) ?: "Invalid request"
                answer(reply, it, EthereumProvider.Reply.Err(EthereumProvider.INVALID_PARAMS, why))
            }
            return
        }
        val origin = providerOriginKey(sourceOrigin)
        if (!isMainFrame || origin == null) {
            ticket.release()
            val why = if (!isMainFrame) "window.ethereum is only available to the top-level page" else "Origin not permitted"
            answer(reply, request.id, EthereumProvider.Reply.Err(EthereumProvider.UNAUTHORIZED, why))
            return
        }
        // Which of the tab's documents this is ([radicleDocumentFor]): a
        // message from the outgoing document can arrive after the tab
        // started the next, and its sheet must never show over (and name
        // another site than) the page now on screen.
        val doc = radicleDocumentFor(
            current = documents[tab.id] ?: 0,
            origin = origin,
            committedOrigin = committedOrigins[tab.id],
        )
        if (doc != STALE_DOCUMENT) {
            bridge.origin = origin
            bridge.reply = reply
        }
        // Only the id and method outlive the call: the params go with it.
        val id = request.id
        val method = request.method
        var params: JSONArray? = request.params
        scope.launch {
            val result = try {
                val p = provider ?: throw IllegalStateException("provider not ready")
                val ps = params!!
                params = null
                p.request(origin, method, ps) { ask -> askOnTab(tab, doc, ask) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "ethereum request $method failed: ${e.javaClass.simpleName}")
                EthereumProvider.Reply.Err(EthereumProvider.INTERNAL, "Internal error")
            } finally {
                ticket.release()
            }
            answer(reply, id, result)
        }
    }

    private fun answer(reply: JavaScriptReplyProxy, id: Long, result: EthereumProvider.Reply) {
        val body = JSONObject().put("id", id)
        when (result) {
            is EthereumProvider.Reply.Ok -> body.put("result", result.value)
            is EthereumProvider.Reply.Err -> body.put("error", result.toJson())
        }
        // The document may be gone by now; then there's nobody to tell.
        runCatching { reply.postMessage(body.toString()) }
    }

    private fun emit(origin: String, event: String, data: Any) {
        val message = JSONObject().put("event", event).put("data", data).toString()
        for (bridge in bridges.values.toList()) {
            if (bridge.origin != origin) continue
            runCatching { bridge.reply?.postMessage(message) }
        }
    }

    /**
     * Put [ask] up on [tab] and wait for the answer — rejected at once if
     * the tab is blocked from prompting, the document that asked ([doc])
     * is no longer the tab's, or the tab moves on or closes while it waits.
     */
    private suspend fun askOnTab(tab: BrowserState, doc: Int, ask: EthAsk): EthAnswer {
        fun live() = (documents[tab.id] ?: 0) == doc && tab.id !in blockedTabs
        if (tab.id in blockedTabs && (documents[tab.id] ?: 0) == doc) return EthAnswer.Paused
        if (!live()) return EthAnswer.Rejected
        if (ask is EthAsk.CantSend && tab.id in cantSendClosed) return EthAnswer.Unseen
        val lock = promptLocks.getOrPut(tab.id) { Mutex() }
        if (ask is EthAsk.SwitchNotice) return switchTurn(tab, doc, ask, lock)
        // A sheet takes the turn from a "switched to" notice holding it: the page's sign or
        // send right after its switch comes up at once, not once the notice has timed out
        // (#446 R4-F1). The notice's hold is only there to space out no-sheet switches; a
        // sheet is a gate of its own. The notice itself stays up, with its Undo (R5-M2).
        sheetsWaiting[tab.id] = (sheetsWaiting[tab.id] ?: 0) + 1
        notices[tab.id]?.hold?.complete(Unit)
        try {
            return sheetTurn(tab, ask, lock, ::live)
        } finally {
            val left = (sheetsWaiting[tab.id] ?: 1) - 1
            if (left > 0) sheetsWaiting[tab.id] = left else sheetsWaiting.remove(tab.id)
        }
    }

    private suspend fun sheetTurn(tab: BrowserState, ask: EthAsk, lock: Mutex, live: () -> Boolean): EthAnswer {
        return lock.withLock {
            if (!live()) return@withLock EthAnswer.Rejected
            // Checked again here: asks that queued behind the one the user closed must not each come up in turn.
            if (ask is EthAsk.CantSend && tab.id in cantSendClosed) return@withLock EthAnswer.Unseen
            val reason = Strings.get(
                if (ask is EthAsk.Payment || ask is EthAsk.SendLink) R.string.send_eth_setup_reason_pay else R.string.send_eth_setup_reason_connect,
                permissionOriginDisplay(ask.origin),
            )
            val request = EthereumPromptRequest(ask) { setUpWallet(reason) }
            pending.getOrPut(tab.id) { mutableSetOf() }.add(request)
            tab.ethereumPrompt = request
            val answer = try {
                request.answer.await()
            } finally {
                pending[tab.id]?.remove(request)
                if (tab.ethereumPrompt === request) tab.ethereumPrompt = null
            }
            when {
                !live() -> Unit
                answer == EthAnswer.Closed -> cantSendClosed += tab.id
                answer !is EthAnswer.Approved && answer != EthAnswer.Unseen -> blockedTabs += tab.id
            }
            if (live()) answer else EthAnswer.Rejected
        }
    }

    /**
     * A no-sheet chain switch's turn on [tab] (#440, #446 R1-F1): taken in
     * the same line as the tab's sheets ([lock]) and put up as its prompt,
     * which the browser answers [EthAnswer.Approved] at once — but only
     * when it's the prompt's turn, so the tab is on screen and uncovered.
     * A background tab's switch waits there like its sheet would, and a
     * paused tab's is refused. Once approved, [lock] stays held through
     * the switch and its notice ([showNotice]), so a page switching in a
     * loop gets one switch per notice, each named rightly, and its Undo
     * pauses the tab. A sheet asked meanwhile ends that hold (#446 R4-F1),
     * but not the notice.
     */
    private suspend fun switchTurn(tab: BrowserState, doc: Int, ask: EthAsk.SwitchNotice, lock: Mutex): EthAnswer {
        fun live() = (documents[tab.id] ?: 0) == doc && tab.id !in blockedTabs
        lock.lock()
        var handedOn = false
        try {
            if (!live()) return EthAnswer.Rejected
            val request = EthereumPromptRequest(ask) { }
            pending.getOrPut(tab.id) { mutableSetOf() }.add(request)
            tab.ethereumPrompt = request
            val answer = try {
                request.answer.await()
            } finally {
                pending[tab.id]?.remove(request)
                if (tab.ethereumPrompt === request) tab.ethereumPrompt = null
            }
            // Never shown (withdrawn, or the tab moved on): nothing was turned down, so no pause.
            if (answer !is EthAnswer.Approved || !live()) return EthAnswer.Rejected
            handedOn = true
            scope.launch { showNotice(tab.id, doc, ask) { lock.unlock() } }
            return answer
        } finally {
            if (!handedOn) lock.unlock()
        }
    }

    /**
     * The notice for [ask]'s switch, once made, until it's closed, holding
     * the tab's line until then ([release]s it when the hold ends).
     *
     * Bounded where nobody owns the notice: [NOTICE_MAX_MS] for the browser
     * to take it ([SwitchNotice.received]: a screen being rebuilt may miss
     * it), then [NOTICE_MAX_MS] on screen while it still holds the line.
     * Once a sheet or a new document has ended the hold, the notice holds
     * nothing and the browser keeps it until it's down (#446 R6-M1). In between, the
     * browser has it and closes it itself if it gives it up, so the wait
     * for its tab to be the active one again has no limit (#446 R5-M3):
     * until then the tab isn't on screen, and its line couldn't move
     * anyway.
     *
     * A switch made is always announced (#446 R4-M2, R5-M1), even if its
     * page has started a new document since: the switch is the site's, and
     * stays. Its Undo then puts the site back but pauses nothing, as
     * there's no page left to pause. A sheet already waiting for the tab,
     * or one asked while the notice is up, has the turn (R4-F1): the hold
     * ends, so the sheet comes up at once, but the notice stays — a switch
     * that got the turn just ahead of a sheet is still named (R5-M2).
     */
    private suspend fun showNotice(tabId: Long, doc: Int, ask: EthAsk.SwitchNotice, release: () -> Unit) {
        var released = false
        fun free() {
            if (!released) {
                released = true
                release()
            }
        }
        try {
            val switch = withTimeoutOrNull(SWITCH_WAIT_MS) { ask.switched.await() } ?: return
            // The tab closed: there's nowhere left to show it.
            if (tabId !in documents) return
            val notice = SwitchNotice(tabId, switch)
            notices[tabId] = notice
            if ((sheetsWaiting[tabId] ?: 0) > 0) notice.hold.complete(Unit)
            try {
                coroutineScope {
                    val holding = launch {
                        notice.hold.await()
                        free()
                    }
                    switchedFlow.tryEmit(notice)
                    val taken = withTimeoutOrNull(NOTICE_MAX_MS) {
                        select {
                            notice.received.onAwait { true }
                            notice.closed.onAwait { false }
                        }
                    } ?: false
                    val up = taken && select {
                        notice.onScreen.onAwait { true }
                        notice.closed.onAwait { false }
                    }
                    if (up) {
                        // Only a hold that's still on is bounded: once a sheet or a new document
                        // has ended it, the notice holds nothing, and the browser may keep it (or
                        // put it back up after a sheet that covered it) as long as it needs to
                        // be seen (#446 R6-M1).
                        val held = withTimeoutOrNull(NOTICE_MAX_MS) {
                            select {
                                notice.hold.onAwait { }
                                notice.closed.onAwait { }
                            }
                        }
                        if (held != null) {
                            val undone = notice.closed.await()
                            if (undone && (documents[tabId] ?: 0) == doc) blockedTabs += tabId
                        }
                    }
                    notice.close(undo = false)
                    holding.join()
                }
            } finally {
                notice.close(undo = false)
                if (notices[tabId] === notice) notices.remove(tabId)
            }
        } finally {
            free()
        }
    }

    /** Which of [tabId]'s documents is its current one (bumped by [onDocumentStarted]). */
    internal fun currentDocument(tabId: Long): Int = documents[tabId] ?: 0

    /**
     * Put [ask] up on [tab] for its document [doc] (from [currentDocument])
     * and wait for the answer, in turn with the provider's own sheets:
     * the x402 payment sheet (#140). Rejected at once if [doc] isn't the
     * tab's any more or the tab's sheets are paused.
     */
    internal suspend fun askOnDocument(tab: BrowserState, doc: Int, ask: EthAsk): EthAnswer = askOnTab(tab, doc, ask)

    /** The tab started (committed) a new document on [url] — null when it's being torn down: what the old one asked is rejected. */
    fun onDocumentStarted(tab: BrowserState, url: String?) {
        documents[tab.id] = (documents[tab.id] ?: 0) + 1
        committedOrigins[tab.id] = providerOriginKey(url)
        // A switch notice stays: the switch it names is the site's, not the document's (#446
        // R5-M1). Its hold ends, though: the new document's own first switch doesn't wait
        // behind the old page's notice (R6-M3).
        notices[tab.id]?.hold?.complete(Unit)
        withdraw(tab.id)
    }

    /** The tab closed. */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId)
        notices[tabId]?.close(undo = false)
        documents.remove(tabId)
        committedOrigins.remove(tabId)
        promptLocks.remove(tabId)
        pending.remove(tabId)
        blockedTabs.remove(tabId)
        cantSendClosed.remove(tabId)
        sheetsWaiting.remove(tabId)
    }

    /** The user navigated [tabId] themselves: its pages may ask again. */
    fun allowPrompts(tabId: Long) {
        blockedTabs.remove(tabId)
        cantSendClosed.remove(tabId)
    }

    private fun withdraw(tabId: Long) {
        pending[tabId]?.toList()?.forEach { it.respond(EthAnswer.Rejected) }
    }

    private const val TAG = "EthereumProvider"
    private const val ICON_ASSET = "wallet/provider-icon.png"

    /** How long a request waits for the account list to be read back after a launch. */
    private const val ACCOUNTS_WAIT_MS = 3_000L

    /** The longest a no-sheet switch's turn waits for the provider to have switched (one store write). */
    private const val SWITCH_WAIT_MS = 30_000L

    /**
     * The longest a switch notice holds its tab's next no-sheet switch,
     * once on screen (and the longest it waits for the browser to take
     * it). A notice whose hold has ended isn't bounded by it (#446
     * R6-M1). Past a
     * snackbar's long duration, and its accessibility-extended one — up to
     * 2 minutes with Android's "Time to take action" (#446 R2-M1). Should a
     * notice still be up when this runs out, closing it takes it down
     * ([SwitchNotice.awaitClosed]), so no Undo outlives the turn it holds.
     */
    private const val NOTICE_MAX_MS = 150_000L

    /** The longest an Add network sheet waits for the chain catalog. */
    private const val CATALOG_WAIT_MS = 4_000L
}

/** One `window.ethereum` request off the channel. */
internal data class EthereumRequest(val id: Long, val method: String, val params: JSONArray)

/** Parse `{"id": n, "method": "eth_…", "params": […]}`, or null if it isn't one. */
internal fun parseEthereumRequest(data: String?): EthereumRequest? {
    if (data == null || data.length > MAX_ETH_REQUEST_CHARS) return null
    // Parsed on the main thread, before any origin or grant check: JSON
    // whose parse costs far more memory than its length (330K empty
    // objects in a megabyte) would take the whole browser down (#459).
    if (!jsonShapeWithin(data, MAX_ETH_REQUEST_VALUES, MAX_ETH_REQUEST_CONTAINERS)) return null
    val json = try {
        JSONObject(data)
    } catch (e: Exception) {
        return null
    } catch (e: StackOverflowError) {
        return null
    }
    val id = (json.opt("id") as? Number)?.toLong() ?: return null
    val method = json.opt("method") as? String ?: return null
    if (method.isEmpty() || method.length > 64) return null
    val params = when (val p = json.opt("params")) {
        null, JSONObject.NULL -> JSONArray()
        is JSONArray -> p
        is JSONObject -> JSONArray().put(p)
        else -> return null
    }
    return EthereumRequest(id, method, params)
}

/** Bigger than any sensible request: a large typed-data payload or contract call, JSON-escaped. */
private const val MAX_ETH_REQUEST_CHARS = 1024 * 1024

/**
 * Values in one request, at most: a typed-data payload at its own limit
 * ([baby.freedom.mobile.wallet.Eip712.MAX_JSON]) has far fewer.
 */
internal const val MAX_ETH_REQUEST_VALUES = 100_000

/** Arrays and objects in one request, at most. */
internal const val MAX_ETH_REQUEST_CONTAINERS = 10_000

/**
 * The page side of [EthereumProviders]: `window.ethereum` (EIP-1193:
 * `request`, events; the legacy `enable` / `send` / `sendAsync` too, and
 * `isMetaMask` for sites that still sniff for it, as desktop's and iOS's
 * do), announced over EIP-6963 with a fresh UUID per document. The
 * channel object the platform puts on `window` is taken off it before
 * the page's own scripts run (#69), and has a random name; subframes and
 * non-http(s) documents get no provider. A request that can bring up a
 * sheet waits up to ten minutes, any other one a minute.
 */
internal fun ethereumProviderJs(channel: String, iconDataUri: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    require(iconDataUri.isEmpty() || Regex("^data:image/png;base64,[A-Za-z0-9+/=]+$").matches(iconDataUri)) { "icon must be a PNG data URI" }
    return """
(function () {
  var w = window, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var proto = w.location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;
  if (w.top !== w) return;
  var send = port.postMessage.bind(port), stringify = JSON.stringify, parse = JSON.parse;
  var setT = w.setTimeout, clearT = w.clearTimeout, P = w.Promise, E = w.Error;
  var pending = new w.Map(), nextId = 0;
  var PROMPTING = { eth_requestAccounts: 1, wallet_requestPermissions: 1, personal_sign: 1, eth_signTypedData_v4: 1,
    eth_sendTransaction: 1, wallet_switchEthereumChain: 1, wallet_addEthereumChain: 1 };
  var listeners = { connect: [], disconnect: [], chainChanged: [], accountsChanged: [], message: [] };
  var state = { chainId: null, accounts: [] };
  function emit(event, data) {
    var hs = listeners[event];
    if (!hs) return;
    hs.slice().forEach(function (h) { try { h(data); } catch (e) {} });
  }
  function setAccounts(a) {
    if (!a || typeof a.length !== 'number') return;
    state.accounts = Array.prototype.slice.call(a);
  }
  port.addEventListener('message', function (ev) {
    var msg;
    try { msg = parse(ev.data); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (typeof msg.event === 'string') {
      if (msg.event === 'chainChanged') state.chainId = msg.data;
      else if (msg.event === 'accountsChanged') setAccounts(msg.data);
      else if (msg.event === 'connect' && msg.data) state.chainId = msg.data.chainId;
      emit(msg.event, msg.data);
      return;
    }
    var p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    clearT(p.timer);
    if (msg.error) {
      var err = new E(msg.error.message || 'Unknown error');
      err.code = msg.error.code;
      if (msg.error.data !== undefined) err.data = msg.error.data;
      p.reject(err);
    } else {
      if (p.method === 'eth_chainId') state.chainId = msg.result;
      else if (p.method === 'eth_accounts' || p.method === 'eth_requestAccounts') setAccounts(msg.result);
      p.resolve(msg.result);
    }
  });
  function request(method, params) {
    return new P(function (resolve, reject) {
      if (typeof method !== 'string' || method.length === 0) {
        var bad = new E('method must be a non-empty string');
        bad.code = -32602;
        reject(bad);
        return;
      }
      var id = ++nextId;
      var timer = setT(function () {
        if (pending.delete(id)) {
          var t = new E('Request timed out');
          t.code = -32603;
          reject(t);
        }
      }, PROMPTING[method] ? 600000 : 60000);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer, method: method });
      try {
        send(stringify({ id: id, method: method, params: params === undefined ? [] : params }));
      } catch (e) {
        pending.delete(id);
        clearT(timer);
        reject(e);
      }
    });
  }
  var ethereum = {
    isMetaMask: true,
    isFreedomBrowser: true,
    get chainId() { return state.chainId; },
    get networkVersion() { return state.chainId ? String(parseInt(state.chainId, 16)) : null; },
    get selectedAddress() { return state.accounts[0] || null; },
    isConnected: function () { return true; },
    request: function (payload) {
      if (!payload || typeof payload !== 'object') {
        var bad = new E('request() takes { method, params }');
        bad.code = -32602;
        return P.reject(bad);
      }
      return request(payload.method, payload.params);
    },
    enable: function () { return request('eth_requestAccounts', []); },
    send: function (methodOrPayload, paramsOrCallback) {
      if (typeof methodOrPayload === 'string') return request(methodOrPayload, paramsOrCallback);
      if (typeof paramsOrCallback === 'function') { ethereum.sendAsync(methodOrPayload, paramsOrCallback); return; }
      return request(methodOrPayload && methodOrPayload.method, methodOrPayload && methodOrPayload.params);
    },
    sendAsync: function (payload, callback) {
      request(payload && payload.method, payload && payload.params).then(
        function (result) { callback(null, { id: payload.id, jsonrpc: '2.0', result: result }); },
        function (error) { callback(error, null); });
    },
    on: function (event, handler) {
      if (listeners[event] && typeof handler === 'function') listeners[event].push(handler);
      return ethereum;
    },
    addListener: function (event, handler) { return ethereum.on(event, handler); },
    once: function (event, handler) {
      if (typeof handler !== 'function') return ethereum;
      var wrapped = function (data) { ethereum.removeListener(event, wrapped); handler(data); };
      return ethereum.on(event, wrapped);
    },
    removeListener: function (event, handler) {
      var hs = listeners[event];
      if (hs) {
        var i = hs.indexOf(handler);
        if (i > -1) hs.splice(i, 1);
      }
      return ethereum;
    },
    off: function (event, handler) { return ethereum.removeListener(event, handler); },
    removeAllListeners: function (event) {
      if (event) { if (listeners[event]) listeners[event] = []; }
      else { for (var k in listeners) listeners[k] = []; }
      return ethereum;
    }
  };
  try {
    Object.defineProperty(w, 'ethereum', { value: ethereum, configurable: true, enumerable: false, writable: true });
  } catch (e) { return; }
  var uuid;
  try {
    uuid = w.crypto.randomUUID();
  } catch (e) {
    var b = new w.Uint8Array(16);
    w.crypto.getRandomValues(b);
    b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
    var h = Array.prototype.map.call(b, function (x) { return (x + 256).toString(16).slice(1); }).join('');
    uuid = h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
  }
  var detail = Object.freeze({
    info: Object.freeze({ uuid: uuid, name: 'Freedom Browser', icon: '$iconDataUri', rdns: 'baby.freedom.browser' }),
    provider: ethereum
  });
  var CE = w.CustomEvent, dispatch = w.dispatchEvent.bind(w);
  function announce() {
    try { dispatch(new CE('eip6963:announceProvider', { detail: detail })); } catch (e) {}
  }
  w.addEventListener('eip6963:requestProvider', announce);
  announce();
  try { dispatch(new w.Event('ethereum#initialized')); } catch (e) {}
})();
""".trimIndent()
}
