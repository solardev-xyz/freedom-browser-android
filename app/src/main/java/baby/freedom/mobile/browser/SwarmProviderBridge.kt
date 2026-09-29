package baby.freedom.mobile.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.data.SwarmFeedStore
import baby.freedom.mobile.data.SwarmGrantStore
import baby.freedom.mobile.wallet.PublisherIdentity
import baby.freedom.mobile.wallet.PublisherIdentityStore
import baby.freedom.mobile.wallet.PublisherKeys
import baby.freedom.mobile.wallet.Vault
import baby.freedom.swarm.SwarmNode
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * An approval sheet waiting on its tab (#120): [ask] is what a page on
 * [SwarmAsk.origin] wants, [respond] the user's answer. Shown by
 * [BrowserScreen] ([SwarmPromptSheet]) only while its tab is the active
 * one and its page is on screen.
 */
class SwarmPromptRequest internal constructor(val ask: SwarmAsk) {
    /** How the sheet ended: [answer], and whether it was the user's own ([byUser]). */
    internal class Ending(val answer: SwarmProvider.Answer, val byUser: Boolean)

    internal val ending = CompletableDeferred<Ending>()

    fun respond(answer: SwarmProvider.Answer) {
        ending.complete(Ending(answer, byUser = true))
    }

    /**
     * Taken down without the user answering (the tab started a new
     * document or closed): a refusal for the request, but not the
     * user's answer — so not a Don't allow on a shared manifest consent
     * (#226 R2-F1).
     */
    internal fun withdraw() {
        ending.complete(Ending(SwarmProvider.Answer.REJECTED, byUser = false))
    }
}

/**
 * The page side of the `window.swarm` provider (#120) and its channel: a
 * document-start script ([swarmProviderJs]) defines `window.swarm` in
 * every http(s) top-level document of a normal (not private) tab — as
 * `window.ethereum` is — and sends each request down a
 * `WebMessageListener` channel to [SwarmProvider], which answers back on
 * it. Bytes cross the channel as base64 (`{"$b64": …}`), and an
 * unsigned 64-bit value over JS's safe range as `{"$bigint": "…"}`, so a
 * page passes and gets exactly what it would on desktop: `Uint8Array`s,
 * `ArrayBuffer`s, strings, and a `bigint` span.
 *
 * Who is asking is the platform's `sourceOrigin` for the document that
 * sent the message — never anything the page claims — and only a tab's
 * top-level document may ask. The origin must be a secure one: https
 * (the dweb origins are https too) or http on loopback.
 *
 * Sheets ([SwarmPromptRequest]) are one at a time per tab, taken down
 * (as a refusal) when the tab starts a new document or closes. Once the
 * user refuses one, that tab's pages get no more sheets — every ask is
 * refused at once — until the user navigates the tab themselves
 * ([allowPrompts]), so a page can't hold the browser behind a loop of
 * them. A sheet that needs a wallet opens wallet setup only once the
 * user approves it, and a setup the user backs out of counts as a
 * refusal too.
 *
 * A sheet waits at most [SHEET_WAIT_MS] from the request's arrival, short
 * of the page's own five-minute timer; once a request is approved
 * (a sheet or an "always allow") the page is told (`{"id", "approved"}`)
 * and stops its timer, since from then on its answer is the result of
 * something real — an upload, a signature — that it must not lose.
 *
 * A Swarm app's permission manifest (#122, [SwarmManifests]) is checked
 * before any request that needs a grant ([MANIFEST_GATED]): once per
 * committed document of the tab and origin, shared by every request that
 * document makes meanwhile. `swarm_requestAccess` looks for one even on
 * a site with no manifest record yet; other requests only re-check a
 * site that has one. New rows put up the manifest sheet
 * ([SwarmAsk.Manifest]) the way any other ask goes up; a site whose
 * manifest can't be fetched for now gets `4900` (`manifest_unresolved`)
 * rather than the grants its manifest gave.
 */
object SwarmProviders {
    @Volatile
    private var provider: SwarmProvider? = null

    private val scope = MainScope()

    /** Live bridges, one per WebView; main thread only. */
    private val bridges = WeakHashMap<WebView, Bridge>()

    /** Main thread only, like everything below. */
    private val documents = HashMap<Long, Int>()
    private val committedOrigins = HashMap<Long, String?>()
    private val promptLocks = HashMap<Long, Mutex>()
    private val pending = HashMap<Long, MutableSet<SwarmPromptRequest>>()
    private val blockedTabs = HashSet<Long>()

    /** Tab → the document its manifest checks ran for, and each origin's shared check (#122). */
    private val manifestChecks = HashMap<Long, Pair<Int, HashMap<String, ManifestCheck>>>()

    private class ManifestCheck(val eager: Boolean, val result: Deferred<SwarmProvider.Reply.Err?>)

    @Volatile
    private var manifests: SwarmManifests? = null

    /** Opens the wallet page to create, import or unlock a wallet ([Vault.requireUnlocked]); set by [init]. */
    @Volatile
    internal var setUpWallet: suspend (reason: String) -> Boolean = { false }

    private class Bridge(val tab: BrowserState, val ensPins: EnsDocumentPins?) {
        /** The origin and channel of the top-level document that last spoke. */
        var origin: String? = null
        var reply: JavaScriptReplyProxy? = null
        var channel: String? = null
        var script: ScriptHandler? = null
    }

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /** Wire the provider to the stores, the wallet and the node, once per process. */
    fun init(context: Context) {
        if (provider != null) return
        val app = context.applicationContext
        val grantStore = SwarmGrantStore.get(app)
        val feedStore = SwarmFeedStore.get(app)
        val vault = Vault.get(app)
        val identities = PublisherIdentityStore.get(app)
        val p = SwarmProvider(
            grants = object : SwarmProvider.Grants {
                override suspend fun connected(origin: String) = grantStore.grantFor(origin) != null
                override suspend fun connect(origin: String) = grantStore.connect(origin)
                override suspend fun autoApprove(origin: String, kind: SwarmProvider.AutoApprove) =
                    grantStore.grantFor(origin)?.autoApprove?.contains(kind.wire) == true
                override suspend fun setAutoApprove(origin: String, kind: SwarmProvider.AutoApprove) =
                    grantStore.setAutoApprove(origin, kind.wire, true)
            },
            feeds = object : SwarmProvider.Feeds {
                override fun granted(origin: String) = feedStore.granted(origin)
                override fun grant(origin: String) = feedStore.grant(origin)
                override fun feed(origin: String, name: String) = feedStore.feed(origin, name)
                override fun all(origin: String) = feedStore.all(origin)
                override fun put(origin: String, record: SwarmProvider.FeedRecord) = feedStore.put(origin, record)
            },
            publishers = object : SwarmProvider.Publishers {
                override fun walletExists() = vault.state.value != Vault.State.Empty
                override fun unlocked() = vault.unlockedNow()
                override fun site(origin: String) = identities.site(origin)
                override fun ensureSite(origin: String) = identities.ensureSite(origin)
                override fun signingKey(identity: PublisherIdentity) = PublisherKeys.signingKey(vault, identity)
                override fun noteActivity() = vault.noteActivity()
            },
            node = GatewayHttp,
        )
        setUpWallet = { reason -> vault.requireUnlocked(reason) }
        manifests = SwarmManifests(
            SwarmManifestFile(File(app.filesDir, "swarm-manifests.json")),
            manifestProjections(grantStore, feedStore, identities, vault),
        )
        p.events = SwarmProvider.Events { origin, event, data ->
            scope.launch { emit(origin, event, data) }
        }
        provider = p
    }

    /**
     * The user disconnects [origin] (the wallet page's Swarm section):
     * its connection, "always allow"s and feed access go — its feed
     * records stay, as on desktop, for when it's connected again — and
     * its open pages hear `disconnect`. False if that couldn't be saved.
     */
    suspend fun disconnect(context: Context, origin: String): Boolean {
        val app = context.applicationContext
        val feedsDropped = withContext(Dispatchers.IO) {
            try {
                SwarmFeedStore.get(app).revoke(origin)
                true
            } catch (e: IOException) {
                false
            } catch (e: IllegalStateException) {
                false
            }
        }
        if (!SwarmGrantStore.get(app).revoke(origin) || !feedsDropped) return false
        // Manifest tracking goes with it: nothing the manifest granted is left to take back.
        try {
            manifests?.forget(origin)
        } catch (e: IOException) {
            Log.w(TAG, "couldn't drop the manifest record of a disconnected site")
        }
        emit(origin, "disconnect", JSONObject().put("origin", swarmOriginKey(origin)))
        return true
    }

    /** The sites whose manifest manages some rows (#122), for the connected-sites list. */
    fun manifestRows(): Map<String, List<ManifestCapability>> = manifests?.managedRows().orEmpty()

    /**
     * The user chose to be asked each time on [origin]: what its manifest
     * granted is taken back (the connection stays, as theirs). False if
     * that couldn't be saved.
     */
    suspend fun useIndividualApprovals(origin: String): Boolean = try {
        manifests?.useIndividual(origin) ?: false
    } catch (e: IOException) {
        false
    }

    /** The grant stores behind [SwarmManifests]' projections. */
    private fun manifestProjections(
        grantStore: SwarmGrantStore,
        feedStore: SwarmFeedStore,
        identities: PublisherIdentityStore,
        vault: Vault,
    ) = object : SwarmManifests.Projections {
        // Feed access and publisher identities belong to a wallet: with
        // none yet, the first signing sheet sets one up.
        override fun available(projection: ManifestProjection) = when (projection) {
            ManifestProjection.Identity, ManifestProjection.FeedGrant -> vault.state.value != Vault.State.Empty
            else -> true
        }

        override suspend fun enabled(origin: String, projection: ManifestProjection): Boolean = when (projection) {
            ManifestProjection.Connection -> grantStore.grantFor(origin) != null
            ManifestProjection.FeedGrant -> withContext(Dispatchers.IO) { feedStore.granted(origin) }
            ManifestProjection.Identity -> withContext(Dispatchers.IO) { identities.site(origin) != null }
            else -> grantStore.grantFor(origin)?.autoApprove?.contains(autoKind(projection)) == true
        }

        override suspend fun set(origin: String, projection: ManifestProjection, on: Boolean) {
            when (projection) {
                ManifestProjection.Connection -> if (on) {
                    if (!grantStore.connect(origin)) throw IOException("couldn't save the connection")
                    emit(origin, "connect", JSONObject().put("origin", swarmOriginKey(origin)))
                } else {
                    if (!grantStore.revoke(origin)) throw IOException("couldn't save the disconnection")
                    emit(origin, "disconnect", JSONObject().put("origin", swarmOriginKey(origin)))
                }
                ManifestProjection.FeedGrant -> withContext(Dispatchers.IO) {
                    try {
                        if (on) feedStore.grant(origin) else feedStore.revoke(origin)
                    } catch (e: IllegalStateException) {
                        throw IOException("there is no wallet")
                    }
                }
                // Ensure, never replace: an identity the site has is kept, and removal never deletes one.
                ManifestProjection.Identity -> if (on) {
                    withContext(Dispatchers.IO) {
                        try {
                            identities.ensureSite(origin)
                        } catch (e: IllegalStateException) {
                            throw IOException("there is no wallet")
                        }
                    }
                }
                else -> {
                    // Turning an "always allow" off on a site that's no longer connected: nothing left to turn off.
                    if (!on && grantStore.grantFor(origin) == null) return
                    if (!grantStore.setAutoApprove(origin, autoKind(projection), on)) {
                        throw IOException("couldn't save the auto-approve rule")
                    }
                }
            }
        }
    }

    private fun autoKind(projection: ManifestProjection) = when (projection) {
        ManifestProjection.AutoPublish -> SwarmProvider.AutoApprove.Publish.wire
        ManifestProjection.AutoFeeds -> SwarmProvider.AutoApprove.Feeds.wire
        ManifestProjection.AutoSigning -> SwarmProvider.AutoApprove.Signing.wire
        else -> throw IllegalArgumentException("not an auto-approve projection")
    }

    /**
     * Track [webView] (a tab's, before its first load) and register the
     * channel and the script on it. Nothing for a private tab: its grants
     * would have nowhere to live that the private session could forget.
     */
    fun install(webView: WebView, tab: BrowserState, ensPins: EnsDocumentPins? = null) {
        if (tab.private || !isSupported()) return
        val bridge = Bridge(tab, ensPins)
        bridges[webView] = bridge
        try {
            val channel = newBottomUiChannelName()
            WebViewCompat.addWebMessageListener(webView, channel, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                onMessage(bridge, message, sourceOrigin.toString(), isMainFrame, reply)
            }
            bridge.channel = channel
            bridge.script = WebViewCompat.addDocumentStartJavaScript(webView, swarmProviderJs(channel), setOf("*"))
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
        val deadline = SystemClock.elapsedRealtime() + SHEET_WAIT_MS
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val request = parseSwarmRequest(message.data) ?: return
        val origin = providerOriginKey(sourceOrigin)
        if (!isMainFrame || origin == null) {
            val why = if (!isMainFrame) "window.swarm is only available to the top-level page" else "Origin not permitted"
            answer(reply, request.id, SwarmProvider.Reply.Err(SwarmProvider.UNAUTHORIZED, why))
            return
        }
        // Which of the tab's documents this is: a message from the
        // outgoing document can arrive after the tab started the next
        // (see radicleDocumentFor).
        val doc = radicleDocumentFor(
            current = documents[tab.id] ?: 0,
            origin = origin,
            committedOrigin = committedOrigins[tab.id],
        )
        if (doc != STALE_DOCUMENT) {
            bridge.origin = origin
            bridge.reply = reply
        }
        scope.launch {
            val result = try {
                val p = provider ?: throw IllegalStateException("provider not ready")
                val approved = { approved(reply, request.id) }
                manifestFresh(bridge, doc, origin, request.method, deadline)
                    ?: p.request(origin, request.method, request.params, approved) { ask ->
                        askOnTab(tab, doc, ask, deadline - SystemClock.elapsedRealtime(), p::current, approved = approved)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "swarm request ${request.method} failed: ${e.javaClass.simpleName}")
                SwarmProvider.Reply.Err(SwarmProvider.INTERNAL, "Internal error")
            }
            answer(reply, request.id, result)
        }
    }

    private fun answer(reply: JavaScriptReplyProxy, id: Long, result: SwarmProvider.Reply) {
        val body = JSONObject().put("id", id)
        when (result) {
            is SwarmProvider.Reply.Ok -> body.put("result", result.value)
            is SwarmProvider.Reply.Err -> body.put("error", result.toJson())
        }
        // The document may be gone by now; then there's nobody to tell.
        runCatching { reply.postMessage(body.toString()) }
    }

    /** The request was approved: the page stops its timer and waits for the result. */
    private fun approved(reply: JavaScriptReplyProxy, id: Long) {
        runCatching { reply.postMessage(JSONObject().put("id", id).put("approved", true).toString()) }
    }

    private fun emit(origin: String, event: String, data: Any) {
        val message = JSONObject().put("event", event).put("data", data).toString()
        for (bridge in bridges.values.toList()) {
            if (bridge.origin != origin) continue
            runCatching { bridge.reply?.postMessage(message) }
        }
    }

    /**
     * Put [ask] up on [tab] and wait for the answer — a refusal at once if
     * the tab is blocked from prompting, the document that asked ([doc])
     * is no longer the tab's, or the tab moves on or closes while it waits,
     * and a refusal (that doesn't block the tab) once [waitMs] runs out
     * with the sheet unanswered. [approved] is told as soon as the user
     * approves. A [SwarmAsk.Sign] that [needs a wallet][SwarmAsk.Sign.needsWallet]
     * then opens wallet setup, right from the user's tap on the sheet —
     * and a setup the user backs out of blocks the tab like a refused
     * sheet, so a page can't reopen the wallet page in a loop. The tab's
     * other asks wait until setup is over, and after a backed-out one are
     * refused without a sheet. The sheet shows [current]'s view of [ask]
     * once the lock is ours ([SwarmProvider.current]), not the one built
     * before an earlier ask's setup; null (the feed it signs for lost its
     * identity meanwhile) answers [SwarmProvider.Answer.OWNER_GONE]
     * without a sheet or a block. [answered] hears the user's own answer
     * to the sheet, the moment there is one: a refusal that never reaches
     * it (no sheet, a timeout, a sheet withdrawn because the tab reloaded,
     * navigated or closed) wasn't the user's.
     */
    internal suspend fun askOnTab(
        tab: BrowserState,
        doc: Int,
        ask: SwarmAsk,
        waitMs: Long = SHEET_WAIT_MS,
        current: suspend (SwarmAsk) -> SwarmAsk? = { it },
        answered: (SwarmProvider.Answer) -> Unit = {},
        approved: () -> Unit = {},
    ): SwarmProvider.Answer {
        fun live() = (documents[tab.id] ?: 0) == doc && tab.id !in blockedTabs
        if (!live() || waitMs <= 0) return SwarmProvider.Answer.REJECTED
        val lock = promptLocks.getOrPut(tab.id) { Mutex() }
        // The tab's prompt lock is held through wallet setup too, not just
        // the sheet: another request's sheet waits until setup is over, so
        // one the user backs out of (blocking the tab) refuses the rest
        // without a sheet popping up behind the wallet page.
        var held = false
        var shown = ask
        try {
            val answer = withTimeoutOrNull(waitMs) {
                // No suspension between lock() returning and the flag:
                // a timeout either lands before the lock is ours (and
                // lock() gives it back) or after we've noted it.
                lock.lock()
                held = true
                if (!live()) return@withTimeoutOrNull SwarmProvider.Answer.REJECTED
                // Built before the lock was ours: an earlier ask may have
                // set up the wallet or created the identity since.
                shown = current(ask) ?: return@withTimeoutOrNull SwarmProvider.Answer.OWNER_GONE
                if (!live()) return@withTimeoutOrNull SwarmProvider.Answer.REJECTED
                val request = SwarmPromptRequest(shown)
                pending.getOrPut(tab.id) { mutableSetOf() }.add(request)
                tab.swarmPrompt = request
                val ending = try {
                    request.ending.await()
                } finally {
                    pending[tab.id]?.remove(request)
                    if (tab.swarmPrompt === request) tab.swarmPrompt = null
                }
                val answer = ending.answer
                if (ending.byUser) answered(answer)
                if (!answer.allowed && live()) blockedTabs += tab.id
                if (answer.allowed && live()) answer else SwarmProvider.Answer.REJECTED
            } ?: return SwarmProvider.Answer.REJECTED
            if (!answer.allowed) return answer
            approved()
            val sign = shown as? SwarmAsk.Sign
            if (sign == null || !sign.needsWallet) return answer
            // Past the sheet's deadline now: setting a wallet up (and
            // writing its phrase down) takes as long as it takes, and the
            // page has stopped its timer.
            val set = setUpWallet(swarmWalletReason(sign.origin))
            if (!set && live()) blockedTabs += tab.id
            return if (set && live()) answer else SwarmProvider.Answer.REJECTED
        } finally {
            if (held) lock.unlock()
        }
    }

    /**
     * Whether [origin]'s manifest (#122) lets [method] go on for the
     * tab's document [doc]: null to go on (no manifest authority
     * involved, or it's fresh and decided), or the error the page gets —
     * a refused manifest sheet, or a manifest that can't be fetched for
     * now. One check per document and origin, shared by the requests
     * that arrive while it runs; a refusal holds for the document, any
     * other error is checked again on the next request. A request from
     * an outgoing document ([STALE_DOCUMENT]) is checked on its own,
     * and any sheet it would need is refused ([askOnTab]).
     */
    private suspend fun manifestFresh(bridge: Bridge, doc: Int, origin: String, method: String, deadline: Long): SwarmProvider.Reply.Err? {
        val m = manifests ?: return null
        if (method !in MANIFEST_GATED) return null
        val tab = bridge.tab
        val eager = method == "swarm_requestAccess"
        if (doc == STALE_DOCUMENT) return manifestCheck(m, bridge, doc, origin, eager, deadline)
        val slot = manifestChecks[tab.id]?.takeIf { it.first == doc }
            ?: (doc to HashMap<String, ManifestCheck>()).also { manifestChecks[tab.id] = it }
        var check = slot.second[origin]
        if (check == null || (eager && !check.eager)) {
            check = ManifestCheck(eager, scope.async { manifestCheck(m, bridge, doc, origin, eager, deadline) })
            slot.second[origin] = check
        }
        val error = check.result.await()
        if (error != null && error.code != SwarmProvider.USER_REJECTED && slot.second[origin] === check) {
            slot.second.remove(origin)
        }
        return error
    }

    private suspend fun manifestCheck(
        m: SwarmManifests,
        bridge: Bridge,
        doc: Int,
        origin: String,
        eager: Boolean,
        deadline: Long,
    ): SwarmProvider.Reply.Err? = try {
        when (val check = m.check(origin, eager) { discoverManifest(origin, bridge.ensPins) }) {
            SwarmManifests.Check.Legacy, SwarmManifests.Check.Ready -> null
            is SwarmManifests.Check.Unresolved -> SwarmProvider.Reply.Err(
                SwarmProvider.UNAVAILABLE,
                "Couldn't refresh this app's permission manifest",
                JSONObject().put("reason", "manifest_unresolved"),
            )
            is SwarmManifests.Check.Consent -> {
                val ask = SwarmAsk.Manifest(origin, check.consent)
                var users: SwarmProvider.Answer? = null
                val answer = askOnTab(bridge.tab, doc, ask, deadline - SystemClock.elapsedRealtime(), answered = { users = it })
                // A refusal the user never gave (a withdrawn or timed-out
                // sheet, a tab that moved on) refuses this request only: the
                // consent may be shared with another tab, which can still
                // answer it (#226 R1-F2).
                val outcome = manifestOutcome(ask, answer, users)
                if (outcome != null && m.decide(check.token, outcome)) {
                    null
                } else {
                    SwarmProvider.Reply.Err(SwarmProvider.USER_REJECTED, "User rejected the request")
                }
            }
        }
    } catch (e: IllegalStateException) {
        // The decision was overtaken (another tab's answer, a redeploy): nothing was granted; the next request checks again.
        SwarmProvider.Reply.Err(SwarmProvider.UNAVAILABLE, "This app's permissions changed; try again", JSONObject().put("reason", "manifest_stale"))
    } catch (e: IOException) {
        SwarmProvider.Reply.Err(SwarmProvider.INTERNAL, "Couldn't save this app's permissions on this device")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Answered, not thrown: a thrown check would stay in the document's cache and fail every later request.
        Log.w(TAG, "manifest check failed: ${e.javaClass.simpleName}")
        SwarmProvider.Reply.Err(SwarmProvider.INTERNAL, "Internal error")
    }

    /**
     * What the manifest sheet's result [answer] decides, given the user's
     * own answer to it ([users], null if they never gave one): a grant
     * only when the sheet came back approved, Don't allow only when the
     * user said so, and nothing — the consent stays open — otherwise.
     */
    internal fun manifestOutcome(
        ask: SwarmAsk.Manifest,
        answer: SwarmProvider.Answer,
        users: SwarmProvider.Answer?,
    ): SwarmManifests.Outcome? = when {
        answer.allowed -> ask.outcomeOf(answer)
        users != null && !users.allowed -> SwarmManifests.Outcome.Deny
        else -> null
    }

    /** The tab started (committed) a new document on [url] — null when it's being torn down. */
    fun onDocumentStarted(tab: BrowserState, url: String?) {
        manifestChecks.remove(tab.id)
        documents[tab.id] = (documents[tab.id] ?: 0) + 1
        committedOrigins[tab.id] = providerOriginKey(url)
        withdraw(tab.id)
    }

    /** The tab closed. */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId)
        manifestChecks.remove(tabId)
        documents.remove(tabId)
        committedOrigins.remove(tabId)
        promptLocks.remove(tabId)
        pending.remove(tabId)
        blockedTabs.remove(tabId)
    }

    /** The user navigated [tabId] themselves: its pages may ask again. */
    fun allowPrompts(tabId: Long) {
        blockedTabs.remove(tabId)
    }

    private fun withdraw(tabId: Long) {
        pending[tabId]?.toList()?.forEach { it.withdraw() }
    }

    private const val TAG = "SwarmProvider"

    /**
     * The methods a manifest's authority can stand behind (profile §4):
     * all but the permission-free reads and introspection. Messaging
     * (#121) isn't answered on this device yet, so it isn't checked.
     */
    internal val MANIFEST_GATED: Set<String> = setOf(
        "swarm_requestAccess",
        "swarm_getUploadStatus",
        "swarm_publishData", "swarm_publishFiles", "swarm_publishChunk",
        "swarm_createFeed", "swarm_updateFeed", "swarm_writeFeedEntry",
        "swarm_writeSingleOwnerChunk", "swarm_getSigningIdentity",
    )

    /** How long a sheet waits for the user: well short of the page's five-minute timer ([swarmProviderJs]). */
    internal const val SHEET_WAIT_MS = 270_000L
}

