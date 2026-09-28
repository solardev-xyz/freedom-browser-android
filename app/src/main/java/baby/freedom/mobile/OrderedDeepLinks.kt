package baby.freedom.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Publishes App Links that arrive while the app is running in the
 * order they arrived, even though some of them have to be parsed off
 * the main thread.
 *
 * A Unicode ENS link (`xn--…` host) tapped during the ENSIP-15
 * warm-up is parsed on [background] once the tables are decoded; an
 * ASCII link is parseable at once. Parsing each as soon as possible
 * would let an ASCII link that arrived *after* a Unicode one be
 * published — and get its tab — first. So a link is published synchronously only
 * when it is fast *and* nothing is still queued ahead of it; otherwise
 * it is chained behind the previous link's job.
 *
 * [submit] must be called from [scope]'s (main) thread; [publish] runs
 * there too.
 */
internal class OrderedDeepLinks(
    private val scope: CoroutineScope,
    private val background: CoroutineContext,
    private val publish: (String) -> Unit,
) {
    private var tail: Job? = null

    /**
     * Resolve and publish one link. [slow] says [resolve] must not run
     * on the calling thread; a `null` result is dropped (not one of our
     * origins) but still keeps its place in the order.
     */
    fun submit(slow: Boolean, resolve: () -> String?) {
        val previous = tail?.takeIf { it.isActive }
        if (!slow && previous == null) {
            resolve()?.let(publish)
            return
        }
        tail = scope.launch {
            previous?.join()
            val url = if (slow) withContext(background) { resolve() } else resolve()
            url?.let(publish)
        }
    }
}
