package baby.freedom.mobile.node

import android.os.SystemClock

/**
 * The UI side of the lazy IPFS start (#373): an `ensureIpfsStarted()` a
 * navigation asked for, kept until `:node`'s binder can take it.
 *
 * A cold start from an `ipfs://` link runs the link's navigation before
 * `bindService(NodeService)` has connected, so the binder is still null
 * and a plain `binder?.ensureIpfsStarted()` drops the ask. The tab then
 * waits for an IPFS node nothing ever started. So an ask made while the
 * service is bound (or binding) stays live for [windowMs] — as long as the
 * tab that made it waits for the node — and every connect inside that
 * window sends it again: the first one after a cold start, and a
 * reconnect after `:node` died. `NodeService` dedups repeat asks.
 *
 * Until [settled] — the cold start has read whether the node runs and,
 * if it does, started the bind — an unbound ask is kept too: `bound` is
 * still false then only because that read hasn't resumed yet, not
 * because the node is off, and a bind may well be on its way. [settled]
 * with the node off drops it again. After that, an ask made while the
 * service isn't bound at all (the node switched off) isn't kept: no
 * connect is coming, and that tab has already given up.
 * Switching IPFS or the node off forgets the ask, so a later connect
 * doesn't start a node the user just stopped.
 *
 * One per `MainActivity`; the waiting tab's coroutine dies with it.
 */
internal class IpfsStartRequest(
    private val windowMs: Long,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var askedAt: Long? = null
    private var settled = false

    /**
     * A navigation (or the Settings toggle) wants IPFS. [bound] is whether
     * the service is bound or binding; [send] calls the binder, a no-op
     * while it isn't connected. Before [settled], an unbound ask is kept
     * as well.
     */
    @Synchronized
    fun ask(bound: Boolean, send: () -> Unit) {
        askedAt = if (bound || !settled) now() else null
        send()
    }

    /**
     * The cold start has decided whether to bind: [bound] is whether the
     * service is now bound or binding. With the node off, an ask kept
     * while that was still unknown is dropped — no connect is coming.
     */
    @Synchronized
    fun settled(bound: Boolean) {
        settled = true
        if (!bound) askedAt = null
    }

    /** The binder just connected: send the ask again if it's still live. */
    @Synchronized
    fun onConnected(send: () -> Unit) {
        val at = askedAt ?: return
        if (now() - at in 0 until windowMs) send() else askedAt = null
    }

    /** IPFS or the node was switched off, or the service unbound. */
    @Synchronized
    fun forget() {
        askedAt = null
    }

    /** Whether an ask is kept for the next connect; for tests. */
    @get:Synchronized
    val pending: Boolean
        get() = askedAt.let { it != null && now() - it in 0 until windowMs }
}
