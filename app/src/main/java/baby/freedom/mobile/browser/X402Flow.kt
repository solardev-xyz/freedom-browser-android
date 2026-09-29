package baby.freedom.mobile.browser

import baby.freedom.mobile.data.X402Store
import java.util.concurrent.ConcurrentHashMap

/**
 * Which navigation of a tab an x402 402 or a paid request belongs to
 * ([X402Payments], #140), apart from the WebView so it can be tested.
 *
 * A **detection** ([D]: a 402's terms, seen in `onReceivedHttpError`) is
 * for that one response: only its own commit ([committed] on its URL)
 * uses it. Anything that shows the response won't commit — Stop, a new
 * load of the browser's or the page's, a redirect, the response turning
 * into a download, the load finishing on another page — drops it, so it
 * can't be picked up by a later commit of the same URL that never asked
 * to be paid (#218 R2-M2).
 *
 * A **paid request** is followed through its server redirects
 * ([redirected]): its answer is whichever hop answers, so a paid `/a`
 * redirected to a `/b` that answers 402 is the paid request refused, not
 * a fresh 402 to pay again (the loop guard, #218 R2-M1). Any other
 * navigation of the tab — the user's Reload or address, the page's own
 * link or script, Stop — is not its answer: the paid request is over,
 * unconfirmed, and what comes next is judged on its own (#218 R2-M3).
 * A form POST is one too, though only the interceptor sees it
 * ([mainFrameRequested], #218 R3-M1) — one the interceptor saw *before*
 * the paid request went out is the old page's, which the paid load
 * cancelled, so it's told apart by the payment [epoch] it was seen in
 * (#218 R4-M1). A paid request is settled paid only when the interceptor
 * saw it go out itself: a page a service worker controls answers its
 * navigations — the paid GET, or a form POST to the paid URL — where the
 * interceptor never sees them, so a commit there can't be told for the
 * paid request's answer, and is left unconfirmed (#218 R4-M2).
 *
 * Who started the navigation a 402 commits in ([navigationStarted]) goes
 * with it: a site's allowance pays silently only for the user's own load
 * or a navigation from that site's own page, never one another site
 * started — its link or script, or a popup it opened (#218 R4-M3).
 *
 * Main thread only, but for [epoch].
 */
