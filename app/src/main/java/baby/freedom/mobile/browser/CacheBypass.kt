package baby.freedom.mobile.browser

import android.webkit.WebSettings

/**
 * A tab's Hard reload (#262), on its WebView's side: the HTTP cache is
 * bypassed for the reload's own load and the subresources its document
 * fetches while loading, then the WebView's cache mode is put back.
 *
 * WebView has no `reloadIgnoringCache()`; its cache mode
 * ([WebSettings.setCacheMode]) is read for every request as it goes out,
 * so [LOAD_NO_CACHE][WebSettings.LOAD_NO_CACHE] set just before the load
 * covers exactly the requests made until it is restored:
 *
 * - [loadStarting] with `bypass` true sets it, just before the WebView is
 *   handed the Hard reload's URL;
 * - the first [pageFinished] after that load's own [pageStarted] (its
 *   commit) restores it: the document and everything it loaded on the way
 *   to its `load` event went past the cache, and what the page fetches
 *   afterwards is cached as usual. A finish before any commit is the page
 *   the load replaced stopping, not this load's end; nor is a fragment
 *   navigation's (a `location.hash` the loading page sets, R3-M1) — see
 *   [historyUpdated];
 * - any other load the app starts ([loadStarting] with `bypass` false —
 *   Back, Forward, a submit, Home) and Stop ([stopped]) restore it at
 *   once, so it can't outlive the navigation it was for. A Hard reload
 *   that commits nothing (a `204`) leaves it on until one of those, or
 *   until the next page that commits has finished.
 *
 * The Hard reload's URL is never loaded with `loadUrl` where that would
 * be a fragment navigation of the page on screen — the same address with
 * a `#fragment` — which fetches and commits nothing (R3-F1): the page
 * reloads instead ([staysInDocument]).
 *
 * [readCacheMode] / [writeCacheMode] are the WebView's setting; [post]
 * queues a task behind the WebView's callbacks already queued on the UI
 * thread. Injectable so the rules are tested without a WebView. UI thread
 * only.
 */
internal class CacheBypass(
    private val readCacheMode: () -> Int,
    private val writeCacheMode: (Int) -> Unit,
    private val post: (Runnable) -> Unit,
) {
    /** The cache mode to put back, while bypassing; null otherwise. */
    private var restoreTo: Int? = null

    /** The bypassed load has committed (its [pageStarted]). */
    private var committed = false

    /** Is the WebView loading past its cache right now? */
    val active: Boolean get() = restoreTo != null

    /**
     * The app is about to start a load in this WebView: a Hard reload's
     * if [bypass], which bypasses the cache from here; anything else
     * ends a bypass still on.
     */
    fun loadStarting(bypass: Boolean) {
        if (!bypass) {
            restore()
            return
        }
        if (restoreTo == null) restoreTo = readCacheMode()
        committed = false
        writeCacheMode(WebSettings.LOAD_NO_CACHE)
    }

    /** The last callback was [pageStarted]: the [historyUpdated] next is that commit's own. */
    private var justStarted = false

    /**
     * The URL of a same-document history step just reported, whose
     * `onPageFinished` — a fragment navigation's — may be queued right
     * behind it; null otherwise.
     */
    private var sameDocumentStep: String? = null

    /** Tells a [sameDocumentStep]'s posted clearing from a later step's. */
    private var stepGeneration = 0

    /** A document committed (`onPageStarted`). */
    fun pageStarted() {
        justStarted = true
        sameDocumentStep = null
        if (restoreTo != null) committed = true
    }

    /**
     * `doUpdateVisitedHistory`. A new document's commit posts it right
     * after its `onPageStarted`; one with no `onPageStarted` before it is
     * a same-document step (`pushState`, a fragment navigation). WebView
     * posts a fragment navigation's `onPageFinished` in the same breath,
     * right behind it, while the document goes on loading (Chromium's
     * `AwWebContentsObserver.didFinishNavigationInPrimaryMainFrame`): that
     * finish is not the bypassed document's `load`, and must not end the
     * bypass early (R3-M1). A task [post]ed from here runs after it, so the
     * window closes there — a `pushState`, which has no finish, doesn't
     * swallow a later real one.
     */
    fun historyUpdated(url: String?) {
        if (justStarted) {
            justStarted = false
            return
        }
        val generation = ++stepGeneration
        sameDocumentStep = url
        post(Runnable { if (stepGeneration == generation) sameDocumentStep = null })
    }

    /** A load finished (`onPageFinished`): the bypassed one's end, once it has committed. */
    fun pageFinished(url: String?) {
        justStarted = false
        val step = sameDocumentStep
        if (step != null) {
            sameDocumentStep = null
            // The fragment navigation's own finish, not the document's.
            if (step == url) return
        }
        if (committed) restore()
    }

    /** The user or the app stopped loading. */
    fun stopped() = restore()

    companion object {
        /**
         * Would `loadUrl(target)` in a WebView showing [current] stay in
         * that document — a fragment navigation, which loads nothing
         * (R3-F1)? True when [target] has a fragment and, without it, is
         * [current]'s address.
         */
        fun staysInDocument(current: String?, target: String): Boolean {
            if (current == null) return false
            val hash = target.indexOf('#')
            if (hash < 0) return false
            return current.substringBefore('#') == target.substring(0, hash)
        }
    }

    private fun restore() {
        val mode = restoreTo ?: return
        restoreTo = null
        committed = false
        writeCacheMode(mode)
    }
}
