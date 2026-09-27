package baby.freedom.mobile.browser

import java.net.URI

/**
 * Address-bar search providers — the same set, ids and templates as the
 * desktop browser's `src/renderer/lib/search-utils.js`, plus one custom
 * template the user types in Settings.
 *
 * A template is a results URL with a single `{searchTerms}` placeholder
 * (OpenSearch's spelling); the familiar `%s` alias is accepted on input
 * and canonicalised on save.
 */
object SearchEngines {
    data class Engine(val id: String, val label: String, val template: String)

    const val PLACEHOLDER = "{searchTerms}"
    const val DEFAULT_ID = "duckduckgo"
    const val CUSTOM_ID = "custom"
    private const val MAX_TEMPLATE_LENGTH = 2048
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    /** Built-ins, in the order the Settings picker lists them. */
    val BUILT_IN: List<Engine> = listOf(
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=$PLACEHOLDER"),
        Engine("google", "Google", "https://www.google.com/search?q=$PLACEHOLDER"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=$PLACEHOLDER"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=$PLACEHOLDER"),
        Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=$PLACEHOLDER"),
        Engine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=$PLACEHOLDER"),
    )

    val DEFAULT: Engine = BUILT_IN.first { it.id == DEFAULT_ID }

    /**
     * Canonicalise and validate a user-typed template, or `null` if it
     * can't be used. Valid means: exactly one placeholder (`{searchTerms}`
     * or `%s`, not both), at most 2048 chars, and — with the placeholder
     * filled in — an absolute `https://` URL (or `http://` to a loopback
     * host, for a self-hosted engine on the device) with a host and no
     * user-info. Mirrors desktop's `normalizeSearchUrlTemplate`.
     */
    fun normalizeTemplate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEMPLATE_LENGTH) return null
        val openSearch = trimmed.occurrences(PLACEHOLDER)
        val percent = trimmed.occurrences("%s")
        if (openSearch + percent != 1) return null
        val normalized =
            if (percent == 1) trimmed.replace("%s", PLACEHOLDER) else trimmed
        val parsed = runCatching { URI(normalized.replace(PLACEHOLDER, "test")) }
            .getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase()
        if (host.isNullOrEmpty() || parsed.rawUserInfo != null) return null
        val allowed = scheme == "https" || (scheme == "http" && host in LOOPBACK_HOSTS)
        return if (allowed) normalized else null
    }

    /**
     * The template [id] resolves to. An unknown id, or `custom` without a
     * valid [customTemplate], falls back to [DEFAULT] so a stale or
     * hand-edited setting can never break address-bar search.
     */
    fun templateFor(id: String?, customTemplate: String?): String {
        if (id == CUSTOM_ID) {
            customTemplate?.let(::normalizeTemplate)?.let { return it }
            return DEFAULT.template
        }
        return BUILT_IN.firstOrNull { it.id == id }?.template ?: DEFAULT.template
    }

    /** Settings-row label for [id]; unknown ids read as the default. */
    fun labelFor(id: String?): String = when (id) {
        CUSTOM_ID -> "Custom"
        else -> (BUILT_IN.firstOrNull { it.id == id } ?: DEFAULT).label
    }

    /** Results URL for [query] (already trimmed, non-empty) on [template]. */
    fun searchUrl(template: String, query: String): String =
        template.replace(PLACEHOLDER, encodeQueryComponent(query))

    /**
     * `encodeURIComponent`: UTF-8 percent-encoding of everything but
     * `A-Z a-z 0-9 - _ . ! ~ * ' ( )`. Written out rather than using
     * `android.net.Uri.encode` (same result) so it runs in JVM unit
     * tests, and not `URLEncoder`, whose `+` for space is only a space in
     * a query string — a custom template may put the terms in the path.
     */
    internal fun encodeQueryComponent(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return out.toString()
    }

    private fun String.occurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }
}