/**
 * The embedded node's gateway ([SwarmNode.GATEWAY_URL]) over
 * `HttpURLConnection`: what [SwarmProvider] publishes and reads through.
 * Answers are read up to [MAX_ANSWER_BYTES].
 *
 * `timeoutMs` bounds the whole request, not just each read: a watchdog
 * on its own thread disconnects the connection once it runs out, so a
 * node that stops draining an upload body (whose write no read timeout
 * covers) or trickles its answer fails the call with a
 * [SocketTimeoutException] — which the page gets as a timeout
 * (`node-timeout`), not as the node being stopped — instead of holding it — and any feed lock around it — forever. The
 * page stops its own timer once a request is approved, so this is what
 * settles it.
 */
internal object GatewayHttp : SwarmProvider.Http {
    private const val MAX_ANSWER_BYTES = 16 * 1024 * 1024

    /** The answer's body ran past the call's limit; the rest of it was never read. */
    class AnswerTooLarge : IOException("the node's answer is too large")
    private const val RUNNING = 0
    private const val EXPIRED = 1
    private const val DONE = 2

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "swarm-gateway-watchdog").apply { isDaemon = true }
    }

    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): SwarmProvider.Http.Answer = requestAt(SwarmNode.GATEWAY_URL, method, path, headers, body, timeoutMs)

    /** [request] against [base] (tests point it at a local server). */
    internal fun requestAt(
        base: String,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
        /** Tests only: runs once the answer is complete, before it's returned. */
        afterAnswer: () -> Unit = {},
        /** The most of the answer's body that is read: past it the call fails with [AnswerTooLarge]. */
        maxBytes: Int = MAX_ANSWER_BYTES,
    ): SwarmProvider.Http.Answer {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        // RUNNING until either the answer is read to its end (DONE) or
        // the deadline passes first (EXPIRED) — whichever gets there
        // first decides, so an answer complete in time is never turned
        // into a timeout by a watchdog firing while it's being returned.
        val state = AtomicInteger(RUNNING)
        // Disconnecting from this thread would block behind the stalled
        // write; the watchdog's thread aborts it at once.
        val abort = watchdog.schedule(
            { if (state.compareAndSet(RUNNING, EXPIRED)) runCatching { conn.disconnect() } },
            timeoutMs.toLong(), TimeUnit.MILLISECONDS,
        )
        val expired = { state.get() == EXPIRED }
        try {
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 10_000)
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    // Between reads too: a node trickling its answer never trips the read timeout.
                    if (expired()) throw SocketTimeoutException("the node didn't answer in $timeoutMs ms")
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw AnswerTooLarge()
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            // Read to its end: done, unless the deadline got there first.
            if (!state.compareAndSet(RUNNING, DONE)) throw SocketTimeoutException("the node didn't answer in $timeoutMs ms")
            afterAnswer()
            val answerHeaders = conn.headerFields.entries
                .filter { it.key != null && it.value.isNotEmpty() }
                .associate { it.key to it.value.first() }
            return SwarmProvider.Http.Answer(status, answerHeaders, bytes)
        } catch (e: IOException) {
            if (expired() && e !is SocketTimeoutException) throw SocketTimeoutException("the node didn't answer in $timeoutMs ms")
            throw e
        } finally {
            abort.cancel(false)
            conn.disconnect()
        }
    }
}

