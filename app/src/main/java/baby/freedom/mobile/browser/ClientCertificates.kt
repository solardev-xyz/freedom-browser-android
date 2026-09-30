package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** What to do with a site's request for a TLS client certificate (#316). */
internal sealed interface ClientCertPlan {
    /** Send nothing, and remember nothing: a private tab ([ClientCertRequest.ignore]). */
    data object SendNone : ClientCertPlan

    /**
     * The user just said not to send one to this server: sent none
     * ([ClientCertRequest.ignore]). Only for the requests already waiting
     * when they said so ([ClientCertChoices.planFor]); a later one asks
     * again, once the browser starts a load that reaches the server
     * ([ClientCertificates.onBrowserLoad]).
     */
    data object Refuse : ClientCertPlan

    /** The user picked [alias] for this server this run. */
    data class Send(val alias: String) : ClientCertPlan

    /** Nothing decided yet: open the system chooser. */
    data object Ask : ClientCertPlan
}

/**
 * The user's answer per server (host and port) for this app run only
 * (#316). Nothing here is written anywhere, and *Clear cookies & site
 * data* empties it ([clear]).
 *
 * A picked certificate holds for the rest of the run. A refusal (Deny,
 * Back, or "no certificates" before one is installed) doesn't: it
 * answers the requests that were already waiting on that chooser, and
 * the next request — a reload, another tab — asks again. So installing
 * the certificate, or taking back an accidental Deny, only needs a reload
 * (which also empties WebView's own record of the refusal,
 * [ClientCertificates.onBrowserLoad]).
 *
 * A private tab never gets an answer from here: it sends no certificate
 * and is never asked, whatever a normal tab decided for the same server.
 */
internal class ClientCertChoices {
    private sealed interface Choice {
        data class Picked(val alias: String) : Choice
        /** Refused; holds for requests with a [ticket] up to [upTo]. */
        data class Declined(val upTo: Long) : Choice
    }

    private val choices = HashMap<String, Choice>()

    /** The last [ticket] handed out. */
    private var tickets = 0L

    /** A number for a request as it arrives: later requests get larger ones. */
    fun ticket(): Long = ++tickets

    /**
     * Bumped by [clear]: an answer from a chooser opened before the clear
     * is used for its own request but not remembered.
     */
    var generation = 0
        private set

    /**
     * What to answer a request that got [ticket] on arrival. A refusal
     * only answers requests that arrived before it was given (queued
     * behind its chooser); by default, a request arriving now asks.
     */
    fun planFor(private: Boolean, host: String, port: Int, ticket: Long = Long.MAX_VALUE): ClientCertPlan {
        if (private) return ClientCertPlan.SendNone
        return when (val c = choices[key(host, port)]) {
            is Choice.Picked -> ClientCertPlan.Send(c.alias)
            is Choice.Declined -> if (ticket <= c.upTo) ClientCertPlan.Refuse else ClientCertPlan.Ask
            null -> ClientCertPlan.Ask
        }
    }

    /** Remember the chooser's answer ([alias] `null`: none), if nothing was cleared since [asOf]. */
    fun answered(host: String, port: Int, alias: String?, asOf: Int) {
        if (asOf != generation) return
        choices[key(host, port)] = if (alias == null) Choice.Declined(upTo = tickets) else Choice.Picked(alias)
    }

    /** The picked certificate can't be read any more (removed from the device): ask again next time. */
    fun forget(host: String, port: Int) {
        choices.remove(key(host, port))
    }

    fun clear() {
        choices.clear()
        generation++
    }

    private fun key(host: String, port: Int) = "${host.lowercase()}:$port"
}

