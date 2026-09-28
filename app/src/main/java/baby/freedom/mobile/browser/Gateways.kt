package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide holder + router for the content gateways.
 *
 * The ant gateway lives on a fixed port so [SwarmNode.GATEWAY_URL] is a
 * compile-time constant; the IPFS gateway binds to an ephemeral port at
 * startup, so the UI process mirrors it into [setIpfsBase] whenever the
 * `:node` process broadcasts a new [baby.freedom.swarm.IpfsInfo].
 *
 * Either can be replaced by an external endpoint the user configured
 * in Settings (#125, [ExternalEndpoints]); `MainActivity` mirrors those
 * into [setExternalEndpoints]. [swarmBase] / [ipfsBase] are what's in
 * use right now — the external endpoint when one is set, the embedded
 * node's gateway otherwise.
 *
 * Since the virtual-origin switch the WebView never loads gateway URLs
 * directly: [toLoadable] hands it a per-root `https://….freedom.baby`
 * URL (see [VirtualOrigin]) and the interceptor translates back to the
 * gateway via [gatewayUrlFor]. [toGatewayUrl] keeps the direct mapping
 * for the pieces that talk to the node itself ([GatewayProbe], the
 * interceptor).
 */
object Gateways {
    /** The embedded ant node's gateway. */
    const val EMBEDDED_SWARM_BASE: String = SwarmNode.GATEWAY_URL

    /*
     * `@Volatile` (and state flows below) because the writers (the AIDL
     * callback on the Binder thread, the settings collector on the main
     * thread) and the readers (webview interceptors, suspend navigation
     * gates on the UI and IO threads) are all on different threads.
     */
    @Volatile
    private var embeddedIpfsBase: String = ""

    private val externalSwarm = MutableStateFlow("")
    private val externalIpfs = MutableStateFlow("")

    /** External Swarm endpoint base URL, or `""` for the embedded node. */
    val externalSwarmBase: String get() = externalSwarm.value

    /** External IPFS gateway base URL, or `""` for the embedded node. */
    val externalIpfsBase: String get() = externalIpfs.value

    /** [externalIpfsBase] as a flow, for UI that must follow a switch. */
    val externalIpfsBaseFlow: StateFlow<String> = externalIpfs.asStateFlow()

    /**
     * Completed once the external endpoint settings are known.
     * Already complete unless [expectExternalEndpoints] armed it (the
     * app does, at start; tests that never load the settings don't).
     */
    @Volatile
    private var endpointsKnown: CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }

    /** The Swarm gateway in use: the external endpoint, else the embedded node's. */
    val swarmBase: String
        get() = externalSwarmBase.ifEmpty { EMBEDDED_SWARM_BASE }

    /**
     * The IPFS gateway in use: the external gateway, else the embedded
     * one's (e.g. `http://127.0.0.1:58312`), or `""` when there's no
     * external gateway and the embedded IPFS node isn't running.
     */
    val ipfsBase: String
        get() = externalIpfsBase.ifEmpty { embeddedIpfsBase }

    /** The embedded IPFS gateway's base, `""` while it isn't running. */
    fun setIpfsBase(base: String) {
        embeddedIpfsBase = base
    }

    /**
     * The external endpoint settings are being loaded: until
     * [setExternalEndpoints] lands, [awaitExternalEndpoints] waits. So a
     * cold-start deep link or restored tab can't reach the embedded
     * node's gateway before the setting is read — without blocking the
     * main thread on the read.
     */
    fun expectExternalEndpoints() {
        if (endpointsKnown.isCompleted) endpointsKnown = CompletableDeferred()
    }

    /** The user's external endpoints (`""` = embedded node); normalized base URLs. */
    fun setExternalEndpoints(swarm: String, ipfs: String) {
        externalSwarm.value = swarm
        externalIpfs.value = ipfs
        endpointsKnown.complete(Unit)
    }

    /** Wait until the external endpoint settings are known (see [expectExternalEndpoints]). */
    suspend fun awaitExternalEndpoints() {
        withTimeoutOrNull(ENDPOINTS_WAIT_MS) { endpointsKnown.await() }
    }

    /**
     * [awaitExternalEndpoints] for the request interceptor, which runs
     * on WebView's IO threads (never the main thread). Returns at once
     * in the common case.
     */
    fun awaitExternalEndpointsBlocking() {
        val known = endpointsKnown
        if (known.isCompleted) return
        runBlocking { withTimeoutOrNull(ENDPOINTS_WAIT_MS) { known.await() } }
    }

    /** A bound on [awaitExternalEndpoints]: a DataStore read takes milliseconds. */
    private const val ENDPOINTS_WAIT_MS = 5_000L

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
     * Rewrite a user-facing URL to the direct gateway URL
     * (`http://127.0.0.1:<port>/…`, or the external endpoint's) — the
     * pre-virtual-origin [toLoadable]. Used by [GatewayProbe] (which
     * polls the node, not the WebView) and as the malformed-id fallback
     * above. IPFS URLs pass through unchanged while no IPFS gateway is
     * available yet.
     */
    fun toGatewayUrl(url: String): String {
        if (url.startsWith("bzz://")) return SwarmResolver.toLoadable(url, swarmBase)
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
        val swarm = SwarmResolver.toDisplay(url, swarmBase)
        if (swarm != url) return swarm
        val ipfsNow = ipfsBase
        if (ipfsNow.isNotEmpty()) {
            val ipfs = IpfsGateway.toDisplay(url, ipfsNow)
            if (ipfs != url) return ipfs
        }
        return url
    }

    /**
     * Does [url] belong to a gateway in use right now — the embedded
     * nodes', or the external endpoints that replace them?
     */
    fun isLocalGateway(url: String): Boolean {
        if (url.startsWith("$swarmBase/")) return true
        val ipfs = ipfsBase
        return ipfs.isNotEmpty() && url.startsWith("$ipfs/")
    }

    /**
     * Does [url] belong to one of the *embedded* nodes' gateways
     * (`http://127.0.0.1:…`)? Unlike [isLocalGateway] this never matches
     * an external endpoint (#125): the interceptor answers CORS
     * preflights for the embedded node API itself, but an external node
     * is somebody else's server with its own CORS policy, which a web
     * page must not be able to skip.
     */
    fun isEmbeddedGateway(url: String): Boolean {
        if (url.startsWith("$EMBEDDED_SWARM_BASE/")) return true
        val ipfs = embeddedIpfsBase
        return ipfs.isNotEmpty() && url.startsWith("$ipfs/")
    }

    /**
     * The external IPFS gateway [gatewayUrl] (a [gatewayUrlFor] answer)
     * is served from, or `null` when it isn't one — an embedded node's,
     * or an external Swarm endpoint's.
     */
    fun externalIpfsGatewayOf(gatewayUrl: String): String? {
        val ipfs = externalIpfsBase
        return ipfs.takeIf { it.isNotEmpty() && gatewayUrl.startsWith("$it/") }
    }

    /**
     * Translate a [ContentRoot] + path/query into the gateway URL that
     * actually serves it — the interceptor's core mapping. Returns
     * `null` when the backing node isn't available (no IPFS gateway
     * yet) or an ENS name doesn't resolve; the interceptor turns that
     * into a clean synthesized error instead of a hanging load.
     *
     * An ENS root is served from the incoming [page]'s pin (a main-frame
     * document that was just checked), then the requesting tab's [pins]
     * for the page on screen, then the session registry.
     */
    fun gatewayUrlFor(
        root: ContentRoot,
        pathAndQuery: String,
        pins: EnsDocumentPins? = null,
        page: EnsDocumentPins.Page? = null,
    ): String? = when (root) {
        is ContentRoot.Bzz -> "$swarmBase/bzz/${root.ref}$pathAndQuery"
        is ContentRoot.Ipfs -> ipfsBase.ifEmpty { null }?.let { "$it/ipfs/${root.cid}$pathAndQuery" }
        is ContentRoot.IpnsKey -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.key}$pathAndQuery" }
        is ContentRoot.IpnsName -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.name}$pathAndQuery" }
        is ContentRoot.Ens ->
            ((page?.uriFor(root.name) ?: pins?.uriFor(root.name))
                ?.let { VirtualOrigin.parseContentUrl(it)?.first }
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
    internal fun resolveEnsRoot(name: String): ContentRoot? {
        KnownEnsNames.uriFor(name)?.let { uri ->
            VirtualOrigin.parseContentUrl(uri)?.let { return it.first }
        }
        val result = ensLookup(name)
        // One server's word isn't served unasked (#96); the submit flow
        // records what the user let through.
        if (result is EnsResult.Ok && result.trust.verified) {
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
     * Two more refusals since the RPC cross-check (#96): servers that
     * disagree (`ens_conflict`), and an answer only one server gave that
     * isn't what this tab or the session already had
     * (`ens_unverified`) — the same answer again is served, since it was
     * cross-checked before or the user let it through. Neither forgets
     * the name's earlier answer. That includes one server alone saying
     * the name has no (loadable) content: with an earlier answer it's
     * `ens_unverified`, not `ens_not_found`, and the answer is kept.
     *
     * A main-frame document passes its incoming [page]
     * ([EnsDocumentPins.beginNavigation]); the answer is pinned there, and
     * reaches the page on screen only once that document commits. An
     * iframe passes the page on screen ([EnsDocumentPins.pageFor]); with
     * no [page] the answer pins whatever page is current.
     *
     * Returns `null` when the document may be served (its root is now
     * pinned), or the [ErrorPage] code for the refusal. A refusal that
     * is an *answer* (the name has no loadable content any more) drops
     * the name from the session registry and from this tab's last
     * answers, so neither the address bar's protocol badge / hash-to-name
     * mapping nor a later failed lookup describes content the name no
     * longer points at. Pages already on screen keep their own pins.
     */
    fun reverifyEnsDocument(
        name: String,
        pins: EnsDocumentPins? = null,
        page: EnsDocumentPins.Page? = null,
    ): String? {
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
        fun gone(code: String): String {
            KnownEnsNames.forgetName(name)
            pins?.forgetLastAnswer(name)
            return code
        }
        // "Nothing loadable here" on one server's word only (#96) — the
        // others failed or never answered. With an earlier answer to
        // lose, that's a claim to question, not to act on: one server
        // mustn't turn a real record into "no resolver". Refused, the
        // name's earlier answer kept.
        fun noContent(code: String, trust: EnsTrust): String =
            if (!trust.verified && last != null) "ens_unverified" else gone(code)
        return when (result) {
            is EnsResult.Ok -> {
                if (VirtualOrigin.parseContentUrl(result.uri) == null) {
                    noContent("ens_unsupported_codec", result.trust)
                } else if (!result.trust.verified &&
                    result.uri != pins?.lastAnswerFor(name) &&
                    result.uri != KnownEnsNames.uriFor(name)
                ) {
                    // Only one RPC server's word, and not for what this
                    // tab or the session already had — cross-checked
                    // then, or let through by the user (#96).
                    "ens_unverified"
                } else {
                    KnownEnsNames.record(result.uri, name)
                    pins?.pin(name, result.uri, page)
                    null
                }
            }
            is EnsResult.NotFound -> noContent("ens_not_found", result.trust)
            is EnsResult.Unsupported -> noContent("ens_unsupported_codec", result.trust)
            // Servers disagree about the name right now (#96). Not the
            // name's answer to forget, but nothing to serve on either.
            is EnsResult.Conflict -> "ens_conflict"
            // Failed, or still running at the deadline: not an answer.
            is EnsResult.Error, null -> {
                if (last == null) {
                    "ens_lookup_failed"
                } else {
                    pins?.pin(name, last, page)
                    null
                }
            }
        }
    }
}

/**
 * One tab's `name → content URI` answers (#99), in three parts:
 *
 * - **the current page's**: the root each ENS document of the page on
 *   screen (its main frame and its iframes) was served from. Its
 *   subresources come from the same root even if another tab — or a
 *   later Back in this one — re-checks the name and gets a newer
 *   answer, so an already-loaded page never gets its lazy chunks from
 *   a different version. Read by [Gateways.gatewayUrlFor].
 * - **the incoming page's** ([Page] from [beginNavigation]): what the
 *   main-frame request now in flight was checked against. It replaces
 *   the current page's only once that document *commits*, which WebView
 *   reports in `onPageStarted` ([documentStarted]). A navigation that
 *   never commits (a download link, Stop, `window.stop()`, a 204) leaves
 *   the page on screen where it is and so must leave its pins alone too.
 * - **the tab's last answer** per name, kept across pages: what a
 *   re-check whose lookup failed falls back on.
 *
 * Between the interceptor handing WebView a document that renders in
 * place ([delivered]) and `onPageStarted`, a subresource request can't
 * say which document it belongs to: the new one may already have
 * committed and be parsing, or the navigation may have been cancelled
 * and the request is the old page's. Unless the two pages pin the name
 * to the same root, [pageFor] holds the request until the answer is
 * known — the
 * commit (`onPageStarted`), or [commitWaitMs] passing without one, which
 * means the navigation didn't commit (Stop / `window.stop()` give no
 * other signal) and the request is the old page's.
 *
 * Written by [Gateways.reverifyEnsDocument]. Owned by the tab's WebView
 * client; the interceptor runs on WebView's IO threads and
 * `onPageStarted` on the main thread, hence the concurrent maps and the
 * lock around the page swap.
 */
class EnsDocumentPins {
    /** One document's pins; [url] is the main-frame URL it was requested for. */
    class Page internal constructor(internal val url: String?) {
        internal val pins = ConcurrentHashMap<String, String>()

        /** When the interceptor handed WebView this page's document; 0 = not yet. */
        internal var deliveredAt = 0L

        fun uriFor(name: String): String? = pins[name.lowercase()]
    }

    private val lock = ReentrantLock()
    private val swapped = lock.newCondition()

    @Volatile
    private var current = Page(null)
    private var pending: Page? = null
    private val last = ConcurrentHashMap<String, String>()

    /**
     * Pin [name] to [uri] for [page] (the incoming page from
     * [beginNavigation]), or for the page on screen when `null`.
     */
    fun pin(name: String, uri: String, page: Page? = null) {
        (page ?: current).pins[name.lowercase()] = uri
        last[name.lowercase()] = uri
    }

    /** The root the current page's documents on [name] were served from. */
    fun uriFor(name: String): String? = current.uriFor(name)

    /**
     * The page a subresource (or iframe) request on [name] belongs to:
     * the page on screen, once it is known which page that is. While a
     * delivered navigation is waiting on its commit, only a name both
     * pages pin to the same root is answered at once; for any other —
     * pinned differently, or pinned by neither (a new iframe the incoming
     * document may be asking for, whose answer must land in *its* pins)
     * — wait for `onPageStarted`, at most until [commitWaitMs] after
     * delivery, after which it didn't commit and the page on screen is
     * still the old one. Never call on the main thread.
     */
    fun pageFor(name: String): Page {
        val key = name.lowercase()
        lock.withLock {
            while (true) {
                val incoming = pending
                val agreed = incoming?.pins?.get(key)
                if (incoming == null || incoming.deliveredAt == 0L ||
                    (agreed != null && agreed == current.pins[key])
                ) {
                    return current
                }
                val left = incoming.deliveredAt + commitWaitMs - System.currentTimeMillis()
                if (left <= 0) return current
                swapped.await(left, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** The last answer this tab had for [name], on any page. */
    fun lastAnswerFor(name: String): String? = last[name.lowercase()]

    /**
     * The name answered that it no longer points at loadable content:
     * a later failed lookup must not bring its old root back.
     */
    fun forgetLastAnswer(name: String) {
        last.remove(name.lowercase())
    }

    /**
     * The main frame requests [url]: start the incoming page's pins. The
     * page on screen keeps its own until this one commits.
     */
    fun beginNavigation(url: String): Page = lock.withLock {
        Page(url.substringBefore('#')).also {
            pending = it
            swapped.signalAll()
        }
    }

    /**
     * The interceptor handed WebView [page]'s document, one that renders
     * in place: it commits next — unless the navigation was cancelled
     * meanwhile. Not a commit: the page on screen keeps its pins until
     * [documentStarted] says so (see [pageFor]).
     */
    fun delivered(page: Page) {
        lock.withLock {
            if (pending === page) page.deliveredAt = System.currentTimeMillis()
        }
    }

    /**
     * `onPageStarted([url])`: a main-frame document committed. The
     * incoming page for that URL becomes current; a document that never
     * went through the interceptor (bfcache, `data:`) starts with no pins
     * rather than inheriting the previous page's.
     */
    fun documentStarted(url: String?) {
        lock.withLock {
            val key = url?.substringBefore('#')
            val incoming = pending
            when {
                incoming != null && incoming.url == key -> {
                    current = incoming
                    pending = null
                }
                current.url != key -> current = Page(key)
            }
            swapped.signalAll()
        }
    }

    companion object {
        /**
         * How long after its document was handed over a navigation has to
         * commit before [pageFor] takes it as cancelled. A commit follows
         * the hand-over within milliseconds; this only bounds how long an
         * old page's subresources wait after a Stop.
         */
        @Volatile
        internal var commitWaitMs = 2_000L
    }
}
