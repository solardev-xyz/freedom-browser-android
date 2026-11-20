package baby.freedom.mobile.agent

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.browser.BrowserState
import baby.freedom.mobile.browser.ErrorPage
import baby.freedom.mobile.browser.JsDialogKind
import baby.freedom.mobile.browser.PageWebView
import baby.freedom.mobile.browser.TabsState
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A tab as the agent's tools see it: the app's own record, never the page's word. */
internal data class AgentTab(
    val id: Long,
    /** The committed page's address as the address bar shows it (`name.eth/…` for a dweb page). */
    val url: String,
    val title: String,
    val loading: Boolean,
    val private: Boolean,
    /** Home, an error page or anything else of the app's own: never read or touched (desktop's S5). */
    val internal: Boolean,
    /** Documents this tab has committed: an approval is bound to one. */
    val document: Int,
    val active: Boolean,
) {
    /** What the approval card names: the committed page's site. */
    val site: String get() = siteOf(url)

    companion object {
        fun siteOf(url: String): String {
            if (url.isBlank()) return ""
            val withScheme = if ("://" in url) url else "https://$url"
            return try {
                URI(withScheme).host?.takeIf { it.isNotEmpty() } ?: url.substringBefore('/')
            } catch (e: Exception) {
                url.substringBefore('/')
            }.removePrefix("www.")
        }
    }
}

/** A JavaScript dialog the page in a tab is waiting on. */
internal data class AgentDialog(val kind: String, val message: String, val defaultValue: String?, val url: String?)

/**
 * What the browser lends the agent's tools (#575), installed by
 * [AgentBrowserBridge] while the browser's WebView host is up. Everything
 * runs on the main thread (the tools switch to it).
 */
internal interface AgentBrowserHost {
    fun tabs(): List<AgentTab>
    fun tab(id: Long): AgentTab?

    /** A new tab behind the one on screen, going to [url]; its id. */
    fun openTab(url: String): Long
    fun navigate(id: Long, url: String)
    fun close(id: Long)
    fun stop(id: Long)

    /** Runs [script] in [id]'s page; its JSON result, or null with no WebView or past [timeoutMs]. */
    suspend fun evaluate(id: Long, script: String, timeoutMs: Long = 5_000): String?

    /**
     * Puts [id] on screen for an input the user just approved: makes it
     * the active tab and closes the agent's chat and the other plain
     * panels over the page. True once the page is on screen, uncovered,
     * with the app in front; false if it can't be (a wallet page, say).
     */
    suspend fun bringToFront(id: Long): Boolean

    /** A tap at ([x], [y]) in the WebView's own pixels. */
    suspend fun tap(id: Long, x: Float, y: Float): Boolean

    /** A slow drag by ([dx], [dy]) pixels from ([x], [y]), with no fling at the end. */
    suspend fun drag(id: Long, x: Float, y: Float, dx: Float, dy: Float): Boolean

    /** Presses and releases [keyCode] in the focused element. */
    fun key(id: Long, keyCode: Int): Boolean

    /** [text] into the focused field through the WebView's input connection. */
    suspend fun commitText(id: Long, text: String): Boolean

    /** The JavaScript dialog [id]'s page is waiting on, if any. */
    fun dialog(id: Long): AgentDialog?

    /** Answers that dialog; false if it's gone. */
    fun answerDialog(id: Long, dialog: AgentDialog, accept: Boolean, promptText: String?): Boolean
}

/** Where the tools reach the browser: null while there's no WebView host (no Activity). */
internal object AgentBrowser {
    @Volatile
    var host: AgentBrowserHost? = null

    /**
     * Installed by the browser screen: closes the agent's chat and the
     * plain panels and shows tab [id]. Null before the screen composes.
     */
    @Volatile
    var showTab: ((Long) -> Unit)? = null

