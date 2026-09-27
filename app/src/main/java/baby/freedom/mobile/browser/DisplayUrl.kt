package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsInput

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

    fun forActualUrl(
        actualUrl: String,
        override: BrowserState.Override?,
    ): String {
        if (override != null && actualUrl.startsWith(override.baseUrl)) {
            return override.shown + actualUrl.substring(override.baseUrl.length)
        }

        val display = Gateways.toDisplay(actualUrl)
        return withTransport(applyNamePreservation(display))
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
    fun withTransport(display: String): String {
        if (display.contains("://")) return display
        val name = EnsInput.parse(display)?.name ?: return display
        val protocol = KnownEnsNames.protocolFor(name) ?: return display
        return "$protocol://$display"
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
