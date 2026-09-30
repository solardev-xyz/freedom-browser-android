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
 *   the load replaced stopping, not this load's end;
 * - any other load the app starts ([loadStarting] with `bypass` false —
 *   Back, Forward, a submit, Home) and Stop ([stopped]) restore it at
 *   once, so it can't outlive the navigation it was for. A Hard reload
 *   that commits nothing (a `204`) leaves it on until one of those, or
 *   until the next page that commits has finished.
 *
 * [readCacheMode] / [writeCacheMode] are the WebView's setting; injectable so
 * the rules are tested without a WebView. UI thread only.
 */
internal class CacheBypass(
    private val readCacheMode: () -> Int,
    private val writeCacheMode: (Int) -> Unit,
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

    /** A document committed (`onPageStarted`). */
    fun pageStarted() {
        if (restoreTo != null) committed = true
    }

    /** A load finished (`onPageFinished`): the bypassed one's end, once it has committed. */
    fun pageFinished() {
        if (committed) restore()
    }

    /** The user or the app stopped loading. */
    fun stopped() = restore()

    private fun restore() {
        val mode = restoreTo ?: return
        restoreTo = null
        committed = false
        writeCacheMode(mode)
    }
}