/**
 * `WebViewClient.onReceivedClientCertRequest` for every tab (#316): a
 * site asking for a TLS client certificate (an enterprise portal, a
 * mutual-TLS server). WebView's default cancels it without a word.
 *
 * - A private tab sends none and doesn't ask.
 * - A normal tab asks with the system KeyChain chooser, which names the
 *   server (host and port). Picking a certificate sends it, and the pick
 *   holds for that server for the rest of the app run
 *   ([ClientCertChoices]). Dismissing the chooser sends none, to that
 *   request and the ones queued behind it; the next one asks again.
 * - The chooser only opens over the page that asked: a request from a
 *   background tab, from behind a full-screen panel, while Android's
 *   permission dialog is up or while the app isn't in front waits until
 *   its tab is on screen ([SitePermissionBroker.onScreenTab]). A tab
 *   closed meanwhile — or while its chooser is up — sends none, and a
 *   pick made in that chooser isn't remembered ([chooseInTurn]).
 * - A chooser that closes without answering (its process killed, say)
 *   sends none once the browser is back in front ([awaitChooserAnswer]),
 *   so it can't hold up every later request.
 *
 * Main thread only, like the WebView callbacks that drive it.
 */
object ClientCertificates {
    private const val TAG = "ClientCertificates"

    private val scope = MainScope()
    private val choices = ClientCertChoices()

    /** One chooser at a time; a second request for the same server finds the first one's answer. */
    private val chooserLock = Mutex()

    private class Pending(val tabId: Long) {
        val withdrawn = MutableStateFlow(false)
    }

    private val pending = mutableListOf<Pending>()

    /** The private tabs built so far and not closed ([onPrivateTab]). */
    private val privateTabs = HashSet<Long>()

    /** WebView's own table may hold a certificate a normal tab picked ([send]). */
    private var tableHoldsCertificate = false

    /** WebView's own table may hold a "send none" for some server ([sendNone]). */
    private var tableHoldsRefusal = false

    /**
     * Long enough for the handshake a `proceed` answered to finish
     * before the table is emptied under it — one round trip, normally.
     */
    private const val TABLE_EMPTY_DELAY_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private val emptyTable = Runnable { if (tableHoldsCertificate) emptyWebViewTable() }

    /**
     * Installed by [ClientCertificateBridge]: opens the system chooser for
     * [request] and returns the picked alias, or `null` if the user
     * dismissed it. `null` while no screen is composed to open it from.
     */
    internal var choose: (suspend (ClientCertRequest) -> String?)? = null

    fun onRequest(context: Context, tab: BrowserState, request: ClientCertRequest, broker: SitePermissionBroker) {
        val appContext = context.applicationContext
        val host = request.host
        val port = request.port
        val ticket = choices.ticket()
        when (val plan = choices.planFor(tab.private, host, port, ticket)) {
            ClientCertPlan.SendNone -> {
                Log.i(TAG, "private tab: no client certificate for $host:$port")
                sendNone(request)
            }
            ClientCertPlan.Refuse -> sendNone(request)
            is ClientCertPlan.Send -> {
                val asOf = choices.generation
                scope.launch { send(appContext, request, plan.alias, asOf) }
            }
            ClientCertPlan.Ask -> scope.launch { ask(appContext, tab.id, ticket, request, broker) }
        }
    }

