package baby.freedom.mobile.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream

/**
 * The pop-up blocker (#261), after the desktop browser's (freedom-browser
 * #442, `src/main/popup-blocker.js`).
 *
 * Chromium's own blocker (`javaScriptCanOpenWindowsAutomatically =
 * false`) refuses a script's window without telling anyone, so a page's
 * pop-up opened after an `await` — an OAuth or payment window — simply
 * never appeared. Instead, every page window now reaches
 * `WebChromeClient.onCreateWindow`, and the app applies Chromium's rule
 * itself ([popupOpens]):
 *
 *   user gesture (Chromium's `isUserGesture`) → opens, as before: a new
 *                                               tab with its opener
 *   site holds the `popups` allow            → opens the same way
 *   anything else                            → blocked, and named in a
 *                                               notice on its tab
 *
 * Chromium consumes the page's transient user activation for each
 * window it creates, so one tap still opens at most one tab: a second
 * `window.open()` in the same click arrives with `isUserGesture` false
 * and is blocked like any other.
 *
 * A blocked window is handed to a hidden, throwaway WebView
 * ([PopupProbe]) instead of a tab, only to learn where it was going: its
 * first navigation is read and cancelled, nothing is fetched, no script
 * runs in it, and it is destroyed — the page's handle on it reads
 * `closed` from then on. The notice ([BlockedPopupNotice]) offers
 * **Open** (the address in a new tab, without `window.opener`: the
 * window the page held is gone) and **Always allow pop-ups on this
 * site**, a site permission ([SitePermission.POPUPS]) listed and
 * revocable in Settings like the others — session-only in a private tab.
 */

/** A pop-up [BlockedPopups] holds: where it was going, if the probe found out. */
data class BlockedPopup(
    val id: Long,
    /**
     * The window's address, or null when it isn't known: still being
     * read ([pending]), or the window never navigated (a
     * `window.open('')` the page wrote into) or wasn't probed at all.
     */
    val url: String? = null,
    /** Its [PopupProbe] hasn't reported yet. */
    val pending: Boolean = false,
)

/**
 * Whether a page window opens (as a new tab) rather than being blocked:
 * with the user's gesture, or on a site the user allowed pop-ups.
 */
fun popupOpens(isUserGesture: Boolean, siteAllowed: Boolean): Boolean = isUserGesture || siteAllowed

/**
 * One tab's blocked pop-ups, for its notice. They belong to the document
 * that tried to open them: a new document ([startDocument]) takes them
 * down, and a probe's late report for an earlier one ([resolve] with a
 * stale [document]) is dropped. Main thread only.
 */
class BlockedPopups {
    /** Newest last; at most [MAX_ENTRIES], the oldest dropped first. */
    var entries by mutableStateOf<List<BlockedPopup>>(emptyList())
        private set

    /** The site the document on screen was on when its pop-ups were blocked. */
    var origin by mutableStateOf<String?>(null)
        private set

    /** How many were blocked in all for this document, [entries] or not. */
    var count by mutableStateOf(0)
        private set

    /** The user tapped "Always allow" on this notice: it says so instead. */
    var allowed by mutableStateOf(false)
        private set

    /** Which document the entries are for; bumped by [startDocument]. */
    var document = 0
        private set

    /** Probes still reading a blocked window's address ([PopupProbe]). */
    var liveProbes = 0
        internal set

    private var nextId = 0L

    /** A new document committed in the tab: the notice goes. */
    fun startDocument() {
        document++
        clear()
    }

    /**
     * A pop-up of the document on screen, on [origin], was blocked. With
     * [pending] its address is still being read; [resolve] fills it in.
     * Returns its id.
     */
    fun add(origin: String?, pending: Boolean): Long {
        if (this.origin != origin) {
            entries = emptyList()
            count = 0
            allowed = false
        }
        this.origin = origin
        count++
        val entry = BlockedPopup(nextId++, pending = pending)
        entries = (entries + entry).takeLast(MAX_ENTRIES)
        return entry.id
    }

    /**
     * Pop-up [id] of document [document] was going to [url] (null: no
     * address). Nothing happens if the tab has moved on to another
     * document since, or the entry already left the notice.
     */
    fun resolve(document: Int, id: Long, url: String?) {
        if (document != this.document) return
        entries = entries.map { if (it.id == id) it.copy(url = url, pending = false) else it }
    }

    /** The user opened [entry]: it leaves the notice, the notice goes once empty. */
    fun remove(entry: BlockedPopup) {
        entries = entries.filterNot { it.id == entry.id }
        if (entries.isEmpty()) clear()
    }

    /** "Always allow" was tapped: the notice stays, for the entries' Open, but says so. */
    fun markAllowed() {
        allowed = true
    }

    /** The user closed the notice. */
    fun clear() {
        entries = emptyList()
        origin = null
        count = 0
        allowed = false
    }

    companion object {
        /** Entries the notice keeps; a page blocked in a loop doesn't grow it. */
        const val MAX_ENTRIES = 3

        /**
         * Probes a tab runs at once. Past that a blocked window is refused
         * outright (its address unknown), so a page opening windows in a
         * loop costs at most this many throwaway WebViews at a time.
         */
        const val MAX_LIVE_PROBES = 2
    }
}

/**
 * The address a blocked window's first navigation is for, read from a
 * throwaway WebView Chromium is handed in place of a tab. The WebView is
 * never attached, runs no script, fetches nothing (network loads are
 * blocked and every request is answered empty), and is destroyed once
 * the address is known or after [TIMEOUT_MS], whichever is first.
 *
 * The timeout covers `w = window.open(); …; w.location = url`, which
 * navigates only later; a window that is written into instead
 * (`document.write`) never navigates and reports a null address.
 */
internal object PopupProbe {
    private const val TAG = "PopupProbe"

    /** How long a probe waits for the window's first navigation. */
    const val TIMEOUT_MS = 5_000L

    /**
     * Hand [resultMsg]'s transport a probe and report the window's
     * address (or null) to [onResult], once, on the main thread. False
     * if the probe couldn't be set up — the window is then refused.
     * [private]: the opener is a private tab, whose windows Chromium
     * creates on the private profile; the probe has to be on it too.
     */
    fun start(context: Context, private: Boolean, resultMsg: Message, onResult: (String?) -> Unit): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val probe = try {
            WebView(if (private) PrivateWindowContext.of(context) else context).also { view ->
                if (private) PrivateProfile.attach(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe not created", e)
            return false
        }
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(url: String?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(probe)
            // Not from inside the probe's own callback.
            main.post { runCatching { probe.destroy() } }
            onResult(url)
        }
        probe.settings.apply {
            javaScriptEnabled = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame == true) finish(request.url?.toString())
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // A navigation that skipped shouldOverrideUrlLoading
                // (none is known to): still only its address is kept.
                if (url != null && url != ABOUT_BLANK) {
                    view?.stopLoading()
                    finish(url)
                }
            }
        }
        return try {
            transport.webView = probe
            resultMsg.sendToTarget()
            main.postAtTime({ finish(null) }, probe, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "probe not handed to Chromium", e)
            runCatching { probe.destroy() }
            false
        }
    }
}
