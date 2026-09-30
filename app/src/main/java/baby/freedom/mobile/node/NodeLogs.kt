package baby.freedom.mobile.node

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * The embedded nodes whose recent log lines the Nodes page can show (#276).
 * [ordinal] crosses the `:node` binder ([INodeService.getLogs]), so keep
 * the order.
 */
enum class NodeLogSource(val title: String) {
    Swarm("Swarm"),
    Ipfs("IPFS"),
    Radicle("Radicle"),
    Tor("Tor"),
    LightClient("Light client"),
    ;

    companion object {
        fun of(ordinal: Int): NodeLogSource? = entries.getOrNull(ordinal)
    }
}

/**
 * A bounded ring of log lines, oldest first: at most [maxLines] lines and
 * [maxChars] characters in all, each line cut to [maxLineChars]. Adding
 * past either bound drops the oldest lines, so it never grows (#276).
 * Thread-safe.
 */
class LogRing(
    private val maxLines: Int = MAX_LINES,
    private val maxChars: Int = MAX_CHARS,
    private val maxLineChars: Int = MAX_LINE_CHARS,
) {
    init {
        require(maxLines > 0 && maxChars > 0 && maxLineChars in 1..maxChars)
    }

    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Synchronized
    fun add(line: String) {
        val cut = if (line.length <= maxLineChars) line else line.take(maxLineChars - 1) + "…"
        lines.addLast(cut)
        chars += cut.length
        while (lines.size > maxLines || chars > maxChars) chars -= lines.removeFirst().length
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** The lines, newline-joined. */
    fun text(): String = snapshot().joinToString("\n")

    @get:Synchronized
    val size: Int get() = lines.size

    @get:Synchronized
    val totalChars: Int get() = chars

    companion object {
        const val MAX_LINES = 1_000

        /** Keeps [text] well inside a binder transaction and a share intent (~200 KB as UTF-16). */
        const val MAX_CHARS = 96_000
        const val MAX_LINE_CHARS = 800
    }
}

/**
 * Takes out of a node's log line what must not be kept (#276): the
 * addresses of the pages the user visited. The native nodes log them —
 * freedom-ipfs names every gateway request's `/ipfs/<cid>` path and the
 * CIDs it fetches, ant the Swarm references it can't find — so a line is
 * scrubbed before it reaches a [LogRing], not when it's shown or shared.
 *
 * Kept: peer IDs, overlay and account addresses (`0x…`), multiaddrs,
 * Radicle repository and node IDs — what the share warning names. A
 * legacy `Qm…` peer ID looks exactly like a CIDv0, so it's kept only where
 * the line names it as a peer (`peer_id=`, `/p2p/`); anywhere else it's
 * taken out as the content ID it may be.
 */
object LogScrub {
    private const val REDACTED = "<redacted>"

    /** `key=value` fields that carry a page's address or content ID. */
    private val FIELD = Regex(
        """\b(path|top_level_path|unixfs_path|url|uri|href|referer|referrer|host|hostname|cid|cids|file_cid|root_cid|reference)=("[^"]*"|\[[^\]]*]|[^\s,}]*)""",
    )

    /** Anything with a scheme: `https://…`, `bzz://…`, `ipfs://…`, `rad://…`. */
    private val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]*""")

    /** A gateway path: `/bzz/<ref>/…`, `/ipfs/<cid>/…`. */
    private val GATEWAY_PATH = Regex("""/(bzz|bytes|chunks|ipfs|ipns|feeds|soc)/[^\s"'<>]+""")

    /** A Swarm reference (32 bytes, or 64 encrypted) — not a `0x` overlay or account. */
    private val SWARM_REF = Regex("""(?<![0-9A-Fa-fXx])[0-9A-Fa-f]{64}(?:[0-9A-Fa-f]{64})?(?![0-9A-Fa-f])""")

    /**
     * A CIDv1 in base32 (`bafy…`, `bafk…`) or base16 (`f01…`), or an IPNS
     * key in base36 (`k51…`).
     */
    private val CID = Regex("""\b(?:b[a-z2-7]{50,}|k[0-9a-z]{50,}|f01[0-9a-f]{60,})\b""")

    /**
     * A base58btc content ID: a CIDv0 (`Qm…`, 46 characters) or a CIDv1
     * (`z…`). Not a Radicle node ID (`z6Mk…`, a did:key) or repository ID
     * (`rad:z…`, too short to match).
     */
    private val CID_B58 = Regex("""(?<![1-9A-HJ-NP-Za-km-z])(?:Qm[1-9A-HJ-NP-Za-km-z]{44}|z(?!6M)[1-9A-HJ-NP-Za-km-z]{44,})(?![1-9A-HJ-NP-Za-km-z])""")

    /** What a `Qm…` peer ID follows: a peer field, or a multiaddr's `/p2p/`. */
    private val PEER_CONTEXT = Regex("""(?:peer\w*[=:]\s*"?|/p2p/)$""")

    /** A v3 onion service name. */
    private val ONION = Regex("""\b[a-z2-7]{56}\.onion\b""")

    fun scrub(line: String): String {
        var s = line
        s = FIELD.replace(s) { "${it.groupValues[1]}=$REDACTED" }
        s = URL.replace(s, "<url>")
        s = GATEWAY_PATH.replace(s) { "/${it.groupValues[1]}/$REDACTED" }
        s = SWARM_REF.replace(s, "<ref>")
        s = CID.replace(s, "<cid>")
        val b58 = s
        s = CID_B58.replace(b58) { m ->
            val peer = m.value.startsWith("Qm") &&
                PEER_CONTEXT.containsMatchIn(b58.substring(maxOf(0, m.range.first - 24), m.range.first))
            if (peer) m.value else "<cid>"
        }
        s = ONION.replace(s, "<onion>")
        return s
    }
}

/**
 * One line of `logcat -v threadtime`, as a node log line: time, level,
 * tag and message, with the terminal colours and tracing's own timestamp
 * taken out of native lines.
 */
internal data class LogcatLine(val time: String, val level: Char, val tag: String, val message: String) {
    fun format(): String = "$time $level $tag: $message"

    companion object {
        // 09-30 07:13:07.730  3826  3860 I ant-ffi : message
        private val THREADTIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")
        private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
        private val TRACING_TIME = Regex("""^\d{4}-\d\d-\d\dT[0-9:.]+Z\s+""")

        fun parse(raw: String): LogcatLine? {
            val m = THREADTIME.find(raw) ?: return null
            val (time, level, tag, message) = m.destructured
            val clean = TRACING_TIME.replace(ANSI.replace(message, ""), "")
            return LogcatLine(time, level[0], tag.trim(), clean)
        }
    }
}

/**
 * Which node a `:node` log line belongs to: freedom-ipfs and ant share one
 * tracing subscriber (logcat tag `ant-ffi`), so a native line goes by its
 * tracing target; the Kotlin side by tag, and `NodeService`'s own lines by
 * the node they name. Everything else in `:node` is the Swarm node's.
 */
internal fun nodeProcessSource(tag: String, message: String): NodeLogSource = when {
    tag == "IpfsNode" -> NodeLogSource.Ipfs
    tag == "RadicleNode" -> NodeLogSource.Radicle
    tag == "NodeService" && message.startsWith("ipfs") -> NodeLogSource.Ipfs
    tag == "NodeService" && message.startsWith("radicle") -> NodeLogSource.Radicle
    "freedom_ipfs" in message -> NodeLogSource.Ipfs
    RADICLE_TARGET.containsMatchIn(message) -> NodeLogSource.Radicle
    else -> NodeLogSource.Swarm
}

private val RADICLE_TARGET = Regex("""\b(?:lib)?radicle[a-z_]*(?:::[a-z_:]+)?:""")

/**
 * The recent log lines of the node(s) in this process (#276): `:node`
 * (Swarm, IPFS, Radicle), `:tor`, `:myotis`. A reader thread follows
 * logcat for this process's own PID only — the native nodes log there —
 * and keeps each node's lines, scrubbed ([LogScrub]), in a [LogRing] in
 * memory. Nothing is written to a file; the lines go when the process
 * exits.
 */
object NodeLogs {
    private const val TAG = "NodeLogs"
    private const val RESTART_DELAY_MS = 5_000L

    /** Framework lines every process logs at start; not a node's. */
    private val NOISE_TAGS = setOf(
        "nativeloader", "GraphicsEnvironment", "ApplicationLoaders", "ziparchive", "libc", "linker",
        "Zygote", "ActivityThread", "NetworkSecurityConfig", "CompatChangeReporter", "AppCompatDelegate",
    )

    private val rings = NodeLogSource.entries.associateWith { LogRing() }

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var logcat: java.lang.Process? = null

    @Volatile
    private var stopped = false

    /** This process's recent lines for [source], newline-joined. */
    fun text(source: NodeLogSource): String = rings.getValue(source).text()

    /**
     * Start following this process's log, once; [route] says which node a
     * line is (null drops it). [processName] is left out of the framework's
     * own lines, which it tags with (the end of) the process name.
     */
    @Synchronized
    fun start(processName: String, route: (tag: String, message: String) -> NodeLogSource?) {
        if (reader != null) return
        val pid = Process.myPid()
        // Only what this process logged: logcat's buffer can still hold an
        // earlier process's lines under the same (reused) PID.
        val sinceMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val since = String.format(Locale.US, "%d.%03d", sinceMs / 1000, sinceMs % 1000)
        val thread = Thread({ follow(pid, since, processName, route) }, "node-logs")
        thread.isDaemon = true
        reader = thread
        thread.start()
        // exitProcess() runs shutdown hooks: don't leave logcat behind.
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /** Stop following (the process is about to exit). */
    fun stop() {
        stopped = true
        logcat?.destroy()
    }

    private fun follow(pid: Int, since: String, processName: String, route: (String, String) -> NodeLogSource?) {
        var from = since
        while (!stopped) {
            try {
                val proc = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid", "-T", from)
                    .redirectErrorStream(true)
                    .start()
                logcat = proc
                if (stopped) proc.destroy()
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).useLines { lines ->
                    for (raw in lines) {
                        val line = LogcatLine.parse(raw) ?: continue
                        if (line.tag in NOISE_TAGS || processName.endsWith(line.tag)) continue
                        val source = route(line.tag, line.message) ?: continue
                        rings.getValue(source).add(LogScrub.scrub(line.format()))
                    }
                }
            } catch (t: Throwable) {
                if (!stopped) Log.w(TAG, "logcat reader failed: ${t.javaClass.simpleName}")
            }
            if (stopped) break
            // logcat went away (rare): pick up from now, not from the start again.
            val now = System.currentTimeMillis()
            from = String.format(Locale.US, "%d.%03d", now / 1000, now % 1000)
            Thread.sleep(RESTART_DELAY_MS)
        }
    }
}
