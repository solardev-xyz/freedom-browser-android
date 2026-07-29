package baby.freedom.mobile.browser

import java.math.BigInteger

/**
 * The content root a dweb page is served from — the unit of origin
 * isolation. Each root maps 1:1 to a synthetic https host (see
 * [VirtualOrigin]) that `shouldInterceptRequest` fully owns, so two
 * roots can never see each other's localStorage / IndexedDB / cookies.
 */
sealed interface ContentRoot {
    /** Swarm reference: lowercase hex, 64 chars (plain) or 128 (encrypted). */
    data class Bzz(val ref: String) : ContentRoot

    /** IPFS CID, normalized to a lowercase CIDv1 multibase string. */
    data class Ipfs(val cid: String) : ContentRoot

    /** IPNS key, normalized to a lowercase base36 libp2p-key CIDv1. */
    data class IpnsKey(val key: String) : ContentRoot

    /** DNSLink name under ipns:// (e.g. `ipfs.tech`). */
    data class IpnsName(val name: String) : ContentRoot

    /** ENS name (e.g. `vitalik.eth`). The origin is derived from the
     *  *name*, not the resolved hash, so a site keeps its storage
     *  across contenthash updates. */
    data class Ens(val name: String) : ContentRoot
}

/**
 * Bidirectional mapping between [ContentRoot]s and the per-root virtual
 * https origins the WebView loads them from.
 *
 * ```
 * address bar shows:   bzz://<hash>/gallery/index.html
 * WebView loads:       https://<label>.bzz.freedom.baby/gallery/index.html
 * interceptor serves:  http://127.0.0.1:1633/bzz/<hash>/gallery/index.html
 * ```
 *
 * No DNS or TLS ever happens for these hosts — the interceptor answers
 * before the network stack runs. The hostnames only need to be *valid*
 * (each dot-separated label ≤ 63 chars, lowercase LDH) so Chromium's URL
 * parser keeps them intact.
 *
 * Label encodings (shared with the redirector service in `infra/` — the
 * cross-implementation vectors live in `infra/redirector/test-vectors.json`):
 *  - Swarm refs: each 64-hex (32-byte) chunk is base36-encoded (≈ 50
 *    chars, fits the 63-char label limit); 128-hex encrypted refs use
 *    two labels, most-significant chunk first. Base36 uses the multibase
 *    convention: leading zero *bytes* encode as leading `'0'` chars.
 *  - IPFS: CIDv0 (`Qm…`, case-sensitive base58) is converted to a
 *    lowercase base36 CIDv1 — hostnames are case-folded, so raw CIDv0
 *    in a hostname would silently corrupt. Already-lowercase CIDv1
 *    strings (base32 `b…`, base36 `k…`, base16 `f…`) are used verbatim.
 *  - IPNS: base58 PeerIDs (`12D3Koo…`, `Qm…`) become base36 libp2p-key
 *    CIDv1; DNSLink names are dot-escaped like ENS names.
 *  - ENS / DNSLink names: the IPFS subdomain-gateway convention —
 *    `-` → `--` first, then `.` → `-` (so `foo-bar.eth` → `foo--bar-eth`).
 */
object VirtualOrigin {
    /**
     * Base domains, one per namespace. Pinned in issue #4 and mirrored
     * by the PSL submission + redirector in `infra/` (issue #6) — this
     * constant is the single source of truth on the app side.
     */
    const val BASE_DOMAIN: String = "freedom.baby"

    const val BZZ_SUFFIX: String = "bzz.$BASE_DOMAIN"
    const val IPFS_SUFFIX: String = "ipfs.$BASE_DOMAIN"
    const val IPNS_SUFFIX: String = "ipns.$BASE_DOMAIN"
    const val ENS_SUFFIX: String = "ens.$BASE_DOMAIN"

    /** All virtual suffixes — what the PSL entry (issue #6) must cover. */
    val SUFFIXES: List<String> = listOf(BZZ_SUFFIX, IPFS_SUFFIX, IPNS_SUFFIX, ENS_SUFFIX)

    private const val BZZ_REF_CHUNK_HEX = 64

    // ---------------------------------------------------------------
    // Root → host
    // ---------------------------------------------------------------