    /** Tab [tabId] closed: what it still had waiting for the chooser sends none. */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId)
        privateTabs -= tabId
    }

    /**
     * Tab [tabId]'s page went with its WebView (a renderer gone, an
     * Activity relaunch) while the tab stays: its requests send none,
     * and an answer given in a chooser still open for one of them is
     * neither sent nor remembered.
     */
    fun withdraw(tabId: Long) {
        for (p in pending) if (p.tabId == tabId) p.withdrawn.value = true
    }

    /** Whether a private tab is open ([onPrivateTab], [onTabClosed]). */
    internal val privateTabOpen: Boolean get() = privateTabs.isNotEmpty()

    /**
     * Part of *Clear cookies & site data*: forget every answer, ours and
     * WebView's own (which would otherwise go on re-sending a picked
     * certificate, or refusing, for each server without asking).
     */
    fun clear() {
        choices.clear()
        emptyWebViewTable()
    }

    /**
     * The browser is starting a load in some tab (the address bar, a new
     * tab, Reload, Back/Forward — [PageWebView]'s own loads, not a link
     * the page follows): if WebView may be holding a "send none" from an
     * earlier [sendNone], empty its table first, so the load's server
     * can ask again.
     *
     * `ignore()` isn't remembered by WebView's Java side, but Chromium's
     * network stack still files the empty answer per host and port, for
     * the whole process, and drops it only when the server then refuses
     * the handshake. A server that merely *requests* a certificate
     * (optional client auth) lets the handshake through, so without this
     * it would never ask again this run: a Deny, a "no certificates"
     * before one is installed, or a private tab's answer would stick
     * until *Clear cookies & site data*. Emptying it on every refusal
     * instead (as after `proceed`, [send]) would have such a server ask
     * again on each new connection a page opens, a chooser every few
     * seconds for a page that polls; here it asks again only when the
     * user loads something.
     */
    fun onBrowserLoad() {
        if (tableHoldsRefusal) emptyWebViewTable()
    }

    /** Whether the next [onBrowserLoad] empties WebView's table. */
    internal val emptiesTableOnLoad: Boolean get() = tableHoldsRefusal

    /** Answers [request] with no certificate ([onBrowserLoad]). */
    private fun sendNone(request: ClientCertRequest) {
        answer(request) { ignore() }
        refused()
    }

    /** WebView filed a "send none" in its table ([onBrowserLoad]). */
    internal fun refused() {
        tableHoldsRefusal = true
    }

    private suspend fun ask(
        context: Context,
        tabId: Long,
        ticket: Long,
        request: ClientCertRequest,
        broker: SitePermissionBroker,
    ) {
        val entry = Pending(tabId)
        pending += entry
        try {
            while (true) {
                if (!awaitChooserTurn(broker.onScreenTab, broker.androidDialogUp, tabId, entry.withdrawn)) {
                    sendNone(request)
                    return
                }
                // At or before the chooser opens: a clear after this
                // means the answer isn't WebView's to keep either ([send]).
                val asOf = choices.generation
                val plan = chooseInTurn(
                    choices, chooserLock, request.host, request.port, tabId,
                    broker.onScreenTab, broker.androidDialogUp, entry.withdrawn,
                    ticket,
                    choose?.let { open -> { open(request) } },
                ) ?: continue
                when (plan) {
                    ClientCertPlan.SendNone, ClientCertPlan.Ask, ClientCertPlan.Refuse -> sendNone(request)
                    is ClientCertPlan.Send -> send(context, request, plan.alias, asOf)
                }
                return
            }
        } finally {
            pending -= entry
        }
    }

    /**
     * Sends [alias]'s certificate to [request]. [asOf] is the
     * [ClientCertChoices.generation] the pick was made under: if site
     * data was cleared since, the certificate still answers this request,
     * but WebView's own table is emptied after it too, so it isn't
     * handed on silently to the next connection.
     */
    private suspend fun send(context: Context, request: ClientCertRequest, alias: String, asOf: Int) {
        val material: Pair<PrivateKey, Array<X509Certificate>>? = withContext(Dispatchers.IO) {
            try {
                val key = KeyChain.getPrivateKey(context, alias)
                val chain = KeyChain.getCertificateChain(context, alias)
                if (key != null && !chain.isNullOrEmpty()) key to chain else null
            } catch (e: Exception) {
                Log.w(TAG, "client certificate $alias unreadable", e)
                null
            }
        }
        if (material == null) {
            // Removed from the device since it was picked: send none now,
            // and ask again next time.
            choices.forget(request.host, request.port)
            sendNone(request)
            return
        }
        answer(request) { proceed(material.first, material.second) }
        // `proceed` also files the certificate in WebView's own table for
        // this host and port, and that table is one for the whole
        // process, private profile included: a private tab's next
        // connection there would be handed the certificate without ever
        // reaching [onRequest]. So the table is emptied before a private
        // tab can use it ([onPrivateTab]) and, while one is open, shortly
        // after each `proceed` — not at once: emptying it restarts every
        // handshake still in flight, this one included, which would ask
        // again, proceed again and empty it again until WebView gives up
        // (`ERR_TOO_MANY_RETRIES`). Normal tabs asking again after it's
        // emptied get the same answer from [choices].
        //
        // The same goes for a pick made, or read, while site data was
        // being cleared: its request gets it, but WebView must not keep it.
        tableHoldsCertificate = true
        if (emptiesTableAfterProceed(asOf)) {
            main.removeCallbacks(emptyTable)
            main.postDelayed(emptyTable, TABLE_EMPTY_DELAY_MS)
        }
    }

    /**
     * Whether WebView's table is emptied shortly after a `proceed` for a
     * pick made under generation [asOf] ([send]): while a private tab is
     * open, or when site data was cleared since the pick.
     */
    internal fun emptiesTableAfterProceed(asOf: Int): Boolean =
        privateTabs.isNotEmpty() || asOf != choices.generation

    /** The [ClientCertChoices.generation] answers are made under now. */
    internal val generation: Int get() = choices.generation

    /**
     * Private tab [tabId] is about to be built: empty WebView's table of
     * picked certificates first if it holds one, so the tab's first
     * connection can't be handed a normal tab's pick.
     */
    fun onPrivateTab(tabId: Long) {
        privateTabs += tabId
        if (tableHoldsCertificate) emptyWebViewTable()
    }

    private fun emptyWebViewTable() {
        main.removeCallbacks(emptyTable)
        tableHoldsCertificate = false
        tableHoldsRefusal = false
        runCatching { WebView.clearClientCertPreferences(null) }
    }

    /** WebView throws if a request is answered twice; a late second answer is dropped. */
    private inline fun answer(request: ClientCertRequest, how: ClientCertRequest.() -> Unit) {
        try {
            request.how()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "client certificate request already answered", e)
        }
    }
}

