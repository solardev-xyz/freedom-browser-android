package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide holder + router for the local content gateways.
 *
 * The ant gateway lives on a fixed port so [SwarmNode.GATEWAY_URL] is a
 * compile-time constant; the IPFS gateway binds to an ephemeral port at
 * startup, so the UI process mirrors it into [ipfsBase] whenever the
 * `:node` process broadcasts a new [baby.freedom.swarm.IpfsInfo].
 *
 * Since the virtual-origin switch the WebView never loads gateway URLs
 * directly: [toLoadable] hands it a per-root `https://….freedom.baby`
 * URL (see [VirtualOrigin]) and the interceptor translates back to the
 * gateway via [gatewayUrlFor]. [toGatewayUrl] keeps the direct mapping
 * for the pieces that talk to the node itself ([GatewayProbe], the
 * interceptor).
 */
object Gateways {
    const val SWARM_BASE: String = SwarmNode.GATEWAY_URL

    /**
     * Base URL of the embedded IPFS gateway (e.g.
     * `http://127.0.0.1:58312`), or `""` when IPFS isn't running.
     *
     * `@Volatile` because the AIDL callback that writes this value runs
     * on the Binder thread while readers (webview interceptors,
     * suspend navigation gates) live on both the UI and IO threads.
     */
    @Volatile
    var ipfsBase: String = ""
        private set

    fun setIpfsBase(base: String) {
        ipfsBase = base
    }

    /**
     * Shared ENS resolver. One instance so the submit flow and the
     * request interceptor (which resolves `<name>.ens.…` hosts) share
     * a cache and never disagree mid-session.
     */
    val ensResolver: EnsResolver by lazy { EnsResolver() }

    /**
     * Blocking ENS lookup used by the request interceptor. A seam so the
     * instrumented WebView suite can answer for a fixture name without
     * reaching a real RPC; production always goes through [ensResolver].
     */
    @Volatile
    internal var ensLookup: (String) -> EnsResult =
        { name -> runBlocking { ensResolver.resolveContenthash(name) } }

    /**
     * How long a document re-check ([reverifyEnsDocument]) waits for the
     * resolver when there *is* an earlier answer to fall back on. The
     * resolver can take a minute to give up on a stalled network (five
     * endpoints, 15 s each); before #99 Back served the page instantly,
     * so the re-check may cost a moment, not that. A lookup still
     * running at the deadline carries on in the background (and warms
     * the resolver's cache for the next load); the document is served
     * from the last answer meanwhile. `internal var` for tests.
     */
    @Volatile
    internal var reverifyDeadlineMs: Long = 3_000

    /**
     * After a re-check that failed or ran out of time, later documents
     * for the same name within this window don't wait for the resolver
     * again: an `Error` isn't cached by the resolver, so each iframe and
     * each Back would otherwise pay [reverifyDeadlineMs] anew on a dead
     * network. They join the lookup still in flight, if any, without
     * waiting on it. `internal var` for tests.
     *
     * The window is kept by the lookups themselves, never by a document
     * that merely skipped the wait: a lookup that fails re-opens it when
     * it finishes, one that *answers* (in the background or not) closes
     * it, so the next document waits for — and takes — a fresh answer
     * instead of staying on the stale root for as long as documents keep
     * arriving inside the window.
     */
    @Volatile
    internal var reverifyFailureWindowMs: Long = 30_000

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookupsInFlight = ConcurrentHashMap<String, Deferred<EnsResult>>()
    private val lookupFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Forget recent lookup failures and detach lookups still in flight
     * (tests swapping [ensLookup]); a detached lookup's outcome is not
     * recorded.
     */
    internal fun resetEnsLookupState() {
        lookupsInFlight.clear()
        lookupFailedAt.clear()
    }

    /**
     * [ensLookup] for [name], shared with any lookup for it already in
     * flight, waiting at most [deadlineMs] (`null` = until it answers).
     * `null` when the deadline passed first; the lookup keeps running.
     */
    private fun lookupWithin(name: String, deadlineMs: Long?): EnsResult? {
        val key = name.lowercase()
        var started: Deferred<EnsResult>? = null
        val job = lookupsInFlight.computeIfAbsent(key) {
            lookupScope.async(start = CoroutineStart.LAZY) {
                try {
                    ensLookup(name)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    EnsResult.Error(name, "PROVIDER_ERROR", e.message.orEmpty(), retryable = true)
                }
            }.also { started = it }
        }
        started?.let { own ->
            own.invokeOnCompletion { cause ->
                if (!lookupsInFlight.remove(key, own) || cause != null) return@invokeOnCompletion
                // Record the outcome whoever (if anyone) is still waiting:
                // a failure opens the failure window, an answer closes it.
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val outcome = own.getCompleted()
                if (outcome is EnsResult.Error) {
                    lookupFailedAt[key] = System.currentTimeMillis()
                } else {
                    lookupFailedAt.remove(key)
                }
            }
            own.start()
        }
        val result = runBlocking {
            if (deadlineMs == null) job.await()
            else if (deadlineMs <= 0) (if (job.isCompleted) job.await() else null)
            else withTimeoutOrNull(deadlineMs) { job.await() }
        }
        // Waited the full deadline and it's still running: later documents
        // shouldn't wait for it again. Not for a zero deadline — that
        // document skipped the wait, it didn't find the network any slower,
        // and the running lookup records its own outcome when it finishes.
        if (result == null && deadlineMs != null && deadlineMs > 0 && !job.isCompleted) {
            lookupFailedAt[key] = System.currentTimeMillis()
        }
        return result
    }

