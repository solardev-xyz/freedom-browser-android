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

        /**
         * A SOCKS5 proxy that said "connected" to [CANARY_ONION], a
         * `.onion` name that can't exist: it answers before dialing the
         * target (shadowsocks, v2ray/xray, clash inbounds do), so a
         * "connected" from it proves nothing — and it would carry every
         * onion hostname to wherever it forwards (R1-F1).
         */
        data object NotTor : Probe()

        /** A SOCKS5 proxy that connected to a `.onion` service, and refused one that can't exist: Tor. */
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

    /**
     * A well-formed v3 onion name whose checksum is wrong (an all-zero key,
     * checksum 0): no service can have it. Tor rejects it locally, at
     * once and even before it has bootstrapped, with a SOCKS error (0xF6
     * "invalid onion address" with ExtendedErrors, else a plain error). A
     * proxy that answers success before dialing says "connected" to it
     * like to anything else — which is how [probe] tells it from Tor. It
     * carries no hostname of the user's, so asking costs nothing even
     * where the answer is "not Tor".
     */
    internal const val CANARY_ONION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion"

    /** Tor refuses [CANARY_ONION] without the network, at once; no refusal within this isn't Tor. */
    const val CANARY_TIMEOUT_MS = 10_000L

    /** Connect + SOCKS5 greeting; a loopback listener answers at once. */
    const val HANDSHAKE_TIMEOUT_MS = 3_000L

    /**
     * One onion CONNECT: a Tor client that's up builds the circuit in a
     * few seconds; one still bootstrapping holds the request until it
     * can.
     */
    const val ONION_TIMEOUT_MS = 45_000L

    /**
     * Whether [endpoint] is a Tor SOCKS proxy: [recheck] first (a SOCKS5
     * greeting with no authentication, and a CONNECT to [CANARY_ONION]
     * that must *not* succeed), then a CONNECT to each of [onions] in turn
     * until one succeeds. Only a proxy that refuses the impossible onion
     * and connects a real one is Tor: a plain SOCKS proxy can't connect a
     * `.onion` name, and one that says "connected" before dialing (so
     * would say it to anything) fails the canary — before any real onion
     * name is sent to it. Each step is bounded by its own deadline,
     * enforced from outside the blocking read (the socket is closed from
     * this coroutine), so a listener that accepts and never answers — or
     * trickles — can't hold it longer.
     */
    suspend fun probe(
        endpoint: SocksEndpoint,
        onions: List<String> = PROBE_ONIONS,
        handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        onionTimeoutMs: Long = ONION_TIMEOUT_MS,
        canaryTimeoutMs: Long = CANARY_TIMEOUT_MS,
    ): Probe {
        val canary = recheck(endpoint, handshakeTimeoutMs, canaryTimeoutMs)
        if (canary != Probe.Tor) return canary
        return reachOnion(endpoint, onions, handshakeTimeoutMs, onionTimeoutMs)
    }

    /**
     * The second half of [probe], for a proxy [recheck] just found
     * refusing the canary: a CONNECT to each of [onions] in turn until one
     * succeeds. On its own it proves nothing — only after that refusal
     * (MainActivity's loop runs the two itself, so the canary is sent once
     * per check, R3-M1).
     */
    suspend fun reachOnion(
        endpoint: SocksEndpoint,
        onions: List<String> = PROBE_ONIONS,
        handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        onionTimeoutMs: Long = ONION_TIMEOUT_MS,
    ): Probe {
        var last: Probe = Probe.NoOnion(-1)
        for (onion in onions) {
            when (val result = connectOnion(endpoint, onion, handshakeTimeoutMs, onionTimeoutMs)) {
                Probe.Tor, Probe.NotListening, Probe.NotSocks, Probe.NotTor -> return result
                is Probe.NoOnion -> last = result
            }
        }
        return last
    }

    /**
     * The first half of [probe], without a circuit: the greeting, and a
     * CONNECT to [CANARY_ONION] that must be *refused* — answered with a
     * SOCKS error reply, as Tor does at once (`05 01`, or `05 F6` with
     * ExtendedErrors). [Probe.Tor] means only "may be Tor": a plain SOCKS5
     * proxy refuses the canary too (it can't resolve it), so it's never
     * enough on its own to route onion (R2-F1). No reply at all — a
     * timeout, the connection closed, a read error — is [Probe.NoOnion]
     * `(-1)`, not a pass: Tor always answers, while a proxy that dials
     * before answering and takes long upstream would otherwise pass here
     * and then "connect" the real onion too (R2-F2). Else what's there:
     * [Probe.NotListening], [Probe.NotSocks], or [Probe.NotTor] for a
     * proxy that "connected" the impossible onion.
     */
    suspend fun recheck(
        endpoint: SocksEndpoint,
        handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        canaryTimeoutMs: Long = CANARY_TIMEOUT_MS,
    ): Probe = when (val r = connectOnion(endpoint, CANARY_ONION, handshakeTimeoutMs, canaryTimeoutMs)) {
        Probe.Tor -> Probe.NotTor
        is Probe.NoOnion -> if (r.code >= 0) Probe.Tor else r
        else -> r
    }

    /** A CONNECT to [onion]:80: [Probe.Tor] for reply 0, else what went wrong. */
    private suspend fun connectOnion(
        endpoint: SocksEndpoint,
        onion: String,
        handshakeTimeoutMs: Long,
        timeoutMs: Long,
    ): Probe = attempt(endpoint, handshakeTimeoutMs, timeoutMs) { input, output ->
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

    /**
     * Whether a SOCKS5 proxy (still) listens at [endpoint]: the greeting
     * only, no request.
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

    // --- Re-checking (MainActivity's loop) --------------------------------

    /**
     * A proxy confirmed as Tor: how often it's [probe]d again — the full
     * probe, a real onion included, since only that tells Tor from a plain
     * SOCKS5 proxy that took the port meanwhile (R2-F1).
     */
    const val RECHECK_MS = 20_000L

    /** Nothing listening: how soon to look again (starting Orbot is picked up quickly). */
    const val RETRY_MS = 5_000L

    /** The longest wait between probes of a proxy that listens but isn't (or can't reach) Tor. */
    const val RETRY_MAX_MS = 5 * 60_000L

    /**
     * Whether a wait after [result] backs off: a proxy that listens but
     * isn't Tor, or can't reach an onion. Every full [probe] of it has it
     * look up the probe onion names, so it isn't asked every [RETRY_MS]
     * forever (R1-M2).
     */
    fun backsOff(result: Probe): Boolean = result != Probe.Tor && result != Probe.NotListening

    /** How long to wait before checking again after [result]; [backoffMs] is the last backing-off wait. */
    fun nextCheckMs(result: Probe, backoffMs: Long): Long = when {
        result == Probe.Tor -> RECHECK_MS
        !backsOff(result) -> RETRY_MS
        else -> (backoffMs * 2).coerceIn(RETRY_MS, RETRY_MAX_MS)
    }

    /**
     * After a confirmed proxy stopped reaching onion sites while it still
     * refuses the canary as Tor does: how long it's checked every
     * [RECHECK_MS] with no back-off, so a Tor on a flaky link (or during
     * an onion-service DoS wave) is routed again soon after it recovers
     * (R3-F1). Past this, the usual back-off.
     */
    const val FAST_RETRY_WINDOW_MS = 10 * 60_000L

    /**
     * The checking loop's state between checks (MainActivity, #275).
     *
     * @property confirmed found to be Tor: `.onion` is routed to it.
     * @property graceUsed a confirmed proxy's last check refused the canary
     *   as Tor does but reached neither probe onion, and it was kept routed
     *   for one quick check more (R3-F1).
     * @property unreached the last check found the canary refused as Tor
     *   does, but no onion site reached ([unreachedByTor]) — "Tor that can't get through
     *   right now", not "no Tor client there" (the refusal page says so).
     * @property backoffMs the last backing-off wait ([nextCheckMs]).
     * @property confirmedAtMs when (`elapsedRealtime`) it last passed, or `null`.
     */
    data class Watch(
        val confirmed: Boolean = false,
        val graceUsed: Boolean = false,
        val unreached: Boolean = false,
        val backoffMs: Long = RETRY_MS,
        val confirmedAtMs: Long? = null,
    )

    /** [afterCheck]'s answer: the new state, and how long to wait before the next check. */
    data class Next(val watch: Watch, val waitMs: Long)

    /**
     * The loop's next state after a check at [nowMs] (`elapsedRealtime`)
     * whose [recheck] said [canary] and whose verdict is [result] ([canary]
     * itself, or [reachOnion]'s after a refusal).
     *
     *  - Tor: confirmed, checked again in [RECHECK_MS].
     *  - The canary refused as Tor does but no probe onion reached — timed
     *    out or any SOCKS error, for a proxy that passed within
     *    [FAST_RETRY_WINDOW_MS]; else only a timeout or one of Tor's own
     *    extended onion errors ([unreachedByTor]) — for a
     *    confirmed proxy: once, it stays routed and is checked again in
     *    [RETRY_MS] — one slow circuit on a flaky link isn't "gone" (R3-F1).
     *    A second such check in a row, and it's refused. Anything else — no
     *    listener, not SOCKS, the canary "connected", no refusal — is
     *    refused at once, no grace (the listener isn't the Tor that was
     *    confirmed, R2-F1).
     *  - Refused after such an "unreached" check within
     *    [FAST_RETRY_WINDOW_MS] of the last pass: checked every
     *    [RECHECK_MS] (no back-off), so it's routed again soon after Tor gets
     *    through (R3-F1).
     *  - Else [nextCheckMs]'s wait: [RETRY_MS] with nothing listening, a
     *    growing back-off for a proxy that isn't (or can't reach) Tor.
     */
    fun afterCheck(watch: Watch, canary: Probe, result: Probe, nowMs: Long): Next {
        if (result == Probe.Tor) {
            return Next(Watch(confirmed = true, confirmedAtMs = nowMs), RECHECK_MS)
        }
        val recent = watch.confirmedAtMs?.let { nowMs - it in 0 until FAST_RETRY_WINDOW_MS } == true
        // Any SOCKS error counts for a proxy that passed within the window:
        // Orbot never enables ExtendedErrors, so a Tor that can't fetch an
        // onion descriptor answers a plain `04` (or `01`, `06`), like a
        // plain proxy would (R5-F1).
        val unreached = canary == Probe.Tor && result is Probe.NoOnion &&
            (unreachedByTor(result) || recent)
        if (unreached && watch.confirmed && !watch.graceUsed) {
            return Next(watch.copy(graceUsed = true, unreached = true), RETRY_MS)
        }
        if (unreached && recent) {
            return Next(
                watch.copy(confirmed = false, graceUsed = false, unreached = true, backoffMs = RETRY_MS),
                RECHECK_MS,
            )
        }
        val wait = nextCheckMs(result, watch.backoffMs)
        return Next(
            watch.copy(
                confirmed = false,
                graceUsed = false,
                unreached = unreached,
                backoffMs = if (backsOff(result)) wait else RETRY_MS,
            ),
            wait,
        )
    }

    /**
     * Whether [result], from [reachOnion] after the canary was refused,
     * reads as Tor that couldn't get through rather than as a proxy that
     * can't do onion at all: no reply within the deadline (a circuit that
     * didn't build in time), or one of Tor's own onion-service errors
     * (`0xF0`–`0xF7` with ExtendedErrors), which no other SOCKS5 proxy
     * sends (an undefined `0xF8`–`0xFF` is no Tor reply, R5-M1). A plain
     * SOCKS5 error (1–8) is what a plain proxy answers — it can't look the
     * name up — but also what Tor without ExtendedErrors answers (Orbot
     * never turns them on: `04` once no HSDir has the descriptor). So for
     * a proxy never confirmed, or not within [FAST_RETRY_WINDOW_MS], a
     * plain code is no grace, no "Tor answers" copy and no fast re-checks
     * (R4-M1); for one that passed within the window, [afterCheck] reads
     * it as Tor that can't get through (R5-F1) — a plain proxy that took
     * the port in the 20 s between checks gets onion for at most one
     * [RETRY_MS] grace check more, the price of not unrouting Orbot on
     * every flaky descriptor fetch.
     */
    internal fun unreachedByTor(result: Probe): Boolean =
        result is Probe.NoOnion && (result.code < 0 || result.code in 0xF0..0xF7)

    /**
     * A nudge to check sooner arrived at the loop in state [watch]: the
     * state to carry on with, or `null` to ignore it and keep waiting.
     * [byUser] (the Nodes page's Start Orbot) always checks sooner and
     * starts the back-off over. One from a page (a refusal page shown, a
     * routed onion load that failed) checks sooner only while the loop
     * isn't backing off, and leaves the back-off alone — else a page
     * loading onion frames in a loop would have a proxy that isn't Tor
     * probed every [RETRY_MS] (R4-M2). Either way the check runs no
     * sooner than [RETRY_MS] after the last one.
     */
    fun afterNudge(watch: Watch, byUser: Boolean): Watch? = when {
        byUser -> watch.copy(backoffMs = RETRY_MS)
        watch.backoffMs > RETRY_MS -> null
        else -> watch
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
        Probe.NotTor -> "The proxy on $endpoint isn't Tor: it claims to connect even to a .onion address " +
            "that can't exist, as shadowsocks, v2ray or clash proxies do. Freedom sends onion sites only to Tor."
        is Probe.NoOnion -> if (result.code < 0) {
            "The proxy on $endpoint didn't reach a .onion site in time. Is it Tor, and connected?"
        } else {
            "The proxy on $endpoint couldn't reach a .onion site (SOCKS error ${result.code}). Is it Tor, and connected?"
        }
    }
}