/**
 * One turn at the chooser for a request from tab [tabId] to [host]:[port]
 * (which got [ticket] on arrival), under [lock]. Returns what to answer, or `null` to wait for the tab to
 * be on screen again.
 *
 * [withdrawn] is checked again once [open] returns: the tab may have been
 * closed while its chooser was up (from the tab switcher, say), and then
 * the pick is neither remembered nor sent to a request whose page is gone.
 * Withdrawing also stops waiting for [open] at once, so a chooser that
 * never answers can't keep [lock] from every later request; [open]
 * itself gives up once the chooser is gone ([awaitChooserAnswer]).
 */
internal suspend fun chooseInTurn(
    choices: ClientCertChoices,
    lock: Mutex,
    host: String,
    port: Int,
    tabId: Long,
    onScreenTab: StateFlow<Long?>,
    androidDialogUp: StateFlow<Boolean>,
    withdrawn: StateFlow<Boolean>,
    ticket: Long = Long.MAX_VALUE,
    open: (suspend () -> String?)?,
): ClientCertPlan? = lock.withLock turn@{
    if (withdrawn.value) return@turn ClientCertPlan.SendNone
    // Answered for this server while this request queued ([ticket]: when
    // it arrived, so a refusal given since answers it too).
    val now = choices.planFor(false, host, port, ticket)
    if (now != ClientCertPlan.Ask) return@turn now
    // Switched away while queued behind another chooser: wait again.
    if (onScreenTab.value != tabId || androidDialogUp.value) return@turn null
    if (open == null) return@turn ClientCertPlan.SendNone
    val asOf = choices.generation
    val alias = try {
        coroutineScope {
            val gone = async { withdrawn.first { it } }
            val opening = async { open() }
            select<String?> {
                opening.onAwait { gone.cancel(); it }
                gone.onAwait { opening.cancel(); null }
            }
        }
    } catch (e: Exception) {
        Log.w("ClientCertificates", "client certificate chooser failed", e)
        return@turn ClientCertPlan.SendNone
    }
    if (withdrawn.value) return@turn ClientCertPlan.SendNone
    choices.answered(host, port, alias, asOf)
    if (alias == null) ClientCertPlan.Refuse else ClientCertPlan.Send(alias)
}

