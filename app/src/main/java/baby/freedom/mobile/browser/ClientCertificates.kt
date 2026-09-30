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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** What to do with a site's request for a TLS client certificate (#316). */
internal sealed interface ClientCertPlan {
    /** Send nothing, and remember nothing: a private tab ([ClientCertRequest.ignore]). */
    data object SendNone : ClientCertPlan

    /** The user said not to send one to this server this run ([ClientCertRequest.cancel]). */
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
 * A private tab never gets an answer from here: it sends no certificate
 * and is never asked, whatever a normal tab decided for the same server.
 */
internal class ClientCertChoices {
    private sealed interface Choice {
        data class Picked(val alias: String) : Choice
        data object Declined : Choice
    }

    private val choices = HashMap<String, Choice>()

    /**
     * Bumped by [clear]: an answer from a chooser opened before the clear
     * is used for its own request but not remembered.
     */
    var generation = 0
        private set

    fun planFor(private: Boolean, host: String, port: Int): ClientCertPlan {
        if (private) return ClientCertPlan.SendNone
        return when (val c = choices[key(host, port)]) {
            is Choice.Picked -> ClientCertPlan.Send(c.alias)
            Choice.Declined -> ClientCertPlan.Refuse
            null -> ClientCertPlan.Ask
        }
    }

    /** Remember the chooser's answer ([alias] `null`: none), if nothing was cleared since [asOf]. */
    fun answered(host: String, port: Int, alias: String?, asOf: Int) {
        if (asOf != generation) return
        choices[key(host, port)] = if (alias == null) Choice.Declined else Choice.Picked(alias)
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
 *   server (host and port). Picking a certificate sends it; dismissing
 *   the chooser sends none. The answer holds for that server for the
 *   rest of the app run ([ClientCertChoices]).
 * - The chooser only opens over the page that asked: a request from a
 *   background tab, from behind a full-screen panel, while Android's
 *   permission dialog is up or while the app isn't in front waits until
 *   its tab is on screen ([SitePermissionBroker.onScreenTab]). A tab
 *   closed meanwhile sends none.
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
        val withdrawn = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private val pending = mutableListOf<Pending>()

    /** The private tabs built so far and not closed ([onPrivateTab]). */
    private val privateTabs = HashSet<Long>()

    /** WebView's own table may hold a certificate a normal tab picked ([send]). */
    private var tableHoldsCertificate = false

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
        when (val plan = choices.planFor(tab.private, host, port)) {
            ClientCertPlan.SendNone -> {
                Log.i(TAG, "private tab: no client certificate for $host:$port")
                answer(request) { ignore() }
            }
            ClientCertPlan.Refuse -> answer(request) { cancel() }
            is ClientCertPlan.Send -> scope.launch { send(appContext, request, plan.alias) }
            ClientCertPlan.Ask -> scope.launch { ask(appContext, tab.id, request, broker) }
        }
    }

    /** Tab [tabId] closed: what it still had waiting for the chooser sends none. */
    fun onTabClosed(tabId: Long) {
        for (p in pending) if (p.tabId == tabId) p.withdrawn.value = true
        privateTabs -= tabId
    }

    /**
     * Part of *Clear cookies & site data*: forget every answer, ours and
     * WebView's own (which would otherwise go on re-sending a picked
     * certificate, or refusing, for each server without asking).
     */
    fun clear() {
        choices.clear()
        emptyWebViewTable()
    }

    private suspend fun ask(context: Context, tabId: Long, request: ClientCertRequest, broker: SitePermissionBroker) {
        val entry = Pending(tabId)
        pending += entry
        try {
            while (true) {
                if (!awaitChooserTurn(broker.onScreenTab, broker.androidDialogUp, tabId, entry.withdrawn)) {
                    answer(request) { ignore() }
                    return
                }
                val plan = chooserLock.withLock turn@{
                    if (entry.withdrawn.value) return@turn ClientCertPlan.SendNone
                    // Answered for this server while this request queued.
                    val now = choices.planFor(false, request.host, request.port)
                    if (now != ClientCertPlan.Ask) return@turn now
                    // Switched away while queued behind another chooser: wait again.
                    if (broker.onScreenTab.value != tabId || broker.androidDialogUp.value) return@turn null
                    val open = choose ?: return@turn ClientCertPlan.SendNone
                    val asOf = choices.generation
                    val alias = try {
                        open(request)
                    } catch (e: Exception) {
                        Log.w(TAG, "client certificate chooser failed", e)
                        return@turn ClientCertPlan.SendNone
                    }
                    choices.answered(request.host, request.port, alias, asOf)
                    if (alias == null) ClientCertPlan.Refuse else ClientCertPlan.Send(alias)
                } ?: continue
                when (plan) {
                    ClientCertPlan.SendNone, ClientCertPlan.Ask -> answer(request) { ignore() }
                    ClientCertPlan.Refuse -> answer(request) { cancel() }
                    is ClientCertPlan.Send -> send(context, request, plan.alias)
                }
                return
            }
        } finally {
            pending -= entry
        }
    }

    private suspend fun send(context: Context, request: ClientCertRequest, alias: String) {
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
            answer(request) { ignore() }
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
        tableHoldsCertificate = true
        if (privateTabs.isNotEmpty()) {
            main.removeCallbacks(emptyTable)
            main.postDelayed(emptyTable, TABLE_EMPTY_DELAY_MS)
        }
    }

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

/** Lets [ClientCertificates] open the system chooser from this screen's Activity. */
@Composable
fun ClientCertificateBridge() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity: Activity? = context.findActivity()
        val open: suspend (ClientCertRequest) -> String? = { request ->
            val picked = CompletableDeferred<String?>()
            val a = activity ?: throw IllegalStateException("no activity")
            KeyChain.choosePrivateKeyAlias(
                a,
                { alias -> picked.complete(alias) },
                request.keyTypes,
                request.principals,
                clientCertServerUri(request.host, request.port),
                null,
            )
            picked.await()
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
