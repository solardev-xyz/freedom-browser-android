package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.LocalMatches
import baby.freedom.mobile.data.UrlSuggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex

/**
 * Clear focus (ending an address-bar edit) and hide the keyboard on any
 * press in this element — seen on the Initial pass, before the children,
 * and never consumed, so the child still gets its tap. BrowserScreen's
 * `dismissKeyboardOnTap`, applied to every band outside the capsule.
 */
internal fun Modifier.endEditOnPress(
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        focusManager.clearFocus()
        keyboard?.hide()
    }
}

/**
 * The page area and, while the user is editing the address, the
 * suggestions over it (#170).
 *
 * [dismissKeyboardOnTap] — "a press anywhere outside the capsule ends
 * the edit" — goes on the page layer only. The suggestions are its
 * *sibling*, never its child: that modifier clears focus on the press's
 * Initial pass, and the panel is shown only while the address bar has
 * focus, so a panel inside it was torn down on finger-down and the row's
 * click never arrived — tapping a suggestion did nothing. As a sibling
 * drawn on top, the panel gets the press first and the page layer below
 * it never sees it. (The panel dismisses the edit on its own for a tap
 * on its empty space, see [SuggestionsList].)
 */
@Composable
internal fun PageWithSuggestions(
    dismissKeyboardOnTap: Modifier,
    suggestions: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    page: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxSize().then(dismissKeyboardOnTap),
            content = page,
        )
        suggestions?.invoke()
    }
}

/** How long the address bar waits for typing to pause before it searches bookmarks and history (#473). */
internal const val LOCAL_MATCHES_DEBOUNCE_MS = 100L

/**
 * [lookup] for the latest of [queries] (#473): the first query at once,
 * so the panel fills as soon as it opens, and each later one only once
 * no newer one has come for [debounceMs] — typing "github" quickly runs
 * one database search, not six. A newer query cancels the older one's
 * flow, so its late results never show. While waiting, the previous
 * results stay, but only the rows that still contain the text now typed
 * ([stillMatching]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> debouncedLookup(
    queries: Flow<String>,
    debounceMs: Long = LOCAL_MATCHES_DEBOUNCE_MS,
    lookup: (String) -> Flow<T>,
): Flow<T> =
    queries
        .distinctUntilChanged()
        .withIndex()
        .transformLatest { (i, query) ->
            if (i > 0) delay(debounceMs)
            emitAll(lookup(query))
        }

/**
 * [matches], looked up for an earlier text while the lookup for [query]
 * waits out the debounce (#473 R1-F1), cut down to the rows the database
 * would also return for [query]: those whose URL or title contains it
 * (the `LIKE '%q%'` of [BrowsingRepository.suggestionMatches]).
 * [rankSuggestions] counts every database row as at least a
 * [MatchStrength.CONTAINS] match, so a row left in from "g" would
 * otherwise be offered under "github". Rows for the current text pass
 * through untouched. Typing more thus shows a correct subset at once;
 * deleting characters can show fewer rows than it should until the new
 * lookup lands.
 */
internal fun stillMatching(matches: LocalMatches, query: String): LocalMatches {
    val text = query.trim()
    if (matches.query == text) return matches
    fun has(url: String, title: String) =
        url.contains(text, ignoreCase = true) || title.contains(text, ignoreCase = true)
    return LocalMatches(
        bookmarks = matches.bookmarks.filter { has(it.url, it.title) },
        pages = matches.pages.filter { has(it.url, it.title) },
        query = matches.query,
    )
}

/**
 * Opaque panel that overlays the WebView while the address bar is
 * focused and edited: the action rows for what has been typed (#171 —
 * go to it, or search for it with the Settings engine), then the open
 * tabs, bookmarks and history that match it, best match first (#443,
 * [rankSuggestions]), and — only while Settings → Search → *Search
 * suggestions* is on and never in a private tab — the search engine's
 * own suggestions ([SearchSuggestions]). The list is reversed so the
 * best row sits right above the (bottom) address bar and the thumb,
 * with weaker ones stacking upwards. Picking a row hands its text to
 * the browser's `submit` path — the same one Enter uses — which hides
 * the keyboard and clears focus (and therefore dismisses this panel);
 * an open tab's row switches to that tab instead, and each row's arrow
 * puts its text in the field to keep editing ([onFill]).
 */