    /** Virtual hostname for [root], or `null` if the root is malformed
     *  (bad hex length, un-normalizable CID). */
    fun hostFor(root: ContentRoot): String? = when (root) {
        is ContentRoot.Bzz -> bzzLabels(root.ref)?.let { "$it.$BZZ_SUFFIX" }
        is ContentRoot.Ipfs -> cidLabel(root.cid)?.let { "$it.$IPFS_SUFFIX" }
        is ContentRoot.IpnsKey -> ipnsKeyLabel(root.key)?.let { "$it.$IPNS_SUFFIX" }
        is ContentRoot.IpnsName -> "${escapeName(root.name.lowercase())}.$IPNS_SUFFIX"
        is ContentRoot.Ens -> "${escapeName(root.name.lowercase())}.$ENS_SUFFIX"
    }

    /** `https://<label(s)>.<ns>.freedom.baby` for [root]. */
    fun originFor(root: ContentRoot): String? = hostFor(root)?.let { "https://$it" }

    // ---------------------------------------------------------------
    // Host → root
    // ---------------------------------------------------------------

    fun isVirtualHost(host: String?): Boolean = parseHost(host) != null

    /** Does [url] point at a virtual origin? */
    fun isVirtualUrl(url: String?): Boolean {
        if (url == null || !url.startsWith("https://")) return false
        return isVirtualHost(hostOf(url))
    }

    /**
     * Decode a virtual hostname back to its [ContentRoot]. Returns
     * `null` for hosts outside the virtual suffixes or with labels that
     * don't decode.
     */
    fun parseHost(host: String?): ContentRoot? {
        val h = host?.lowercase() ?: return null
        val (labels, ns) = splitHost(h) ?: return null
        return when (ns) {
            "bzz" -> decodeBzzLabels(labels)
            "ipfs" -> if (labels.size == 1) decodeIpfsLabel(labels[0]) else null
            "ipns" -> if (labels.size == 1) decodeIpnsLabel(labels[0]) else null
            "ens" -> if (labels.size == 1) ContentRoot.Ens(unescapeName(labels[0])) else null
            else -> null
        }
    }

    // ---------------------------------------------------------------
    // URL-level helpers
    // ---------------------------------------------------------------

    /**
     * `bzz://…` / `ipfs://…` / `ipns://…` / `ens://…` → the virtual
     * https URL the WebView should load, or `null` for other schemes /
     * malformed ids.
     */
    fun toVirtualUrl(contentUrl: String): String? {
        val (root, tail) = parseContentUrl(contentUrl) ?: return null
        val origin = originFor(root) ?: return null
        return origin + if (tail.isEmpty() || tail.startsWith("/")) tail.ifEmpty { "/" } else "/$tail"
    }

    /**
     * Inverse of [toVirtualUrl]: virtual https URL → the user-facing
     * display form. `bzz://<ref>…`, `ipfs://<cid>…`, `ipns://<id>…` for
     * content ids; the bare `name.eth…` form for ENS (matching the
     * address-bar convention — `ens://` is an input-only alias).
     * Returns `null` when [url] isn't a virtual-origin URL.
     */
    fun displayUrlFor(url: String): String? {
        val root = parseHostOfUrl(url) ?: return null
        val tail = pathAndQueryOf(url).let { if (it == "/") "" else it }
        return when (root) {
            is ContentRoot.Bzz -> "bzz://${root.ref}$tail"
            is ContentRoot.Ipfs -> "ipfs://${root.cid}$tail"
            is ContentRoot.IpnsKey -> "ipns://${root.key}$tail"
            is ContentRoot.IpnsName -> "ipns://${root.name}$tail"
            is ContentRoot.Ens -> "${root.name}$tail"
        }
    }

    /** [parseHost] applied to a URL's hostname. */
    fun parseHostOfUrl(url: String?): ContentRoot? {
        if (url == null || !url.startsWith("https://")) return null
        return parseHost(hostOf(url))
    }

