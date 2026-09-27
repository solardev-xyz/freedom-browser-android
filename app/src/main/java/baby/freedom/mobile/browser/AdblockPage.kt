package baby.freedom.mobile.browser

/**
 * A tab's top-level document as ad blocking judges its requests against
 * it (#126): the page the allowlist, `$document` / `$elemhide`
 * exceptions, `third-party` and `domain=` are about.
 *
 * That is the committed document ([committed], `onPageStarted`) — except
 * in the short window between the browser's own main-frame answer that
 * will replace it going out ([answered], interceptor thread) and that
 * commit reaching the UI thread: Chromium starts the incoming document's
 * subresource fetches as soon as it has the answer, before the posted
 * `onPageStarted` runs, so those are judged against the incoming page.
 *
 * A navigation the WebView fetches from the network itself (the
 * interceptor answers null) has no answer yet when it's asked — the
 * page on screen stays up, and keeps making requests, for the
 * destination's whole time to first byte (R2-F1) — yet the destination's
 * own head and preload requests routinely reach the interceptor before
 * the posted `onPageStarted` (R3-F1). WebView reports nothing earlier
 * about a network commit, so for that window ([fetching]) a request is
 * told apart by its `Referer`: one naming the destination (its URL, or
 * its origin under the default `strict-origin-when-cross-origin`) and
 * not the page on screen is the destination's; everything else is still
 * the page on screen's. A document sending no referrer at all is judged
 * against the page on screen until its commit.
 *
 * A navigation that never commits — it became a download, a 204, a hop
 * cancelled as a link to another app, a Stop — leaves the page on screen
 * as it was ([kept]), so the page's later requests are judged against
 * its own host, not the one it never left for (R1-F1). A redirect hop
 * the WebView reports moves the incoming (or fetching) page along with
 * it ([redirected]); one it doesn't is corrected at the commit.
 *
 * Any thread.
 */
internal class AdblockPage {
    private var committed: String? = null
    private var incoming: String? = null
    private var fetching: String? = null

    /**
     * The page a subresource request now is judged against; [referer] is
     * the request's `Referer` header, if it sent one.
     */
    @Synchronized
    fun current(referer: String? = null): String? {
        incoming?.let { return it }
        val destination = fetching
        if (destination != null && referer != null && namesDestination(referer, destination)) return destination
        return committed
    }

    private fun namesDestination(referer: String, destination: String): Boolean {
        if (referer == destination) return true
        val origin = originOf(referer) ?: return false
        return origin == originOf(destination) && origin != committed?.let(::originOf)
    }

    /**
     * The main-frame answer for [url] is going to Chromium.
     * [replacesDocument] false (a 204, an attachment): nothing commits.
     * [fetchedByWebView] (the interceptor answered null): the answer
     * itself is still to come from the network, and the page on screen
     * stays the page — but for requests naming [url] as their referrer —
     * until it commits.
     */
    @Synchronized
    fun answered(url: String, replacesDocument: Boolean, fetchedByWebView: Boolean = false) {
        if (!replacesDocument) return
        if (fetchedByWebView) {
            fetching = url
            incoming = null
        } else {
            incoming = url
            fetching = null
        }
    }

    /** A redirect hop of the pending navigation, to [url]. */
    @Synchronized
    fun redirected(url: String) {
        if (incoming != null) incoming = url
        if (fetching != null) fetching = url
    }

    /**
     * The pending navigation — to [url], or whichever it is when null —
     * ended without replacing the page on screen.
     */
    @Synchronized
    fun kept(url: String? = null) {
        if (url == null || url == incoming) incoming = null
        if (url == null || url == fetching) fetching = null
    }

    /** [url]'s document committed (or is the one on screen): it's the page now. */
    @Synchronized
    fun committed(url: String?) {
        committed = url
        incoming = null
        fetching = null
    }
}

/** `scheme://host[:port]` of [url], lower-case; `null` when it has none. */
private fun originOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    var end = schemeEnd + 3
    while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
    return url.substring(0, end).lowercase()
}