    /**
     * The chat whose approved input last took the screen from the chat
     * page ([BrowserToolset]): the page opens on it again next time,
     * instead of on a new chat. Taken once.
     */
    private val reopen = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun chatTookScreen(sessionId: String) = reopen.set(sessionId)

    fun takeChatToReopen(): String? = reopen.getAndSet(null)

    /** The page script ([PAGE_SCRIPT_ASSET]), loaded once. */
    @Volatile
    var pageScript: String? = null

    const val PAGE_SCRIPT_ASSET = "agent/page.js"
}

/**
 * Lends the browser's tabs to Freedom Agent's tools ([AgentBrowser.host])
 * from inside the WebView host, which alone knows which WebView backs a
 * tab. Gone with the host, on a relaunch and a real destroy alike: every
 * approval still waiting is withdrawn then, since the page it was for
 * goes with its WebView.
 */
@Composable
internal fun AgentBrowserBridge(tabs: TabsState, covered: Boolean, webViewFor: (Long) -> WebView?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val coveredNow by rememberUpdatedState(covered)
    val lookup by rememberUpdatedState(webViewFor)
    remember(context) {
        if (AgentBrowser.pageScript == null) {
            AgentBrowser.pageScript = runCatching {
                context.assets.open(AgentBrowser.PAGE_SCRIPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }
    DisposableEffect(tabs) {
        val host = BrowserTabsHost(tabs, lifecycle, { coveredNow }, { lookup(it) })
        AgentBrowser.host = host
        onDispose {
            if (AgentBrowser.host === host) AgentBrowser.host = null
            AgentApprovals.withdrawAll()
        }
    }
}

private class BrowserTabsHost(
    private val tabs: TabsState,
    private val lifecycle: Lifecycle,
    private val covered: () -> Boolean,
    private val webViewFor: (Long) -> WebView?,
) : AgentBrowserHost {
    private fun state(id: Long): BrowserState? = tabs.tabs.firstOrNull { it.id == id }

    private fun BrowserState.toAgentTab() = AgentTab(
        id = id,
        url = url,
        title = title,
        loading = progress >= 0 || resolving,
        private = private,
        internal = isHome || showsErrorPage || rendererGone != null || !agentReadable(url),
        document = committedDocuments,
        active = this === tabs.active,
    )

    override fun tabs(): List<AgentTab> = tabs.tabs.map { it.toAgentTab() }

    override fun tab(id: Long): AgentTab? = state(id)?.toAgentTab()

    override fun openTab(url: String): Long {
        val tab = tabs.newTab(url = null, activate = false)
        tabs.requestSubmit?.invoke(tab, url)
        return tab.id
    }

    override fun navigate(id: Long, url: String) {
        val tab = state(id) ?: return
        tabs.requestSubmit?.invoke(tab, url)
    }

    override fun close(id: Long) {
        val index = tabs.tabs.indexOfFirst { it.id == id }
        if (index >= 0) tabs.closeTab(index)
    }

    override fun stop(id: Long) {
        state(id)?.let { tabs.stopLoading?.invoke(it) }
    }

    override suspend fun evaluate(id: Long, script: String, timeoutMs: Long): String? {
        val wv = webViewFor(id) ?: return null
        if ((wv as? PageWebView)?.destroyed == true) return null
        // A page blocked on a dialog never answers: the deadline is the way out.
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) }
            }
        }
    }

    private fun onScreen(id: Long): Boolean {
        val wv = webViewFor(id) ?: return false
        return tabs.active.id == id && !covered() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            wv.isAttachedToWindow && wv.isShown && wv.width > 0 && wv.height > 0
    }

    override suspend fun bringToFront(id: Long): Boolean {
        if (state(id) == null) return false
        if (!onScreen(id)) AgentBrowser.showTab?.invoke(id)
        // Laid out and drawn once more, so the page's coordinates are the ones on screen.
        return withTimeoutOrNull(3_000) {
            while (!onScreen(id)) delay(50)
            delay(250)
            onScreen(id)
        } == true
    }

    private fun page(id: Long): PageWebView? = (webViewFor(id) as? PageWebView)?.takeIf { onScreen(id) }

    override suspend fun tap(id: Long, x: Float, y: Float): Boolean {
        val wv = page(id) ?: return false
        if (x < 0 || y < 0 || x >= wv.width || y >= wv.height) return false
        val down = SystemClock.uptimeMillis()
        wv.agentTouch(motion(down, down, MotionEvent.ACTION_DOWN, x, y))
        delay(60)
        wv.agentTouch(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
        return true
    }

    override suspend fun drag(id: Long, x: Float, y: Float, dx: Float, dy: Float): Boolean {
        val wv = page(id) ?: return false
        val down = SystemClock.uptimeMillis()
        wv.agentTouch(motion(down, down, MotionEvent.ACTION_DOWN, x, y))
        val steps = 24
        for (i in 1..steps) {
            delay(16)
            val f = i.toFloat() / steps
            wv.agentTouch(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + dx * f, y + dy * f))
        }
        // Held still before the lift, so the page doesn't fling on past the drag.
        delay(180)
        wv.agentTouch(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + dx, y + dy))
        delay(60)
        wv.agentTouch(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x + dx, y + dy))
        return true
    }

    private fun motion(down: Long, at: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(down, at, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }

    override fun key(id: Long, keyCode: Int): Boolean {
        val wv = page(id) ?: return false
        if (!wv.hasFocus()) wv.requestFocus()
        val now = SystemClock.uptimeMillis()
        wv.agentKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        wv.agentKey(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    override suspend fun commitText(id: Long, text: String): Boolean {
        val wv = page(id) ?: return false
        if (!wv.hasFocus()) wv.requestFocus()
        val ic = wv.agentInputConnection(EditorInfo()) ?: return false
        val handler = ic.handler
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val commit = Runnable {
            done.complete(
                runCatching {
                    ic.beginBatchEdit()
                    val ok = ic.commitText(text, 1)
                    ic.endBatchEdit()
                    ok
                }.getOrDefault(false),
            )
        }
        if (handler == null) commit.run() else handler.post(commit)
        return withTimeoutOrNull(3_000) { done.await() } == true
    }

    override fun dialog(id: Long): AgentDialog? {
        val request = state(id)?.jsDialog?.takeIf { !it.answered } ?: return null
        return AgentDialog(
            kind = when (request.kind) {
                JsDialogKind.ALERT -> "alert"
                JsDialogKind.CONFIRM -> "confirm"
                JsDialogKind.PROMPT -> "prompt"
                JsDialogKind.BEFORE_UNLOAD -> "beforeunload"
            },
            message = request.message.orEmpty(),
            defaultValue = request.defaultValue,
            url = request.url,
        )
    }

    override fun answerDialog(id: Long, dialog: AgentDialog, accept: Boolean, promptText: String?): Boolean {
        val request = state(id)?.jsDialog?.takeIf { !it.answered } ?: return false
        // Only the dialog the approval named.
        if (dialog(id) != dialog) return false
        if (accept) request.confirm(promptText) else request.cancel()
        return true
    }
}

/** Schemes whose pages the agent may read and act in (desktop's navigation set, as the WebView commits them). */
internal fun agentReadable(url: String): Boolean {
    if (url.isBlank() || ErrorPage.isErrorPage(url)) return false
    val scheme = url.substringBefore("://", "").lowercase()
    // A bare `name.eth/…` is how the address bar shows a dweb page.
    return scheme.isEmpty() || scheme in setOf("http", "https", "bzz", "ipfs", "ipns", "ens", "rad")
}

/** Runs [block] on the main thread. */
internal suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

/** A JSON object from what `evaluateJavascript` handed back, or null. */
internal fun parseResult(raw: String?): JSONObject? {
    if (raw == null || raw == "null") return null
    return AgentWire.parseObject(raw)
}