    /**
     * Split a `bzz://` / `ipfs://` / `ipns://` / `ens://` URL into its
     * [ContentRoot] and the path+query tail (`""` or starting with `/`).
     */
    fun parseContentUrl(url: String): Pair<ContentRoot, String>? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val slash = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val id = if (slash >= 0) rest.substring(0, slash) else rest
        val tail = if (slash >= 0) rest.substring(slash) else ""
        if (id.isEmpty()) return null
        val root = when (scheme) {
            "bzz" -> normalizeBzzRef(id)?.let { ContentRoot.Bzz(it) }
            "ipfs" -> normalizeCid(id)?.let { ContentRoot.Ipfs(it) }
            "ipns" -> ipnsRootFor(id)
            "ens" -> ContentRoot.Ens(id.lowercase())
            else -> null
        } ?: return null
        // A query/fragment directly after the id still needs the root
        // slash so relative URLs on the page resolve under the root.
        val normalizedTail = when {
            tail.isEmpty() -> ""
            tail.startsWith("/") -> tail
            else -> "/$tail"
        }
        return root to normalizedTail
    }

    /** Path + query of [url] (never empty — `/` for bare origins). Fragments are dropped. */
    fun pathAndQueryOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val slash = url.indexOf('/', start)
        val raw = if (slash >= 0) url.substring(slash) else "/"
        return raw.substringBefore('#').ifEmpty { "/" }
    }

    private fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        val host = if (end >= 0) rest.substring(0, end) else rest
        return host.ifEmpty { null }
    }

    private fun splitHost(host: String): Pair<List<String>, String>? {
        for (ns in listOf("bzz", "ipfs", "ipns", "ens")) {
            val suffix = ".$ns.$BASE_DOMAIN"
            if (host.endsWith(suffix)) {
                val labels = host.removeSuffix(suffix).split('.')
                if (labels.isEmpty() || labels.any { it.isEmpty() }) return null
                return labels to ns
            }
        }
        return null
    }

    // ---------------------------------------------------------------
    // Swarm refs
    // ---------------------------------------------------------------

    private val hexRegex = Regex("^[0-9a-f]+$")

    /** Lowercase and validate a Swarm ref: 64 or 128 hex chars. */
    fun normalizeBzzRef(ref: String): String? {
        val lower = ref.lowercase()
        if (lower.length != BZZ_REF_CHUNK_HEX && lower.length != 2 * BZZ_REF_CHUNK_HEX) return null
        if (!hexRegex.matches(lower)) return null
        return lower
    }

    private fun bzzLabels(ref: String): String? {
        val normalized = normalizeBzzRef(ref) ?: return null
        return normalized
            .chunked(BZZ_REF_CHUNK_HEX)
            .joinToString(".") { base36EncodeHexChunk(it) }
    }

    private fun decodeBzzLabels(labels: List<String>): ContentRoot.Bzz? {
        if (labels.size !in 1..2) return null
        val hex = StringBuilder()
        for (label in labels) {
            hex.append(base36DecodeToHexChunk(label) ?: return null)
        }
        return ContentRoot.Bzz(hex.toString())
    }

    // ---------------------------------------------------------------
    // IPFS CIDs
    // ---------------------------------------------------------------

    private val lowerMultibaseRegex = Regex("^[a-z0-9]+$")

    /**
     * Normalize a CID for use in a hostname: CIDv0 (`Qm…`) and base58
     * CIDv1 (`z…`) are re-encoded as base36 CIDv1; already-lowercase
     * CIDv1 strings pass through. Case-sensitive strings that we can't
     * re-encode return `null` rather than silently corrupting.
     */
    fun normalizeCid(cid: String): String? {
        if (cid.length == 46 && cid.startsWith("Qm")) {
            val multihash = base58Decode(cid) ?: return null
            // CIDv0 *is* a bare sha2-256 multihash (0x12 0x20 …).
            if (multihash.size != 34 || multihash[0] != 0x12.toByte()) return null
            return multibase36(byteArrayOf(0x01, 0x70) + multihash)
        }
        if (cid.startsWith("z")) {
            val bytes = base58Decode(cid.substring(1)) ?: return null
            if (bytes.isEmpty() || bytes[0] != 0x01.toByte()) return null
            return multibase36(bytes)
        }
        if (lowerMultibaseRegex.matches(cid) && cid.length <= 63 && !cid.startsWith("qm")) {
            return cid
        }
        return null
    }

    private fun cidLabel(cid: String): String? = normalizeCid(cid)

    private fun decodeIpfsLabel(label: String): ContentRoot.Ipfs? {
        if (!lowerMultibaseRegex.matches(label)) return null
        return ContentRoot.Ipfs(label)
    }

    // ---------------------------------------------------------------
    // IPNS
    // ---------------------------------------------------------------

    private fun ipnsRootFor(id: String): ContentRoot? {
        val keyLabel = ipnsKeyLabel(id)
        if (keyLabel != null && id != id.lowercase()) {
            // Case-sensitive base58 PeerID — canonicalize to the base36
            // form so the id survives hostname case-folding.
            return ContentRoot.IpnsKey(keyLabel)
        }
        if (lowerMultibaseRegex.matches(id) && (id.startsWith("k") || id.startsWith("b")) &&
            id.length >= 30
        ) {
            return ContentRoot.IpnsKey(id)
        }
        return ContentRoot.IpnsName(id.lowercase())
    }

    private fun ipnsKeyLabel(key: String): String? {
        // Already a lowercase multibase CIDv1 (base36 `k…` / base32 `b…`).
        if (lowerMultibaseRegex.matches(key) && (key.startsWith("k") || key.startsWith("b")) &&
            key.length >= 30
        ) {
            return key
        }
        // Base58 PeerID (`12D3Koo…` ed25519, `Qm…` RSA): bare multihash →
        // wrap in a libp2p-key (0x72) CIDv1, base36.
        if (key.startsWith("12D3Koo") || (key.length == 46 && key.startsWith("Qm"))) {
            val multihash = base58Decode(key) ?: return null
            return multibase36(byteArrayOf(0x01, 0x72) + multihash)
        }
        return null
    }

    private fun decodeIpnsLabel(label: String): ContentRoot? {
        if (label.contains('-')) return ContentRoot.IpnsName(unescapeName(label))
        if ((label.startsWith("k") || label.startsWith("b")) && label.length >= 30) {
            return ContentRoot.IpnsKey(label)
        }
        return ContentRoot.IpnsName(label)
    }

    // ---------------------------------------------------------------
    // Name escaping (ENS / DNSLink) — the IPFS subdomain-gateway
    // convention ("inline DNSLink"): `-` → `--`, then `.` → `-`.
    // ---------------------------------------------------------------

    fun escapeName(name: String): String =
        name.replace("-", "--").replace(".", "-")

    fun unescapeName(label: String): String {
        val out = StringBuilder(label.length)
        var i = 0
        while (i < label.length) {
            val c = label[i]
            if (c == '-') {
                if (i + 1 < label.length && label[i + 1] == '-') {
                    out.append('-'); i += 2
                } else {
                    out.append('.'); i += 1
                }
            } else {
                out.append(c); i += 1
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------
    // Base encodings
    // ---------------------------------------------------------------

    private const val B36 = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** 64-hex chunk → base36 label (multibase leading-zero convention). */
    internal fun base36EncodeHexChunk(hexChunk: String): String {
        var leadingZeroBytes = 0
        var i = 0
        while (i + 1 < hexChunk.length && hexChunk[i] == '0' && hexChunk[i + 1] == '0') {
            leadingZeroBytes++; i += 2
        }
        val n = BigInteger(hexChunk, 16)
        val body = if (n == BigInteger.ZERO) "" else n.toString(36)
        return "0".repeat(leadingZeroBytes) + body
    }

    /** base36 label → 64-hex chunk, or `null` if it doesn't decode. */
    internal fun base36DecodeToHexChunk(label: String): String? {
        if (label.isEmpty() || !lowerMultibaseRegex.matches(label)) return null
        val zeros = label.takeWhile { it == '0' }.length
        val body = label.substring(zeros)
        val n = if (body.isEmpty()) BigInteger.ZERO else runCatching { BigInteger(body, 36) }.getOrNull()
            ?: return null
        val hex = if (n == BigInteger.ZERO) "" else n.toString(16)
        val total = zeros * 2 + hex.length
        if (total > BZZ_REF_CHUNK_HEX) return null
        return "0".repeat(BZZ_REF_CHUNK_HEX - hex.length) + hex
    }

    /** Multibase base36 (`k` prefix) of [bytes]. */
    private fun multibase36(bytes: ByteArray): String {
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        val n = BigInteger(1, bytes)
        val body = if (n == BigInteger.ZERO) "" else n.toString(36)
        return "k" + "0".repeat(zeros) + body
    }

    /** Bitcoin-alphabet base58 decode (used for CIDv0 / PeerIDs). */
    internal fun base58Decode(s: String): ByteArray? {
        if (s.isEmpty()) return null
        var n = BigInteger.ZERO
        val fiftyEight = BigInteger.valueOf(58)
        for (c in s) {
            val idx = B58.indexOf(c)
            if (idx < 0) return null
            n = n.multiply(fiftyEight).add(BigInteger.valueOf(idx.toLong()))
        }
        val zeros = s.takeWhile { it == '1' }.length
        var body = n.toByteArray()
        // BigInteger.toByteArray() adds a sign byte for values with the
        // high bit set — strip it.
        if (body.size > 1 && body[0] == 0.toByte()) body = body.copyOfRange(1, body.size)
        if (n == BigInteger.ZERO) body = ByteArray(0)
        return ByteArray(zeros) + body
    }
}
