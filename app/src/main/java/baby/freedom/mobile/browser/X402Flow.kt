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
 * started — its link or script, or a popup it opened (#218 R4-M3). Nor
 * does it once the navigation's server redirects have taken it through
 * another origin: whoever started it named where it went first, not
 * where another origin's server sent it next, so a 402 at the end of a
 * chain that ever left the paying origin — the user's address on
 * `evil.example` redirected to `pay.example`, or `pay.example`'s link to
 * `evil.example` redirected back — asks (#218 R5-M1). A form POST that
 * was redirected asks too, wherever it went: a 307/308 keeps the POST,
 * and WebView shows such a hop to neither `shouldOverrideUrlLoading` nor
 * the interceptor, so a chain `pay.example` → 307 → `evil.example` → 303
 * → `pay.example` would look as if it never left (#218 R6-M1).
 *
 * The site's own page pays silently only for a navigation the user
 * made it start — a link they tapped, a script run from their tap
 * (`hasGesture`) — never one it starts on its own: a paid page setting
 * `location.href` to the next 402, and that one's to the next, would
 * otherwise spend the whole allowance in seconds, one silent payment a
 * hop (#237). And once a paid request of a site's is answered Refused,
 * that site's allowance pays nothing silently — in any tab — until the
 * user navigates to it themselves: their address on that site
 * ([navigationStarted] `byUser`), or their own Reload or Back/Forward on
 * its page ([usersStep]) — as the server may keep a refused payment's
 * authorization and collect it anyway (#237). A hold outlives the
 * process: it's handed to [onHold] / [onLift] to be kept on disk, and the
 * holds an earlier run left are [restore]d — until then, and for good if
 * they couldn't be read, [holds] says every site is held but one the user
 * has since navigated to themselves (#347).
 *
 * Main thread only, but for [epoch].
 */
internal class X402Flow<D : Any>(
    /** Settles a paid request's history entry. */
    private val settle: (recordId: String, status: X402Store.Status, httpStatus: Int?) -> Unit,
    /** A URL's origin key, as [Committed.allowanceMayPay] is asked for; null for one that has none. */
    private val originOf: (String) -> String?,
    /** [origin] was put on hold: keep it across restarts (#347). */
    private val onHold: (origin: String) -> Unit = {},
    /** [origin]'s hold, if it has one kept, is lifted (#347). */
    private val onLift: (origin: String) -> Unit = {},
) {
    private class Detection<D>(val url: String, val value: D)

    private class Retry(val url: String, val recordId: String) {
        /** The paid URL and every server-redirect hop it has taken. */
        val hops = linkedSetOf(url)

        /** The interceptor saw the paid GET go out: no service worker answered it (#218 R4-M2). */
        var seen = false
    }

    /**
     * Who started a navigation: the user (their address, their
     * pull-to-refresh Reload), or a page of [fromOrigin]; and the origin of every
     * URL it has been at — its start's, if known, and each server
     * redirect's (#218 R5-M1).
     */
    private class Initiator(val byUser: Boolean, val fromOrigin: String?, val post: Boolean, val gesture: Boolean) {
        val hopOrigins = mutableListOf<String?>()
    }

    /** A 402's terms at their commit, with who started the navigation that brought them, and where it went. */
    class Committed<D>(
        val value: D,
        private val byUser: Boolean,
        private val fromOrigin: String?,
        private val gesture: Boolean,
        private val hopOrigins: List<String?>,
        /** Whether an origin was held as the 402 committed ([holds]). */
        private val held: (String) -> Boolean,
    ) {
        /**
         * An allowance of [origin]'s may pay this without asking: the user
         * named the load, or [origin]'s own page started it on the user's
         * tap (#237) — not another site's link, script or popup, nor a
         * navigation nobody was seen starting (#218 R4-M3), nor one the
         * page started on its own (#237) — and it never left [origin] on
         * the way: a redirect through another origin is that origin's say,
         * not the starter's (#218 R5-M1). Never while [origin] is held
         * after a Refused payment (#237) — as the hold stood at the
         * commit: a 402 that commits before [restore] (the first moments
         * after launch) counts every site the user hasn't gone to as held,
         * and keeps that answer even if the read-back then finds no hold,
         * so it asks once where it might not have. Fails closed on purpose:
         * the sheet, never a silent payment (#347 R1-M2).
         */
        fun allowanceMayPay(origin: String): Boolean =
            !held(origin) && (byUser || (fromOrigin == origin && gesture)) && hopOrigins.all { it == origin }
    }

    private val detections = HashMap<Long, Detection<D>>()
    private val retries = HashMap<Long, Retry>()
    private val initiators = HashMap<Long, Initiator>()
    private val epochs = ConcurrentHashMap<Long, Long>()

    /** Origins a paid request of which was answered Refused: no allowance of theirs pays silently (#237). */
    private val held = HashSet<String>()

    /** The holds an earlier run kept have been [restore]d (#347). */
    private var restored = false

    /** The kept holds couldn't be read: every origin is held but those [lifted] (#347). */
    private var heldUnknown = false

    /**
     * Origins the user navigated to themselves while the kept holds weren't
     * known — before [restore], or after it found them unreadable — so a
     * kept hold of theirs is lifted already (#347).
     */
    private val lifted = HashSet<String>()

    /** The origin of the page each tab last committed: what the user's Reload or Back/Forward navigates away from. */
    private val pages = HashMap<Long, String>()

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
        // The server may keep the refused authorization and collect it anyway: its site's
        // allowance pays nothing more silently until the user navigates to it (#237).
        originOf(retry.url)?.let(::hold)
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

    /**
     * A main-frame server redirect on [tab] to [target] — the WebView's,
     * or one the browser cancelled to switch the user agent and loaded
     * [target] in place of (#180): the same navigation either way, so not
     * the user's address on [target]'s site (#237 R2-F1).
     */
    fun redirected(tab: Long, target: String) {
        // A response that redirects isn't the 402 noted before it, which will never commit now.
        detections.remove(tab)
        retries[tab]?.hops?.add(target)
        // Where it goes next is the redirecting server's say (#218 R5-M1).
        initiators[tab]?.hopOrigins?.let { hops ->
            // A redirected POST may have passed through hops no callback shows (a 307/308
            // keeps the POST): somewhere unknown, which no allowance's origin is (#218 R6-M1).
            if (initiators[tab]?.post == true) hops.add(null)
            hops.add(originOf(target))
        }
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
     * A navigation of [tab]'s to [url] (null: a reload or history step,
     * of an entry already in the tab's history) began, [byUser] (their
     * address, or their own pull-to-refresh Reload) or from the page of
     * [fromOrigin] on screen (its link, script or form) — with the
     * user's [gesture] (`hasGesture`: their tap, or script run from it)
     * or on its own; [post]: it's a form POST (or other non-GET), whose
     * 307/308 redirects no callback shows. Called after [superseded].
     * The user's address on a site held after a Refused payment lets its
     * allowance pay again (#237); their Reload or Back/Forward does
     * through [usersStep]. Not if it mustn't [lifts]: a private tab's (#347).
     */
    fun navigationStarted(
        tab: Long,
        byUser: Boolean,
        fromOrigin: String?,
        url: String?,
        post: Boolean = false,
        gesture: Boolean = false,
        lifts: Boolean = true,
    ) {
        if (lifts && byUser && url != null) originOf(url)?.let(::lift)
        // Where it starts: [url]'s origin, or — a reload of the entry on
        // screen, whose own URL isn't named — the page the tab last
        // committed, or somewhere unknown (null) if it has none. A Reload
        // of another site's page that redirects to the 402 was that site's
        // say, as much as the user's address on it would be (#218 R5-M1).
        initiators[tab] = Initiator(byUser, fromOrigin, post, gesture).also {
            it.hopOrigins.add(if (url != null) originOf(url) else pages[tab])
        }
    }

    /**
     * [origin] is held after a Refused payment, now: its allowance pays
     * nothing silently. [Committed.allowanceMayPay] is the hold as the
     * 402 committed; a payment it let through is checked again here just
     * before it goes out, as a paid request of the site's in another tab
     * may have been Refused while this one was worked out (#237 R2-M1).
     */
    fun holds(origin: String): Boolean = origin in held || (!restored || heldUnknown) && origin !in lifted

    /**
     * [holds] as it is now, not as it will be: frozen into a 402's
     * [Committed], so one committed before [restore] stays "held" for
     * every site the user hasn't gone to, whatever the read-back finds
     * (#347 R1-M2).
     */
    private fun heldNow(): (String) -> Boolean {
        val held = held.toSet()
        val unknown = !restored || heldUnknown
        val lifted = lifted.toSet()
        return { origin -> origin in held || unknown && origin !in lifted }
    }

    /**
     * The holds an earlier run kept ([onHold]), read back: held too, but
     * for any the user has lifted since this run began. Null: they
     * couldn't be read — every origin stays held until the user navigates
     * to it themselves, as any of them may have been (#347).
     */
    fun restore(kept: Set<String>?) {
        if (restored) return
        restored = true
        if (kept == null) {
            heldUnknown = true
            return
        }
        held += kept - lifted - held
        lifted.clear()
    }

    private fun hold(origin: String) {
        held += origin
        lifted -= origin
        onHold(origin)
    }

    private fun lift(origin: String) {
        var changed = held.remove(origin)
        // A kept hold this run doesn't know about (yet) is lifted too.
        if (!restored || heldUnknown) changed = lifted.add(origin) || changed
        if (changed) onLift(origin)
    }

    /**
     * The user's own Reload or Back/Forward on [tab] — the bar's buttons,
     * pull-to-refresh; never the app's own reloads (a Tor-down or sweep
     * reload) nor the page's `history.back()` / `location.reload()` —
     * on the page the tab last committed: a hold on that page's site
     * after a Refused payment is lifted (#237 R1-M1). Only the hold: who
     * started the navigation is still [navigationStarted]'s to say.
     */
    fun usersStep(tab: Long) {
        pages[tab]?.let(::lift)
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
        navigationStarted(tab, byUser = false, fromOrigin = fromOrigin, url = url, post = true)
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
        val page = url?.let(originOf)
        if (page != null) pages[tab] = page else pages.remove(tab)
        retries.remove(tab)?.let { retry ->
            val answered = url != null && url in retry.hops && retry.seen
            settle(retry.recordId, if (answered) X402Store.Status.PAID else X402Store.Status.UNCONFIRMED, null)
        }
        val detection = detections.remove(tab) ?: return null
        if (url == null || url != detection.url) return null
        return Committed(
            detection.value,
            initiator?.byUser == true,
            initiator?.fromOrigin,
            initiator?.gesture == true,
            initiator?.hopOrigins.orEmpty(),
            heldNow(),
        )
    }

    fun closed(tab: Long) {
        superseded(tab)
        epochs.remove(tab)
        pages.remove(tab)
    }
}
