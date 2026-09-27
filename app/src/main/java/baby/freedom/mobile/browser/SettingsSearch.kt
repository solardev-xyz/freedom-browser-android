package baby.freedom.mobile.browser

/**
 * Settings search (#93): the Settings page's filter field narrows every
 * section to the rows whose text contains the query — case-insensitive
 * substring, no fuzzy matching, like the desktop browser's settings
 * search. A section with nothing left is hidden; its card title stays on
 * every section that is shown, so each match is listed with the section
 * it lives in.
 *
 * Each section describes its rows as [SettingsRow]s: a stable [key] the
 * section composable checks before drawing the row, and every string
 * the row puts on screen (label, description, value, helper line). The
 * index is built from what the page shows at that moment — a site
 * permission is findable by its site and state, the search engine row
 * by the engine in use — and a section that isn't on the page (IPFS
 * while advanced options are off) isn't searched.
 */
internal class SettingsRow(val key: Any, val texts: List<String>)

internal fun settingsRow(key: Any, vararg texts: String?): SettingsRow =
    SettingsRow(key, texts.filterNotNull().filter { it.isNotBlank() })

/**
 * The keys of [rows] to show for [query]: every row when the query is
 * blank or names the section itself ("browsing" → the whole Browsing
 * data card), otherwise only the rows with a text
 * containing it. Empty means the section has no match and is hidden.
 */
internal fun visibleSettingsRows(
    query: String,
    sectionTitle: String,
    rows: List<SettingsRow>,
): Set<Any> {
    val q = query.trim()
    val shown = if (q.isEmpty() || sectionTitle.contains(q, ignoreCase = true)) {
        rows
    } else {
        rows.filter { row -> row.texts.any { it.contains(q, ignoreCase = true) } }
    }
    return shown.mapTo(LinkedHashSet()) { it.key }
}