    /**
     * Rewrite a user-facing URL (`bzz://` / `ipfs://` / `ipns://` /
     * `ens://`) to what the WebView should load: the per-root virtual
     * https origin. Ids that can't be label-encoded (malformed refs)
     * fall back to the direct gateway URL so the gateway's own error
     * surfaces instead of `ERR_UNKNOWN_URL_SCHEME`. `http(s)://` and
     * other external URLs are returned unchanged.
     */
    fun toLoadable(url: String): String {
        VirtualOrigin.toVirtualUrl(url)?.let { return it }
        return toGatewayUrl(url)
    }

    /**
     * Rewrite a user-facing URL to the direct
     * `http://127.0.0.1:<port>/…` gateway URL — the pre-virtual-origin
     * [toLoadable]. Used by [GatewayProbe] (which polls the node, not
     * the WebView) and as the malformed-id fallback above. IPFS URLs
     * pass through unchanged while the IPFS node hasn't published a
     * gateway yet.
     */
    fun toGatewayUrl(url: String): String {
        if (url.startsWith("bzz://")) return SwarmResolver.toLoadable(url)
        if (IpfsGateway.isIpfsScheme(url)) return IpfsGateway.toLoadable(url, ipfsBase)
        return url
    }

    /**
     * Inverse of [toLoadable]: map a loaded URL (virtual-origin https
     * or raw gateway http) back to the user-facing display form.
     * Returns the input unchanged if it's neither.
     */
    fun toDisplay(url: String): String {
        VirtualOrigin.displayUrlFor(url)?.let { return it }
        val swarm = SwarmResolver.toDisplay(url)
        if (swarm != url) return swarm
        if (ipfsBase.isNotEmpty()) {
            val ipfs = IpfsGateway.toDisplay(url, ipfsBase)
            if (ipfs != url) return ipfs
        }
        return url
    }

    /** Does [url] belong to any currently-active local gateway origin? */
    fun isLocalGateway(url: String): Boolean {
        if (url.startsWith("$SWARM_BASE/")) return true
        val ipfs = ipfsBase
        return ipfs.isNotEmpty() && url.startsWith("$ipfs/")
    }

