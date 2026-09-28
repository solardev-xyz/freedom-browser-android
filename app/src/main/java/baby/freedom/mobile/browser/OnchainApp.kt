package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataResult
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.RoutingContext
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * A contract-hosted application (draft ERC-8244, #123): an app whose
 * whole document is the return value of the contract's `html()` view on
 * an EVM chain. The Android port of desktop's
 * `src/main/onchain/onchain-app-protocol.js` and iOS's `OnchainApp`.
 *
 * Two URL shapes carry the same app:
 *
 * ```
 * address bar shows:   web3://<Contract>[:<chainId>]/…              (friendly)
 * WebView loads:       https://0x<contract>-<chainId>.web3.freedom.baby/…
 * ```
 *
 * The friendly form is ERC-4804's: omitting `:<chainId>` means Ethereum
 * mainnet. It's what the address bar, history, bookmarks and the error
 * pages show. The WebView loads a synthetic https origin instead — the
 * same trick as the dweb [VirtualOrigin]s: no DNS or TLS ever happens,
 * `shouldInterceptRequest` answers first ([interceptOnchainAppRequest]). One
 * host per contract-and-chain pair, so each pair gets its own
 * localStorage / IndexedDB / cookies, and the same contract on another
 * chain is another app. A single label (`0x` + 40 hex + `-` + at most
 * 16 digits of [Chain.MAX_ID] ≤ 59 characters) keeps every pair on its
 * own name under the suffix, as the dweb origins do; desktop's
 * `web3://<contract>.eip155-<chainId>` host plays the same part there.
 */
data class OnchainAppRef(
    /** Lowercase `0x…` contract address. */
    val address: String,
    val chainId: Long,
) {
    init {
        require(ADDRESS.matches(address)) { "not a lowercase address: $address" }
        require(chainId in 1..Chain.MAX_ID) { "chain id out of range: $chainId" }
    }

    /** EIP-55 mixed-case form of [address], for display. */
    val checksumAddress: String get() = checksum(address)

    /** The friendly `web3://` form, always with at least a root path. */
    fun displayUrl(tail: String = ""): String {
        val chain = if (chainId == DEFAULT_CHAIN_ID) "" else ":$chainId"
        return "$SCHEME://$checksumAddress$chain${normalizedTail(tail)}"
    }

    /**
     * The form for a link a page follows (an error page's Try Again, the
     * in-place refusal's Review): desktop's canonical
     * `web3://<contract>.eip155-<chainId>/`. Chromium parses the friendly
     * form's chain as a port, so `web3://0x…:424242/` — any chain ID above
     * 65535 — is an invalid URL it blocks before the app ever sees the
     * navigation; this one it hands over. [parse] reads both.
     */
    fun linkUrl(tail: String = ""): String = "$SCHEME://$address.eip155-$chainId${normalizedTail(tail)}"

    /** The chain-scoped origin the WebView loads. */
    val origin: String get() = "https://$host"

    val host: String get() = "$address-$chainId.$SUFFIX"

    fun virtualUrl(tail: String = ""): String = origin + normalizedTail(tail)

    /**
     * Desktop's `getPermissionKey`: `web3://<addr>` on mainnet,
     * `web3://<addr>:<chainId>` elsewhere, lowercase, no path — the
     * routing context of the app's reads.
     */
    val permissionKey: String
        get() = if (chainId == DEFAULT_CHAIN_ID) "$SCHEME://$address" else "$SCHEME://$address:$chainId"

    companion object {
        const val SCHEME = "web3"
        const val SUFFIX = "web3.${VirtualOrigin.BASE_DOMAIN}"
        const val DEFAULT_CHAIN_ID = 1L

        private val ADDRESS = Regex("^0x[0-9a-f]{40}$")
        private val FRIENDLY = Regex("^web3://(0x[0-9a-fA-F]{40})(?::([0-9]{1,16}))?([/?#].*)?$", RegexOption.IGNORE_CASE)
        private val CANONICAL = Regex("^web3://(0x[0-9a-fA-F]{40})\\.eip155-([0-9]{1,16})([/?#].*)?$", RegexOption.IGNORE_CASE)
        private val VIRTUAL_HOST = Regex("^(0x[0-9a-f]{40})-([0-9]{1,16})\\.web3\\.freedom\\.baby$")

        /** Does [input] use the `web3:` scheme at all (parseable or not)? */
        fun isWeb3Scheme(input: String): Boolean = input.trim().startsWith("$SCHEME:", ignoreCase = true)

        /**
         * The app and its percent-encoded `path?query#fragment` tail
         * (`""` for the root) of a friendly `web3://` URL (or of desktop's
         * canonical `web3://<contract>.eip155-<chainId>/`, [linkUrl]), or `null` if
         * [url] isn't one this browser can load: a contract address, not
         * an ENS name (ERC-4804's other form, not supported yet), and a
         * chain ID in `1..`[Chain.MAX_ID].
         */
        fun parse(url: String): Pair<OnchainAppRef, String>? {
            val m = FRIENDLY.matchEntire(url.trim()) ?: CANONICAL.matchEntire(url.trim()) ?: return null
            val chain = m.groupValues[2]
            val chainId = if (chain.isEmpty()) DEFAULT_CHAIN_ID else chain.toLongOrNull() ?: return null
            if (chainId !in 1..Chain.MAX_ID) return null
            return OnchainAppRef(m.groupValues[1].lowercase(), chainId) to tailOf(m.groupValues[3])
        }

        /** The app a chain-scoped origin URL belongs to, and its tail; `null` for any other URL. */
        fun parseVirtual(url: String?): Pair<OnchainAppRef, String>? {
            if (url == null || !url.startsWith("https://", ignoreCase = true)) return null
            val rest = url.substring("https://".length)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (end >= 0) rest.substring(0, end) else rest
            val m = VIRTUAL_HOST.matchEntire(authority.lowercase()) ?: return null
            val chainId = m.groupValues[2].toLongOrNull()?.takeIf { it in 1..Chain.MAX_ID } ?: return null
            return OnchainAppRef(m.groupValues[1], chainId) to tailOf(if (end >= 0) rest.substring(end) else "")
        }

        fun isVirtualUrl(url: String?): Boolean = parseVirtual(url) != null

        /** Friendly `web3://` → the URL the WebView loads; `null` if [url] isn't one. */
        fun toVirtualUrl(url: String): String? = parse(url)?.let { (app, tail) -> app.virtualUrl(tail) }

        /** Inverse of [toVirtualUrl]; `null` if [url] isn't on a chain-scoped origin. */
        fun displayUrlFor(url: String): String? = parseVirtual(url)?.let { (app, tail) -> app.displayUrl(tail) }

        private fun tailOf(raw: String): String = if (raw == "/") "" else raw

        private fun normalizedTail(tail: String): String = when {
            tail.isEmpty() -> "/"
            tail.startsWith("/") -> tail
            else -> "/$tail"
        }

        /** EIP-55: uppercase each hex letter whose nibble in keccak256(lowercase hex) is ≥ 8. */
        fun checksum(address: String): String {
            val hex = address.removePrefix("0x").lowercase()
            val hash = Keccak256.digest(hex.toByteArray(Charsets.US_ASCII)).toHex()
            return "0x" + hex.mapIndexed { i, c ->
                if (c in 'a'..'f' && hash[i].digitToInt(16) >= 8) c.uppercaseChar() else c
            }.joinToString("")
        }
    }
}

/**
 * A fetched `html()` document and how it was read — what the tab hands
 * to the interceptor, and what the warning shows while the user decides.
 */
class OnchainDocument(
    val app: OnchainAppRef,
    val html: String,
    /** `0x…` keccak256 of the UTF-8 bytes: the identity the user approves. */
    val hash: String,
    val trust: ChainTrust,
    /** The chain's name as the user's chain list has it. */
    val network: String,
) {
    /**
     * RPCs answered differently and nothing verified the answer: the
     * bytes can't be trusted, and no "Continue once" is offered
     * (desktop's hard block).
     */
    val conflict: Boolean get() = trust.dissented.isNotEmpty() && trust.level != ChainTrust.Level.VERIFIED

    /** Loads without asking: a proof, a quorum, or the user's own RPC. */
    val trusted: Boolean
        get() = !conflict && (trust.level == ChainTrust.Level.VERIFIED || trust.level == ChainTrust.Level.USER_CONFIGURED)

    /** Who answered, for the warning. */
    val source: String get() = trust.agreed.firstOrNull() ?: trust.queried.firstOrNull() ?: trust.source.key

    /** The warning's details: what the user is being asked to run, whole. */
    fun unverifiedDetail(): String = listOf(
        "Network: $network (chain ${app.chainId})",
        "Contract: ${app.checksumAddress}",
        "HTML keccak256: $hash",
        "From: $source (not cross-checked)",
    ).joinToString("\n")

    fun conflictDetail(): String = listOf(
        "Network: $network (chain ${app.chainId})",
        "Contract: ${app.checksumAddress}",
        "Answered: ${trust.agreed.joinToString(", ").ifEmpty { "?" }}",
        "Disagreed: ${trust.dissented.joinToString(", ")}",
    ).joinToString("\n")
}

/** [OnchainAppLoader.load]'s answer. */
sealed interface OnchainLoad {
    class Loaded(val document: OnchainDocument) : OnchainLoad

    /** Nothing to load: [code] is the [ErrorPage] code, [detail] says why. */
    data class Failed(val code: String, val detail: String) : OnchainLoad
}

/**
 * Reads an app's document: one `eth_call` of `html()` at `latest`
 * through the [ChainDataRouter] — Myotis, Colibri, the RPC quorum, a
 * single RPC, per the chain's policy — with the app's
 * [OnchainAppRef.permissionKey] as a page's routing context, and *who
 * answered* on the result. Bounded by [timeoutMs] overall, whatever the
 * tiers underneath would take.
 */
class OnchainAppLoader(
    private val request: suspend (chainId: Long, method: String, params: JSONArray, context: RoutingContext) -> ChainDataResult,
    private val chains: suspend () -> List<Chain>,
) {
    suspend fun load(app: OnchainAppRef, timeoutMs: Long = REQUEST_TIMEOUT_MS): OnchainLoad {
        val chain = runCatching { chains() }.getOrNull()?.firstOrNull { it.id == app.chainId }
            ?: return OnchainLoad.Failed(
                "web3_unknown_chain",
                "Chain ${app.chainId} isn't in your chain list. Add it in Settings → Chains.",
            )
        val params = JSONArray()
            .put(JSONObject().put("to", app.address).put("data", HTML_SELECTOR))
            .put("latest")
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                request(app.chainId, "eth_call", params, RoutingContext.forPage(app.permissionKey))
            } ?: return OnchainLoad.Failed("web3_lookup_failed", "No answer within ${timeoutMs / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChainRpcException.UnknownChain) {
            return OnchainLoad.Failed("web3_unknown_chain", "Chain ${app.chainId} isn't in your chain list.")
        } catch (e: ChainRpcException.Rpc) {
            // A revert is the contract's own answer: it has no html().
            return OnchainLoad.Failed("web3_not_an_app", "html() failed: ${e.rpcMessage}")
        } catch (e: ChainRpcException) {
            Log.i(TAG, "[onchain] html() chain=${app.chainId} failed: ${e.message}")
            return OnchainLoad.Failed("web3_lookup_failed", e.message ?: "No RPC answered")
        }
        val html = when (val decoded = decodeHtml(result.result as? String)) {
            is Decoded.Html -> decoded.html
            Decoded.TooLarge -> return OnchainLoad.Failed(
                "web3_too_large",
                "The document is larger than ${MAX_HTML_BYTES / (1024 * 1024)} MiB.",
            )
            Decoded.Malformed -> return OnchainLoad.Failed(
                "web3_not_an_app",
                "html() returned no ERC-8244 document (is this an onchain app, on this chain?)",
            )
        }
        val doc = OnchainDocument(app, html, htmlHash(html), result.trust, chain.name)
        Log.i(TAG, "[onchain] html() chain=${app.chainId} via ${result.trust.source.key} " +
            "${result.trust.level.name.lowercase()} dissent=${result.trust.dissented.size}")
        return OnchainLoad.Loaded(doc)
    }

    internal sealed interface Decoded {
        class Html(val html: String) : Decoded
        data object TooLarge : Decoded
        data object Malformed : Decoded
    }

    companion object {
        private const val TAG = "OnchainApp"

        /** `bytes4(keccak256("html()"))`. */
        const val HTML_SELECTOR = "0x33c34ac3"

        /**
         * Desktop's decoded-document cap. The RPC transport's own 8 MiB
         * response cap ([baby.freedom.mobile.chains.rpc.PinnedHttpTransport])
         * comes first on Android: hex doubles the bytes, so a document
         * over about 4 MiB never gets this far.
         */
        const val MAX_HTML_BYTES = 8 * 1024 * 1024

        /** Wall-clock budget for the `html()` read across every tier (desktop's and iOS's). */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /**
         * ABI-decode `html() returns (string)`. The encoded size is
         * checked before decoding, so an oversized answer is never
         * materialized: ABI adds an offset word, a length word and up to
         * 31 bytes of padding.
         */
        internal fun decodeHtml(result: String?): Decoded {
            if (result == null || !result.startsWith("0x")) return Decoded.Malformed
            val hex = result.substring(2)
            if (hex.length % 2 != 0 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                return Decoded.Malformed
            }
            val size = hex.length / 2
            if (size > MAX_HTML_BYTES + 95) return Decoded.TooLarge
            if (size < 64) return Decoded.Malformed
            val offset = word(hex, 0) ?: return Decoded.Malformed
            if (offset.toLong() + 32 > size || offset % 32 != 0) return Decoded.Malformed
            val length = word(hex, offset) ?: return Decoded.Malformed
            if (length > MAX_HTML_BYTES) return Decoded.TooLarge
            val start = offset + 32
            if (start.toLong() + length > size) return Decoded.Malformed
            val bytes = ByteArray(length) { i ->
                val p = (start + i) * 2
                ((hex[p].digitToInt(16) shl 4) or hex[p + 1].digitToInt(16)).toByte()
            }
            val decoder = Charsets.UTF_8.newDecoder()
            val html = try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                return Decoded.Malformed
            }
            return Decoded.Html(html)
        }

        /** The 32-byte word at byte [at] of [hex], if it fits an Int; else `null`. */
        private fun word(hex: String, at: Int): Int? {
            val p = at * 2
            if (p + 64 > hex.length) return null
            val v = BigInteger(hex.substring(p, p + 64), 16)
            return if (v.bitLength() <= 31) v.toInt() else null
        }

        fun htmlHash(html: String): String = "0x" + Keccak256.digest(html.toByteArray(Charsets.UTF_8)).toHex()
    }
}

