package baby.freedom.mobile.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import baby.freedom.swarm.SwarmNode
import java.io.ByteArrayInputStream

/**
 * Keeps web pages away from the Swarm node's own API: its money (#114)
 * and everything it knows about the user (#283).
 *
 * The embedded node's gateway serves the dapp surface (`/bzz`, `/bytes`,
 * `/chunks`, `/soc`, `/feeds`, pss) next to bee's node API on the same
 * port. In light mode that API holds a funded Gnosis account: `POST
 * /stamps/{amount}/{depth}` buys a postage batch, `PATCH
 * /stamps/topup|dilute/…` tops one up or dilutes it, `POST
 * /chequebook/deposit` moves xBZZ into the chequebook — each signs and
 * sends a transaction with no prompt. Its reads are private too:
 * `/addresses` (the node's Ethereum address, overlay, public keys and
 * underlay IPs — a stable identifier across sites that also names LAN
 * addresses), `/wallet` (the address and its balances), `/stamps`,
 * `/chequebook/…`, `/balances`, `/settlements`, `/peers`, `/topology`,
 * `/pins`, `/tags`. The gateway sits on localhost, which pages may reach
 * (it's the documented dapp write path, and the interceptor even answers
 * its CORS preflights), a `no-cors` POST needs no preflight at all, and
 * ant answers `Origin: null` with `Access-Control-Allow-Origin: null` —
 * so any page could read those answers from a sandboxed iframe, a
 * `data:` worker or anything else with an opaque origin.
 *
 * So every page request to the gateway — from a tab, a private tab, a
 * worker or a service worker, all of which pass [interceptVirtualRequest]
 * — whose endpoint isn't on the dapp surface ([DAPP_PATHS]) is answered
 * here with a 403 before it reaches the network, whatever its method and
 * origin. An allowlist, not a list of private endpoints: an endpoint a
 * later ant adds stays closed until it's added here. Spending and the
 * node's details go through the app's own screens, which talk to the
 * node natively.
 *
 * This is the page-facing, readable refusal, not the whole enforcement:
 * a navigation's redirect is followed inside Chromium without asking
 * the interceptor again, and other apps and browsers reach the port
 * without a WebView at all. What keeps the funds safe from all of them
 * is the node's own chain transport (`ant_jni.c`), which refuses every
 * broadcast but the transactions of a spend the user confirmed in the
 * app ([baby.freedom.swarm.SpendGuard]). The reads have no such backstop
 * in the node yet: ant's FFI gateway pins its CORS allow-list to `null`
 * (tracked upstream, see `docs/virtual-origins-hardening.md`).
 *
 * Matched by the gateway's port, not its host: any DNS name that resolves
 * to 127.0.0.1 reaches the node as well as `127.0.0.1` itself does, and
 * ant doesn't look at `Host`.
 */
internal object NodeApiGuard {
    /**
     * First path segments a page may use: the content and messaging
     * surface `docs/dapp-compatibility.md` promises (uploads, downloads,
     * feeds, chunks, single-owner chunks, pss/gsoc), plus the liveness
     * probes (`/health` — the error page asks it whether the node is up —
     * and `/readiness`), which say nothing about the user.
     */
    private val DAPP_PATHS = setOf(
        "bzz", "bytes", "chunks", "soc", "feeds", "pss", "gsoc", "health", "readiness",
    )

    /**
     * First path segments of bee's API endpoints that sign transactions:
     * postage batches, the chequebook (deposit, withdraw, cash-out),
     * staking, wallet withdrawals and pending-transaction resend/cancel.
     * Refused anyway (none is in [DAPP_PATHS]); named only to explain a
     * write's refusal.
     */
    private val CHAIN_PATHS = setOf("stamps", "chequebook", "stake", "wallet", "transactions")

    private val GATEWAY_PORT: Int = SwarmNode.GATEWAY_URL.substringAfterLast(':').toInt()

    /** The 403 for [request] if it's a page's request to the node's own API, else null. */
    fun refusalFor(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val method = request.method.orEmpty()
        if (!refuses(method, url)) return null
        val segment = firstSegment(pathOf(url))
        val write = isWrite(method) && segment in CHAIN_PATHS
        // The endpoint only: a query can carry anything.
        Log.w(TAG, "refused a page's request to the Swarm node's API: ${method.uppercase()} /${segment.orEmpty()}")
        return WebResourceResponse(
            "text/plain", "utf-8", 403, "Forbidden",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream((if (write) SPEND_REFUSAL else READ_REFUSAL).toByteArray(Charsets.UTF_8)),
        )
    }

    /** Is a [method] request to [url] one for the node's gateway, outside the dapp surface? */
    internal fun refuses(method: String, url: String): Boolean {
        if (!onGatewayPort(url)) return false
        return firstSegment(pathOf(url)) !in DAPP_PATHS
    }

    private fun isWrite(method: String): Boolean = method.uppercase().let { it != "GET" && it != "HEAD" }

    /** Does [url] (http or https, any host) name the gateway's port? */
    private fun onGatewayPort(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd).lowercase()
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return false
        }
        val rest = url.substring(schemeEnd + 3)
        val authorityEnd = authorityEnd(rest)
        val hostPort = rest.substring(0, authorityEnd).substringAfterLast('@')
        val port = portOf(hostPort) ?: defaultPort
        return port == GATEWAY_PORT
    }

    private fun authorityEnd(rest: String): Int =
        rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) rest.length else it }

    /** [url]'s path, without its query or fragment. */
    private fun pathOf(url: String): String {
        val rest = url.substringAfter("://", "")
        return rest.substring(authorityEnd(rest)).substringBefore('?').substringBefore('#')
    }

    /** The explicit port of a `host[:port]` / `[v6][:port]` authority, or null for the default. */
    private fun portOf(hostPort: String): Int? {
        val afterHost = if (hostPort.startsWith("[")) {
            hostPort.substringAfter(']', "")
        } else {
            hostPort.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        }
        val digits = afterHost.removePrefix(":")
        if (digits.isEmpty()) return null
        // A malformed port can't be the gateway's; one that overflows isn't either.
        return digits.takeIf { d -> d.all { it in '0'..'9' } }?.trimStart('0')?.ifEmpty { "0" }
            ?.takeIf { it.length <= 5 }?.toInt()
    }

    /**
     * The path's first real segment, percent-decoded (before splitting) and
     * lowercased; empty and dot segments are dropped rather than resolved,
     * so none of them can hide the endpoint (`//stamps`, `/%2e%2e/stamps`,
     * `/%2Fstamps`). Stricter than ant's router, which matches the raw path.
     */
    private fun firstSegment(path: String): String? =
        percentDecode(path).replace('\\', '/').lowercase().split('/')
            .firstOrNull { it.isNotEmpty() && it != "." && it != ".." }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3) else null
            val byte = hex?.takeIf { h -> h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }?.toInt(16)
            if (byte != null) {
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val TAG = "NodeApiGuard"

    private const val SPEND_REFUSAL =
        "Freedom doesn't let web pages spend the Swarm node's funds. " +
            "Postage stamps and the chequebook are managed in the app."

    private const val READ_REFUSAL =
        "Freedom doesn't let web pages use the Swarm node's own API. " +
            "Pages can upload and read content (/bzz, /bytes, /chunks, /soc, /feeds); " +
            "the node's wallet, stamps and addresses are shown in the app."
}
