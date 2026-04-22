package baby.freedom.mobile.data

/**
 * A single row in the address-bar auto-complete drop-down.
 *
 * The UI decides the icon / styling from [source]; everything else is
 * already in display-ready form (canonical URL + page title).
 */
data class UrlSuggestion(
    val url: String,
    val title: String,
    val source: Source,
) {
    enum class Source { BOOKMARK, HISTORY }
}

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