/**
 * Documents the user chose to run despite an unverified read, and the
 * ones that loaded trusted, for this process's lifetime — never
 * persisted (desktop and iOS parity). Keyed by chain + contract + exact
 * HTML hash, so changed bytes warn again. A private tab's decisions stay
 * in [private]'s own set and end with the process like everything else
 * about them.
 */
class OnchainApprovals(private val capacity: Int = CAPACITY) {
    private val keys = LinkedHashSet<String>()

    private fun key(doc: OnchainDocument) = "${doc.app.chainId}:${doc.app.address}:${doc.hash.lowercase()}"

    @Synchronized
    fun contains(doc: OnchainDocument): Boolean = key(doc) in keys

    @Synchronized
    fun add(doc: OnchainDocument) {
        val k = key(doc)
        keys.remove(k)
        keys += k
        while (keys.size > capacity) keys.remove(keys.first())
    }

    companion object {
        const val CAPACITY = 1024
    }
}

/**
 * One tab's onchain-app documents (#123), shared by the tab's submit
 * flow (main thread) and its interceptor (WebView's IO threads).
 *
 * - **Hand-off**: the document the submit flow just fetched and gated,
 *   for the navigation it schedules. Taken once, by the next main-frame
 *   request for that app, so the interceptor doesn't read the chain a
 *   second time; a hand-off nothing took within [HANDOFF_TTL_MS] is
 *   stale and dropped.
 * - **Last documents**: what this tab last served per app (the
 *   [CAPACITY] most recent). A later load of the app (Back / Forward,
 *   pull-to-refresh, a link within the app) reads the chain again and
 *   falls back on this only when that read fails.
 * - **Pending**: the unverified document the warning is asking about, so
 *   "Continue once" loads exactly those bytes without another read.
 */
