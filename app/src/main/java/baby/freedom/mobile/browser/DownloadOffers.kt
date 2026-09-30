package baby.freedom.mobile.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Most page-initiated downloads one tab can have waiting for an answer
 * at once; later ones from that tab are dropped (and counted, see
 * [DownloadOffers.dropped]). Per tab, so a background tab can't use up
 * another tab's room, and never applied to the user's own requests.
 */
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
    /** The tab ([BrowserState.id]) whose page asked; only that tab shows it. */
    val tabId: Long,
    /**
     * The origin of the page that asked (`https://example.com`), shown as
     * "Requested by"; null for an address the user submitted themselves.
     */
    val requestedBy: String?,
    /** The name the file will most likely get (the server may still rename it). */
    val fileName: String,
    /** Where it comes from, as the downloads list shows it. */
    val source: String,
    /** Announced size in bytes; -1 when unknown. */
    val totalBytes: Long,
    /**
     * From a private tab (#86): its download gets no notification, so
     * accepting it is no occasion to ask for that permission (#265).
     */
    val private: Boolean = false,
    internal val start: () -> Unit,
)

/**
 * The offers waiting for an answer, oldest first. At most
 * [MAX_PENDING_OFFERS] page-initiated ones per tab: a page firing
 * downloads in a loop can't queue an endless line of prompts (or hold
 * its `data:` payloads in memory). The cap counts only that tab's
 * offers — only the tab in view shows its offers, so a shared cap would
 * let an unanswered background tab refuse every other tab's — and never
 * refuses a download the user asked for themselves. What it drops is
 * counted per tab in [dropped], so the prompt can say so rather than
 * losing downloads silently.
 *
 * The prompt is modal, so a page that keeps firing downloads could
 * otherwise re-raise it as fast as it's answered and hold the whole
 * browser behind it. So a *no* to a page's offer blocks that tab: every
 * further download a page in it asks for is dropped unasked, until the
 * user navigates the tab themselves ([allow]: a submitted address or a
 * reload) or closes it ([retainTabs]). Offers that were already waiting
 * from that tab go with the no. A download of an address the user
 * submitted (no [DownloadOffer.requestedBy]) is theirs, never blocked.
 */
internal class DownloadOffers {
    private val nextKey = AtomicLong(1)
    private val _pending = MutableStateFlow<List<DownloadOffer>>(emptyList())
    val pending: StateFlow<List<DownloadOffer>> = _pending.asStateFlow()
    private val blockedTabs = mutableSetOf<Long>()
    private val _dropped = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /**
     * Per tab, how many page-initiated offers were refused because that
     * tab already had [MAX_PENDING_OFFERS] waiting. Cleared once the tab
     * has no page offers waiting any more (answered, blocked or closed).
     */
    val dropped: StateFlow<Map<Long, Int>> = _dropped.asStateFlow()

    /**
     * Queue an offer from [tabId]'s page [requestedBy]. False when it was
     * dropped: the tab is blocked, or it already has
     * [MAX_PENDING_OFFERS] page offers waiting (counted in [dropped]).
     * An offer with no [requestedBy] — the user's own request — is
     * always queued.
     */
    fun offer(
        tabId: Long,
        requestedBy: String?,
        fileName: String,
        source: String,
        totalBytes: Long,
        private: Boolean = false,
        start: () -> Unit,
    ): Boolean {
        val offer = DownloadOffer(nextKey.getAndIncrement(), tabId, requestedBy, fileName, source, totalBytes, private, start)
        synchronized(this) {
            if (requestedBy != null && tabId in blockedTabs) return false
            if (requestedBy != null &&
                _pending.value.count { it.tabId == tabId && it.requestedBy != null } >= MAX_PENDING_OFFERS
            ) {
                _dropped.value = _dropped.value + (tabId to (_dropped.value[tabId] ?: 0) + 1)
                return false
            }
            _pending.value = _pending.value + offer
            return true
        }
    }

    /** Whether [tabId]'s pages may currently ask. */
    fun isBlocked(tabId: Long): Boolean = synchronized(this) { tabId in blockedTabs }

    /**
     * The user said yes to [key]: it leaves the queue and starts. Only
     * once, however often it's accepted — a double tap on Download
     * mustn't fetch the file twice.
     */
    fun accept(key: Long) {
        take(key)?.start?.invoke()
    }

    /**
     * The user said no to [key]: it goes, nothing is fetched, and if a
     * page asked, its tab's pages can't ask again (nor can the other
     * offers they left waiting) until [allow].
     */
    fun decline(key: Long) = synchronized(this) {
        val offer = take(key) ?: return@synchronized
        if (offer.requestedBy != null) block(offer.tabId)
        pruneDropped()
    }

    /** No to every offer [tabId] has waiting, and block it like [decline]. */
    fun declineAll(tabId: Long) = synchronized(this) {
        val mine = _pending.value.filter { it.tabId == tabId }
        _pending.value = _pending.value - mine.toSet()
        if (mine.any { it.requestedBy != null }) block(tabId)
        pruneDropped()
    }

    /** The user navigated [tabId] themselves: its pages may ask again. */
    fun allow(tabId: Long) = synchronized(this) {
        blockedTabs.remove(tabId)
        Unit
    }

    /**
     * Only [tabIds] are open: offers and blocks of any other tab go (a
     * closed tab, or a screen that's been rebuilt with new tabs).
     */
    fun retainTabs(tabIds: Set<Long>) = synchronized(this) {
        blockedTabs.retainAll(tabIds)
        _pending.value = _pending.value.filter { it.tabId in tabIds }
        pruneDropped()
    }

    private fun block(tabId: Long) {
        blockedTabs += tabId
        _pending.value = _pending.value.filterNot { it.tabId == tabId && it.requestedBy != null }
    }

    private fun take(key: Long): DownloadOffer? = synchronized(this) {
        val taken = _pending.value.firstOrNull { it.key == key } ?: return@synchronized null
        _pending.value = _pending.value.filterNot { it.key == key }
        pruneDropped()
        taken
    }

    /** A drop count lasts only while its tab still has page offers waiting. */
    private fun pruneDropped() {
        val live = _pending.value.filter { it.requestedBy != null }.mapTo(mutableSetOf()) { it.tabId }
        if (_dropped.value.keys.any { it !in live }) _dropped.value = _dropped.value.filterKeys { it in live }
    }
}
