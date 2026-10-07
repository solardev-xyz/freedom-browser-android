package baby.freedom.mobile.browser

import androidx.annotation.StringRes
import baby.freedom.mobile.l10n.Strings

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
 * by the engine in use. Results are grouped by the Settings sub-page
 * each section lives on ([settingsResultGroups], #400).
 *
 * Every text comes from string resources (#280), section titles and the
 * extra words a row is found by ([searchKeywords]) included, so a
 * translated build searches the words it shows.
 */
internal class SettingsRow(val key: Any, val texts: List<String>)

internal fun settingsRow(key: Any, vararg texts: String?): SettingsRow =
    SettingsRow(key, texts.filterNotNull().filter { it.isNotBlank() })

/**
 * The keys of [rows] to show for [query]: every row when the query is
 * blank or names the section itself ("browsing" → the whole Browsing
 * data card) or the Settings page it's on ([pageTitle], #400: "privacy"
 * → every card on Privacy & security), otherwise only the rows with a
 * text containing it. Empty means the section has no match and is hidden.
 */
internal fun visibleSettingsRows(
    query: String,
    sectionTitle: String,
    rows: List<SettingsRow>,
    pageTitle: String? = null,
): Set<Any> {
    val q = query.trim()
    val titleMatch = sectionTitle.contains(q, ignoreCase = true) ||
        pageTitle?.contains(q, ignoreCase = true) == true
    val shown = if (q.isEmpty() || titleMatch) {
        rows
    } else {
        rows.filter { row -> row.texts.any { it.contains(q, ignoreCase = true) } }
    }
    return shown.mapTo(LinkedHashSet()) { it.key }
}

/**
 * The extra words a row is found by, from the comma-separated resource
 * [id] ("dark mode, light mode, night mode"): words people search for
 * that the row doesn't show. In resources like the rest, so a
 * translation searches its own words.
 */
internal fun searchKeywords(@StringRes id: Int): Array<String> =
    Strings.get(id).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