class OnchainAppTab(val private: Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    private class Handoff(val document: OnchainDocument, val at: Long)

    private val handoffs = HashMap<OnchainAppRef, Handoff>()
    private val last = LinkedHashMap<OnchainAppRef, OnchainDocument>()
    private var pending: OnchainDocument? = null

    @Synchronized
    fun handOff(doc: OnchainDocument) {
        handoffs[doc.app] = Handoff(doc, clock())
        remember(doc)
    }

    @Synchronized
    fun takeHandoff(app: OnchainAppRef): OnchainDocument? {
        val h = handoffs.remove(app) ?: return null
        return h.document.takeIf { clock() - h.at in 0..HANDOFF_TTL_MS }
    }

    @Synchronized
    fun remember(doc: OnchainDocument) {
        last.remove(doc.app)
        last[doc.app] = doc
        while (last.size > CAPACITY) last.remove(last.keys.first())
    }

    @Synchronized
    fun lastFor(app: OnchainAppRef): OnchainDocument? = last[app]

    @Synchronized
    fun offer(doc: OnchainDocument) {
        pending = doc
    }

    /** The pending document if it is [app]'s with exactly [hash]; cleared either way. */
    @Synchronized
    fun takePending(app: OnchainAppRef, hash: String): OnchainDocument? {
        val p = pending
        pending = null
        return p?.takeIf { it.app == app && it.hash.equals(hash, ignoreCase = true) }
    }

    companion object {
        const val CAPACITY = 8
        const val HANDOFF_TTL_MS = 60_000L
    }
}