    /**
     * Translate a [ContentRoot] + path/query into the gateway URL that
     * actually serves it — the interceptor's core mapping. Returns
     * `null` when the backing node isn't available (no IPFS gateway
     * yet) or an ENS name doesn't resolve; the interceptor turns that
     * into a clean synthesized error instead of a hanging load.
     *
     * An ENS root is served from the requesting tab's [pins] first — the
     * root its document was just checked against — then the session
     * registry.
     */
    fun gatewayUrlFor(
        root: ContentRoot,
        pathAndQuery: String,
        pins: EnsDocumentPins? = null,
    ): String? = when (root) {
        is ContentRoot.Bzz -> "$SWARM_BASE/bzz/${root.ref}$pathAndQuery"
        is ContentRoot.Ipfs -> ipfsBase.ifEmpty { null }?.let { "$it/ipfs/${root.cid}$pathAndQuery" }
        is ContentRoot.IpnsKey -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.key}$pathAndQuery" }
        is ContentRoot.IpnsName -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.name}$pathAndQuery" }
        is ContentRoot.Ens ->
            (pins?.uriFor(root.name)?.let { VirtualOrigin.parseContentUrl(it)?.first }
                ?: resolveEnsRoot(root.name))
                ?.let { gatewayUrlFor(it, pathAndQuery) }
    }

    /**
     * `<name>.ens.…` hosts serve whatever the name's contenthash points
     * at *right now* — the origin stays name-derived (storage survives
     * content updates), resolution happens here per request.
     *
     * The submit flow resolves + records every name before navigating,
     * so the registry hit is the hot path; the blocking fallback covers
     * subresource fetches after a process restart (restored tab) and
     * uses the resolver's own TTL cache after the first call. Blocking
     * is fine — the interceptor never runs on the UI thread.
     */
    private fun resolveEnsRoot(name: String): ContentRoot? {
        KnownEnsNames.uriFor(name)?.let { uri ->
            VirtualOrigin.parseContentUrl(uri)?.let { return it.first }
        }
        val result = ensLookup(name)
        if (result is EnsResult.Ok) {
            KnownEnsNames.record(result.uri, name)
            return VirtualOrigin.parseContentUrl(result.uri)?.first
        }
        return null
    }

    /**
     * Resolve [name] again for a document on its `<name>.ens.…` host
     * (the top-level page or an iframe), and make this tab's [pins] —
     * and the session registry — follow the answer (#99).
     *
     * The registry used to be the only thing a name was served from,
     * and only the submit flow ever wrote it — so Back / Forward (the
     * bar, the system gesture, a page's own `history.back()`), which
     * restore a history entry without going through submit, served the
     * page from whatever the name resolved to when it was first
     * visited, however long ago. Every document load now takes the same
     * lookup a typed navigation does: the resolver's answer, subject to
     * the resolver's own freshness rules.
     *
     * The answer is pinned in the tab's own [pins], which is what that
     * tab's subresources are served from ([gatewayUrlFor]): a re-check
     * in one tab must not move another tab's already-loaded document
     * onto a different root halfway through its lazy chunks. The
     * process-global registry is still updated — it names the hash in
     * the address bar and serves requests that belong to no tab
     * (service workers).
     *
     * Only an *answer* refuses the document: `NotFound` / `Unsupported`
     * say the name no longer points at loadable content. A lookup that
     * merely failed (RPC unreachable) serves the last answer this tab
     * or this session had for the name, as it did before the re-check
     * existed — the network being down is no reason to stop Back from
     * working. With no earlier answer at all it's `ens_lookup_failed`.
     * The same goes for a lookup that hasn't answered within
     * [reverifyDeadlineMs] when there is an earlier answer: a stalled
     * network costs Back a few seconds at most, and nothing on the
     * next loads of the name while [reverifyFailureWindowMs] runs.
     * With no earlier answer the document waits for the resolver, as a
     * typed navigation does.
     *
     * Returns `null` when the document may be served (its root is now
     * pinned), or the [ErrorPage] code for the refusal. A refusal
     * leaves the registry and the pins untouched.
     */
    fun reverifyEnsDocument(name: String, pins: EnsDocumentPins? = null): String? {
        val key = name.lowercase()
        val last = (pins?.lastAnswerFor(name) ?: KnownEnsNames.uriFor(name))
            ?.takeIf { VirtualOrigin.parseContentUrl(it) != null }
        // With something to fall back on, don't hold the document for
        // the resolver's worst case (see [reverifyDeadlineMs]).
        val deadline = when {
            last == null -> null
            lookupFailedAt[key]?.let {
                System.currentTimeMillis() - it < reverifyFailureWindowMs
            } == true -> 0L
            else -> reverifyDeadlineMs
        }
        // [lookupWithin] keeps [lookupFailedAt] — see [reverifyFailureWindowMs].
        val result = lookupWithin(name, deadline)
        return when (result) {
            is EnsResult.Ok -> {
                if (VirtualOrigin.parseContentUrl(result.uri) == null) {
                    "ens_unsupported_codec"
                } else {
                    KnownEnsNames.record(result.uri, name)
                    pins?.pin(name, result.uri)
                    null
                }
            }
            is EnsResult.NotFound -> "ens_not_found"
            is EnsResult.Unsupported -> "ens_unsupported_codec"
            // Failed, or still running at the deadline: not an answer.
            is EnsResult.Error, null -> {
                if (last == null) {
                    "ens_lookup_failed"
                } else {
                    pins?.pin(name, last)
                    null
                }
            }
        }
    }
}

/**
 * One tab's `name → content URI` answers (#99), in two parts:
 *
 * - **the current page's**: the root each ENS document of the page on
 *   screen (its main frame and its iframes) was served from. Its
 *   subresources come from the same root even if another tab — or a
 *   later Back in this one — re-checks the name and gets a newer
 *   answer, so an already-loaded page never gets its lazy chunks from
 *   a different version. Cleared by [newPage] when the tab's main frame
 *   requests a new document; read by [Gateways.gatewayUrlFor].
 * - **the tab's last answer** per name, kept across pages: what a
 *   re-check whose lookup failed falls back on.
 *
 * Written by [Gateways.reverifyEnsDocument]. Owned by the tab's WebView
 * client; the interceptor runs on WebView's IO threads, hence the
 * concurrent maps.
 */
class EnsDocumentPins {
    private val page = ConcurrentHashMap<String, String>()
    private val last = ConcurrentHashMap<String, String>()

    fun pin(name: String, uri: String) {
        page[name.lowercase()] = uri
        last[name.lowercase()] = uri
    }

    /** The root the current page's documents on [name] were served from. */
    fun uriFor(name: String): String? = page[name.lowercase()]

    /** The last answer this tab had for [name], on any page. */
    fun lastAnswerFor(name: String): String? = last[name.lowercase()]

    /** The main frame is loading a new document: the page's pins go. */
    fun newPage() = page.clear()
}