/**
 * Look for [origin]'s permission manifest (#122) the way its pages are
 * served: `freedom-manifest.json` at the root of the content the
 * interceptor serves the origin from — a raw bzz reference itself, or
 * the Swarm content a name currently points at (the answer the tab's
 * page on screen was served from, [pins], where there is one). Anything
 * not on Swarm is [ManifestDiscovery.Unsupported]; a name that doesn't
 * resolve for now is [ManifestDiscovery.Unresolved].
 *
 * The name is resolved once, and the manifest fetched from that very
 * answer. [timeoutMs] bounds the whole call — the endpoint settings
 * wait, name resolution and the fetch — from outside ([withinDeadline]),
 * so a slow resolver can't hold the origin's lock past it (#226 R1-F5).
 */
internal suspend fun discoverManifest(
    origin: String,
    pins: EnsDocumentPins?,
    timeoutMs: Long = MANIFEST_TIMEOUT_MS.toLong(),
): ManifestDiscovery {
    val root = VirtualOrigin.parseHostOfUrl(origin)
    if (root !is ContentRoot.Bzz && root !is ContentRoot.Ens) return ManifestDiscovery.Unsupported
    return withinDeadline(timeoutMs) { remainingMs ->
        Gateways.awaitExternalEndpointsBlocking()
        val served = Gateways.servedRootFor(root, pins)
            ?: return@withinDeadline ManifestDiscovery.Unresolved("the name didn't resolve")
        if (served !is ContentRoot.Bzz) return@withinDeadline ManifestDiscovery.Unsupported
        val url = Gateways.gatewayUrlFor(served, "/" + SwarmManifestFormat.FILE)
            ?: return@withinDeadline ManifestDiscovery.Unresolved("no gateway")
        val left = remainingMs()
        if (left <= 0) ManifestDiscovery.Unresolved("timed out") else fetchManifest(url, left.toInt())
    }
}