/**
 * What the interceptor does with a main-frame load of an app it wasn't
 * handed ([OnchainAppTab]): serve [Serve.document], or refuse in place
 * with an [ErrorPage] code.
 */
sealed interface OnchainDecision {
    class Serve(val document: OnchainDocument) : OnchainDecision
    data class Refuse(val code: String, val detail: String) : OnchainDecision
}

/**
 * The interceptor's rule for a read it made itself, with no user in the
 * loop: trusted documents load; one the user already let through this
 * session (or that loaded trusted before) loads again; servers that
 * disagreed and an unverified document never seen before are refused —
 * the user can review the latter from the address bar, where it gets
 * its warning. A read that failed falls back on the tab's [fallback],
 * if any, as Back to an ENS page does when the RPCs are down.
 */
internal fun decideOnchainDocument(
    load: OnchainLoad,
    approvals: OnchainApprovals,
    fallback: OnchainDocument?,
): OnchainDecision = when (load) {
    is OnchainLoad.Loaded -> {
        val doc = load.document
        when {
            doc.conflict -> OnchainDecision.Refuse("web3_conflict", doc.conflictDetail())
            doc.trusted || approvals.contains(doc) -> OnchainDecision.Serve(doc)
            else -> OnchainDecision.Refuse("web3_unverified", doc.unverifiedDetail())
        }
    }
    is OnchainLoad.Failed ->
        if (load.code == "web3_lookup_failed" && fallback != null) {
            OnchainDecision.Serve(fallback)
        } else {
            OnchainDecision.Refuse(load.code, load.detail)
        }
}

