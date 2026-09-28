package baby.freedom.mobile.browser

/**
 * Gets a tab off a document a sweep left stale (#125, see
 * [UnverifiedOrigins]), whatever that document was.
 *
 * The sweep's first move is a plain reload, which keeps the user where
 * they were. But a reload doesn't always navigate: on a page reached by
 * a form POST, WebView asks `onFormResubmission` and its default answer
 * is not to resend, so nothing is requested, nothing commits, and the
 * old document — with any frame the external gateway served — lives on
 * (R6-F1). So until the tab commits a new document ([committed]):
 *
 *  1. [Step.RELOAD] — `reload()`.
 *  2. [Step.GET] — if the reload is refused ([refused]: the POST
 *     resubmission prompt, answered "don't resend" — resending would
 *     repeat the form's side effect) or hasn't committed within
 *     [OVERDUE_MS]: `loadUrl` of the same address, as a GET. The closest
 *     to a reload that can't be refused: same page, same place in
 *     history, no side effect repeated. A POST-only address may answer
 *     it with an error page, but that page replaces the stale document
 *     all the same.
 *  3. [Step.BLANK] — if that hasn't committed within [OVERDUE_MS] either
 *     (a GET answered with a download or a 204 starts a navigation but
 *     never commits, and leaves the old document on screen):
 *     `about:blank`, which always commits. Repeated every [OVERDUE_MS]
 *     until something commits.
 *
 * The tab's hold on the swept origins ([UnverifiedOrigins.hold]) has no
 * timeout of its own: it ends at the tab's next commit, so a document
 * that won't go keeps its origins' next documents on the cleanup page
 * until one of these steps has replaced it.
 *
 * Every step is tagged with a generation, so a deadline scheduled for
 * one sweep can't act on a later one, nor after the commit that ended
 * it. Main thread.
 */
internal class SweptReload(
    private val navigate: (Step) -> Unit,
    /** Run the given action after the delay (ms), on the main thread. */
    private val schedule: (Long, () -> Unit) -> Unit,
) {
    enum class Step { RELOAD, GET, BLANK }

    /** The step under way, or `null` if the tab isn't on a stale document. */
    var step: Step? = null
        private set

    /** The stale document's address: what [Step.GET] loads. */
    var address: String? = null
        private set

    private var generation = 0L

    /** Inside [navigate]: the loads it starts are this class's own. */
    private var navigating = false

    /**
     * The last load the tab started is [Step.RELOAD]'s own. Cleared by any
     * other load ([navigationStarted]) — a Back to a POST entry, the
     * user's own reload — so their resubmission prompt isn't taken for
     * the sweep's (R1-F1).
     */
    private var ownReloadIsLatest = false

    /** A sweep found the tab on a stale document at [address]. */
    fun swept(address: String?) {
        this.address = address
        go(Step.RELOAD)
    }

    /**
     * WebView refused the navigation just asked for — the POST
     * resubmission prompt, answered "don't resend". Moves on at once
     * rather than waiting out [OVERDUE_MS]. Only [Step.RELOAD] can be
     * refused that way, and only while no other load has started since
     * ([navigationStarted]): a prompt for the user's own navigation —
     * Back to a POST entry — is theirs, not the sweep's, and doesn't send
     * them to the stale page's address. (If that navigation is refused
     * too, the stale document stays until [OVERDUE_MS] moves on.)
     * Returns whether this was it.
     */
    fun refused(): Boolean {
        if (step != Step.RELOAD || !ownReloadIsLatest) return false
        next(Step.RELOAD)
        return true
    }

    /**
     * The tab started a load — any the app asks of the WebView: typed URL,
     * reload, back / forward. Those [navigate] starts are ignored.
     */
    fun navigationStarted() {
        if (!navigating) ownReloadIsLatest = false
    }

    /** The tab committed a new document: the stale one is gone. */
    fun committed() {
        if (step == null) return
        step = null
        address = null
        ownReloadIsLatest = false
        generation++
    }

    private fun next(after: Step) {
        go(
            when (after) {
                Step.RELOAD -> if (address.isHttp()) Step.GET else Step.BLANK
                Step.GET, Step.BLANK -> Step.BLANK
            },
        )
    }

    private fun go(to: Step) {
        step = to
        val g = ++generation
        navigating = true
        try {
            navigate(to)
        } finally {
            navigating = false
        }
        ownReloadIsLatest = to == Step.RELOAD
        schedule(OVERDUE_MS) { if (generation == g) next(to) }
    }

    private fun String?.isHttp() =
        this != null && (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true))

    companion object {
        /** How long a step gets to commit before the next one. */
        const val OVERDUE_MS = 5_000L
    }
}
