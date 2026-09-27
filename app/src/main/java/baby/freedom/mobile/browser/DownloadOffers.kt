package baby.freedom.mobile.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Most downloads that can wait for an answer at once; later ones are dropped. */
internal const val MAX_PENDING_OFFERS = 10

/**
 * A download a page asked for that nothing has fetched yet: it waits
 * for the user's Download / Cancel (#79). `DownloadListener` fires for
 * script-initiated `<a download>` clicks and `location` changes as well
 * as taps, so saving on the listener's word alone would let any page
 * write whatever it likes into shared Downloads.
 */
class DownloadOffer internal constructor(
    val key: Long,
    /** The name the file will most likely get (the server may still rename it). */
    val fileName: String,
    /** Where it comes from, as the downloads list shows it. */
    val source: String,
    /** Announced size in bytes; -1 when unknown. */
    val totalBytes: Long,
    internal val start: () -> Unit,
)

/**
 * The offers waiting for an answer, oldest first. At most
 * [MAX_PENDING_OFFERS]: a page firing downloads in a loop can't queue
 * an endless line of prompts (or hold its `data:` payloads in memory).
 */
internal class DownloadOffers {
    private val nextKey = AtomicLong(1)
    private val _pending = MutableStateFlow<List<DownloadOffer>>(emptyList())
    val pending: StateFlow<List<DownloadOffer>> = _pending.asStateFlow()

    /** Queue an offer. False when the queue is full and it was dropped. */
    fun offer(fileName: String, source: String, totalBytes: Long, start: () -> Unit): Boolean {
        val offer = DownloadOffer(nextKey.getAndIncrement(), fileName, source, totalBytes, start)
        var added = false
        _pending.update { current ->
            added = current.size < MAX_PENDING_OFFERS
            if (added) current + offer else current
        }
        return added
    }

    /**
     * The user said yes to [key]: it leaves the queue and starts. Only
     * once, however often it's accepted — a double tap on Download
     * mustn't fetch the file twice.
     */
    fun accept(key: Long) {
        take(key)?.start?.invoke()
    }

    /** The user said no to [key]: it goes, nothing is fetched. */
    fun decline(key: Long) {
        take(key)
    }

    /** No to every offer waiting. */
    fun declineAll() {
        _pending.value = emptyList()
    }

    private fun take(key: Long): DownloadOffer? {
        var taken: DownloadOffer? = null
        _pending.update { current ->
            taken = current.firstOrNull { it.key == key }
            if (taken == null) current else current.filterNot { it.key == key }
        }
        return taken
    }
}
