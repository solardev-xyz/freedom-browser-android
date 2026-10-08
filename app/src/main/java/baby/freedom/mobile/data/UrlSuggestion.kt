package baby.freedom.mobile.data

/**
 * A single row in the address-bar auto-complete drop-down.
 *
 * The UI decides the icon / styling from [source]; everything else is
 * already in display-ready form (canonical URL + page title). A
 * [Source.TAB] row is an open tab ([tabId]): picking it switches to
 * that tab instead of loading [url] again (#443).
 */
data class UrlSuggestion(
    val url: String,
    val title: String,
    val source: Source,
    val tabId: Long? = null,
) {
    enum class Source { TAB, BOOKMARK, HISTORY }
}

/**
 * What the address bar's text matched in the database (#443): the
 * bookmarks, and the history [pages] with their visit counts, each
 * best candidate first ([HistoryDao.suggest]) — ranked against the open
 * tabs by the browser (`rankSuggestions`).
 */
data class LocalMatches(
    val bookmarks: List<UrlSuggestion>,
    val pages: List<HistoryPage>,
    /** The trimmed text these rows were looked up for. */
    val query: String = "",
)

/**
 * SQL `LIKE` treats `%`, `_`, and `\` as wildcards / escape. The
 * auto-complete passes raw user input into `... LIKE :q`, so we escape
 * those three characters and (by convention with Room) leave the
 * default `\` escape — there's no `ESCAPE` clause on the query, so we
 * just strip them from the input to avoid surprising matches. URLs
 * don't contain raw `%` or `_` often, but bookmarks can, and a user
 * typing `100%` shouldn't match everything.
 */
internal fun String.escapeForLike(): String {
    if (isEmpty()) return this
    val out = StringBuilder(length)
    for (c in this) {
        when (c) {
            '%', '_', '\\' -> { /* drop */ }
            else -> out.append(c)
        }
    }
    return out.toString()
}

/**
 * A `LIKE … ESCAPE '\'` pattern matching any text that contains [text]
 * literally: `%`, `_` and `\` are escaped rather than dropped (unlike
 * [escapeForLike]), so a History search for `my_page` or `100%` finds
 * exactly that and nothing looser.
 */
internal fun likeContains(text: String): String {
    val out = StringBuilder(text.length + 2).append('%')
    for (c in text) {
        if (c == '%' || c == '_' || c == '\\') out.append('\\')
        out.append(c)
    }
    return out.append('%').toString()
}