@Composable
internal fun SuggestionsPanel(
    repo: BrowsingRepository,
    query: String,
    searchTemplate: String,
    tabs: List<TabCandidate>,
    currentTabId: Long,
    private: Boolean,
    searchSuggestionsOn: Boolean,
    onPick: (String) -> Unit,
    onSwitchToTab: (Long) -> Unit,
    onFill: (String) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    // Re-subscribe once typing pauses (#473); Room's Flow keeps emitting
    // fresh results if the underlying tables change too.
    val latestQuery by rememberUpdatedState(query)
    val matchesFlow = remember(repo) { debouncedLookup(snapshotFlow { latestQuery }) { repo.suggestionMatches(it) } }
    val answered by matchesFlow.collectAsState(initial = null)
    val matches = remember(answered, query) { answered?.let { stillMatching(it, query) } }
    val history = remember(matches) {
        matches?.pages.orEmpty().map { HistoryCandidate(it.url, it.title, it.visits, it.lastVisit) }
    }
    val suggestions = rankSuggestions(
        query = query,
        tabs = tabs,
        currentTabId = currentTabId,
        private = private,
        bookmarks = matches?.bookmarks.orEmpty(),
        history = history,
    )
    val actions = remember(query, searchTemplate) { addressActions(query, searchTemplate) }
    // Engine suggestions: debounced, the previous request cancelled by
    // the next, and none at all — not even asked for — while off, in a
    // private tab, or for an address ([SearchSuggestions.requestUrl]).
    val request = SearchSuggestions.requestUrl(searchSuggestionsOn, private, query, searchTemplate)
        ?.let { SuggestRequest(query.trim(), it) }
    val latestRequest by rememberUpdatedState(request)
    val engineFlow = remember { engineSuggestions(snapshotFlow { latestRequest }) }
    val engine by engineFlow.collectAsState(initial = null)
    SuggestionsList(
        query = query,
        actions = actions,
        suggestions = suggestions,
        engineSuggestions = shownEngineSuggestions(request, engine),
        searchTemplate = searchTemplate,
        onPick = onPick,
        onSwitchToTab = onSwitchToTab,
        onFill = onFill,
        favicon = { url -> rememberFavicon(repo, url) },
        bottomContentPadding = bottomContentPadding,
        modifier = modifier,
    )
}

/**
 * The engine rows to show for [request], the latest answer being
 * [engine]: only an answer to that very query. Until the debounced
 * fetch for a newer query lands, the previous query's answer is
 * withheld rather than shown under it — tapping one would search for
 * the old text. None while no request may be sent.
 */
internal fun shownEngineSuggestions(request: SuggestRequest?, engine: EngineSuggestions?): List<String> =
    if (request == null || engine == null || engine.query != request.query) emptyList()
    else engine.suggestions

/**
 * Text a suggestion row's arrow asks the address field to take (#443).
 * A new instance each time, so filling the same text twice still
 * lands: the field's effect is keyed on the instance.
 */
internal class AddressFill(val text: String)

/** Test tag of every row in [SuggestionsList]; each row's text is its own. */
internal const val SUGGESTION_ROW_TAG = "suggestion-row"

/** Test tag of each row's fill arrow ([SuggestionsList]'s `onFill`). */
internal const val SUGGESTION_FILL_TAG = "suggestion-fill"

/**
 * [SuggestionsPanel]'s content, given the rows. A tap on the panel's
 * empty space (above the rows) ends the edit, the way a tap on the page
 * did before the panel covered it; a tap on a row picks the row.
 */
