package baby.freedom.mobile.ens

/**
 * Parse user-typed input into an ENS lookup, mirroring
 * `src/renderer/lib/page-urls.js:parseEnsInput` from the Freedom desktop
 * browser. Accepts:
 *
 *   - `vitalik.eth` (the canonical form)
 *   - `ens://vitalik.eth` (compatibility alias, normalized away on display)
 *   - `ens://VITALIK.eth/docs?q=1`
 *   - `foo.box/path`
 *
 * Returns `null` for anything that doesn't end in `.eth` or `.box`.
 *
 * [parseConstrained] handles the scheme-constrained forms
 * (`bzz://name.eth`, `ipfs://name.eth`, `ipns://name.eth`): the name is
 * still resolved through ENS, but the scheme is a *constraint* — the
 * caller must reject the resolution if the contenthash protocol doesn't
 * match. (ENS has a single `contenthash`, so the scheme can never select
 * between alternatives, only assert.)
 */
object EnsInput {
    private val nameAndSuffixRegex = Regex("^([^/?#]+)([/?#].*)?$")
    private val contentSchemes = listOf("bzz", "ipfs", "ipns")

    data class Parsed(val name: String, val suffix: String)

    /** An ENS name typed under a content scheme, e.g. `bzz://name.eth/p`. */
    data class Constrained(val name: String, val suffix: String, val protocol: String)

    fun parse(raw: String?): Parsed? {
        var value = (raw ?: "").trim()
        if (value.isEmpty()) return null

        if (value.length >= 6 && value.substring(0, 6).equals("ens://", ignoreCase = true)) {
            value = value.substring(6)
        }

        val match = nameAndSuffixRegex.matchEntire(value) ?: return null
        val name = match.groupValues[1]
        val suffix = match.groupValues[2]

        val lower = name.lowercase()
        if (!lower.endsWith(".eth") && !lower.endsWith(".box")) return null

        return Parsed(name = lower, suffix = suffix)
    }

    /** `foo.eth` or `ens://foo.eth` → true. Fast pre-check before hitting network. */
    fun looksLikeEns(raw: String?): Boolean = parse(raw) != null

    /**
     * `bzz://name.eth[/p]` / `ipfs://name.eth[/p]` / `ipns://name.eth[/p]`
     * → the ENS name plus the protocol the scheme demands. Raw content
     * ids under those schemes (`bzz://<hex>`, `ipfs://<cid>`, DNSLink
     * hosts like `ipns://ipfs.tech`) return `null` because they don't
     * end in `.eth`/`.box` — they stay on the direct gateway path.
     */
    fun parseConstrained(raw: String?): Constrained? {
        val value = (raw ?: "").trim()
        val scheme = contentSchemes.firstOrNull {
            value.startsWith("$it://", ignoreCase = true)
        } ?: return null
        val parsed = parse(value.substring(scheme.length + 3)) ?: return null
        return Constrained(name = parsed.name, suffix = parsed.suffix, protocol = scheme)
    }
}