/** Process-wide onchain-app state: the loader, and the session's approvals. */
object OnchainApps {
    /**
     * How long a re-read may hold a document the tab already has an
     * earlier copy of before that copy is served instead: a stalled
     * network costs Back a few seconds, not the full [OnchainAppLoader.REQUEST_TIMEOUT_MS].
     */
    const val FALLBACK_DEADLINE_MS = 5_000L

    @Volatile
    var loader: OnchainAppLoader? = null
        private set

    val approvals = OnchainApprovals()
    val privateApprovals = OnchainApprovals()

    fun approvalsFor(private: Boolean) = if (private) privateApprovals else approvals

    /** Wire the loader to the app's chain-data router; idempotent. */
    fun init(context: Context) {
        if (loader != null) return
        val app = context.applicationContext
        val router = ChainDataRouter.get(app)
        val store = ChainStore.get(app)
        loader = OnchainAppLoader(
            request = { chainId, method, params, ctx -> router.request(chainId, method, params, ctx) },
            chains = { store.chains.first() },
        )
    }

    /** Content-Security-Policy of every app document: desktop's `ONCHAIN_APP_CSP` verbatim. */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; script-src 'unsafe-inline' blob:; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'none'; " +
            "sandbox allow-scripts allow-same-origin allow-forms allow-modals allow-downloads"