@Composable
internal fun SuggestionsList(
    query: String,
    actions: List<AddressAction>,
    suggestions: List<UrlSuggestion>,
    onPick: (String) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    engineSuggestions: List<String> = emptyList(),
    searchTemplate: String = SearchEngines.DEFAULT.template,
    onSwitchToTab: (Long) -> Unit = {},
    onFill: (String) -> Unit = {},
    favicon: @Composable (String) -> ImageBitmap? = { null },
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            // A row's click consumes its tap, so this only fires on
            // space no row covers.
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            },
    ) {
        if (actions.isEmpty() && suggestions.isEmpty() && engineSuggestions.isEmpty()) {
            Text(
                text = stringResource(
                    R.string.browser_suggestions_no_matches_for,
                    BidiControls.marked(query),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = 32.dp + bottomContentPadding,
                        start = 16.dp,
                        end = 16.dp,
                    ),
            )
        } else {
            val engineName = remember(searchTemplate) { SearchEngines.nameForTemplate(searchTemplate) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                // The capsule floats over this panel — keep the
                // best-match row (which sits at the bottom, nearest the
                // thumb) clear of it.
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomContentPadding,
                ),
            ) {
                items(
                    items = actions,
                    key = { a -> a::class.simpleName!! },
                ) { a ->
                    ActionRow(action = a, onClick = { onPick(a.submitText) })
                }
                items(
                    items = suggestions,
                    key = { s -> s.source.name + "|" + s.url + "|" + s.tabId },
                ) { s ->
                    SuggestionRow(
                        suggestion = s,
                        highlight = query.trim(),
                        favicon = favicon(s.url),
                        onClick = {
                            val tab = s.tabId
                            if (s.source == UrlSuggestion.Source.TAB && tab != null) onSwitchToTab(tab)
                            else onPick(s.url)
                        },
                        onFill = { onFill(s.url) },
                    )
                }
                items(
                    items = engineSuggestions,
                    key = { term -> "engine|$term" },
                ) { term ->
                    RowLayout(
                        icon = Icons.Filled.Search,
                        iconDescription = stringResource(R.string.browser_suggestions_search),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = highlightedText(term, query.trim()),
                        subtitle = AnnotatedString(stringResource(R.string.browser_suggestions_engine, engineName)),
                        onClick = { onPick(UrlParser.searchUrl(term, searchTemplate)) },
                        onFill = { onFill(term) },
                    )
                }
            }
        }
    }
}

/**
 * "🔍 swarm storage / Search with DuckDuckGo", or "🌐 example.com / Go to address".
 *
 * The field's buffer keeps what the tab holds or the user typed, bidi
 * controls and all (Go submits it as it is), so every row drawn from it
 * shows them marked ([BidiControls.marked]), as the field itself and the
 * resting label do: an edited `ens://‮moc.lapyap.eth` must not read
 * `te.paypal.com` in the rows right above the field.
 */
@Composable
private fun ActionRow(action: AddressAction, onClick: () -> Unit) {
    when (action) {
        is AddressAction.Search -> RowLayout(
            icon = Icons.Filled.Search,
            iconDescription = stringResource(R.string.browser_suggestions_search),
            iconTint = MaterialTheme.colorScheme.primary,
            title = AnnotatedString(action.shownTitle),
            subtitle = AnnotatedString(stringResource(R.string.browser_suggestions_search_with, action.engine)),
            onClick = onClick,
        )
        is AddressAction.Go -> RowLayout(
            icon = Icons.Filled.Public,
            iconDescription = stringResource(R.string.browser_suggestions_address),
            iconTint = MaterialTheme.colorScheme.primary,
            title = AnnotatedString(action.shownTitle),
            subtitle = AnnotatedString(action.subtitle),
            onClick = onClick,
        )
    }
}

/** The row's title: the query or address as typed, bidi controls marked. */
internal val AddressAction.shownTitle: String
    get() = BidiControls.marked(
        when (this) {
            is AddressAction.Search -> query
            is AddressAction.Go -> input
        },
    )

/**
 * An open tab, bookmark or history page: its favicon (the source's icon
 * until one is cached), the title, and under it where it came from and
 * the address without scheme — "Switch to tab · example.com/a" for a tab.
 */
