package baby.freedom.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import baby.freedom.mobile.browser.DeepLinkQueue
import baby.freedom.mobile.browser.Incoming
import baby.freedom.mobile.browser.IncomingLinks
import baby.freedom.mobile.browser.VirtualOrigin
import baby.freedom.mobile.ens.EnsNormalize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * Links, shares and searches from other apps (#268) that haven't been
 * opened in a tab yet, held outside the Activity so they outlive it.
 *
 * `configChanges` keeps [MainActivity] across rotation and the like, but
 * a resource-overlay change (navigation mode, wallpaper colours) still
 * relaunches it (#183, see [baby.freedom.mobile.browser.TabsSession]).
 * A relaunch doesn't read its intent again ([MainActivity.onCreate]), so
 * a link still parsing off Main, or queued but not yet in a tab (its
 * search engine still being read), would be lost with an Activity-owned
 * queue: kept here, the parse runs on in [viewModelScope] and the next
 * Activity's screen opens it.
 *
 * Main thread only, like [DeepLinkQueue].
 */
class IncomingSession(
    background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val queue = DeepLinkQueue()

    /** Publishes into [queue] in arrival order. */
    private val ordered = OrderedDeepLinks<Incoming>(viewModelScope, background) {
        when (it) {
            is Incoming.Open -> queue.offer(it.url)
            is Incoming.Search -> queue.offer(it.query, search = true)
        }
    }

    /**
     * The link the app was cold-started from, while it is still being
     * parsed: a relaunch in that window waits for it before composing,
     * as the first launch did, so it still gets the first tab.
     */
    var coldStart: Job? = null
        get() = field?.takeIf { it.isActive }

    /**
     * Queue [incoming], its link in the address-bar form
     * ([IncomingLinks.displayUrl], through [VirtualOrigin], so the
     * deep-link path can't drift from the mapping the WebView and the
     * redirector use); off Main if that needs the ENSIP-15 tables still
     * decoding (a link tapped right after launch), and [ordered] keeps a
     * later ASCII link from overtaking it.
     *
     * Returns the job still parsing it, or null once it's queued.
     */
    fun submit(incoming: Incoming): Job? {
        val slow = incoming is Incoming.Open &&
            !EnsNormalize.isWarm && VirtualOrigin.needsEnsTables(incoming.url)
        return ordered.submit(slow) {
            if (slow) EnsNormalize.warm()
            when (incoming) {
                is Incoming.Open -> Incoming.Open(IncomingLinks.displayUrl(incoming.url))
                is Incoming.Search -> incoming
            }
        }
    }
}