    const val PERMISSIONS_POLICY =
        "accelerometer=(), camera=(), display-capture=(), geolocation=(), gyroscope=(), " +
            "microphone=(), midi=(), payment=(), publickey-credentials-create=(), " +
            "publickey-credentials-get=(), usb=()"

    /** The response headers an app document is served with. */
    val DOCUMENT_HEADERS: Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
        "Permissions-Policy" to PERMISSIONS_POLICY,
        "Referrer-Policy" to "no-referrer",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        // `Vary: *`: no service worker's Cache Storage keeps it (none can
        // register here anyway — `worker-src 'none'`).
        "Vary" to "*",
    )
}

/**
 * The interceptor's answer for anything on a chain-scoped origin or
 * under the `web3:` scheme, or `null` when [url] is neither and the
 * caller carries on. Never `null` for one of ours: that would send
 * Chromium to DNS for a host that doesn't exist.
 *
 * Only a top-level document is ever served — `frame-ancestors 'none'`
 * and the app's own CSP deny it everything else anyway — so a page
 * framing or fetching an app gets a refusal, and an app gets nothing
 * but its own document. The document is the one [tab]'s submit flow
 * fetched, gated and handed off ([OnchainAppTab.takeHandoff]); any
 * other load of it (Back / Forward, a restored tab, pull-to-refresh, a
 * link within the app) reads the chain here, blocking this IO thread,
 * and goes by [decideOnchainDocument]. A refusal is the error page
 * itself, served in place like an ENS refusal ([nameResolutionRefusal])
 * so Back / Forward move past it, tagged so it stays out of history.
 */
internal fun interceptOnchainAppRequest(
    req: WebResourceRequest,
    url: String,
    tab: OnchainAppTab?,
): WebResourceResponse? {
    val virtual = OnchainAppRef.parseVirtual(url)
    if (virtual == null && !OnchainAppRef.isWeb3Scheme(url)) return null
    if (virtual == null || !req.isForMainFrame) {
        return onchainTextResponse(403, "Forbidden", "Onchain applications load as top-level documents only.")
    }
    val method = req.method?.uppercase() ?: "GET"
    if (method != "GET" && method != "HEAD") {
        return onchainTextResponse(405, "Method Not Allowed", "Onchain applications only support GET and HEAD.")
    }
    val (app, tail) = virtual
    val handedOff = tab?.takeHandoff(app)
    val decision = if (handedOff != null) {
        OnchainDecision.Serve(handedOff)
    } else {
        val approvals = OnchainApps.approvalsFor(tab?.private == true)
        val fallback = tab?.lastFor(app)
        val load = OnchainApps.loader?.let { loader ->
            kotlinx.coroutines.runBlocking {
                loader.load(
                    app,
                    if (fallback != null) OnchainApps.FALLBACK_DEADLINE_MS else OnchainAppLoader.REQUEST_TIMEOUT_MS,
                )
            }
        } ?: OnchainLoad.Failed("web3_lookup_failed", "Chain access isn't ready yet.")
        decideOnchainDocument(load, approvals, fallback).also {
            if (it is OnchainDecision.Serve) {
                // Loaded trusted: the same bytes are fine again later.
                if (it.document.trusted) approvals.add(it.document)
                tab?.remember(it.document)
            }
        }
    }
    return when (decision) {
        is OnchainDecision.Serve -> WebResourceResponse(
            "text/html", "utf-8", 200, "OK", OnchainApps.DOCUMENT_HEADERS,
            ByteArrayInputStream(
                if (method == "HEAD") ByteArray(0) else decision.document.html.toByteArray(Charsets.UTF_8),
            ),
        )
        is OnchainDecision.Refuse ->
            onchainRefusal(app.displayUrl(tail), app.linkUrl(tail), decision.code, decision.detail)
    }
}

