package baby.freedom.mobile.browser

import baby.freedom.mobile.BuildConfig
import baby.freedom.mobile.ens.Secp256k1
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Trust anchor for filter-list updates over Swarm (#127), shared with
 * desktop (`src/main/adblock/feed-config.js`) and iOS
 * (`AdblockUpdateFeed.swift`); [SCHEMA] and [TOPIC] are a cross-repo
 * contract with the publisher, freedom-adblock-service's
 * `src/manifest.ts`.
 *
 * The publisher writes a signed manifest to the Swarm feed ([OWNER],
 * [TOPIC_HEX]). The app reads that feed by its compiled-in owner and
 * topic — never a reference handed to it — and trusts nothing in the
 * payload until the manifest's own `sig` recovers to [SIGNER]
 * ([verifyAdblockManifest]); every list it names must then hash to the
 * `sha256` the signed manifest gives. The feed's single-owner-chunk
 * signature (owner [OWNER]) is checked by the Swarm node, not here: a
 * user-configured external endpoint could skip that, and the manifest
 * signature doesn't depend on it.
 *
 * One key (the production publisher's, key ceremony 2026-07-06) fills
 * both roles today; they are pinned separately so the feed key and the
 * signing key can rotate independently. A development build can name a
 * test publisher instead ([OWNER], [SIGNER]).
 */
internal object AdblockFeed {
    /** Kept in lockstep with freedom-adblock-service `MANIFEST_SCHEMA`. */
    const val SCHEMA = 1L
    const val TOPIC = "freedom/adblock/lists/v1"

    /** keccak256([TOPIC]) — bee-js `Topic.fromString`; a unit test holds the two together. */
    const val TOPIC_HEX = "96741f799248290fa67445649f572f1c773238c1f07e2e98643c625370f9bc15"

    /** The production publisher's address; see docs/adblock-production-key-ceremony.md in the iOS repo. */
    const val PRODUCTION_PUBLISHER = "0xb818FF019BC15BC3DfbdaD4CE0ab66A6f74e8f1E"

    /**
     * The feed owner and the manifest signer: the production publisher,
     * unless the build was made with `-Pfreedom.adblockFeedOwner=` /
     * `-Pfreedom.adblockSigner=` (a test publisher, see app/build.gradle.kts).
     */
    val OWNER: String = BuildConfig.ADBLOCK_FEED_OWNER.ifEmpty { PRODUCTION_PUBLISHER }
    val SIGNER: String = BuildConfig.ADBLOCK_SIGNER.ifEmpty { PRODUCTION_PUBLISHER }
}

/** One list the manifest offers (`platforms.desktop.lists[]` — raw ABP text, compiled on the device). */
internal data class AdblockManifestList(
    val category: String,
    val listId: String,
    val title: String?,
    val ref: String,
    val sha256: String,
    val bytes: Long,
    val ruleCount: Long,
)

/** A manifest that passed [verifyAdblockManifest]. */
internal data class AdblockManifest(
    val version: Long,
    val generatedAt: String,
    val lists: List<AdblockManifestList>,
)

/** What [verifyAdblockManifest] made of a payload. */
internal sealed interface AdblockManifestVerdict {
    data class Ok(val manifest: AdblockManifest) : AdblockManifestVerdict

    /** [reason] uses desktop's names (`bad_sig`, `not_newer`, …) so logs read the same. */
    data class Rejected(val reason: String, val version: Long? = null) : AdblockManifestVerdict
}

/** A list bigger than this is refused before it's downloaded; the largest today is ~2 MB. */
internal const val MAX_ADBLOCK_LIST_BYTES = 32L * 1024 * 1024

/** A manifest bigger than this is refused unread; today's is ~8 KB. */
internal const val MAX_ADBLOCK_MANIFEST_BYTES = 1024 * 1024

// Field formats, as desktop's update-manifest.js: `list_id` and
// `category` are plain filename tokens (nothing that could escape the
// staging directory), `ref` a Swarm reference, `sha256` a hex digest.
private val LIST_ID_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
private val CATEGORY_RE = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
private val SWARM_REF_RE = Regex("^[0-9a-f]{64}(?:[0-9a-f]{64})?$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")

/**
 * Validate and authenticate a feed [payload] (the raw bytes the feed
 * returned). In order: a well-formed JSON object; the [AdblockFeed.SCHEMA];
 * a positive integer `version`; every list entry well-formed, with no
 * category or list id twice; `version` above [appliedVersion] (or equal
 * to it when [allowRepublish], to fetch a category switched on since
 * that version was applied — never below); and last `sig`, an EIP-191
 * signature over [canonicalManifestBytes], recovering to [signer].
 *
 * The signature covers exactly the parsed tree the rest of the app acts
 * on, re-serialized, so no difference between two JSON parsers can put
 * unsigned content in front of it.
 */
internal fun verifyAdblockManifest(
    payload: ByteArray,
    signer: String,
    appliedVersion: Long,
    allowRepublish: Boolean = false,
): AdblockManifestVerdict {
    fun reject(reason: String, version: Long? = null) = AdblockManifestVerdict.Rejected(reason, version)

    if (payload.size > MAX_ADBLOCK_MANIFEST_BYTES) return reject("too_large")
    val root = runCatching { CanonicalJson.parse(payload) }.getOrNull() as? Map<*, *>
        ?: return reject("not_an_object")
    if (root["schema"] != AdblockFeed.SCHEMA) return reject("schema_mismatch")
    val version = root["version"] as? Long
    if (version == null || version <= 0) return reject("bad_version")
    val generatedAt = root["generated_at"] as? String ?: return reject("bad_generated_at")

    val desktop = (root["platforms"] as? Map<*, *>)?.get("desktop") as? Map<*, *>
    val rawLists = desktop?.get("lists") as? List<*> ?: return reject("bad_desktop_section")
    val lists = rawLists.map { parseListEntry(it) ?: return reject("bad_list_entry") }
    if (lists.map { it.listId }.toSet().size != lists.size) return reject("duplicate_list_id")
    if (lists.map { it.category }.toSet().size != lists.size) return reject("duplicate_category")

    val floor = if (allowRepublish) appliedVersion - 1 else appliedVersion
    if (version <= floor) return reject("not_newer", version)

    val sig = root["sig"] as? String
    if (sig.isNullOrEmpty()) return reject("missing_sig")
    val canonical = runCatching { canonicalManifestBytes(root) }.getOrNull() ?: return reject("not_canonical")
    val recovered = Secp256k1.recoverPersonalSign(canonical, sig) ?: return reject("bad_sig")
    if (!recovered.equals(signer, ignoreCase = true)) return reject("wrong_signer")

    return AdblockManifestVerdict.Ok(AdblockManifest(version, generatedAt, lists))
}

private fun parseListEntry(value: Any?): AdblockManifestList? {
    val e = value as? Map<*, *> ?: return null
    val category = e["category"] as? String ?: return null
    val listId = e["list_id"] as? String ?: return null
    val ref = e["ref"] as? String ?: return null
    val sha256 = e["sha256"] as? String ?: return null
    val bytes = e["bytes"] as? Long ?: return null
    val ruleCount = e["rule_count"] as? Long ?: return null
    val title = e["title"]
    for (key in listOf("title", "source_url", "license")) {
        val v = e[key]
        if (v != null && v !is String) return null
    }
    if (!CATEGORY_RE.matches(category) || !LIST_ID_RE.matches(listId)) return null
    if (!SWARM_REF_RE.matches(ref) || !SHA256_RE.matches(sha256)) return null
    if (bytes <= 0 || bytes > MAX_ADBLOCK_LIST_BYTES || ruleCount < 0) return null
    return AdblockManifestList(category, listId, title as String?, ref, sha256, bytes, ruleCount)
}

/**
 * The bytes the manifest's `sig` is made over: the manifest without
 * `sig`, keys sorted at every level, compact — byte for byte what the
 * publisher's `canonicalManifestForSigning` gives (`JSON.stringify` of
 * the deep-sorted object).
 */
internal fun canonicalManifestBytes(manifest: Map<*, *>): ByteArray =
    CanonicalJson.stringify(manifest.filterKeys { it != "sig" }).toByteArray(Charsets.UTF_8)

/**
 * A strict JSON reader and a writer that reproduces JavaScript's
 * `JSON.stringify` over deep-sorted keys, for [canonicalManifestBytes].
 *
 * The reader refuses anything the signature could be ambiguous about:
 * invalid UTF-8, duplicate keys, a `__proto__` key (which the
 * publisher's `sortDeep` would silently drop), and numbers other than
 * integers within ±2^53 (the writer can't promise JavaScript's
 * formatting of the rest). Objects come back as [Map], arrays as
 * [List], numbers as [Long].
 */
internal object CanonicalJson {
    private const val MAX_DEPTH = 64
    private val MAX_SAFE = BigDecimal(9007199254740991L)

    fun parse(bytes: ByteArray): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("not UTF-8", e)
        }
        val r = Reader(text.removePrefix("\uFEFF"))
        val value = r.value(0)
        r.skipWs()
        require(r.i == r.s.length) { "trailing data" }
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Long -> out.append(value)
            is Int -> out.append(value)
            is String -> quote(out, value)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                val keys = value.keys.map { it as String }
                keys.sortedWith(JS_PROPERTY_ORDER).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    quote(out, k)
                    out.append(':')
                    write(out, value[k])
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("unsupported value ${value.javaClass}")
        }
    }

    /**
     * The order a JavaScript object built by inserting keys in sorted
     * order enumerates them in: integer-like keys ("array indices") first,
     * numerically, then the rest in insertion — here UTF-16 — order.
     */
    private val JS_PROPERTY_ORDER = Comparator<String> { a, b ->
        val ia = arrayIndex(a)
        val ib = arrayIndex(b)
        when {
            ia != null && ib != null -> ia.compareTo(ib)
            ia != null -> -1
            ib != null -> 1
            else -> a.compareTo(b)
        }
    }

    private fun arrayIndex(key: String): Long? {
        if (key.isEmpty() || key.length > 10 || !key.all { it in '0'..'9' }) return null
        if (key.length > 1 && key[0] == '0') return null
        val n = key.toLong()
        return if (n < 4294967295L) n else null
    }

    /** `JSON.stringify`'s string form (ES2019 well-formed: lone surrogates escaped). */
    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    out.append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private class Reader(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "too deep" }
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                require(i < s.length && s[i] == '"') { "expected key" }
                val key = string()
                require(key != "__proto__") { "__proto__ key" }
                require(key !in out) { "duplicate key" }
                skipWs()
                require(i < s.length && s[i] == ':') { "expected :" }
                i++
                out[key] = value(depth + 1)
                skipWs()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value(depth + 1)
                skipWs()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad \\u escape" }
                                var code = 0
                                repeat(4) {
                                    val d = when (val h = s[i++]) {
                                        in '0'..'9' -> h - '0'
                                        in 'a'..'f' -> h - 'a' + 10
                                        in 'A'..'F' -> h - 'A' + 10
                                        else -> throw IllegalArgumentException("bad \\u escape")
                                    }
                                    code = code * 16 + d
                                }
                                out.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Long {
            val start = i
            if (i < s.length && s[i] == '-') i++
            require(i < s.length && s[i] in '0'..'9') { "bad number" }
            if (s[i] == '0') i++ else while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                i++
                require(i < s.length && s[i] in '0'..'9') { "bad number" }
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                require(i < s.length && s[i] in '0'..'9') { "bad number" }
                while (i < s.length && s[i] in '0'..'9') i++
            }
            require(i - start <= 32) { "number too long" }
            val n = BigDecimal(s.substring(start, i))
            // JavaScript prints an integral number as an integer ("1.0",
            // "1e2" and "-0" come back as 1, 100 and 0), exact within ±2^53.
            require(n.abs() <= MAX_SAFE) { "number out of range" }
            val integral = n.stripTrailingZeros()
            require(integral.signum() == 0 || integral.scale() <= 0) { "non-integer number" }
            return integral.toLong()
        }
    }
}
