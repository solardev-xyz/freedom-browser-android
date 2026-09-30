package baby.freedom.mobile.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
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
 * its CORS preflights), and a `no-cors` POST needs no preflight at all.
 * Up to ant 0.5.48 the gateway also answered `Origin: null` with
 * `Access-Control-Allow-Origin: null`, so any page could read those
 * answers from a sandboxed iframe, a `data:` worker or anything else with
 * an opaque origin; since #284 it allows no CORS origin at all
 * ([SwarmNode.GATEWAY_CORS_ORIGINS]).
 *
 * So every page request to the gateway port on a host that may be this
 * device (see below) — from a tab, a private tab, a worker or a service
 * worker, all of which pass [interceptVirtualRequest] — whose endpoint
 * isn't on the dapp surface ([DAPP_PATHS]) is answered here with a 403
 * before it reaches the network, whatever its method and origin; a chain
 * write is refused on any host. An allowlist, not a list of private endpoints: an endpoint a
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
 * app ([baby.freedom.swarm.SpendGuard]). What keeps the reads private
 * from them is the gateway's empty CORS allow-list (#284,
 * [SwarmNode.GATEWAY_CORS_ORIGINS]): a page on another origin, a
 * redirected (`Origin: null`) fetch included, can send the request but
 * never read the answer. (A non-browser app on the device can still read
 * them; CORS is a browser's rule, and the gateway has no auth.)
 *
 * Matched by the gateway's port, and by host only as far as a URL can
 * prove it isn't the device: any DNS name that resolves to 127.0.0.1
 * reaches the node as well as `127.0.0.1` itself does, and ant doesn't
 * look at `Host`. The node binds 127.0.0.1 only, so an IP literal outside
 * loopback and the unspecified address (`http://192.168.1.20:1633`) is
 * some other machine's Bee node — the user's own on their LAN, say — and
 * its reads pass; so does the host of the external Swarm node the user
 * set in Settings ([Gateways.externalSwarmBase]), which they named
 * themselves. Any other name on the port (`http://nas:1633`) is refused,
 * with a refusal that says how to reach such a node. Chain writes stay
 * refused on every host, as they were before the reads were covered.
 */
internal object NodeApiGuard {
    /**
     * First path segments a page may use: the content and messaging
     * surface `docs/dapp-compatibility.md` promises (uploads, downloads,
     * feeds, chunks, single-owner chunks, pss/gsoc), plus the liveness
     * probes (`/health` — the error page asks it whether the node is up —
     * and `/readiness`), which say nothing about the user.
     */
    internal val DAPP_PATHS = setOf(
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
        val text = when {
            write -> Strings.get(R.string.node_api_spend_refusal)
            isLoopbackLiteral(WhatwgHost.parse(url)?.hostname) -> READ_REFUSAL
            else -> Strings.get(R.string.node_api_read_refusal_other_node, dappPathList())
        }
        // The endpoint only: a query can carry anything.
        Log.w(TAG, "refused a page's request to the Swarm node's API: ${method.uppercase()} /${segment.orEmpty()}")
        return WebResourceResponse(
            "text/plain", "utf-8", 403, "Forbidden",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
                REFUSAL_HEADER to "1",
            ),
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * Marks [refusalFor]'s answer, so `onReceivedHttpError` leaves a
     * refused navigation showing the refusal itself rather than covering
     * it with the "content not found yet" error page — the node isn't the
     * problem, and Try Again would only be refused again (R4-F1).
     */
    internal const val REFUSAL_HEADER = "X-Node-Api-Refused"

    /**
     * Is a response with [headers] to a [method] request for [url] this
     * guard's refusal? The header alone isn't proof: a URL the WebView
     * loads straight from a server (an external Swarm node's own `/bzz/…`
     * page, which no interceptor proxies or strips) could send it too and
     * keep its own error body on screen (R6-F1). So the request must also
     * be one [refuses] answers here, which never reaches the network, and
     * whose answer therefore can only have come from [refusalFor].
     */
    internal fun isRefusal(
        method: String,
        url: String,
        headers: Map<String, String>?,
        externalSwarm: String = Gateways.externalSwarmBase,
    ): Boolean =
        headers?.keys?.any { it.equals(REFUSAL_HEADER, ignoreCase = true) } == true &&
            refuses(method, url, externalSwarm)

    /**
     * Is a [method] request to [url] one for the node's gateway, outside the
     * dapp surface? [externalSwarm] is the user's external Swarm node
     * ([Gateways.externalSwarmBase]; `""` for none).
     */
    internal fun refuses(
        method: String,
        url: String,
        externalSwarm: String = Gateways.externalSwarmBase,
    ): Boolean {
        if (!onGatewayPort(url)) return false
        val segment = firstSegment(pathOf(url))
        if (segment in DAPP_PATHS) return false
        if (isWrite(method) && segment in CHAIN_PATHS) return true
        return mayBeThisDevice(url, externalSwarm)
    }

    /**
     * Could [url]'s host be the embedded node, bound to 127.0.0.1? Yes for
     * a loopback or unspecified literal and for any name (it may resolve
     * to loopback), except the external node's own host the user set; no
     * for any other IP literal. Unparsable counts as yes.
     */
    private fun mayBeThisDevice(url: String, externalSwarm: String): Boolean {
        val host = WhatwgHost.parse(url)?.hostname ?: return true
        if (isLoopbackLiteral(host)) return true
        if (host.startsWith("[")) return false
        if (host.split('.').let { p -> p.size == 4 && p.all { o -> o.isNotEmpty() && o.all { it in '0'..'9' } } }) {
            return false
        }
        if (externalSwarm.isEmpty() || !onGatewayPort(externalSwarm)) return true
        return WhatwgHost.parse(externalSwarm)?.hostname?.trimEnd('.') != host.trimEnd('.')
    }

    /**
     * Does [host] (WHATWG-serialised) name the device itself: `localhost`
     * and `*.localhost`, IPv4 `127.0.0.0/8` or `0.0.0.0/8` (connecting to
     * `0.0.0.0` reaches loopback on Linux), or an IPv6 address whose first
     * 80 bits are zero (`::1`, `::`, `::ffff:127.0.0.1`, the deprecated
     * `::127.0.0.1`)? `null` (unparsable) counts as yes.
     */
    private fun isLoopbackLiteral(host: String?): Boolean {
        val h = host?.trimEnd('.') ?: return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.startsWith("[")) {
            val v6 = h.removePrefix("[").removeSuffix("]")
            // WHATWG compresses the longest zero run, so five or more leading
            // zero pieces always serialise as a leading `::` followed by at
            // most three pieces (`ffff:7f00:1`, `1`, none).
            return v6.startsWith("::") && v6.removePrefix("::").split(':').filter { it.isNotEmpty() }.size <= 3
        }
        val octets = h.split('.')
        if (octets.size != 4 || !octets.all { o -> o.isNotEmpty() && o.all { it in '0'..'9' } }) return false
        return octets[0].toInt() == 127 || octets[0].toInt() == 0
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

    /** Lists every path in [DAPP_PATHS], so the text can't drift from what's allowed. */
    internal val READ_REFUSAL: String
        get() = Strings.get(R.string.node_api_read_refusal, dappPathList())

    /** [DAPP_PATHS] as the refusal lists them: `/bzz, /bytes, …`. */
    private fun dappPathList(): String = DAPP_PATHS.joinToString(", ") { "/$it" }
}
