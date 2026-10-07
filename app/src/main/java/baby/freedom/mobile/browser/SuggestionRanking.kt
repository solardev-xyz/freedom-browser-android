package baby.freedom.mobile.browser

import baby.freedom.mobile.data.UrlSuggestion

/*
 * The address bar's local suggestions (#443): open tabs, bookmarks and
 * history matching what was typed, merged into one list, best match
 * first. Kept free of Compose and Room so it is unit-tested; the panel
 * ([SuggestionsPanel]) draws what [rankSuggestions] returns.
 */

/** An open tab, as far as the suggestions are concerned. */
internal data class TabCandidate(
    val id: Long,
    val url: String,
    val title: String,
    val private: Boolean,
)

/**
 * A page from history: its latest title, how many of the visits read
 * matched it ([visits]), and the newest one ([lastVisit], epoch ms).
 */
internal data class HistoryCandidate(
    val url: String,
    val title: String,
    val visits: Int,
    val lastVisit: Long,
)

/** How well a page matches the typed text; higher is better, 0 is no match. */
internal object MatchStrength {
    /** No match at all: the page is left out. */
    const val NONE = 0

    /** The text is somewhere in the title or address. */
    const val CONTAINS = 1

    /** A word of the title starts with it: `rust` in "The Rust Book". */
    const val TITLE_WORD = 2

    /** A part of the host starts with it: `wiki` in `en.wikipedia.org`. */
    const val HOST_LABEL = 3

    /** The address starts with it, past scheme and `www.`: `exa` for `https://www.example.com/`. */
    const val ADDRESS_PREFIX = 4
}

/**
 * The address as the user would type it: no scheme, no `www.`, lower
 * case — `https://www.Example.com/a` is `example.com/a`.
 */
internal fun typedForm(url: String): String {
    var s = url.trim()
    val scheme = s.indexOf("://")
    if (scheme in 1..16) s = s.substring(scheme + 3)
    s = s.lowercase()
    if (s.startsWith("www.")) s = s.substring(4)
    return s
}

/**
 * The host part of [url]'s [typedForm] (`en.wikipedia.org` for
 * `https://en.wikipedia.org/wiki/X`), user info and port dropped.
 */
internal fun suggestionHost(url: String): String =
    typedForm(url)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')

/**
 * The page [url] is for, so one page reached as a tab, a bookmark and a
 * history entry is suggested once: [typedForm] with an empty trailing
 * `#` and a lone trailing `/` dropped (`https://example.com/` and
 * `http://www.example.com` are one page).
 */
internal fun pageKey(url: String): String =
    typedForm(url).removeSuffix("#").removeSuffix("/")

/** How well [query] matches a page with [url] and [title] ([MatchStrength]). */
internal fun matchStrength(query: String, url: String, title: String): Int {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return MatchStrength.NONE
    // Typed with a scheme or `www.`: compare like for like.
    val typed = typedForm(q)
    val address = typedForm(url)
    if (typed.isNotEmpty() && address.startsWith(typed)) return MatchStrength.ADDRESS_PREFIX
    val host = suggestionHost(url)
    if (host.split('.').any { it.isNotEmpty() && it.startsWith(q) }) return MatchStrength.HOST_LABEL
    val lowerTitle = title.lowercase()
    if (lowerTitle.split(WORD_BREAK).any { it.startsWith(q) } || lowerTitle.startsWith(q)) {
        return MatchStrength.TITLE_WORD
    }
    if (lowerTitle.contains(q) || url.lowercase().contains(q)) return MatchStrength.CONTAINS
    return MatchStrength.NONE
}

private val WORD_BREAK = Regex("[\\s\\p{Punct}]+")

/** Where a suggestion came from, best first when two match equally well. */
private fun sourceRank(source: UrlSuggestion.Source): Int = when (source) {
    UrlSuggestion.Source.TAB -> 0
    UrlSuggestion.Source.BOOKMARK -> 1
    UrlSuggestion.Source.HISTORY -> 2
}

private class Ranked(
    var suggestion: UrlSuggestion,
    var strength: Int,
    var visits: Int,
    var lastVisit: Long,
)

