package baby.freedom.mobile.browser

/**
 * How much of a page's `window.ethereum` / `window.radicle` /
 * `window.swarm` traffic a tab may have in flight at once (#459). A
 * request is held — its message, its parse — until it's answered, and a
 * page with no permission at all can queue any number of them behind its
 * tab's prompt; unbounded, a loop of big requests runs the app out of
 * memory and takes every tab with it.
 *
 * Per tab, at most [maxRequests] requests; those up to [largeAbove]
 * characters long share [smallChars] between them, and one longer
 * request (an upload) may be in flight besides. A request over any of
 * these is answered at once ([LIMIT_EXCEEDED]), before it's parsed.
 *
 * One instance per bridge, used from the main thread only.
 */
internal class BridgeRequestBudget(
    private val maxRequests: Int,
    private val smallChars: Long,
    private val largeAbove: Int,
) {
    private class Tab(var requests: Int = 0, var chars: Long = 0, var large: Boolean = false)

    private val tabs = HashMap<Long, Tab>()

    /** One request in flight on [tab]: give it back with [Ticket.release] once it's answered. */
    inner class Ticket internal constructor(private val tab: Long, private val chars: Int) {
        private var released = false

        /** Answered (or dropped): its share is free again. A second call does nothing. */
        fun release() {
            if (released) return
            released = true
            val t = tabs[tab] ?: return
            t.requests--
            if (chars > largeAbove) t.large = false else t.chars -= chars
            if (t.requests <= 0) tabs.remove(tab)
        }
    }

    /** A [Ticket] for a [chars]-character request on [tab], or null if that's more than the tab has left. */
    fun reserve(tab: Long, chars: Int): Ticket? {
        val t = tabs[tab] ?: Tab()
        if (t.requests >= maxRequests) return null
        if (chars > largeAbove) {
            if (t.large) return null
            t.large = true
        } else {
            if (t.chars + chars > smallChars) return null
            t.chars += chars
        }
        t.requests++
        tabs[tab] = t
        return Ticket(tab, chars)
    }

    /** How many requests [tab] has in flight. */
    fun inFlight(tab: Long): Int = tabs[tab]?.requests ?: 0

    companion object {
        /** EIP-1474's "Limit exceeded": a well-behaved page retries it later. */
        const val LIMIT_EXCEEDED = -32005

        const val LIMIT_MESSAGE = "Too many requests in flight; try again once some are answered"
    }
}

/**
 * [jsonShape]'s refusal of [data] as a message: why, with the caps, when
 * it's over them, or null when it's just malformed (or fine).
 */
internal fun tooComplexMessage(data: String, maxValues: Int, maxContainers: Int): String? =
    if (jsonShape(data, maxValues, maxContainers) != JsonShape.TOO_COMPLEX) null
    else "Request has more than $maxValues values or $maxContainers arrays and objects"
