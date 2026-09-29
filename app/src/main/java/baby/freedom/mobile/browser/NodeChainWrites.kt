package baby.freedom.mobile.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import baby.freedom.swarm.SwarmNode
import java.io.ByteArrayInputStream

/**
 * Keeps web pages away from the Swarm node's money (#114).
 *
 * In light mode the embedded node's gateway holds a funded Gnosis account:
 * `POST /stamps/{amount}/{depth}` buys a postage batch, `PATCH
 * /stamps/topup|dilute/…` tops one up or dilutes it, `POST
 * /chequebook/deposit` moves xBZZ into the chequebook — each signs and
 * sends a transaction with no prompt. The gateway sits on localhost, which
 * pages may reach (it's the documented dapp write path, and the
 * interceptor even answers its CORS preflights), and a `no-cors` POST needs
 * no preflight at all. So every page request that would do one of those —
 * from a tab, a private tab or a service worker, all of which pass
 * [interceptVirtualRequest] — is answered here with a 403 before it
 * reaches the network. Spending goes through the app's own screens.
 *
 * This is the page-facing, readable refusal, not the enforcement: a
 * navigation's redirect (a form POST that 307s to the gateway) is
 * followed inside Chromium without asking the interceptor again, and
 * other apps reach the port without a WebView at all. What keeps the
 * funds safe from all of them is the node's own chain transport
 * (`ant_jni.c`), which refuses every broadcast but the transactions of a
 * spend the user confirmed in the app ([baby.freedom.swarm.SpendGuard]).
 *
 * Matched by the gateway's port, not its host: any DNS name that resolves
 * to 127.0.0.1 reaches the node as well as `127.0.0.1` itself does, and
 * ant doesn't look at `Host`. The paths are bee's whole on-chain write
 * surface ([CHAIN_PATHS]) — also the ones ant doesn't serve yet — with
 * reads (GET, HEAD) left alone. Uploads, feeds and chunks are not on-chain
 * writes and stay open, as `docs/dapp-compatibility.md` promises.
 */
internal object NodeChainWrites {
    /**
     * First path segments of bee's API endpoints that sign transactions:
     * postage batches, the chequebook (deposit, withdraw, cash-out),
     * staking, wallet withdrawals and pending-transaction resend/cancel.
     */
    private val CHAIN_PATHS = setOf("stamps", "chequebook", "stake", "wallet", "transactions")

    private val GATEWAY_PORT: Int = SwarmNode.GATEWAY_URL.substringAfterLast(':').toInt()

    /** The 403 for [request] if it's a page's on-chain write to the node, else null. */
    fun refusalFor(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val method = request.method.orEmpty()
        if (!refuses(method, url)) return null
        // The endpoint only: a query can carry anything.
        Log.w(TAG, "refused a page's on-chain write to the Swarm node: $method /${firstSegment(pathOf(url))}")
        return WebResourceResponse(
            "text/plain", "utf-8", 403, "Forbidden",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(REFUSAL.toByteArray(Charsets.UTF_8)),
        )
    }

    /** Is a [method] request to [url] an on-chain write to the node's gateway? */
    internal fun refuses(method: String, url: String): Boolean {
        val m = method.uppercase()
        if (m == "GET" || m == "HEAD") return false
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
        if (port != GATEWAY_PORT) return false
        return firstSegment(pathOf(url)) in CHAIN_PATHS
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

    private const val TAG = "NodeChainWrites"

    private const val REFUSAL =
        "Freedom doesn't let web pages spend the Swarm node's funds. " +
            "Postage stamps and the chequebook are managed in the app."
}
