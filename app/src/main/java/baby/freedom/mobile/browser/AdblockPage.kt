package baby.freedom.mobile.browser

/**
 * A tab's top-level document as ad blocking judges its requests against
 * it (#126): the page the allowlist, `$document` / `$elemhide`
 * exceptions, `third-party` and `domain=` are about.
 *
 * That is the committed document ([committed], `onPageStarted`) — except
 * in the short window between a main-frame answer that will replace it
 * going out ([answered], interceptor thread) and that commit reaching
 * the UI thread: Chromium starts the incoming document's subresource
 * fetches as soon as it has the answer, before the posted
 * `onPageStarted` runs, so those are judged against the incoming page.
 *
 * A navigation that never commits — it became a download, a 204, a hop
 * cancelled as a link to another app, a Stop — leaves the page on screen
 * as it was ([kept]), so the page's later requests are judged against
 * its own host, not the one it never left for (R1-F1). A redirect hop
 * the WebView reports moves the incoming page along with it
 * ([redirected]); one it doesn't is corrected at the commit.
 *
 * Any thread.
 */
internal class AdblockPage {
    private var committed: String? = null
    private var incoming: String? = null

    /** The page a subresource request now is judged against. */
    @Synchronized
    fun current(): String? = incoming ?: committed

    /**
     * The main-frame answer for [url] is going to Chromium.
     * [replacesDocument] false (a 204, an attachment): nothing commits.
     */
    @Synchronized
    fun answered(url: String, replacesDocument: Boolean) {
        if (replacesDocument) incoming = url
    }

    /** A redirect hop of the pending navigation, to [url]. */
    @Synchronized
    fun redirected(url: String) {
        if (incoming != null) incoming = url
    }

    /**
     * The pending navigation — to [url], or whichever it is when null —
     * ended without replacing the page on screen.
     */
    @Synchronized
    fun kept(url: String? = null) {
        if (url == null || url == incoming) incoming = null
    }

    /** [url]'s document committed (or is the one on screen): it's the page now. */
    @Synchronized
    fun committed(url: String?) {
        committed = url
        incoming = null
    }
}
