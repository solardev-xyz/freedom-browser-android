package baby.freedom.mobile.browser

/**
 * Turn whatever the user typed into an actual URL to load.
 *
 *  - keeps `http://`, `https://`, `bzz://`, `about:`, `file:`, `data:` as-is
 *  - bare hosts like `example.com` or `1.1.1.1:8080` → prepend `https://`
 *  - anything else (contains a space, or no dot) → treat as a web search
 *    on [searchTemplate] (the engine chosen in Settings, see [SearchEngines])
 */
object UrlParser {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val bareHostRegex = Regex("^[^\\s/]+\\.[^\\s/]+(/.*)?$")
    private val ipPortRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    fun toUrl(
        input: String,
        searchTemplate: String,
    ): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (schemeRegex.containsMatchIn(trimmed)) return trimmed
        if (bareHostRegex.matches(trimmed) || ipPortRegex.matches(trimmed)) {
            return "https://$trimmed"
        }
        return searchUrl(trimmed, searchTemplate)
    }

    /**
     * The web search for [query] on [searchTemplate] — the engine chosen
     * in Settings ([SearchEngines.templateFor]). The address bar's "not a
     * URL" fallback above and the selection toolbar's "Search" (#84) both
     * build their URL here, so the two can never name different engines.
     */
    fun searchUrl(query: String, searchTemplate: String): String =
        SearchEngines.searchUrl(searchTemplate, query.trim())
}
