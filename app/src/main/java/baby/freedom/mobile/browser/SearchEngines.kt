package baby.freedom.mobile.browser

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

    /** Why [validateTemplate] refused a template — each gets its own hint in Settings. */
    enum class Rejection { EMPTY, TOO_LONG, NO_PLACEHOLDER, MULTIPLE_PLACEHOLDERS, NOT_A_URL, SCHEME, USER_INFO }

    /** [validateTemplate]'s answer: the canonical [template], or why not. */
    data class Validation(val template: String?, val rejection: Rejection?)

    /**
     * Canonicalise and validate a user-typed template, or `null` if it
     * can't be used. See [validateTemplate] for the rules.
     */
    fun normalizeTemplate(value: String): String? = validateTemplate(value).template

    /**
     * Desktop's `normalizeSearchUrlTemplate`, with the reason on refusal.
     * Valid means: exactly one placeholder (`{searchTerms}` or `%s`, not
     * both), at most 2048 chars, and — with the placeholder filled in — a
     * URL that WHATWG `new URL()` accepts (see [WhatwgHost]) whose scheme is
     * `https:` (or `http:` to a loopback host, for a self-hosted engine on
     * the device) and that carries no user name or password.
     */
    fun validateTemplate(value: String): Validation {
        fun no(r: Rejection) = Validation(null, r)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return no(Rejection.EMPTY)
        if (trimmed.length > MAX_TEMPLATE_LENGTH) return no(Rejection.TOO_LONG)
        val openSearch = trimmed.occurrences(PLACEHOLDER)
        val percent = trimmed.occurrences("%s")
        if (openSearch + percent == 0) return no(Rejection.NO_PLACEHOLDER)
        if (openSearch + percent > 1) return no(Rejection.MULTIPLE_PLACEHOLDERS)
        val normalized =
            if (percent == 1) trimmed.replace("%s", PLACEHOLDER) else trimmed
        val parsed = WhatwgHost.parse(normalized.replace(PLACEHOLDER, "test"))
            ?: return no(Rejection.NOT_A_URL)
        val secure = parsed.scheme == "https"
        val loopbackHttp = parsed.scheme == "http" && parsed.hostname in LOOPBACK_HOSTS
        if (!secure && !loopbackHttp) return no(Rejection.SCHEME)
        if (parsed.hasCredentials) return no(Rejection.USER_INFO)
        return Validation(normalized, null)
    }

    /**
     * The engine id [id] actually resolves to — the one [templateFor]
     * searches with. An unknown id, or `custom` without a valid
     * [customTemplate], is [DEFAULT_ID]. The Settings row, the picker's
     * checked radio and the address bar all go through this, so they can
     * never disagree about which engine is in use.
     */
    fun effectiveId(id: String?, customTemplate: String?): String = when {
        id == CUSTOM_ID ->
            if (customTemplate?.let(::normalizeTemplate) != null) CUSTOM_ID else DEFAULT_ID
        BUILT_IN.any { it.id == id } -> id!!
        else -> DEFAULT_ID
    }

    /**
     * The template [id] resolves to. An unknown id, or `custom` without a
     * valid [customTemplate], falls back to [DEFAULT] so a stale or
     * hand-edited setting can never break address-bar search.
     */
    fun templateFor(id: String?, customTemplate: String?): String =
        when (val effective = effectiveId(id, customTemplate)) {
            CUSTOM_ID -> normalizeTemplate(customTemplate!!)!!
            else -> BUILT_IN.first { it.id == effective }.template
        }

    /** Settings-row label for what [id] resolves to (see [effectiveId]). */
    fun labelFor(id: String?, customTemplate: String?): String =
        when (val effective = effectiveId(id, customTemplate)) {
            CUSTOM_ID -> "Custom"
            else -> BUILT_IN.first { it.id == effective }.label
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
