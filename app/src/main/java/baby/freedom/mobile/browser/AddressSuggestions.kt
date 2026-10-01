package baby.freedom.mobile.browser

import androidx.compose.foundation.background
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
import baby.freedom.mobile.data.UrlSuggestion

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

/**
 * Opaque panel that overlays the WebView while the address bar is
 * focused and edited: the action rows for what has been typed (#171 —
 * go to it, or search for it with the Settings engine) and then the
 * bookmarks and history that match it. The list is reversed so the best
 * row sits right above the (bottom) address bar and the thumb, with
 * weaker ones stacking upwards. Picking a row hands its text to the
 * browser's `submit` path — the same one Enter uses — which hides the
 * keyboard and clears focus (and therefore dismisses this panel).
 */
@Composable
internal fun SuggestionsPanel(
    repo: BrowsingRepository,
    query: String,
    searchTemplate: String,
    onPick: (String) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    // Re-subscribe when the query changes; Room's Flow keeps emitting
    // fresh results if the underlying tables change too.
    val suggestionsFlow = remember(repo, query) { repo.suggestions(query) }
    val suggestions by suggestionsFlow.collectAsState(initial = emptyList())
    val actions = remember(query, searchTemplate) { addressActions(query, searchTemplate) }
    SuggestionsList(
        query = query,
        actions = actions,
        suggestions = suggestions,
        onPick = onPick,
        bottomContentPadding = bottomContentPadding,
        modifier = modifier,
    )
}

/** Test tag of every row in [SuggestionsList]; each row's text is its own. */
internal const val SUGGESTION_ROW_TAG = "suggestion-row"

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
        if (actions.isEmpty() && suggestions.isEmpty()) {
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
                    key = { s -> s.source.name + "|" + s.url },
                ) { s ->
                    SuggestionRow(
                        suggestion = s,
                        highlight = query.trim(),
                        onClick = { onPick(s.url) },
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

@Composable
private fun SuggestionRow(
    suggestion: UrlSuggestion,
    highlight: String,
    onClick: () -> Unit,
) {
    val displayTitle = suggestion.title.ifBlank { suggestion.url }
    RowLayout(
        icon = when (suggestion.source) {
            UrlSuggestion.Source.BOOKMARK -> Icons.Filled.Bookmark
            UrlSuggestion.Source.HISTORY -> Icons.Filled.History
        },
        iconDescription = when (suggestion.source) {
            UrlSuggestion.Source.BOOKMARK -> stringResource(R.string.browser_suggestions_bookmark)
            UrlSuggestion.Source.HISTORY -> stringResource(R.string.browser_suggestions_history)
        },
        iconTint = when (suggestion.source) {
            UrlSuggestion.Source.BOOKMARK -> MaterialTheme.colorScheme.primary
            UrlSuggestion.Source.HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        title = highlightedText(displayTitle, highlight),
        subtitle = highlightedText(suggestion.url, highlight),
        onClick = onClick,
    )
}

/**
 * One suggestion row: the whole row is the touch target, at least
 * 48 dp tall, with the ripple clipped to its rounded shape.
 */
@Composable
private fun RowLayout(
    icon: ImageVector,
    iconDescription: String,
    iconTint: Color,
    title: AnnotatedString,
    subtitle: AnnotatedString,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag(SUGGESTION_ROW_TAG)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
