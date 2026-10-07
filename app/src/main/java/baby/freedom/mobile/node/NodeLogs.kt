package baby.freedom.mobile.node

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The embedded nodes whose recent log lines the Nodes page can show (#276).
 * [ordinal] crosses the `:node` binder ([INodeService.getLogs]), so keep
 * the order.
 */
enum class NodeLogSource(@StringRes private val titleRes: Int) {
    Swarm(R.string.node_log_source_swarm),
    Ipfs(R.string.node_log_source_ipfs),
    Radicle(R.string.node_log_source_radicle),
    Tor(R.string.node_log_source_tor),
    LightClient(R.string.node_log_source_light_client),
    ;

    /** The node's name on the logs page. */
    val title: String get() = Strings.get(titleRes)

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
     * closing quote, past any `\"` inside it. A bare value runs to the
     * next field (` key=`), the `}:` closing its span, or the end of the
     * line — not to a `,` or a space: a page's path may hold commas
     * (`/ipfs/<cid>/a,b.html`, R4-F1), and freedom-ipfs logs the
     * percent-decoded request path unquoted, so `alan%20secret.html` is
     * `path=/ipfs/<cid>/alan secret.html` (R5-F1). Only the `}` closing a
     * span is left out of it. Where a line's free text follows a field
     * rather than another field, that text goes too: more is taken out,
     * never less. (A decoded file name that itself holds ` key=` still
     * reads as the next field there; nothing tells the two apart.)
     * `(?s)` and `\z`: a decoded path can hold U+2028, U+2029 or U+0085,
     * which a plain `.` stops at and `$` matches before (R2-M1).
     */
    private val FIELD_KEY_ENDS = listOf(
        "path", "paths", "cid", "cids", "name", "names", "target", "targets", "url", "uri", "href",
        "referer", "referrer", "host", "hostname", "domain", "dnslink", "etag", "reference",
    )
    private val FIELD_VALUE = Regex("""(?s)"(?:[^"\\]|\\.)*"|\[[^\]]*]|.*?(?=\}+:|\}*\z|\}*\s+[A-Za-z_][\w.]*=)""").toPattern()

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
                // The pattern always matches; if it ever didn't, the rest of
                // the line goes rather than m.end() throwing (R2-M1).
                val end = if (m.lookingAt()) m.end() else s.length
                val sb = out ?: StringBuilder(s.length).also { out = it }
                sb.append(s, copied, i + 1).append(REDACTED)
                copied = end
                next = maxOf(end, i + 1)
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
 * One entry of this process's log, as a node log line: time, level, tag
 * and message, with the terminal colours and tracing's own timestamp
 * taken out of native lines.
 */
internal data class LogcatLine(
    val time: String,
    val level: Char,
    val tag: String,
    val message: String,
    /** When it was logged, wall-clock ms (0: unknown). */
    val atMs: Long = 0,
) {
    fun format(): String = "$time $level $tag: $message"

    companion object {
        /** Stands in for a line break inside one log write. */
        const val JOIN = " ⏎ "

        /**
         * The line for one log write: [priority] is logcat's (4 = info),
         * [raw] the whole message as written. A write that held line
         * breaks stays one line — each break becomes [JOIN] — so it's
         * scrubbed as a whole: freedom-ipfs logs a request's
         * percent-decoded path unquoted, and a `%0A` or `%0D` in a
         * visited address is a break there (R6-F1, R1-F1).
         */
        fun of(epochSec: Long, nanos: Int, priority: Int, tag: String, raw: String, zone: ZoneId): LogcatLine {
            val t = Instant.ofEpochSecond(epochSec, nanos.toLong()).atZone(zone)
            val time = String.format(
                Locale.US, "%02d:%02d:%02d.%03d", t.hour, t.minute, t.second, t.nano / 1_000_000,
            )
            val sb = StringBuilder(raw.length)
            cleanNative(raw, sb)
            return LogcatLine(time, levelOf(priority), tag, sb.toString(), epochSec * 1000 + nanos / 1_000_000)
        }

        /** logcat's letter for an `android_LogPriority`. */
        fun levelOf(priority: Int): Char = "??VDIWEFS".getOrElse(priority) { '?' }

        /**
         * [raw] into [sb], trailing line breaks trimmed, every other line
         * break (`\r\n`, `\n` or a lone `\r`) as [JOIN], and the terminal
         * colours (`ESC [ … letter`) and tracing's own leading timestamp
         * (`2026-09-30T05:13:07.733503Z `) taken out.
         */
        private fun cleanNative(raw: String, sb: StringBuilder) {
            var end = raw.length
            while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) end--
            var i = 0
            while (i < end) {
                val c = raw[i]
                if (c == '\u001B' && raw.getOrNull(i + 1) == '[') {
                    var j = i + 2
                    while (j < end && (raw[j] in '0'..'9' || raw[j] == ';')) j++
                    if (j < end && (raw[j] in 'A'..'Z' || raw[j] in 'a'..'z')) {
                        i = j + 1
                        continue
                    }
                }
                if (c == '\r' || c == '\n') {
                    sb.append(JOIN)
                    i += if (c == '\r' && raw.getOrNull(i + 1) == '\n') 2 else 1
                    continue
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
                    m.delete(0, k)
                }
            }
        }
    }
}

/**
 * Reads `logcat -B` (binary) output, one [LogcatLine] per log write.
 *
 * Why binary: in its text formats logcat prints every line of one write
 * as a line of its own, and nothing but the text itself tells a follow-on
 * line from a new write — text a visited address can forge (a `%0A` then
 * a fake tracing timestamp, or a lone `%0D` then a fake logcat header).
 * In binary each write is one length-prefixed entry, so where a write
 * starts and ends isn't up to what it says (R1-F1). ant-ffi hands each
 * tracing event to one `__android_log_write`, and liblog cuts a longer
 * write at ~4 KB rather than splitting it, so an entry is a whole event.
 *
 * The entry (`struct logger_entry`, little-endian): `u16 len`,
 * `u16 hdr_size`, `i32 pid`, `u32 tid`, `u32 sec`, `u32 nsec`, then more
 * header up to `hdr_size` (`lid`, `uid`); then `len` bytes of payload:
 * the priority byte, the tag and the message, each NUL-terminated.
 */
internal class LogcatEntries(input: InputStream, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val input = DataInputStream(BufferedInputStream(input, 16 * 1024))
    private val header = ByteArray(MAX_HEADER)
    private val payload = ByteArray(0xFFFF)

    /** The next text entry, or null at the end of the stream. */
    fun next(): LogcatLine? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val len = first or (input.readUnsignedByte() shl 8)
            val hdrSize = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            // Out of step with the stream: nothing after this can be trusted.
            if (hdrSize < MIN_HEADER || hdrSize > MAX_HEADER) throw IOException("logcat entry header size $hdrSize")
            input.readFully(header, 4, hdrSize - 4)
            input.readFully(payload, 0, len)
            val lid = int32(20)
            if (lid !in TEXT_BUFFERS || len < 1) continue
            val tagEnd = indexOfNul(1, len)
            if (tagEnd < 0) continue
            val msgEnd = indexOfNul(tagEnd + 1, len).let { if (it < 0) len else it }
            val tag = String(payload, 1, tagEnd - 1, Charsets.UTF_8).trim()
            val message = String(payload, tagEnd + 1, msgEnd - tagEnd - 1, Charsets.UTF_8)
            val sec = int32(12).toLong() and 0xFFFF_FFFFL
            val nsec = int32(16)
            return LogcatLine.of(sec, nsec.coerceIn(0, 999_999_999), payload[0].toInt(), tag, message, zone)
        }
    }

    private fun int32(at: Int): Int =
        (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8) or
            ((header[at + 2].toInt() and 0xFF) shl 16) or ((header[at + 3].toInt() and 0xFF) shl 24)

    private fun indexOfNul(from: Int, until: Int): Int {
        for (k in from until until) if (payload[k] == 0.toByte()) return k
        return -1
    }

    companion object {
        /**
         * `logger_entry` v3 is 24 bytes (with `lid`), v4 28 (with `uid`);
         * logd writes nothing older. A v1 entry has no `hdr_size` (its
         * `__pad` is 0), so it reads as out of step (R2-M2).
         */
        private const val MIN_HEADER = 24
        private const val MAX_HEADER = 100

        /** The text buffers: main, radio, system, crash, kernel. Not events, stats or security. */
        private val TEXT_BUFFERS = setOf(0, 1, 3, 4, 7)
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

    /**
     * How long after a [clear] lines aren't kept (R1-M1). The pages the
     * clear is for — the private tabs just closed, or the site data just
     * cleared — can still have node requests in flight: freedom-ipfs goes
     * on fetching and logging a closed tab's request for a few seconds.
     */
    internal const val SETTLE_MS = 20_000L

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

    /**
     * [elapsedMs] of the last [clear] (null: none yet), for the settle
     * window. Monotonic, not wall-clock: a clock set back after a clear
     * must not stretch the window by the size of the step (R2-M1).
     */
    private var clearedAtElapsed: Long? = null

    /**
     * [wallMs] as it read at the last [clear] (0: none yet), not moved
     * by a clock step since: logcat's stamps are the clock as
     * it read when each line was logged, so this is what the lines
     * logged before the clear are stamped at or below (R3-M1).
     */
    private var clearedAtWall = 0L

    /** The wall clock; tests stand in their own. */
    @Volatile
    internal var wallMs: () -> Long = System::currentTimeMillis

    /** The monotonic clock ([SystemClock.elapsedRealtime]); tests stand in their own. */
    @Volatile
    internal var elapsedMs: () -> Long = SystemClock::elapsedRealtime

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
        val thread = Thread({ follow(pid, sinceMs, processName, route) }, "node-logs")
        thread.isDaemon = true
        reader = thread
        thread.start()
        // exitProcess() runs shutdown hooks: don't leave logcat behind.
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /**
     * Forget every line kept so far (part of *Delete browsing data*'s
     * *Cookies and site data*): what a line the scrubber missed, or the timing of the
     * user's browsing, says goes with the rest of the site data. Lines
     * logcat has already handed the reader but it hasn't kept yet are
     * dropped too — the reader starts logcat over from this moment. So
     * are lines logged in the [SETTLE_MS] after it, whatever they're from:
     * requests the cleared pages still had in flight log then too, and a
     * line can't be told apart by page (R1-M1).
     */
    fun clear() {
        synchronized(lock) {
            generation++
            clearedAtElapsed = elapsedMs()
            clearedAtWall = wallMs()
            rings.values.forEach { it.clear() }
        }
        logcat?.destroy()
    }

    /** [clearedAtWall], for [restartFrom]. */
    internal fun clearedAtWallMs(): Long = synchronized(lock) { clearedAtWall }

    /** The current [generation], for [keep]. */
    internal fun generation(): Int = synchronized(lock) { generation }

    /**
     * Keep [line] in [source]'s ring, scrubbed, unless a [clear] has come
     * since [gen] was read: then false, and the reader starts over.
     * A line logged ([atMs], wall-clock; 0: unknown, taken as now) or
     * read within [SETTLE_MS] of the last clear is dropped, and the
     * reader goes on. The window is on the monotonic clock: the line's
     * wall-clock stamp is moved onto it by how far the two clocks stand
     * apart now, so a wall clock stepped back (or forward) since the
     * clear neither stretches nor shortens it (R2-M1).
     */
    internal fun keep(gen: Int, source: NodeLogSource, line: String, kind: String = "", atMs: Long = 0): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            val cleared = clearedAtElapsed
            if (cleared != null) {
                val nowElapsed = elapsedMs()
                val atElapsed = if (atMs == 0L) nowElapsed else atMs - (wallMs() - nowElapsed)
                val quietUntil = cleared + SETTLE_MS
                if (nowElapsed < quietUntil || atElapsed < quietUntil) return true
            }
        }
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

    private fun follow(pid: Int, sinceMs: Long, processName: String, route: (String, String) -> NodeLogSource?) {
        // The latest stamp of an entry logcat has handed over (0: none yet).
        var seenMs = 0L
        while (!stopped) {
            // Never from before the latest clear (R5-M1), nor from before
            // an entry already read (R3-M1): see [restartFrom].
            val (gen, clearedAt) = synchronized(lock) { generation to clearedAtWall }
            val from = formatSince(restartFrom(sinceMs, seenMs, clearedAt))
            var proc: java.lang.Process? = null
            try {
                // Binary: one entry per log write, however many lines it holds (LogcatEntries).
                proc = ProcessBuilder("logcat", "-B", "--pid=$pid", "-T", from)
                    // logcat's own complaints (a bad -T, say) aren't entries.
                    .redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                    .start()
                logcat = proc
                if (stopped) proc.destroy()
                val entries = LogcatEntries(proc.inputStream)
                while (true) {
                    val line = entries.next() ?: break
                    if (line.atMs > seenMs) seenMs = line.atMs
                    if (line.tag in NOISE_TAGS || processName.endsWith(line.tag)) continue
                    val source = route(line.tag, line.message) ?: continue
                    if (!keep(gen, source, line.format(), LogRing.kindOf(line.tag, line.message), line.atMs)) break
                }
            } catch (t: Throwable) {
                // Not when stop() or clear() destroyed logcat under the reader.
                if (!stopped && generation() == gen) Log.w(TAG, "logcat reader failed: ${t.javaClass.simpleName}")
            } finally {
                // Also when the reader failed: a restart would leave this one running.
                proc?.destroy()
            }
            if (stopped) break
            // Cleared: pick up from the clear (the loop's top), at once.
            if (generation() != gen) continue
            // logcat went away (rare): pick up after what was read, not from the start again.
            Thread.sleep(RESTART_DELAY_MS)
        }
    }

    /**
     * Where logcat starts (its `-T`, wall-clock ms): this process's start
     * ([sinceMs]), just after the latest entry already read ([seenMs]; 0:
     * none), or the latest clear ([clearedAtWallMs], as the clock read
     * then; 0: none), whichever is latest.
     *
     * All three are stamps as the clock read at the time, never moved by
     * a step since (R3-M1). logd starts a `-T` read at the first entry
     * stamped after it and sends everything logged from there on, so
     * after the clock is set back by N, a `-T` of "now", or of the clear
     * moved back by N, would send N worth of entries again — read
     * already, or logged before the clear, whose own stamps are still on
     * the clock as it was. A clock set back only costs the entries
     * logged while logcat was away (the restart delay) that are stamped
     * below the floor; entries logged after it starts come live.
     */
    internal fun restartFrom(sinceMs: Long, seenMs: Long, clearedAtWallMs: Long): Long =
        maxOf(sinceMs, if (seenMs > 0) seenMs + 1 else 0L, clearedAtWallMs)

    /** logcat's `-T` time, `<seconds>.<millis>`, for a wall-clock [ms]. */
    internal fun formatSince(ms: Long): String = String.format(Locale.US, "%d.%03d", ms / 1000, ms % 1000)
}
