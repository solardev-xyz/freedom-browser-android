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
 * the latest count. So does a count that gains its first peer or loses
 * its last (0 → N, N → 0): "no peers" is a state Home and the
 * notification show as such, not just a number. A held-back count isn't lost: the latest one goes out
 * when its window ends, unless something newer replaces it first. [now] is
 * a monotonic clock in ms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<NodeInfo>.throttlePeerCount(
    windowMs: Long,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
): Flow<NodeInfo> = flow {
    // Per collection: each collector gets its own window.
    var sent: NodeInfo? = null
    var sentAt = 0L
    // transformLatest cancels a held-back count's wait when a newer value
    // arrives, so only the latest is ever sent.
    emitAll(this@throttlePeerCount.transformLatest { info ->
        val last = sent
        if (info == last) return@transformLatest
        if (last != null &&
            (info.connectedPeers > 0) == (last.connectedPeers > 0) &&
            info.copy(connectedPeers = last.connectedPeers) == last
        ) {
            val wait = sentAt + windowMs - now()
            if (wait > 0) delay(wait)
        }
        emit(info)
        sent = info
        sentAt = now()
    })
}
