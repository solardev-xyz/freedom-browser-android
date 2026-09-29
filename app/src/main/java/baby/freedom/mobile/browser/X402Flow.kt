package baby.freedom.mobile.browser

import baby.freedom.mobile.data.X402Store

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
 * ([mainFrameRequested], #218 R3-M1).
 *
 * Main thread only.
 */
internal class X402Flow<D : Any>(
    /** Settles a paid request's history entry. */
    private val settle: (recordId: String, status: X402Store.Status, httpStatus: Int?) -> Unit,
) {
    private class Detection<D>(val url: String, val value: D)

    private class Retry(url: String, val recordId: String) {
        /** The paid URL and every server-redirect hop it has taken. */
        val hops = linkedSetOf(url)
    }

    private val detections = HashMap<Long, Detection<D>>()
    private val retries = HashMap<Long, Retry>()

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
        retries.remove(tab)?.let { settle(it.recordId, X402Store.Status.UNCONFIRMED, null) }
    }

    /**
     * A main-frame request with [method] went out on [tab]. The paid
     * request is a GET, and so are its redirect hops: a POST is a form
     * the page submitted — which WebView never shows
     * `shouldOverrideUrlLoading` — replacing the paid request (and any
     * 402 waiting for its commit), so its answer isn't the paid
     * request's (#218 R3-M1).
     */
    fun mainFrameRequested(tab: Long, method: String?) {
        if (!method.equals("GET", ignoreCase = true)) superseded(tab)
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
     * request's answer if it's one of its hops, and the 402 noted for
     * [url], if any, to be paid for.
     */
    fun committed(tab: Long, url: String?): D? {
        retries.remove(tab)?.let { retry ->
            val status = if (url != null && url in retry.hops) X402Store.Status.PAID else X402Store.Status.UNCONFIRMED
            settle(retry.recordId, status, null)
        }
        val detection = detections.remove(tab) ?: return null
        return detection.value.takeIf { url != null && url == detection.url }
    }

    fun closed(tab: Long) = superseded(tab)
}