private fun onchainTextResponse(status: Int, reason: String, text: String) = WebResourceResponse(
    "text/plain", "utf-8", status, reason,
    mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
    ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
)

/**
 * The in-place refusal (see [interceptOnchainAppRequest]). Its link is
 * the app's `web3://` address ([OnchainAppRef.linkUrl]), which the tab's client hands to the
 * submit flow — so an unverified document gets its warning, with
 * "Continue once", and anything else a fresh try. No script.
 */
internal fun onchainRefusal(displayUrl: String, linkUrl: String, code: String, detail: String): WebResourceResponse {
    val (title, description, action) = when (code) {
        "web3_unverified" -> Triple(
            "Not cross-checked",
            "Only one RPC server returned this app's code, so Freedom couldn't check it " +
                "against another server, and it isn't code you already let through in this session. " +
                "Nothing was run.",
            "Review",
        )
        "web3_conflict" -> Triple(
            "RPC servers disagreed",
            "The RPC servers Freedom asked returned different code for this app. At least one of " +
                "them is wrong, so nothing was run.",
            "Try again",
        )
        else -> Triple(onchainErrorTitle(code), onchainErrorDescription(code), "Try again")
    }
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>$title</title><style>
html,body{margin:0;min-height:100%}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#141414;color:#f5f5f5;padding:32px 20px;box-sizing:border-box;text-align:center}
.c{max-width:560px;margin:0 auto}
h1{font-size:22px;margin:24px 0 12px;color:#ff5e5e}
p{line-height:1.55;margin:0 0 20px;color:#ccc;font-size:15px}
.d{background:#1a1a1a;padding:14px 16px;border-radius:8px;font-family:ui-monospace,Menlo,monospace;
font-size:13px;color:#ff8a8a;margin:0 0 24px;word-break:break-all;white-space:pre-wrap;text-align:left}
a{display:inline-block;padding:12px 22px;background:#2c2c2c;color:#fff;border:1px solid #444;
border-radius:8px;font-size:15px;text-decoration:none}
@media (prefers-color-scheme:light){body{background:#fff;color:#24292f}h1{color:#cf222e}
p{color:#57606a}.d{background:#f6f8fa;color:#cf222e}a{background:#f6f8fa;border-color:#d0d7de;color:#24292f}}
</style></head><body><div class="c"><h1>$title</h1><p>$description</p>
<div class="d">${esc(displayUrl)}

${esc(code)}
${esc(detail)}</div><a href="${esc(linkUrl)}">$action</a></div></body></html>"""
    return WebResourceResponse(
        "text/html", "utf-8", if (code == "web3_lookup_failed") 502 else 403, "Onchain App Refused",
        mapOf(
            NAME_RESOLUTION_ERROR_HEADER to code,
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'",
            "X-Content-Type-Options" to "nosniff",
        ),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )
}

internal fun onchainErrorTitle(code: String): String = when (code) {
    "web3_unknown_chain" -> "Unknown chain"
    "web3_not_an_app" -> "Not an onchain app"
    "web3_too_large" -> "App too large"
    "web3_invalid" -> "Not a web3:// app address"
    else -> "Couldn't read the app"
}

internal fun onchainErrorDescription(code: String): String = when (code) {
    "web3_unknown_chain" -> "This app lives on a chain that isn't in your chain list, so Freedom " +
        "doesn't know which RPC servers to ask. Add the chain in Settings, then try again."
    "web3_not_an_app" -> "The contract didn't return an ERC-8244 html() document on this chain. " +
        "Check the address and the chain ID."
    "web3_too_large" -> "The app's document is larger than Freedom loads."
    "web3_invalid" -> "Freedom loads web3:// apps by contract address: web3://0x…[:chainId]/."
    else -> "Couldn't get an answer from the chain's RPC servers. Check your connection and try again."
}
