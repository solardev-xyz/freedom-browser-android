package baby.freedom.mobile.browser

/**
 * Maps an "actual" URL (the one the WebView physically loaded) to the
 * friendly string for the address bar.
 *
 * Precedence mirrors `deriveDisplayAddress` + `applyEnsNamePreservation`
 * from `freedom-browser/src/renderer/lib/navigation-utils.js` and
 * `url-utils.js`:
 *
 *   1. Active per-tab [BrowserState.Override] → substitute the ENS prefix
 *      for the gateway base. This is what keeps in-manifest clicks on
 *      `ens://name/path`.
 *   2. `bzz://<hash>[…]` (after [SwarmResolver.toDisplay]); rewrite the
 *      hash to `ens://<name>` if [KnownEnsNames] knows it.
 *   3. (Port-plan §2) `ipfs://<cid>[…]` / `ipns://<name>[…]` — same shape;
 *      currently only exercised if an external caller hands us one of
 *      those URIs directly.
 *   4. Otherwise pass-through.
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
            return override.prefix + actualUrl.substring(override.baseUrl.length)
        }

        val display = SwarmResolver.toDisplay(actualUrl)
        return applyNamePreservation(display)
    }

    private fun applyNamePreservation(display: String): String {
        bzzRegex.matchEntire(display)?.let { m ->
            val hash = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(hash.lowercase())
            if (name != null) return "ens://$name$tail"
            return display
        }
        ipfsRegex.matchEntire(display)?.let { m ->
            val cid = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(cid)
            if (name != null) return "ens://$name$tail"
            return display
        }
        ipnsRegex.matchEntire(display)?.let { m ->
            val id = m.groupValues[1]
            val tail = m.groupValues[2]
            val name = KnownEnsNames.nameFor(id)
            if (name != null) return "ens://$name$tail"
            return display
        }
        return display
    }
}