/** Blocking work for manifest discovery: runs off the caller's job, so a deadline can leave it behind. */
private val manifestDiscoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs the blocking [work] (given what's left of [timeoutMs]) and
 * answers [ManifestDiscovery.Unresolved] once [timeoutMs] has passed,
 * whether or not [work] has: a blocking call ignores cancellation, so
 * it runs in its own job, which is left to finish on its own.
 */
internal suspend fun withinDeadline(timeoutMs: Long, work: (remainingMs: () -> Long) -> ManifestDiscovery): ManifestDiscovery {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    val job = manifestDiscoveryScope.async {
        try {
            work { (deadline - System.nanoTime()) / 1_000_000 }
        } catch (e: RuntimeException) {
            ManifestDiscovery.Unresolved(e.javaClass.simpleName)
        }
    }
    return withTimeoutOrNull(timeoutMs) { job.await() } ?: run {
        job.cancel()
        ManifestDiscovery.Unresolved("timed out")
    }
}

/**
 * GET the manifest at [url] (a gateway URL), reading at most
 * [SwarmManifestFormat.MAX_BYTES] of it — the limit is enforced while
 * reading — within [timeoutMs] for the whole call.
 */
internal fun fetchManifest(url: String, timeoutMs: Int = MANIFEST_TIMEOUT_MS): ManifestDiscovery {
    val answer = try {
        GatewayHttp.requestAt(url, "GET", "", emptyMap(), null, timeoutMs, maxBytes = SwarmManifestFormat.MAX_BYTES)
    } catch (e: GatewayHttp.AnswerTooLarge) {
        return ManifestDiscovery.Invalid("manifest exceeds 8 KiB")
    } catch (e: IOException) {
        // A body that started and died is the transport's failure, not an invalid manifest.
        return ManifestDiscovery.Unresolved(e.javaClass.simpleName)
    }
    // HttpURLConnection ends a fixed-length body that the connection
    // dropped part-way without an error: short of its length, it's the
    // transport's failure too, not bytes to judge.
    val length = answer.headers["content-length"]?.trim()?.toLongOrNull()
    if (length != null && answer.body.size < length) return ManifestDiscovery.Unresolved("the answer was cut short")
    return manifestFromAnswer(answer.status, answer.body)
}

/** What a gateway's answer for the manifest means (profile §2.3): only a 404 is absence, only a 5xx is transient. */
internal fun manifestFromAnswer(status: Int, body: ByteArray): ManifestDiscovery = when {
    status == 404 -> ManifestDiscovery.Absent
    status in 400..499 -> ManifestDiscovery.Invalid("HTTP $status")
    status !in 200..299 -> ManifestDiscovery.Unresolved("HTTP $status")
    else -> try {
        val manifest = SwarmManifestFormat.parse(body)
        ManifestDiscovery.Found(manifest, SwarmManifestFormat.sha256Hex(body), SwarmManifestFormat.fingerprint(manifest))
    } catch (e: InvalidManifestException) {
        ManifestDiscovery.Invalid(e.message ?: "invalid manifest")
    }
}

private const val MANIFEST_TIMEOUT_MS = 30_000

/** One `window.swarm` request off the channel. */
internal data class SwarmRequest(val id: Long, val method: String, val params: JSONObject)

/** Parse `{"id": n, "method": "swarm_…", "params": {…}}`, or null if it isn't one. */
internal fun parseSwarmRequest(data: String?): SwarmRequest? {
    if (data == null || data.length > MAX_SWARM_REQUEST_CHARS) return null
    val json = try {
        JSONObject(data)
    } catch (e: Exception) {
        return null
    } catch (e: StackOverflowError) {
        return null
    }
    val id = (json.opt("id") as? Number)?.toLong() ?: return null
    val method = json.opt("method") as? String ?: return null
    if (method.length > 64) return null
    val params = when (val p = json.opt("params")) {
        null, JSONObject.NULL -> JSONObject()
        is JSONObject -> p
        else -> return null
    }
    return SwarmRequest(id, method, params)
}

/** Bigger than any valid request: 50 MB of files, base64-encoded, and their paths. */
private const val MAX_SWARM_REQUEST_CHARS = 72 * 1024 * 1024

/**
 * The page side of [SwarmProviders]: `window.swarm` with `request()`, one
 * wrapper per method (desktop's), and `on` / `removeListener` for events
 * (`connect`, `disconnect`). The channel object the platform puts on
 * `window` is taken off it before the page's own scripts run (#69), and
 * has a random name; subframes and non-http(s) documents get no provider.
 * The natives the script relies on are saved at document start, so a
 * page that later overrides them can't see or change what's sent.
 * Uploads, signing and prompts time out after five minutes, anything
 * else after one (desktop's) — until the request is approved
 * (`{"id", "approved": true}`): then its timer stops, and the page waits
 * for the result of the upload or signature it approved, which
 * [GatewayHttp]'s whole-request deadline bounds, rather than lose the
 * reference to something that got published.
 */
internal fun swarmProviderJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
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
  var U8 = w.Uint8Array, AB = w.ArrayBuffer, isView = AB.isView, btoa = w.btoa.bind(w);
  var fromCharCode = String.fromCharCode, BI = w.BigInt, isArray = Array.isArray;
  var apply = Function.prototype.apply, keys = Object.keys;
  var pending = new w.Map(), nextId = 0;
  var listeners = { connect: [], disconnect: [] };
  function b64(bytes) {
    var parts = [], CHUNK = 0x8000;
    for (var i = 0; i < bytes.length; i += CHUNK) {
      parts.push(apply.call(fromCharCode, null, bytes.subarray(i, i + CHUNK)));
    }
    return btoa(parts.join(''));
  }
  function encode(v, depth) {
    if (depth > 32) return null;
    if (typeof v === 'bigint') return { '${'$'}bigint': String(v) };
    if (v === null || typeof v !== 'object') return v;
    if (v instanceof AB) return { '${'$'}b64': b64(new U8(v)) };
    if (isView(v)) return { '${'$'}b64': b64(new U8(v.buffer, v.byteOffset, v.byteLength)) };
    if (isArray(v)) {
      var a = [];
      for (var i = 0; i < v.length; i++) a.push(encode(v[i], depth + 1));
      return a;
    }
    var o = {}, ks = keys(v);
    for (var j = 0; j < ks.length; j++) o[ks[j]] = encode(v[ks[j]], depth + 1);
    return o;
  }
  function decode(v) {
    if (v === null || typeof v !== 'object') return v;
    if (isArray(v)) { for (var i = 0; i < v.length; i++) v[i] = decode(v[i]); return v; }
    var ks = keys(v);
    if (ks.length === 1 && ks[0] === '${'$'}bigint' && typeof v['${'$'}bigint'] === 'string' && BI) return BI(v['${'$'}bigint']);
    for (var j = 0; j < ks.length; j++) v[ks[j]] = decode(v[ks[j]]);
    return v;
  }
  var LONG = { swarm_publishData: 1, swarm_publishFiles: 1, swarm_publishChunk: 1, swarm_createFeed: 1,
    swarm_updateFeed: 1, swarm_writeFeedEntry: 1, swarm_writeSingleOwnerChunk: 1, swarm_getSigningIdentity: 1,
    swarm_requestAccess: 1 };
  port.addEventListener('message', function (ev) {
    var msg;
    try { msg = parse(ev.data); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (typeof msg.event === 'string') {
      var hs = listeners[msg.event];
      if (!hs) return;
      hs.slice().forEach(function (h) { try { h(msg.data); } catch (e) {} });
      return;
    }
    var p = pending.get(msg.id);
    if (!p) return;
    if (msg.approved === true) { clearT(p.timer); return; }
    pending.delete(msg.id);
    clearT(p.timer);
    if (msg.error) {
      var err = new E(msg.error.message || 'Unknown error');
      err.code = msg.error.code;
      if (msg.error.data) err.data = msg.error.data;
      p.reject(err);
    } else {
      p.resolve(decode(msg.result));
    }
  });
  function request(method, params) {
    return new P(function (resolve, reject) {
      if (typeof method !== 'string' || !method) {
        var bad = new E('method is required');
        bad.code = -32602;
        reject(bad);
        return;
      }
      var id = ++nextId, body;
      try {
        body = stringify({ id: id, method: method, params: encode(params || {}, 0) });
      } catch (e) {
        var enc = new E('params could not be encoded');
        enc.code = -32602;
        reject(enc);
        return;
      }
      var timer = setT(function () {
        if (pending.delete(id)) {
          var t = new E('Request timed out');
          t.code = -32603;
          reject(t);
        }
      }, LONG[method] ? 300000 : 60000);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      try {
        send(body);
      } catch (e) {
        pending.delete(id);
        clearT(timer);
        reject(e);
      }
    });
  }
  function method(name) {
    return function (params) { return request(name, params); };
  }
  var swarm = {
    isFreedomBrowser: true,
    request: function (payload) {
      return request(payload && payload.method, payload && payload.params);
    },
    requestAccess: method('swarm_requestAccess'),
    getCapabilities: method('swarm_getCapabilities'),
    publishData: method('swarm_publishData'),
    publishFiles: method('swarm_publishFiles'),
    getUploadStatus: method('swarm_getUploadStatus'),
    createFeed: method('swarm_createFeed'),
    updateFeed: method('swarm_updateFeed'),
    writeFeedEntry: method('swarm_writeFeedEntry'),
    readFeedEntry: method('swarm_readFeedEntry'),
    listFeeds: method('swarm_listFeeds'),
    publishChunk: method('swarm_publishChunk'),
    readChunk: method('swarm_readChunk'),
    writeSingleOwnerChunk: method('swarm_writeSingleOwnerChunk'),
    readSingleOwnerChunk: method('swarm_readSingleOwnerChunk'),
    getSigningIdentity: method('swarm_getSigningIdentity'),
    getMessagingIdentity: method('swarm_getMessagingIdentity'),
    subscribe: method('swarm_subscribe'),
    unsubscribe: method('swarm_unsubscribe'),
    sendPss: method('swarm_sendPss'),
    sendGsoc: method('swarm_sendGsoc'),
    on: function (event, handler) {
      if (listeners[event] && typeof handler === 'function') listeners[event].push(handler);
      return swarm;
    },
    addListener: function (event, handler) { return swarm.on(event, handler); },
    removeListener: function (event, handler) {
      var hs = listeners[event];
      if (hs) {
        var i = hs.indexOf(handler);
        if (i > -1) hs.splice(i, 1);
      }
      return swarm;
    },
    removeAllListeners: function (event) {
      if (event && listeners[event]) listeners[event] = [];
      if (!event) { listeners.connect = []; listeners.disconnect = []; }
      return swarm;
    }
  };
  try {
    Object.defineProperty(w, 'swarm', { value: swarm, configurable: true, enumerable: false, writable: true });
  } catch (e) {}
})();
""".trimIndent()
}