internal class X402Flow<D : Any>(
    /** Settles a paid request's history entry. */
    private val settle: (recordId: String, status: X402Store.Status, httpStatus: Int?) -> Unit,
) {
    private class Detection<D>(val url: String, val value: D)

    private class Retry(val url: String, val recordId: String) {
        /** The paid URL and every server-redirect hop it has taken. */
        val hops = linkedSetOf(url)

        /** The interceptor saw the paid GET go out: no service worker answered it (#218 R4-M2). */
        var seen = false
    }

    /** Who started a navigation: the user (their address, Reload, Back/Forward), or a page of [fromOrigin]. */
    private class Initiator(val byUser: Boolean, val fromOrigin: String?)

    /** A 402's terms at their commit, with who started the navigation that brought them. */
    class Committed<D>(val value: D, private val byUser: Boolean, private val fromOrigin: String?) {
        /**
         * An allowance of [origin]'s may pay this without asking: the user
         * named the load, or [origin]'s own page started it — not another
         * site's link, script or popup, nor a navigation nobody was seen
         * starting (#218 R4-M3).
         */
        fun allowanceMayPay(origin: String): Boolean = byUser || fromOrigin == origin
    }

    private val detections = HashMap<Long, Detection<D>>()
    private val retries = HashMap<Long, Retry>()
    private val initiators = HashMap<Long, Initiator>()
    private val epochs = ConcurrentHashMap<Long, Long>()

    /**
     * [tab]'s payment epoch, from any thread: the interceptor reads it
     * as a main-frame request goes out, so [mainFrameRequested], posted
     * to the main thread, can tell a request seen before the latest paid
     * request went out ([sending]) from one after it (#218 R4-M1).
     */
    fun epoch(tab: Long): Long = epochs[tab] ?: 0L

    /** [tab]'s paid request is about to be loaded: requests seen from here on are after it. */
    fun sending(tab: Long) {
        epochs.merge(tab, 1L, Long::plus)
    }

    /**
     * A main-frame HTTP error answered [url] ([method]) on [tab]: true if
     * it's the paid request's answer — settled refused, and not to be
     * paid again.
     */
    fun httpError(tab: Long, url: String, method: String?, status: Int): Boolean {
        val retry = retries[tab] ?: return false
        // The paid request is a GET; a form POST's answer is some other navigation's.
        if (!method.equals("GET", ignoreCase = true) || url !in retry.hops) return false
        retries.remove(tab)
        detections.remove(tab)
        settle(retry.recordId, X402Store.Status.REFUSED, status)
        return true
    }

    /** A 402 with terms answered [url] on [tab]: [value] waits for that response's commit. */
    fun detected(tab: Long, url: String, value: D) {
        detections[tab] = Detection(url, value)
    }

    /** [tab]'s paid request for [url], history entry [recordId], has just gone out. */
    fun paid(tab: Long, url: String, recordId: String) {
        retries.remove(tab)?.let { settle(it.recordId, X402Store.Status.UNCONFIRMED, null) }
        retries[tab] = Retry(url, recordId)
    }

    /** A main-frame server redirect on [tab] to [target]. */
    fun redirected(tab: Long, target: String) {
        // A response that redirects isn't the 402 noted before it, which will never commit now.
        detections.remove(tab)
        retries[tab]?.hops?.add(target)
    }

    /**
     * A navigation of [tab]'s own began or ended without a commit — the
     * browser's load (address, Reload, Back/Forward), the page's link or
     * script navigation, Stop, a response that became a download: no
     * pending 402 commits, and a paid request in flight is over without
     * its answer.
     */
    fun superseded(tab: Long) {
        detections.remove(tab)
        initiators.remove(tab)
        retries.remove(tab)?.let { settle(it.recordId, X402Store.Status.UNCONFIRMED, null) }
    }

    /**
     * A navigation of [tab]'s began, [byUser] (their address, Reload,
     * Back/Forward) or from the page of [fromOrigin] on screen (its link,
     * script or form). Called after [superseded].
     */
    fun navigationStarted(tab: Long, byUser: Boolean, fromOrigin: String?) {
        initiators[tab] = Initiator(byUser, fromOrigin)
    }

    /**
     * A main-frame request for [url] with [method] went out on [tab], seen
     * by the interceptor in payment [epoch], from the page of [fromOrigin].
     * One seen before the latest paid request was sent is the old page's,
     * which that load cancelled: nothing (#218 R4-M1). The paid request's
     * own GET is noted as seen (#218 R4-M2). The paid request is a GET,
     * and so are its redirect hops: a POST is a form the page submitted —
     * which WebView never shows `shouldOverrideUrlLoading` — replacing the
     * paid request (and any 402 waiting for its commit), so its answer
     * isn't the paid request's (#218 R3-M1).
     */
    fun mainFrameRequested(tab: Long, url: String?, method: String?, epoch: Long, fromOrigin: String?) {
        if (epoch != epoch(tab)) return
        if (method.equals("GET", ignoreCase = true)) {
            // The request carries no fragment; the page's URL may.
            retries[tab]?.let { if (url != null && url.substringBefore('#') == it.url.substringBefore('#')) it.seen = true }
            return
        }
        superseded(tab)
        navigationStarted(tab, byUser = false, fromOrigin = fromOrigin)
    }

    /** `onPageFinished` for [url] on [tab]: a 402 noted for it that hasn't committed never will. */
    fun loadFinished(tab: Long, url: String?) {
        if (url != null && detections[tab]?.url == url) detections.remove(tab)
    }

    /** A main-frame request for [url] on [tab] got no answer. */
    fun failed(tab: Long, url: String?) {
        detections.remove(tab)
        val retry = retries[tab] ?: return
        if (url != null && url !in retry.hops) return
        retries.remove(tab)
        settle(retry.recordId, X402Store.Status.UNCONFIRMED, null)
    }

    /**
     * [tab] committed [url] (null: it's being torn down): the paid
     * request's answer if it's one of its hops and it was seen going out
     * (else unconfirmed), and the 402 noted for [url], if any, to be paid
     * for, with who started the navigation.
     */
    fun committed(tab: Long, url: String?): Committed<D>? {
        val initiator = initiators.remove(tab)
        retries.remove(tab)?.let { retry ->
            val answered = url != null && url in retry.hops && retry.seen
            settle(retry.recordId, if (answered) X402Store.Status.PAID else X402Store.Status.UNCONFIRMED, null)
        }
        val detection = detections.remove(tab) ?: return null
        if (url == null || url != detection.url) return null
        return Committed(detection.value, initiator?.byUser == true, initiator?.fromOrigin)
    }

    fun closed(tab: Long) {
        superseded(tab)
        epochs.remove(tab)
    }
}
