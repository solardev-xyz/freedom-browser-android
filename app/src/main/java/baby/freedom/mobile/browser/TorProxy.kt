package baby.freedom.mobile.browser

import android.content.Context
import android.content.Intent
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A SOCKS5 listener on this device (#275): an IP literal — `127.x.x.x`
 * or `::1`, never a name, so nothing is looked up — and a port.
 */
data class SocksEndpoint(val host: String, val port: Int) {
    /** `127.0.0.1:9050`, `[::1]:9050`: what Settings stores and shows. */
    val authority: String get() = if (':' in host) "[$host]:$port" else "$host:$port"

    /** The literal as an address, without a lookup. */
    val address: InetAddress get() = InetAddress.getByName(host)

    override fun toString() = authority
}

/**
 * Settings → Tor → External SOCKS proxy (#275): parsing the `host:port`
 * the user types, and checking that a Tor client answers there.
 *
 * Loopback only. The proxy is handed every `.onion` hostname the user
 * visits, so it must be a Tor client on this device (Orbot, or any app
 * with a Tor SOCKS port) — a proxy elsewhere on the network would see
 * them in the clear. And only a literal (or `localhost`, stored as
 * `127.0.0.1`): a name would itself be resolved, and could point
 * anywhere.
 */
object TorProxy {
    /** Orbot's default SOCKS port. */
    val DEFAULT = SocksEndpoint("127.0.0.1", 9050)

    enum class Rejection { EMPTY, FORMAT, SCHEME, NOT_LOOPBACK, PORT }

    /** [parse]'s answer: exactly one of [endpoint] and [rejection]. */
    data class Parsed(val endpoint: SocksEndpoint?, val rejection: Rejection?)

    private fun no(r: Rejection) = Parsed(null, r)

    /**
     * [raw] as a loopback SOCKS endpoint: `host:port`, optionally
     * `socks5://` or `socks5h://` in front and a lone `/` behind (as
     * desktop's `socks-endpoint.js` accepts), where host is a four-octet
     * `127.0.0.0/8` literal, `localhost` (→ `127.0.0.1`) or `[::1]`, and
     * the port 1–65535. No user name, no path; a port is required.
     */
    fun parse(raw: String): Parsed {
        var s = raw.trim()
        if (s.isEmpty()) return no(Rejection.EMPTY)
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(s)
        if (scheme != null) {
            val name = scheme.groupValues[1].lowercase()
            if (name != "socks5" && name != "socks5h") return no(Rejection.SCHEME)
            s = s.substring(scheme.range.last + 1)
        }
        s = s.removeSuffix("/")
        if (s.isEmpty() || s.any { it == '/' || it == '@' || it == '?' || it == '#' || it.isWhitespace() }) {
            return no(Rejection.FORMAT)
        }
        val host: String
        val portText: String
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0 || close + 1 >= s.length || s[close + 1] != ':') return no(Rejection.FORMAT)
            host = s.substring(1, close).lowercase()
            portText = s.substring(close + 2)
            if (host != "::1") return no(Rejection.NOT_LOOPBACK)
        } else {
            val colon = s.lastIndexOf(':')
            if (colon <= 0 || s.indexOf(':') != colon) return no(Rejection.FORMAT)
            host = s.substring(0, colon).lowercase().removeSuffix(".")
            portText = s.substring(colon + 1)
        }
        if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) {
            return no(Rejection.PORT)
        }
        val port = portText.toInt()
        if (port !in 1..65535) return no(Rejection.PORT)
        val literal = when {
            host == "::1" -> "::1"
            host == "localhost" -> "127.0.0.1"
            isLoopbackV4Literal(host) -> host
            else -> return no(Rejection.NOT_LOOPBACK)
        }
        return Parsed(SocksEndpoint(literal, port), null)
    }

    /**
     * A dotted-decimal `127.a.b.c` with no leading zeros — nothing an
     * inet_aton-style parser could read as octal or hex, or as a name.
     */
    internal fun isLoopbackV4Literal(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 &&
            octets.all { o ->
                o.length in 1..3 && o.all { it in '0'..'9' } && (o.length == 1 || o[0] != '0') && o.toInt() <= 255
            } &&
            octets[0] == "127"
    }

    /** What's stored for Settings → Tor, read back; the default for anything unreadable. */
    fun stored(value: String): SocksEndpoint = parse(value).endpoint ?: DEFAULT

    // --- Probing ---------------------------------------------------------

    /** [probe]'s verdict, from worst to best. */
    sealed class Probe {
        /** Nothing accepts connections there. */
        data object NotListening : Probe()

        /** Something listens, but doesn't speak SOCKS5 without a password. */
        data object NotSocks : Probe()

        /**
         * A SOCKS5 proxy, but it didn't reach a `.onion` service: [code]
         * is its SOCKS reply, or -1 if it gave none within the deadline.
         */
        data class NoOnion(val code: Int) : Probe()

        /** A SOCKS5 proxy that connected to a `.onion` service: Tor. */
        data object Tor : Probe()
    }

    /**
     * Well-known onion services the probe connects to (port 80, closed at
     * once, nothing sent): the Tor Project's, then DuckDuckGo's. Either
     * answering counts — only Tor can connect to a `.onion` name. A
     * plain SOCKS proxy would look the name up in DNS and fail.
     */
    internal val PROBE_ONIONS = listOf(
        "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion",
        "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
    )

    /** Connect + SOCKS5 greeting; a loopback listener answers at once. */
    const val HANDSHAKE_TIMEOUT_MS = 3_000L

    /**
     * One onion CONNECT: a Tor client that's up builds the circuit in a
     * few seconds; one still bootstrapping holds the request until it
     * can.
     */
    const val ONION_TIMEOUT_MS = 45_000L

    /**
     * Whether [endpoint] is a Tor SOCKS proxy: a SOCKS5 greeting with no
     * authentication, then a CONNECT to each of [onions] in turn until
     * one succeeds. Each step is bounded by its own deadline, enforced
     * from outside the blocking read (the socket is closed from this
     * coroutine), so a listener that accepts and never answers — or
     * trickles — can't hold it longer.
     */
    suspend fun probe(
        endpoint: SocksEndpoint,
        onions: List<String> = PROBE_ONIONS,
        handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        onionTimeoutMs: Long = ONION_TIMEOUT_MS,
    ): Probe {
        var last: Probe = Probe.NoOnion(-1)
        for (onion in onions) {
            val result = attempt(endpoint, handshakeTimeoutMs, onionTimeoutMs) { input, output ->
                val name = onion.toByteArray(Charsets.US_ASCII)
                output.write(
                    byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(0, 80),
                )
                output.flush()
                val reply = readFully(input, 2) ?: return@attempt Probe.NoOnion(-1)
                if (reply[0].toInt() != 5) Probe.NotSocks
                else if (reply[1].toInt() == 0) Probe.Tor
                else Probe.NoOnion(reply[1].toInt() and 0xff)
            }
            when (result) {
                Probe.Tor, Probe.NotListening, Probe.NotSocks -> return result
                is Probe.NoOnion -> last = result
            }
        }
        return last
    }

    /**
     * Whether a SOCKS5 proxy (still) listens at [endpoint]: the greeting
     * only, no request. For re-checking a proxy [probe] already found to
     * be Tor, without a circuit each time.
     */
    suspend fun listening(endpoint: SocksEndpoint, timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Boolean =
        attempt(endpoint, timeoutMs, timeoutMs) { _, _ -> Probe.Tor } == Probe.Tor

    /**
     * Connect and greet within [handshakeTimeoutMs], then run [request]
     * within [requestTimeoutMs] (a timeout there is `NoOnion(-1)`).
     */
    private suspend fun attempt(
        endpoint: SocksEndpoint,
        handshakeTimeoutMs: Long,
        requestTimeoutMs: Long,
        request: (InputStream, java.io.OutputStream) -> Probe,
    ): Probe = coroutineScope {
        val socket = Socket()
        try {
            val greeted = async(Dispatchers.IO) {
                try {
                    socket.connect(InetSocketAddress(endpoint.address, endpoint.port), handshakeTimeoutMs.toInt())
                } catch (_: ConnectException) {
                    return@async Probe.NotListening
                } catch (_: IOException) {
                    return@async Probe.NotListening
                }
                try {
                    socket.soTimeout = maxOf(handshakeTimeoutMs, requestTimeoutMs).toInt()
                    socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                    val choice = readFully(socket.getInputStream(), 2)
                    if (choice == null || choice[0].toInt() != 5 || choice[1].toInt() != 0) Probe.NotSocks else null
                } catch (_: IOException) {
                    Probe.NotSocks
                }
            }
            // Wrapped, so "greeted fine" (null) isn't read as a timeout.
            val greeting = withTimeoutOrNull(handshakeTimeoutMs) { Step(greeted.await()) }
            if (greeting == null) {
                val connected = socket.isConnected
                socket.close()
                greeted.cancel()
                return@coroutineScope if (connected) Probe.NotSocks else Probe.NotListening
            }
            greeting.failure?.let { return@coroutineScope it }
            val asked = async(Dispatchers.IO) {
                try {
                    request(socket.getInputStream(), socket.getOutputStream())
                } catch (_: IOException) {
                    Probe.NoOnion(-1)
                }
            }
            withTimeoutOrNull(requestTimeoutMs) { asked.await() } ?: run {
                socket.close()
                asked.cancel()
                Probe.NoOnion(-1)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private class Step(val failure: Probe?)

    /** [n] bytes, or `null` at end of stream. */
    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) return null
            got += r
        }
        return out
    }

    // --- Orbot -----------------------------------------------------------

    const val ORBOT_PACKAGE = "org.torproject.android"
    private const val ORBOT_ACTION_START = "org.torproject.android.intent.action.START"
    private const val ORBOT_EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"

    /** Whether Orbot is installed (visible through the manifest's `<queries>`). */
    fun orbotInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0) }.isSuccess

    /**
     * Ask Orbot to start Tor in the background — Orbot's public START
     * broadcast, as NetCipher's `OrbotHelper.requestStartTor` sends it.
     * Orbot honours it only with its own *Allow background starts* on;
     * [openOrbot] is the fallback.
     */
    fun requestOrbotStart(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ORBOT_ACTION_START)
                    .setPackage(ORBOT_PACKAGE)
                    .putExtra(ORBOT_EXTRA_PACKAGE_NAME, context.packageName),
            )
        }
    }

    /** Open Orbot's own screen (to tap Connect); false if it can't be launched. */
    fun openOrbot(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** One line on what [probe] found at [endpoint], for Settings and the Nodes page. */
    fun describe(result: Probe, endpoint: SocksEndpoint): String = when (result) {
        Probe.Tor -> "Tor reached a .onion site through $endpoint"
        Probe.NotListening -> "Nothing is listening on $endpoint. Start Orbot (or your Tor app) and check its SOCKS port."
        Probe.NotSocks -> "$endpoint isn't a SOCKS5 proxy without a password"
        is Probe.NoOnion -> if (result.code < 0) {
            "The proxy on $endpoint didn't reach a .onion site in time. Is it Tor, and connected?"
        } else {
            "The proxy on $endpoint couldn't reach a .onion site (SOCKS error ${result.code}). Is it Tor, and connected?"
        }
    }
}
