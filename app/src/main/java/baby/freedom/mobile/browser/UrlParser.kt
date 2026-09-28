package baby.freedom.mobile.browser

/**
 * Turn whatever the user typed into an actual URL to load.
 *
 *  - keeps `http://`, `https://`, `bzz://`, `about:`, `file:`, `data:` as-is
 *  - bare hosts like `example.com` or `1.1.1.1:8080` → prepend `https://`
 *  - a host with a port, `localhost:8700/x` or `example.com:8080/x`,
 *    which the scheme check would take for a `localhost:` scheme →
 *    prepend `http://`, the address the WebView fixes it up to and
 *    requests anyway; spelled out here so everything keyed on the URL
 *    before the request (the desktop-site user agent, #180 R6-F2) sees
 *    the address that is really fetched
 *  - anything else (contains a space, or no dot) → treat as a web search
 *    on [searchTemplate] (the engine chosen in Settings, see [SearchEngines])
 */
object UrlParser {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val bareHostRegex = Regex("^[^\\s/]+\\.[^\\s/]+(/.*)?$")
    private val ipPortRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    /**
     * `host:port`, optionally followed by a path, query or fragment. The
     * host is `localhost`, a dotted name, or a bracketed IPv6 literal —
     * never a bare word, so `tel:5551234` and the like stay schemes.
     */
    private val hostPortRegex = Regex(
        "^(localhost|[^\\s/:?#@\\[\\]]+\\.[^\\s/:?#@\\[\\]]+|\\[[0-9a-fA-F:.]+\\]):\\d{1,5}([/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun toUrl(
        input: String,
        searchTemplate: String,
    ): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (isHostPort(trimmed)) return "http://$trimmed"
        if (schemeRegex.containsMatchIn(trimmed)) return trimmed
        if (isBareHost(trimmed)) return "https://$trimmed"
        return searchUrl(trimmed, searchTemplate)
    }

    /**
     * Whether [toUrl] would turn [input] into a web search rather than an
     * address — what the address bar's top suggestion row
     * ([AddressInput]) predicts Enter will do. Blank input is
     * neither (it loads `about:blank`), so `false`.
     */
    fun isSearch(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.isNotEmpty() &&
            !isHostPort(trimmed) &&
            !schemeRegex.containsMatchIn(trimmed) &&
            !isBareHost(trimmed)
    }

    /** An IPv4 literal with a port keeps its `https://` (see [isBareHost]). */
    private fun isHostPort(trimmed: String): Boolean =
        hostPortRegex.matches(trimmed) && !ipPortRegex.matches(trimmed)

    private fun isBareHost(trimmed: String): Boolean =
        bareHostRegex.matches(trimmed) || ipPortRegex.matches(trimmed)

    /**
     * The web search for [query] on [searchTemplate] — the engine chosen
     * in Settings ([SearchEngines.templateFor]). The address bar's "not a
     * URL" fallback above and the selection toolbar's "Search" (#84) both
     * build their URL here, so the two can never name different engines.
     */
    fun searchUrl(query: String, searchTemplate: String): String =
        SearchEngines.searchUrl(searchTemplate, query.trim())
}
