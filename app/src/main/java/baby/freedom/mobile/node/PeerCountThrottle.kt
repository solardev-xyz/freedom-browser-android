package baby.freedom.mobile.node

import baby.freedom.swarm.NodeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * [this], with a change in nothing but [NodeInfo.connectedPeers] passed on
 * at most once per [windowMs] (#471). The Swarm node counts its peers every
 * second, and in ultra-light mode the count churns all day: without this
 * each new count reposted the notification and crossed the binder.
 *
 * Any other change (status, error, addresses, …) goes out at once, with
 * the latest count. A count that gains its first peer or loses its last
 * (0 → N, N → 0) is let through early too, since "no peers" is a state
 * Home and the notification show as such, not just a number. That
 * exemption is capped at [ZERO_CROSSINGS_PER_WINDOW] per window, so a count
 * flapping 0 ↔ 1 on a poor link can't bring the per-second churn back: past
 * the cap a crossing waits like any other count.
 *
 * A held-back count isn't lost: the latest one goes out when its wait
 * ends, unless something newer replaces it first. [now] is a monotonic
 * clock in ms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<NodeInfo>.throttlePeerCount(
    windowMs: Long,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
): Flow<NodeInfo> = flow {
    // Per collection: each collector gets its own window.
    var sent: NodeInfo? = null
    var sentAt = 0L
    // When the latest early zero crossings went out (the newest few only).
    val crossings = ArrayDeque<Long>()
    // transformLatest cancels a held-back count's wait when a newer value
    // arrives, so only the latest is ever sent.
    emitAll(this@throttlePeerCount.transformLatest { info ->
        val last = sent
        if (info == last) return@transformLatest
        val countOnly = last != null && info.copy(connectedPeers = last.connectedPeers) == last
        val crossing = countOnly && (info.connectedPeers > 0) != ((last?.connectedPeers ?: 0L) > 0)
        val at = now()
        val wait = when {
            !countOnly -> 0L
            crossing && crossings.count { at - it < windowMs } < ZERO_CROSSINGS_PER_WINDOW -> 0L
            else -> sentAt + windowMs - at
        }
        if (wait > 0) delay(wait)
        emit(info)
        sent = info
        sentAt = now()
        if (crossing) {
            if (crossings.size == ZERO_CROSSINGS_PER_WINDOW) crossings.removeFirst()
            crossings.addLast(sentAt)
        }
    })
}

/**
 * How many zero crossings per window [throttlePeerCount] lets through
 * early: two, so finding the first peers and then losing them all both
 * show at once, while a count flapping 0 ↔ 1 settles to about two
 * updates per window.
 */
internal const val ZERO_CROSSINGS_PER_WINDOW = 2
