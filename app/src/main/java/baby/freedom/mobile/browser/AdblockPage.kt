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
 * — a bare origin, what the default `strict-origin-when-cross-origin`
 * sends cross-origin — its origin) and not the page on screen is the
 * destination's; everything else is still the page on screen's. A
 * document sending no referrer at all is judged against the page on
 * screen until its commit.
 *
 * "Not the page on screen" includes its frames (R1-F1 of the R7 round):
 * a page with an embed from the site it links to (a video or social
 * embed) has that frame's requests naming the destination's origin too.
 * So every frame document seen under the page on screen — a subframe's
 * own document request ([frameRequested]), or a full-URL `Referer` from
 * another origin — is remembered until the next commit, and a `Referer`
 * naming one of them (its URL, or its origin) stays the page on
 * screen's. A full-URL cross-origin `Referer` is a frame's same-origin
 * request *or* a subresource of a cross-origin stylesheet (which sends
 * the stylesheet's URL); the latter is harmless to remember for any
 * origin but the pending destination's, where the destination's own CSS
 * does exactly that — so while a navigation is fetching, one from the
 * destination's origin is not taken for a frame.
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

    /** Documents (fragment-less URLs) of the page on screen's frames, oldest first. */
    private val frameUrls = LinkedHashSet<String>()

    /** Origins of [frameUrls]. */
    private val frameOrigins = HashMap<String, Int>()

    /**
     * The page a subresource request now is judged against; [referer] is
     * the request's `Referer` header, if it sent one.
     */
    @Synchronized
    fun current(referer: String? = null): String? {
        incoming?.let { return it }
        val destination = fetching
        if (destination != null && referer != null && namesDestination(referer, destination)) return destination
        if (referer != null && !isBareOrigin(referer)) {
            val origin = originOf(referer)
            // A full URL from the destination's own origin is ambiguous
            // while it's pending: the destination's stylesheet sends its
            // own URL to the fonts and images it loads (R2-F1 of the R8
            // round), so it can't be taken for a frame of the page on
            // screen — that would make the destination's later bare-origin
            // requests look like the page on screen's too. A real frame
            // from there is still known by its own document request
            // ([frameRequested]).
            if (origin != null && origin != committed?.let(::originOf) &&
                (destination == null || origin != originOf(destination))
            ) {
                rememberFrame(referer)
            }
        }
        return committed
    }

    /**
     * A subframe's own document request, for [url], whose `Referer` is
     * [referer]: when it is the page on screen's frame, a later request
     * naming it is the page on screen's.
     */
    @Synchronized
    fun frameRequested(url: String, referer: String?) {
        if (incoming != null) return
        val destination = fetching
        if (destination != null && referer != null && namesDestination(referer, destination)) return
        rememberFrame(url)
    }

    private fun namesDestination(referer: String, destination: String): Boolean {
        val document = withoutFragment(referer)
        if (document in frameUrls) return false
        if (document == withoutFragment(destination)) return true
        // Only a bare origin can stand for the destination; a full URL
        // names the document that sent it, and that isn't the destination.
        if (!isBareOrigin(referer)) return false
        val origin = originOf(referer) ?: return false
        return origin == originOf(destination) &&
            origin != committed?.let(::originOf) &&
            origin !in frameOrigins
    }

    private fun rememberFrame(url: String) {
        val document = withoutFragment(url)
        val origin = originOf(document) ?: return
        if (!frameUrls.add(document)) return
        frameOrigins[origin] = (frameOrigins[origin] ?: 0) + 1
        // A page churning out frames can't grow this without bound.
        if (frameUrls.size > MAX_FRAMES) {
            val oldest = frameUrls.first()
            frameUrls.remove(oldest)
            originOf(oldest)?.let { o ->
                val n = (frameOrigins[o] ?: 1) - 1
                if (n <= 0) frameOrigins.remove(o) else frameOrigins[o] = n
            }
        }
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
        frameUrls.clear()
        frameOrigins.clear()
    }

    private companion object {
        const val MAX_FRAMES = 256
    }
}

private fun withoutFragment(url: String) = url.substringBefore('#')

/** Is [referer] an origin alone (`scheme://host[:port]/`), as sent cross-origin? */
private fun isBareOrigin(referer: String): Boolean {
    val origin = originOf(referer) ?: return false
    return referer.length == origin.length + 1 && referer.endsWith("/") &&
        referer.regionMatches(0, origin, 0, origin.length, ignoreCase = true)
}

/** `scheme://host[:port]` of [url], lower-case; `null` when it has none. */
private fun originOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    var end = schemeEnd + 3
    while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
    return url.substring(0, end).lowercase()
}
