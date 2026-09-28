package baby.freedom.mobile.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One App Link waiting for its own tab; [id] tells repeats of a URL apart. */
data class DeepLink(val id: Long, val url: String)

/**
 * App Links that arrived while the app was running and haven't been
 * opened in a tab yet, oldest first.
 *
 * A single-slot holder isn't enough: several links can be published
 * back to back before the UI recomposes (every link chained behind the
 * ENSIP-15 warm-up is released in one burst when it finishes, and
 * nothing stops two `onNewIntent`s from landing in one frame either),
 * and each would overwrite the last, so only the newest got a tab.
 * Here each is queued; the UI opens [pending]'s head, then calls
 * [handled], which uncovers the next one.
 */
class DeepLinkQueue {
    private var nextId = 0L
    private val queue = MutableStateFlow<List<DeepLink>>(emptyList())

    /** Everything not opened yet, oldest first. */
    val pending: StateFlow<List<DeepLink>> = queue.asStateFlow()

    /** Enqueue [url] behind every link still pending. Main thread only. */
    fun offer(url: String) {
        val link = DeepLink(nextId++, url)
        queue.update { it + link }
    }

    /**
     * [link] has its tab: drop it. A no-op unless it is still the head,
     * so a stale or repeated call can't drop a link that hasn't been
     * opened.
     */
    fun handled(link: DeepLink) {
        queue.update { if (it.firstOrNull() == link) it.drop(1) else it }
    }
}
