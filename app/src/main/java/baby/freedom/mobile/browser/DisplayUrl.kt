package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsNormalize

/**
 * Maps an "actual" URL (the one the WebView physically loaded) to the
 * friendly string for the address bar.
 *
 * Precedence mirrors `deriveDisplayAddress` + `applyEnsNamePreservation`
 * from `freedom-browser/src/renderer/lib/navigation-utils.js` and
 * `url-utils.js`:
 *
 *   1. Active per-tab [BrowserState.Override] → substitute the display
 *      prefix for the gateway base. This is what keeps in-manifest
 *      clicks on `name.eth/path` (or `bzz://name.eth/path` for a
 *      scheme-constrained load).
 *   2. `bzz://<hash>[…]` / `ipfs://<cid>[…]` / `ipns://<name>[…]` (after
 *      [Gateways.toDisplay] maps the loaded `http://127.0.0.1:<port>/…`
 *      back to its user-facing scheme); rewrite the hash / CID to the
 *      `<name>` if [KnownEnsNames] knows it so ENS-sourced navigations
 *      keep their name in the address bar. (`ens://` is an input-only
 *      compat alias — it is never produced for display.)
 *   3. Otherwise pass-through.
 *
 * Transport-aware (#97, desktop's *Transport-Aware Address Bar*): a name
 * is shown under the transport its contenthash resolved to —
 * `ipfs://vitalik.eth`, `bzz://swarm.eth/docs` — never as a bare name
 * once it has resolved. See [withTransport].
 *
 * The home page ([HOME_URL] = `about:blank`) never reaches this function
 * — [BrowserWebView]'s client early-returns from `onPageStarted` /
 * `onPageFinished` before calling through to `displayFor`, so the
 * address bar is kept blank without special-casing here.
 */
object DisplayUrl {
    private val bzzRegex = Regex("^bzz://([a-fA-F0-9]+)(.*)$")
    private val ipfsRegex = Regex("^ipfs://([A-Za-z0-9]+)(.*)$")
    private val ipnsRegex = Regex("^ipns://([A-Za-z0-9.-]+)(.*)$")

    /**
     * [protocolFor] is the transport a name's page is shown under —
     * by default the session's current answer ([KnownEnsNames]); for
     * the document a tab has on screen, the answer that document was
     * actually served from ([EnsDocumentPins.answerFor], R3-F1).
     */
    fun forActualUrl(
        actualUrl: String,
        override: BrowserState.Override?,
        protocolFor: (name: String) -> String? = KnownEnsNames::protocolFor,
    ): String {
        // Only a URL *on* the override's origin: the next character after
        // the base must end it. A bare prefix match would dress a DNS
        // host that merely begins with the virtual one
        // (`https://mysite-eth.ens.freedom.baby.evil.com/`) as
        // `bzz://mysite.eth.evil.com/`, dweb badge included — the same
        // boundary [BrowserState.isUnderOverride] draws.
        if (override != null && override.covers(actualUrl)) {
            return withTransport(override.prefix, protocolFor) +
                actualUrl.substring(override.baseUrl.length)
        }

        val display = Gateways.toDisplay(actualUrl)
        return withTransport(applyNamePreservation(display), protocolFor)
    }

    /**
     * A bare-name [display] (`vitalik.eth/about`) under the transport the
     * name resolved to this session — `ipfs://vitalik.eth/about` — so
     * the bar says which network is serving the page, the way desktop's
     * address bar does. Anything else, and a name not resolved yet
     * (nothing to say about it), comes back unchanged.
     *
     * The scheme is read at display time from [KnownEnsNames], which a
     * document's re-check keeps current (#99): a generic `name.eth` whose
     * contenthash moves from Swarm to IPFS is shown as `ipfs://…` on its
     * next load, not under the transport it had when first typed.
     */
    fun withTransport(
        display: String,
        protocolFor: (name: String) -> String? = KnownEnsNames::protocolFor,
    ): String = shownName(withTransportAsGiven(display, protocolFor))

    private fun withTransportAsGiven(
        display: String,
        protocolFor: (name: String) -> String?,
    ): String {
        if (display.contains("://")) return display
        val name = EnsInput.parse(display)?.name ?: return display
        val protocol = protocolFor(name) ?: return display
        return "$protocol://$display"
    }

    /** The schemes a display URL names a name under (`bzz://swarm.eth`). */
    private val NAME_SCHEMES = setOf("bzz", "ipfs", "ipns", "ens")

    /**
     * [display] with the name it shows in its safe display form
     * ([EnsNormalize.tezosDisplay], #465): `pаypal.tez/x` →
     * `p%D0%B0ypal.tez/x`, so a lookalike `.tez` name reads as one
     * in the capsule, the edit field and everything else built from the
     * display URL. Parsing the result ([EnsInput.parse]) gives back the
     * same name. Anything else comes back unchanged.
     */
    fun shownName(display: String): String = respellName(display, EnsNormalize::tezosDisplay)

    /**
     * [display] spelled the way [shownName] spells it once the ENSIP-15
     * tables are decoded, whatever it was spelled with before (#490
     * R1-M2). [shownName] fails closed while the tables are still
     * decoding at startup, so an honest `café.tez` reads
     * `caf%C3%A9.tez` for that moment; anything saved from then
     * (a bookmark, a history row, a download) would keep that spelling
     * for good. This maps a `.tez` name's `%XX` escapes back
     * ([EnsNormalize.tezosForm]) and shows it again with the tables
     * warm, so what is saved doesn't depend on when it was saved.
     *
     * Decodes the tables if they aren't yet ([EnsNormalize.warm]): call
     * it off the main thread, where the address is written to disk.
     */
    fun settledName(display: String): String = respellName(display) { name ->
        if (!name.lowercase().endsWith(".tez")) return@respellName name
        if (name.all { it.code < 0x80 } && '%' !in name) {
            return@respellName name
        }
        val unicode = EnsNormalize.tezosForm(name) ?: return@respellName name
        if (!EnsNormalize.isWarm) EnsNormalize.warm()
        EnsNormalize.tezosDisplay(unicode)
    }

    /** [display] with the name it names (bare, or after a [NAME_SCHEMES] scheme) passed through [respell]. */
    private inline fun respellName(display: String, respell: (String) -> String): String {
        val sep = display.indexOf("://")
        if (sep >= 0 && display.substring(0, sep).lowercase() !in NAME_SCHEMES) return display
        val start = if (sep < 0) 0 else sep + 3
        val end = display.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) display.length else it }
        val name = display.substring(start, end)
        val shown = respell(name)
        return if (shown == name) display else display.substring(0, start) + shown + display.substring(end)
    }

    private fun applyNamePreservation(display: String): String {
        bzzRegex.matchEntire(display)?.let { m ->
            val hash = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(hash.lowercase())
            if (name != null) return "bzz://$name$tail"
            return display
        }
        ipfsRegex.matchEntire(display)?.let { m ->
            val cid = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(cid)
            if (name != null) return "ipfs://$name$tail"
            return display
        }
        ipnsRegex.matchEntire(display)?.let { m ->
            val id = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(id)
            if (name != null) return "ipns://$name$tail"
            return display
        }
        return display
    }
}