/**
 * Suspends until tab [tabId]'s page is on screen ([onScreenTab]) with no
 * Android permission dialog up ([androidDialogUp]) and returns `true`,
 * or returns `false` as soon as the request is [withdrawn].
 */
internal suspend fun awaitChooserTurn(
    onScreenTab: StateFlow<Long?>,
    androidDialogUp: StateFlow<Boolean>,
    tabId: Long,
    withdrawn: StateFlow<Boolean>,
): Boolean = combine(onScreenTab, androidDialogUp, withdrawn) { shown, dialog, gone ->
    when {
        gone -> false
        shown == tabId && !dialog -> true
        else -> null
    }
}.filterNotNull().first()

/** The server a chooser names: `https://host:port`, an IPv6 literal in brackets. */
internal fun clientCertServerUri(host: String, port: Int): Uri {
    val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return Uri.Builder().scheme("https").encodedAuthority("$h:$port").build()
}

/** The chooser closed without answering (its process killed, the task swiped away). */
internal class ChooserGone : Exception("the certificate chooser closed without an answer")

/**
 * How long, after our screen is back in front, a chooser's answer may
 * still take to arrive: KeyChain grants the alias and calls back from
 * its own process, which can land just after the chooser has closed.
 */
internal const val CHOOSER_ANSWER_GRACE_MS = 5_000L

/**
 * Waits for the KeyChain chooser's answer [picked]. KeyChain only calls
 * back when the user picks or dismisses; if `KeyChainActivity` is
 * destroyed any other way (its process killed, say) no callback ever
 * comes. So once our screen resumes again ([resumes] moves past
 * [launchedAt], the value read before the chooser opened) the answer
 * gets [graceMs] to arrive, and then this throws [ChooserGone]: the
 * request sends none, nothing is remembered, and the chooser lock is
 * free for the next request.
 */
internal suspend fun awaitChooserAnswer(
    picked: Deferred<String?>,
    resumes: StateFlow<Int>,
    launchedAt: Int,
    graceMs: Long = CHOOSER_ANSWER_GRACE_MS,
): String? = coroutineScope {
    val gone = async {
        resumes.first { it != launchedAt }
        delay(graceMs)
    }
    select {
        picked.onAwait { gone.cancel(); it }
        gone.onAwait { throw ChooserGone() }
    }
}

/**
 * Counts every time a browser screen resumes; a chooser covering it
 * keeps it paused ([awaitChooserAnswer]). One count for the process, so
 * a screen rebuilt behind the chooser (a rotation) still counts.
 */
private val browserResumes = MutableStateFlow(0)

/** Lets [ClientCertificates] open the system chooser from this screen's Activity. */
@Composable
fun ClientCertificateBridge() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) browserResumes.value++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val activity: Activity? = context.findActivity()
        val open: suspend (ClientCertRequest) -> String? = { request ->
            val picked = CompletableDeferred<String?>()
            val a = activity ?: throw IllegalStateException("no activity")
            val launchedAt = browserResumes.value
            KeyChain.choosePrivateKeyAlias(
                a,
                { alias -> picked.complete(alias) },
                request.keyTypes,
                request.principals,
                clientCertServerUri(request.host, request.port),
                null,
            )
            awaitChooserAnswer(picked, browserResumes, launchedAt)
        }
        ClientCertificates.choose = open
        onDispose {
            if (ClientCertificates.choose === open) ClientCertificates.choose = null
        }
    }
}
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