@Composable
private fun SuggestionRow(
    suggestion: UrlSuggestion,
    highlight: String,
    favicon: ImageBitmap?,
    onClick: () -> Unit,
    onFill: () -> Unit,
) {
    // Both bounded: a tab's or history entry's address (and a title) can
    // be up to Chromium's 2 MiB, and laying that out ANRs (#517 R3-F1).
    // The row's click and fill still act on the whole [suggestion].
    val displayTitle = AddressFieldText.row(suggestion.title.ifBlank { suggestion.url })
    val address = AddressFieldText.row(suggestionAddress(suggestion.url))
    val switchToTab = stringResource(R.string.browser_suggestions_switch_to_tab)
    RowLayout(
        icon = when (suggestion.source) {
            UrlSuggestion.Source.TAB -> Icons.Filled.Tab
            UrlSuggestion.Source.BOOKMARK -> Icons.Filled.Bookmark
            UrlSuggestion.Source.HISTORY -> Icons.Filled.History
        },
        iconDescription = when (suggestion.source) {
            UrlSuggestion.Source.TAB -> stringResource(R.string.browser_suggestions_open_tab)
            UrlSuggestion.Source.BOOKMARK -> stringResource(R.string.browser_suggestions_bookmark)
            UrlSuggestion.Source.HISTORY -> stringResource(R.string.browser_suggestions_history)
        },
        iconTint = when (suggestion.source) {
            UrlSuggestion.Source.HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        favicon = favicon,
        title = highlightedText(displayTitle, highlight),
        subtitle = if (suggestion.source == UrlSuggestion.Source.TAB) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                    append(switchToTab)
                }
                append(" · ")
                append(highlightedText(address, highlight))
            }
        } else {
            highlightedText(address, highlight)
        },
        onClick = onClick,
        onFill = onFill,
    )
}

/**
 * The address a suggestion row shows under its title: [url] without an
 * `http(s)://` scheme, which says nothing the host doesn't. Any other
 * scheme (`bzz://`, `ipfs://`, `ens://`) stays: it says how the page is
 * reached.
 */
internal fun suggestionAddress(url: String): String = when {
    url.startsWith("https://", ignoreCase = true) -> url.substring(8)
    url.startsWith("http://", ignoreCase = true) -> url.substring(7)
    else -> url
}.ifEmpty { url }

/**
 * One suggestion row: the whole row is the touch target, at least
 * 48 dp tall, with the ripple clipped to its rounded shape. With
 * [onFill], a trailing 48 dp arrow puts the row's text in the address
 * field instead, to keep editing it (Chrome's and Safari's "↖").
 */
@Composable
private fun RowLayout(
    icon: ImageVector,
    iconDescription: String,
    iconTint: Color,
    title: AnnotatedString,
    subtitle: AnnotatedString,
    onClick: () -> Unit,
    favicon: ImageBitmap? = null,
    onFill: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .testTag(SUGGESTION_ROW_TAG)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = if (onFill == null) 16.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favicon != null) {
            Image(
                bitmap = favicon,
                contentDescription = iconDescription,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = iconDescription,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Two lines, so "Switch to tab · <address>" keeps its
            // address readable at a large font scale.
            Text(
                text = subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onFill != null) {
            // The arrow points at the field: down towards the bottom
            // capsule, and towards its start edge.
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            IconButton(
                onClick = onFill,
                modifier = Modifier.testTag(SUGGESTION_FILL_TAG),
            ) {
                Icon(
                    imageVector = Icons.Filled.SouthWest,
                    contentDescription = stringResource(R.string.browser_suggestions_fill),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).graphicsLayer { if (rtl) scaleX = -1f },
                )
            }
        }
    }
}

/**
 * Bold every case-insensitive occurrence of [needle] inside [text].
 * Returns a plain [androidx.compose.ui.text.AnnotatedString] we can
 * drop straight into a [Text] composable. A page title or URL can carry
 * bidi controls too, so the text is drawn marked ([BidiControls.marked],
 * same length, so the ranges found on [text] still line up).
 */
private fun highlightedText(text: String, needle: String): AnnotatedString =
    buildAnnotatedString {
        append(BidiControls.marked(text))
        for ((start, end) in highlightRanges(text, needle)) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
        }
    }

/**
 * Where [highlightedText] bolds [needle] in [text]: `(start, end)`
 * offsets into [text] itself, so always inside it.
 *
 * Matched char by char on [text] rather than found in `text.lowercase()`:
 * lower-casing can lengthen a string (`İ` becomes `i̇`, two chars), so
 * offsets found there ran past the end of a page-chosen title like
 * `İİİİİİ Loginpage`, and Android's `setSpan` threw — every keystroke
 * matching that history entry crashed the app, every tab with it.
 */
internal fun highlightRanges(text: String, needle: String): List<Pair<Int, Int>> {
    if (needle.isEmpty()) return emptyList()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i <= text.length - needle.length) {
        if (text.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) {
            ranges += i to i + needle.length
            i += needle.length
        } else {
            i++
        }
    }
    return ranges
}
