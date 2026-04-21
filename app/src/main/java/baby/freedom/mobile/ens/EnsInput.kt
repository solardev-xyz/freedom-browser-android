package baby.freedom.mobile.ens

/**
 * Parse user-typed input into an ENS lookup, mirroring
 * `src/renderer/lib/page-urls.js:parseEnsInput` from the Freedom desktop
 * browser. Accepts:
 *
 *   - `ens://vitalik.eth`
 *   - `ens://VITALIK.eth/docs?q=1`
 *   - `vitalik.eth`
 *   - `foo.box/path`
 *
 * Returns `null` for anything that doesn't end in `.eth` or `.box`.
 */
object EnsInput {
    private val nameAndSuffixRegex = Regex("^([^/?#]+)([/?#].*)?$")

    data class Parsed(val name: String, val suffix: String)

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
}
