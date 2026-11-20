package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.NodeIdentity
import java.math.BigInteger
import org.json.JSONObject

/**
 * `swarm_openAct` (#540, desktop #648): content a publisher shared with
 * this device's Swarm node through ACT, Swarm's access control. The node
 * does the work — given the publisher's public key and the share's
 * history it finds its own grantee row, unwraps the access key with its
 * own key and serves the content (ant's `act_maybe_resolve`) — so no key
 * of the node's is ever read here. Desktop's `act-publisher.js`,
 * `act-request.js` and the request half of `act-service.js`.
 *
 * Pages still can't send `Swarm-Act-*` headers to the node themselves
 * ([BrowserWebView] strips them from `bzz://` loads): this method, after
 * the user's per-publisher consent, is the only way a page reaches ACT
 * content.
 */
object SwarmAct {
    /** A publisher's key as the sheet, the rule and the node all name it. */
    data class Publisher(
        /** SEC1 compressed, 66 lower-case hex, no `0x`. */
        val publicKey: String,
        /** The key's Ethereum address, EIP-55. */
        val address: String,
    )

    /** A checked request, in canonical form: lower-case hex, the compressed key, never the page's spelling. */
    data class Share(
        /** 64 or 128 lower-case hex: the ACT-encrypted reference. */
        val reference: String,
        val publisher: Publisher,
        /** 64 lower-case hex. */
        val historyAddress: String,
        /** Unix seconds; null: the node uses "now". */
        val timestamp: Long?,
        /** Null for a blob (`/bytes`), a manifest path otherwise (`/bzz`), `""` for its root. */
        val path: String?,
    )

    /** Why a request is refused: the -32602 `data.reason`, and the option named for `unsupported_option`. */
    data class Refusal(val reason: String, val message: String, val option: String? = null)

    /** Paths are capped like a URL's (desktop's `ACT_MAX_PATH_BYTES`). */
    const val MAX_PATH_BYTES = 1024

    /** The whole node request, headers and body: a lookup walks a history, a key-value store and then the content. */
    const val READ_TIMEOUT_MS = 120_000L

    /** ant's and bee's body for `accesscontrol.ErrNotFound`: no grantee row for this node, or no history entry then. */
    private const val NOT_FOUND_MESSAGE = "act or history entry not found"

