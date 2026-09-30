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
 * past either bound drops an old line, so it never grows (#276).
 * Thread-safe.
 *
 * Each line has a kind (its tracing `phase`, or its tag: [kindOf]), and
 * the line dropped is the oldest of the kind that holds the most — not the
 * oldest overall. A node's bulk chatter (freedom-ipfs logs a
 * `block_store_get` / `provider_*` line per block, hundreds a second while
 * a page loads) then only pushes out its own older lines, and the rare
 * lines that say what happened — a request's `request_start`,
 * `name_resolve`, an error — stay until they are themselves the most
 * (R3-M2). [snapshot] puts the kept lines back in the order they came.
 */
class LogRing(
    private val maxLines: Int = MAX_LINES,
    private val maxChars: Int = MAX_CHARS,
    private val maxLineChars: Int = MAX_LINE_CHARS,
) {
    init {
        require(maxLines > 0 && maxChars > 0 && maxLineChars in 1..maxChars)
    }

    private class Entry(val seq: Long, val text: String)

    private class Kind {
        val lines = ArrayDeque<Entry>()
        var chars = 0
    }

    private val kinds = LinkedHashMap<String, Kind>()
    private var seq = 0L
    private var lineCount = 0
    private var chars = 0

    @Synchronized
    fun add(line: String, kind: String = "") {
        val cut = if (line.length <= maxLineChars) line else line.take(maxLineChars - 1) + "…"
        val k = kinds.getOrPut(kind) { Kind() }
        k.lines.addLast(Entry(seq++, cut))
        k.chars += cut.length
        lineCount++
        chars += cut.length
        while (lineCount > maxLines || chars > maxChars) {
            val overLines = lineCount > maxLines
            // The kind holding the most (lines, or characters); on a tie
            // the one whose oldest line is older.
            var worst: Map.Entry<String, Kind>? = null
            for (e in kinds.entries) {
                val w = worst?.value
                val more = when {
                    w == null -> true
                    overLines -> e.value.lines.size > w.lines.size ||
                        (e.value.lines.size == w.lines.size && e.value.lines.first().seq < w.lines.first().seq)
                    else -> e.value.chars > w.chars ||
                        (e.value.chars == w.chars && e.value.lines.first().seq < w.lines.first().seq)
                }
                if (more) worst = e
            }
            val victim = worst!!
            val dropped = victim.value.lines.removeFirst()
            victim.value.chars -= dropped.text.length
            lineCount--
            chars -= dropped.text.length
            if (victim.value.lines.isEmpty()) kinds.remove(victim.key)
        }
    }

    @Synchronized
    fun snapshot(): List<String> =
        kinds.values.flatMap { it.lines }.sortedBy { it.seq }.map { it.text }

    @Synchronized
    fun clear() {
        kinds.clear()
        lineCount = 0
        chars = 0
    }

    /** The lines, newline-joined. */
    fun text(): String = snapshot().joinToString("\n")

    @get:Synchronized
    val size: Int get() = lineCount

    @get:Synchronized
    val totalChars: Int get() = chars

    companion object {
        const val MAX_LINES = 1_000

        /** Keeps [text] well inside a binder transaction and a share intent (~200 KB as UTF-16). */
        const val MAX_CHARS = 96_000
        const val MAX_LINE_CHARS = 800

        /**
         * A node log line's kind, for which line to drop: a native line's
         * tracing `phase="…"`, else its logcat [tag].
         */
        fun kindOf(tag: String, message: String): String {
            val at = message.indexOf("phase=\"")
            if (at < 0) return tag
            val end = message.indexOf('"', at + 7)
            return if (end < 0 || end - at > 71) tag else message.substring(at + 7, end)
        }
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

    /**
     * `key=value` fields that carry a page's address, its name or a
     * content ID — matched by the key's ending, so every variant
     * freedom-ipfs has (`path`, `top_level_path`, `unixfs_path`,
     * `source_peer_previous_top_level_path`; `cid`, `file_cid`,
     * `root_cid`, `directory_cid`, `cids`; `resolved_target`, `targets`;
     * the DNSLink / IPNS `name` of the `name_cache` phases) and any a
     * later version adds is covered, not just the ones named here.
     * `etag` holds a file's CID and path. A quoted value runs to its
     * closing quote, past any `\"` inside it.
     */
    private val FIELD_KEY_ENDS = listOf(
        "path", "paths", "cid", "cids", "name", "names", "target", "targets", "url", "uri", "href",
        "referer", "referrer", "host", "hostname", "domain", "dnslink", "etag", "reference",
    )
    private val FIELD_VALUE = Regex(""""(?:[^"\\]|\\.)*"|\[[^\]]*]|[^\s,}]*""").toPattern()

    /**
     * A DNSLink / IPNS name inside free text — freedom-ipfs's resolver
     * errors (`dnslink record not found for docs.ipfs.tech`, `invalid
     * dnslink record: …`, `invalid IPNS name: …`, `http resolver: …`)
     * and the `_dnslink.<name>` it looks up. The error text runs to the
     * end of the field, so to the next ` key=` or the end of the line.
     */
    private val NAME_ERROR = Regex(
        """(?i)(dnslink record not found for|invalid dnslink record:|invalid ipns name:|invalid ipns record:|http resolver:)\s*.*?(?=\s+[A-Za-z_]+=|"|$)""",
    )
    private val DNSLINK_NAME = Regex("""(?i)\b_dnslink\.[A-Za-z0-9._-]+""")

    /** Anything with a scheme: `https://…`, `bzz://…`, `ipfs://…`, `rad://…`. */
    private val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]*""")

    /** A gateway path: `/bzz/<ref>/…`, `/ipfs/<cid>/…`. */
    private val GATEWAY_ROOTS = listOf("/bzz/", "/bytes/", "/chunks/", "/ipfs/", "/ipns/", "/feeds/", "/soc/")
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

    /**
     * A regex runs only where the line has what it could match — a `=`, a
     * `://`, a run of letters and digits as long as the shortest ID — so
     * the bulk of a node's lines cost a few scans, not every regex (#276,
     * R3-M1: the tap runs whether or not Logs is ever opened). The checks
     * only skip a regex that can't match; what's taken out is the same.
     */
    fun scrub(line: String): String {
        var s = line
        if ('=' in s) {
            s = redactFields(s)
        }
        val lower = s.lowercase(Locale.ROOT)
        if ("dnslink" in lower || "invalid ipns" in lower || "resolver:" in lower) {
            s = NAME_ERROR.replace(s) { "${it.groupValues[1]} $REDACTED" }
            s = DNSLINK_NAME.replace(s, "_dnslink.$REDACTED")
        }
        if ("://" in s) s = URL.replace(s, "<url>")
        if (GATEWAY_ROOTS.any { it in s }) s = GATEWAY_PATH.replace(s) { "/${it.groupValues[1]}/$REDACTED" }
        // The shortest ID below is a CIDv0 / base58 CIDv1 (46 / 45 characters).
        if (longestAlnumRun(s) >= 45) {
            s = SWARM_REF.replace(s, "<ref>")
            s = CID.replace(s, "<cid>")
            val b58 = s
            s = CID_B58.replace(b58) { m ->
                val peer = m.value.startsWith("Qm") &&
                    PEER_CONTEXT.containsMatchIn(b58.substring(maxOf(0, m.range.first - 24), m.range.first))
                if (peer) m.value else "<cid>"
            }
            if (".onion" in s) s = ONION.replace(s, "<onion>")
        }
        return s
    }

    /**
     * Each `key=value` whose key (the run of letters and `_` before the
     * `=`) ends in one of [FIELD_KEY_ENDS] gets its value replaced — also
     * after a digit (`v2path=`), which the word-boundary regex this
     * replaces let through. A scan from each
     * `=`, not a regex tried at every word: this runs on every line.
     */
    private fun redactFields(s: String): String {
        var out: StringBuilder? = null
        var value: java.util.regex.Matcher? = null
        var copied = 0
        var i = s.indexOf('=')
        while (i >= 0) {
            var k = i
            while (k > 0 && (s[k - 1].let { it == '_' || it in 'a'..'z' || it in 'A'..'Z' })) k--
            var next = i + 1
            if (k < i && FIELD_KEY_ENDS.any { it.length <= i - k && s.regionMatches(i - it.length, it, 0, it.length) }) {
                val m = (value ?: FIELD_VALUE.matcher(s).also { value = it }).region(i + 1, s.length)
                m.lookingAt()
                val sb = out ?: StringBuilder(s.length).also { out = it }
                sb.append(s, copied, i + 1).append(REDACTED)
                copied = m.end()
                next = maxOf(m.end(), i + 1)
            }
            i = if (next < s.length) s.indexOf('=', next) else -1
        }
        return out?.append(s, copied, s.length)?.toString() ?: s
    }

    /** The longest run of ASCII letters and digits in [s]. */
    private fun longestAlnumRun(s: String): Int {
        var best = 0
        var run = 0
        for (c in s) {
            if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z') {
                if (++run > best) best = run
            } else {
                run = 0
            }
        }
        return best
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
        //
        // Read by hand, not by regex: this runs on every line a node logs,
        // hundreds a second while a page loads, whether or not Logs is ever
        // opened — and Android's regex engine took ~165 us a line here,
        // most of the reader's CPU (R3-M1).
        fun parse(raw: String): LogcatLine? {
            if (raw.length < 20 || !raw.startsWith(DATE_SHAPE)) return null
            val time = raw.substring(6, 18)
            var i = 18
            i = skipSpaces(raw, i, atLeast = 1) ?: return null
            i = skipDigits(raw, i) ?: return null // pid
            i = skipSpaces(raw, i, atLeast = 1) ?: return null
            i = skipDigits(raw, i) ?: return null // tid
            i = skipSpaces(raw, i, atLeast = 1) ?: return null
            val level = raw.getOrNull(i) ?: return null
            if (level !in "VDIWEFA" || raw.getOrNull(i + 1)?.isWhitespace() != true) return null
            i = skipSpaces(raw, i + 1, atLeast = 1) ?: return null
            val colon = raw.indexOf(':', i)
            if (colon < 0) return null
            val tag = raw.substring(i, colon).trim()
            val from = if (raw.getOrNull(colon + 1) == ' ') colon + 2 else colon + 1
            return LogcatLine(time, level, tag, cleanNative(raw, from))
        }

        /** `MM-DD HH:MM:SS.mmm`, `d` a digit. */
        private const val DATE_SHAPE = "dd-dd dd:dd:dd.ddd"

        private fun String.startsWith(shape: String): Boolean {
            for (k in shape.indices) {
                val c = this[k]
                if (if (shape[k] == 'd') c !in '0'..'9' else c != shape[k]) return false
            }
            return true
        }

        private fun skipSpaces(s: String, from: Int, atLeast: Int): Int? {
            var i = from
            while (i < s.length && s[i] == ' ') i++
            return if (i - from >= atLeast) i else null
        }

        private fun skipDigits(s: String, from: Int): Int? {
            var i = from
            while (i < s.length && s[i] in '0'..'9') i++
            return if (i > from) i else null
        }

        /**
         * The message from [from] on, with the terminal colours
         * (`ESC [ … letter`) and tracing's own leading timestamp
         * (`2026-09-30T05:13:07.733503Z `) taken out.
         */
        private fun cleanNative(raw: String, from: Int): String {
            val sb = StringBuilder(raw.length - from)
            var i = from
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\u001B' && raw.getOrNull(i + 1) == '[') {
                    var j = i + 2
                    while (j < raw.length && (raw[j] in '0'..'9' || raw[j] == ';')) j++
                    if (j < raw.length && (raw[j] in 'A'..'Z' || raw[j] in 'a'..'z')) {
                        i = j + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            // 2026-09-30T05:13:07.733503Z, then whitespace.
            val m = sb
            if (m.length > 11 && (0..3).all { m[it] in '0'..'9' } && m[4] == '-' && m[5] in '0'..'9' &&
                m[6] in '0'..'9' && m[7] == '-' && m[8] in '0'..'9' && m[9] in '0'..'9' && m[10] == 'T'
            ) {
                var j = 11
                while (j < m.length && (m[j] in '0'..'9' || m[j] == ':' || m[j] == '.')) j++
                if (j > 11 && j < m.length && m[j] == 'Z' && j + 1 < m.length && m[j + 1].isWhitespace()) {
                    var k = j + 1
                    while (k < m.length && m[k].isWhitespace()) k++
                    return m.substring(k)
                }
            }
            return m.toString()
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
 * exits, or when the user clears cookies & site data or closes the
 * last private tab ([clear]).
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

    /** Guards [rings] against a [clear] landing between a line's check and its add. */
    private val lock = Any()

    /** Bumped by [clear]: a line read under an older one was logged before the clear. */
    private var generation = 0

    /** Wall-clock ms of the last [clear]: where the reader picks logcat up again. */
    private var clearedAtMs = 0L

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

    /**
     * Forget every line kept so far (part of *Clear cookies & site
     * data*): what a line the scrubber missed, or the timing of the
     * user's browsing, says goes with the rest of the site data. Lines
     * logcat has already handed the reader but it hasn't kept yet are
     * dropped too — the reader starts logcat over from this moment.
     */
    fun clear() {
        synchronized(lock) {
            generation++
            clearedAtMs = System.currentTimeMillis()
            rings.values.forEach { it.clear() }
        }
        logcat?.destroy()
    }

    /** The current [generation], for [keep]. */
    internal fun generation(): Int = synchronized(lock) { generation }

    /**
     * Keep [line] in [source]'s ring, scrubbed, unless a [clear] has come
     * since [gen] was read: then false, and the reader starts over.
     */
    internal fun keep(gen: Int, source: NodeLogSource, line: String, kind: String = ""): Boolean {
        val scrubbed = LogScrub.scrub(line)
        synchronized(lock) {
            if (gen != generation) return false
            rings.getValue(source).add(scrubbed, kind)
            return true
        }
    }

    /** Stop following (the process is about to exit). */
    fun stop() {
        stopped = true
        logcat?.destroy()
    }

    private fun follow(pid: Int, since: String, processName: String, route: (String, String) -> NodeLogSource?) {
        var from = since
        while (!stopped) {
            val gen = generation()
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
                        if (!keep(gen, source, line.format(), LogRing.kindOf(line.tag, line.message))) return@useLines
                    }
                }
                proc.destroy()
            } catch (t: Throwable) {
                // Not when stop() or clear() destroyed logcat under the reader.
                if (!stopped && generation() == gen) Log.w(TAG, "logcat reader failed: ${t.javaClass.simpleName}")
            }
            if (stopped) break
            val clearedAt = synchronized(lock) { if (generation != gen) clearedAtMs else null }
            if (clearedAt != null) {
                // Cleared: pick up from the clear, at once — nothing before it.
                from = String.format(Locale.US, "%d.%03d", clearedAt / 1000, clearedAt % 1000)
                continue
            }
            // logcat went away (rare): pick up from now, not from the start again.
            val now = System.currentTimeMillis()
            from = String.format(Locale.US, "%d.%03d", now / 1000, now % 1000)
            Thread.sleep(RESTART_DELAY_MS)
        }
    }
}
