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
 *   - `alice.wei` (WNS) and `name.gwei` (GNS) — see [NameSystem]
 *   - `alice.tez` (Tezos Domains)
 *   - non-ASCII / emoji names (`🦊.eth`), returned ENSIP-15 normalized
 *     (Ethereum systems only: a `.tez` name gets Tezos Domains' own
 *     UTS-46 form instead, see [EnsNormalize.tezosForm])
 *
 * Returns `null` for anything that doesn't end in one of
 * [NameSystem.navigableSuffixes] (`.eth`, `.box`, `.wei`, `.gwei`, `.tez`).
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

        // The ENSIP-15 form ([EnsNormalize]) where there is one, so the
        // address bar, the `ens://` origin and the resolver's cache all
        // key on the one spelling every client hashes (`Ⓥitalik.eth` and
        // `vitalik.eth` are the same name). A name ENSIP-15 rejects is
        // still a name — lowercased as typed, it goes on to the resolver
        // and comes back as an `INVALID_NAME` error page rather than
        // falling through to web search.
        //
        // Pure-ASCII input skips the library: ENSIP-15 maps an ASCII
        // name it accepts to its lowercase, and a rejected one falls back
        // to the lowercase anyway — so the answer is the same, and the
        // address bar's per-composition checks (every `https://…` URL
        // goes through here via [looksLikeEns]) never trigger the
        // library's spec decode on the main thread. A name with a `\` in
        // it takes the same path: ENSIP-15 disallows that character, so
        // the library could only refuse it, and the address capsule asks
        // about such names in composition (`AddressLabel`).
        val canonical = if (name.all { it.code < 0x80 } || '\\' in name) {
            // A `.tez` name's `%XX` escapes are its Unicode name
            // ([EnsNormalize.tezosForm]) — how a lookalike one is shown.
            EnsNormalize.tezosForm(name) ?: name.lowercase()
        } else {
            EnsNormalize.normalizeOrNull(name) ?: name.lowercase()
        }
        if (NameSystem.navigableSuffixes.none { canonical.endsWith(it) }) return null

        return Parsed(name = canonical, suffix = suffix)
    }

    /** `foo.eth` or `ens://foo.eth` → true. Fast pre-check before hitting network. */
    fun looksLikeEns(raw: String?): Boolean = parse(raw) != null

    /**
     * `bzz://name.eth[/p]` / `ipfs://name.eth[/p]` / `ipns://name.eth[/p]`
     * → the ENS name plus the protocol the scheme demands. Raw content
     * ids under those schemes (`bzz://<hex>`, `ipfs://<cid>`, DNSLink
     * hosts like `ipns://ipfs.tech`) return `null` because they don't
     * end in a name suffix — they stay on the direct gateway path.
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