    private val PUBLISHER = Regex("^(?:0x)?((?:0[23][0-9a-fA-F]{64})|(?:04[0-9a-fA-F]{128}))$")
    private val REFERENCE = Regex("^(?:[0-9a-fA-F]{64}|[0-9a-fA-F]{128})$")
    private val HISTORY = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val TWO = BigInteger.valueOf(2)
    private val SEVEN = BigInteger.valueOf(7)
    private val SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2)

    /**
     * The one normaliser for a publisher key (desktop's
     * `normalizeActPublisher`): compressed (`02`/`03`) or uncompressed
     * (`04`) secp256k1, any hex case, with or without `0x`; null for
     * anything else, a point off the curve included. Hybrid keys
     * (`06`/`07`) are refused: no client makes them, and they'd be a
     * second spelling of one rule.
     */
    fun publisher(value: Any?): Publisher? {
        if (value !is String) return null
        val hex = PUBLISHER.matchEntire(value)?.groupValues?.get(1)?.lowercase() ?: return null
        val x = BigInteger(hex.substring(2, 66), 16)
        if (x >= P) return null
        val rhs = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = if (hex.startsWith("04")) {
            BigInteger(hex.substring(66), 16).takeIf { it < P && it.modPow(TWO, P) == rhs } ?: return null
        } else {
            val root = rhs.modPow(SQRT_EXP, P)
            if (root.modPow(TWO, P) != rhs) return null
            if (root.testBit(0) == (hex[1] == '3')) root else P.subtract(root)
        }
        val compressed = (if (y.testBit(0)) "03" else "02") + hex.substring(2, 66)
        val hash = Keccak256.digest(to32(x) + to32(y))
        return Publisher(compressed, NodeIdentity.checksum(hash.copyOfRange(12, 32)))
    }

    /**
     * Desktop's `validateActRequest`, check for check and in the same
     * order: the sheet is only ever shown for a request that would run,
     * and shows its canonical values. Returns the share or the refusal.
     */
    fun validate(params: JSONObject): Pair<Share?, Refusal?> {
        val options = params.opt("options")
        if (options != null && options != JSONObject.NULL) {
            if (options !is JSONObject) return refuse("invalid_params", "options must be an object")
            options.keys().asSequence().firstOrNull()?.let {
                return null to Refusal("unsupported_option", "Unsupported option: $it", it)
            }
        }
        val reference = (params.opt("reference") as? String)?.takeIf { REFERENCE.matches(it) }
            ?: return refuse("invalid_reference", "reference must be a 64- or 128-character hex string")
        val publisher = publisher(params.opt("publisher")) ?: return refuse(
            "invalid_publisher",
            "publisher must be a secp256k1 public key: 66 hex characters starting 02 or 03, or 130 starting 04",
        )
        val history = (params.opt("historyAddress") as? String)?.takeIf { HISTORY.matches(it) }
            ?: return refuse("invalid_history_address", "historyAddress must be a 64-character hex string")
        val rawTimestamp = params.opt("timestamp")
        val timestamp = if (rawTimestamp == null || rawTimestamp == JSONObject.NULL) {
            null
        } else {
            safeInteger(rawTimestamp)?.takeIf { it >= 0 }
                ?: return refuse("invalid_timestamp", "timestamp must be a non-negative integer (unix seconds)")
        }
        val rawPath = params.opt("path")
        val path = if (rawPath == null || rawPath == JSONObject.NULL) null else pathError(rawPath)?.let { return refuse("invalid_path", it) } ?: rawPath as String
        return Share(reference.lowercase(), publisher, history.lowercase(), timestamp, path) to null
    }

    /** Why [path] can't be a share's path, or null if it can (desktop's `validateActPath`). */
    private fun pathError(path: Any): String? {
        if (path !is String) return "path must be a string"
        if (path.isEmpty()) return null
        // A lone surrogate has no UTF-8 form: the request couldn't be built once the user had allowed it.
        if (!wellFormed(path)) return "path must be well-formed Unicode (no lone surrogates)"
        if (path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) return "path exceeds $MAX_PATH_BYTES UTF-8 bytes"
        if (path.startsWith("/") || path.contains('\\')) return "path must be relative and use / as its separator"
        if (path.any { it.code < 32 || it.code == 127 }) return "path must not contain control characters"
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return "path must not contain empty, \".\" or \"..\" segments"
        }
        return null
    }

    /**
     * The node path for [share]: `/bytes/<ref>` for a blob (bee-js
     * `downloadData`), `/bzz/<ref>/<path>` for a file in a manifest
     * (`downloadFile`), each segment percent-encoded as
     * `encodeURIComponent` does, so nothing in it acts as a separator.
     */
    fun nodePath(share: Share): String {
        val path = share.path ?: return "/bytes/${share.reference}"
        val encoded = if (path.isEmpty()) "" else path.split('/').joinToString("/", transform = ::encodeUriComponent)
        return "/bzz/${share.reference}/$encoded"
    }

    /**
     * The headers that open [share]: its publisher and history, its
     * timestamp if it has one. Identity encoding: a compressed body would
     * decode past its own length.
     */
    fun headers(share: Share): Map<String, String> = buildMap {
        put("Accept", "*/*")
        put("Accept-Encoding", "identity")
        put("Swarm-Act-Publisher", share.publisher.publicKey)
        put("Swarm-Act-History-Address", share.historyAddress)
        share.timestamp?.let { put("Swarm-Act-Timestamp", it.toString()) }
    }

    /** Whether a 404's [message] is the node saying it has no grant from the publisher (or no such history entry). */
    fun noGrant(message: String): Boolean = message.trim().equals(NOT_FOUND_MESSAGE, ignoreCase = true)

    /** JavaScript's `encodeURIComponent`: UTF-8, everything but `A-Za-z0-9-_.!~*'()` as upper-case `%XX`. */
    internal fun encodeUriComponent(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
                append(ch)
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
    }

    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    /** A JavaScript number that's a safe integer, as a Long; null for anything else (a string, a fraction, a `$bigint`). */
    private fun safeInteger(value: Any): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
        is Double -> value.takeIf { !it.isNaN() && !it.isInfinite() && it == Math.floor(it) && Math.abs(it) <= MAX_SAFE_INTEGER }?.toLong()
        is Float -> safeInteger(value.toDouble())
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is java.math.BigDecimal -> runCatching { value.longValueExact() }.getOrNull()?.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
        is BigInteger -> value.takeIf { it.bitLength() < 63 }?.toLong()?.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
        else -> null
    }

    private fun refuse(reason: String, message: String): Pair<Share?, Refusal?> = null to Refusal(reason, message)

    private fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