/**
 * The local suggestions for [query], best first, at most [limit]:
 *
 *  - open [tabs] of the same kind as the tab being edited ([private]),
 *    other than that tab itself ([currentTabId]) and tabs still on the
 *    home page — a private tab's pages never show in a regular tab's
 *    list, nor the other way round;
 *  - [bookmarks] and [history], as the database matched them.
 *
 * One page is one row ([pageKey]): an open tab beats a bookmark, which
 * beats history, and the row keeps the best match and the visits any
 * of them had. Rows are ordered by [matchStrength], then that source
 * order, then how often and how recently the page was visited. A tab
 * has to match the text itself; a database row the text matched only
 * in a way [matchStrength] doesn't see (SQL's `LIKE` is looser about
 * case outside ASCII) still counts as [MatchStrength.CONTAINS].
 */
internal fun rankSuggestions(
    query: String,
    tabs: List<TabCandidate>,
    currentTabId: Long?,
    private: Boolean,
    bookmarks: List<UrlSuggestion>,
    history: List<HistoryCandidate>,
    limit: Int = DEFAULT_SUGGESTION_LIMIT,
): List<UrlSuggestion> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val byPage = LinkedHashMap<String, Ranked>()

    fun offer(s: UrlSuggestion, strength: Int, visits: Int, lastVisit: Long) {
        if (strength == MatchStrength.NONE) return
        val key = pageKey(s.url)
        val held = byPage[key]
        if (held == null) {
            byPage[key] = Ranked(s, strength, visits, lastVisit)
            return
        }
        val better = sourceRank(s.source) < sourceRank(held.suggestion.source)
        if (better) {
            held.suggestion = s.copy(title = s.title.ifBlank { held.suggestion.title })
        } else if (held.suggestion.title.isBlank() && s.title.isNotBlank()) {
            held.suggestion = held.suggestion.copy(title = s.title)
        }
        held.strength = maxOf(held.strength, strength)
        held.visits = maxOf(held.visits, visits)
        held.lastVisit = maxOf(held.lastVisit, lastVisit)
    }

    for (t in tabs) {
        if (t.private != private || t.id == currentTabId || t.url.isBlank()) continue
        offer(
            UrlSuggestion(t.url, t.title, UrlSuggestion.Source.TAB, tabId = t.id),
            matchStrength(query, t.url, t.title),
            visits = 0,
            lastVisit = 0,
        )
    }
    for (b in bookmarks) {
        offer(
            b.copy(source = UrlSuggestion.Source.BOOKMARK, tabId = null),
            matchStrength(query, b.url, b.title).coerceAtLeast(MatchStrength.CONTAINS),
            visits = 0,
            lastVisit = 0,
        )
    }
    for (h in history) {
        offer(
            UrlSuggestion(h.url, h.title, UrlSuggestion.Source.HISTORY),
            matchStrength(query, h.url, h.title).coerceAtLeast(MatchStrength.CONTAINS),
            visits = h.visits,
            lastVisit = h.lastVisit,
        )
    }
    return byPage.values
        .sortedWith(
            compareByDescending<Ranked> { it.strength }
                .thenBy { sourceRank(it.suggestion.source) }
                .thenByDescending { it.visits }
                .thenByDescending { it.lastVisit },
        )
        .take(limit)
        .map { it.suggestion }
}

/**
 * History rows (one per visit, newest first) as pages: one
 * [HistoryCandidate] per URL with its newest title, visit count and
 * latest visit, in order of that latest visit.
 */
internal fun historyCandidates(visits: List<Triple<String, String, Long>>): List<HistoryCandidate> {
    val byUrl = LinkedHashMap<String, HistoryCandidate>()
    for ((url, title, at) in visits) {
        val held = byUrl[url]
        byUrl[url] = if (held == null) {
            HistoryCandidate(url, title, 1, at)
        } else {
            held.copy(
                title = if (at > held.lastVisit || held.title.isBlank()) title.ifBlank { held.title } else held.title,
                visits = held.visits + 1,
                lastVisit = maxOf(held.lastVisit, at),
            )
        }
    }
    return byUrl.values.toList()
}

internal const val DEFAULT_SUGGESTION_LIMIT = 8
