package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * What the WebView last reported for a tab's find session:
 * [active] is the 1-based ordinal of the highlighted match (0 when none
 * is highlighted — no matches, or an interim report sent before one was
 * picked), [matches] the total. [final] is Chromium's "done counting";
 * interim results stream in while a long page is still being scanned and
 * are shown as they come, so the count visibly converges.
 */
data class FindResult(val active: Int, val matches: Int, val final: Boolean)

/**
 * Build a [FindResult] from `WebView.FindListener.onFindResultReceived`'s
 * arguments. The listener's ordinal is **0-based** and is meaningless
 * when there are no matches, so it is normalised here once rather than at
 * every reader. An interim report can also arrive before any match is
 * marked current (ordinal -1 with matches > 0); that reads "0/N", as in
 * Chrome, rather than claiming match 1 is the highlighted one.
 */
internal fun findResultFrom(
    activeMatchOrdinal: Int,
    numberOfMatches: Int,
    isDoneCounting: Boolean,
): FindResult {
    val matches = numberOfMatches.coerceAtLeast(0)
    val active = if (activeMatchOrdinal in 0 until matches) activeMatchOrdinal + 1 else 0
    return FindResult(active = active, matches = matches, final = isDoneCounting)
}

/** The bar's "3/12" count; empty while there is no result to describe. */
internal fun findCountLabel(result: FindResult?): String =
    if (result == null) "" else "${result.active}/${result.matches}"

/**
 * What TalkBack hears for the count: words rather than "3 slash 12", and
 * nothing at all while no result is in (see [findCountLiveRegion]).
 */
internal fun findCountSpoken(result: FindResult?): String = when {
    result == null -> ""
    result.matches == 0 -> "No matches"
    result.active == 0 -> "${result.matches} matches"
    else -> "Match ${result.active} of ${result.matches}"
}

/**
 * The count is a polite live region only once Chromium has finished
 * counting: a long page streams interim reports several times a second,
 * and announcing each one buries the answer under a string of partial
 * counts. Steps arrive as final reports, so each one is still announced.
 */
internal fun findCountLiveRegion(result: FindResult?): Boolean = result?.final == true

/**
 * Whether stepping does anything: the one rule behind both the previous /
 * next arrows and Search / Enter ([findSubmitAction]), so the two never
 * disagree. [shown] is the count on the bar ([FindInPageState.displayed]),
 * held across keystrokes — while a new search's first report is pending
 * both follow the held count, and both settle together when it lands.
 * With nothing shown yet (the session's first search, still pending) a
 * step is queued behind the search and does step, so it's allowed; a
 * count of 0 has nowhere to go.
 */
internal fun findStepEnabled(searching: Boolean, shown: FindResult?): Boolean =
    searching && (shown == null || shown.matches > 0)

/** What the keyboard's Search key / Enter does in the find field. */
internal enum class FindSubmit {
    /** Run the field's text — a prefilled query not yet searched. */
    Search,

    /** Advance to the next / previous match of the running search. */
    Step,

    /** Nothing to do: empty field, or a finished search with no matches. */
    None,
}

/**
 * Decide what Search / Enter does. The live search is identified by
 * [FindInPageState.searching], not by a missing result: a result is also
 * null in the moment between sending a search and Chromium's first
 * report, and re-running `findAllAsync` there would restart at match 1
 * instead of advancing. `findNext` issued while that first report is
 * still pending is queued behind the search, so it steps as intended.
 * Whether it steps is [findStepEnabled], shared with the arrows.
 */
internal fun findSubmitAction(searching: Boolean, fieldText: String, shown: FindResult?): FindSubmit =
    when {
        fieldText.isEmpty() -> FindSubmit.None
        !searching -> FindSubmit.Search
        findStepEnabled(searching, shown) -> FindSubmit.Step
        else -> FindSubmit.None
    }

/**
 * Per-tab find-in-page session, the way Chrome models it: every tab owns
 * its open/closed bar, its query and its count, and switching tabs shows
 * the foreground tab's own session (a background tab's highlights stay
 * painted, as in Chrome).
 *
 * The WebView side lives in [BrowserWebViewHost] behind
 * [TabsState.find]; this class only holds what the chrome renders.
 */
class FindInPageState {
    /** The find bar is showing in place of the capsule. */
    var open by mutableStateOf(false)
        private set

    /**
     * Text in the bar. Kept across close and navigation so reopening the
     * bar prefills it (Chrome does the same) — but never re-run on its own
     * against a new document.
     */
    var query by mutableStateOf("")

    /**
     * Last result for the live search — null when none is running, and
     * also while one has just been sent and Chromium hasn't reported yet.
     */
    var result: FindResult? by mutableStateOf(null)
        private set

    /**
     * What the bar's count and arrows show: the latest report of the live
     * search, held across keystrokes. Typing sends a new search per
     * character, and blanking the count (and greying the arrows) until
     * each one's first report made them flicker while typing — so the
     * previous count stays up until the new one lands. Null only once the
     * session has ended or the query is empty. Whether a step does
     * anything (arrows and Enter alike, [findStepEnabled]) follows this
     * held count too; only announcing reads the new search's own [result].
     */
    var displayed: FindResult? by mutableStateOf(null)
        private set

    /**
     * A search for a non-empty [query] has been sent to the WebView in
     * this session (whether or not its first report is in yet). False on
     * a freshly opened bar whose prefilled query hasn't been run.
     */
    var searching by mutableStateOf(false)
        private set

    fun show() {
        open = true
    }

    /** Close the bar; the caller clears the WebView's highlights. */
    fun close() {
        open = false
        searching = false
        result = null
        displayed = null
    }

    /**
     * A find request for [text] is about to go to the WebView. An empty
     * query is a stopped search, not a search for nothing, so its count
     * is blank rather than "0/0".
     */
    fun startSearch(text: String) {
        query = text
        searching = text.isNotEmpty()
        result = null
        if (!searching) displayed = null
    }

    /**
     * Fold a `FindListener` report in. Dropped once the session has ended
     * (bar closed, query cleared, or a new document committed): Chromium
     * can still deliver a report for the search that was just stopped,
     * and it must not paint a count for text nobody is searching.
     */
    fun onResult(result: FindResult) {
        if (!open || !searching) return
        this.result = result
        displayed = result
    }

    /**
     * A new document committed in this tab. Chrome's rule on a
     * cross-document main-frame navigation: the find session ends and the
     * bar hides, so the previous page's query is never silently re-run on
     * the next one. Same-document navigations (`pushState`, fragments)
     * don't commit a document and never reach here.
     */
    fun onDocumentCommitted() {
        close()
    }
}

/** What the chrome asks a tab's WebView to do for find-in-page. */
sealed interface FindAction {
    /** Search for [text] from the top, highlighting every match. */
    data class Search(val text: String) : FindAction

    /** Move to the next ([forward]) or previous match. */
    data class Step(val forward: Boolean) : FindAction

    /** End the session and drop the highlights. */
    data object Clear : FindAction
}

/**
 * The find bar: shown in the capsule's slot while a tab's find session is
 * open (Safari's bottom-bar find, which is where an Android user's thumb
 * already is), on the capsule's own surface so it reads as the bar
 * turning into a find field rather than a new layer on top of it.
 *
 * Left to right: the query field, the "3/12" count, previous, next and
 * close. Find runs as you type; the keyboard's Search key (or Enter on a
 * hardware keyboard) steps to the next match, Shift+Enter to the previous,
 * and Escape closes the bar.
 *
 * While the bar stands in for the capsule it also carries the capsule's
 * load-progress trace ([CapsuleLoadTrace]), so a load or gateway probe
 * started from the page with the bar up is still visible.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FindBar(
    tab: BrowserState,
    onQueryChange: (String) -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val find = tab.find
    val result = find.displayed
    val focusRequester = remember { FocusRequester() }
    // Prefilled with last time's query, cursor at the end — a user who
    // wants it again just hits Search. Not select-all: the IME answers an
    // Enter over a selection by deleting it (seen with Gboard on the AVD),
    // so a selected prefill would be wiped by the very key that re-runs it.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(find.query, TextRange(find.query.length)))
    }
    // Focus (and so the keyboard) on a fresh open only — coming back to
    // a tab whose search already has results shows them without throwing
    // a keyboard over the page.
    LaunchedEffect(Unit) { if (!find.searching) focusRequester.requestFocus() }
    // Search / Enter: step to the next match, or — for a prefilled query
    // that hasn't been searched in this session yet — run it.
    fun submit(forward: Boolean) {
        when (findSubmitAction(find.searching, fieldValue.text, find.displayed)) {
            FindSubmit.Search -> onQueryChange(fieldValue.text)
            FindSubmit.Step -> onStep(forward)
            FindSubmit.None -> Unit
        }
    }
    // Derived so a ticking load only recomposes at the idle/busy
    // boundary; the percentage itself is read in the trace's draw phase.
    val loading by remember(tab) { derivedStateOf { isCapsuleLoading(tab) } }
    // Room for "0000/0000" in tabular figures, so stepping 9/10 → 10/10
    // or 999/1200 → 1000/1200 (or a count converging past a digit)
    // doesn't nudge the field. Past that the label just grows.
    val countStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val countMinWidth = remember(countStyle, density) {
        with(density) { measurer.measure("0000/0000", countStyle).size.width.toDp() }
    }

    Box(
        modifier = modifier.height(CapsuleControlSize),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleSurface(
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(CapsuleRestingHeight),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textStyle = LocalTextStyle.current.copy(color = colors.onSurface)
            BasicTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    val changed = newValue.text != fieldValue.text
                    fieldValue = newValue
                    if (changed) onQueryChange(newValue.text)
                },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { submit(forward = true) },
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        // Hardware keyboard: Enter / Shift+Enter walk the
                        // matches and Escape closes, as in every desktop
                        // browser. Both halves of the key are consumed so
                        // the down doesn't reach the IME or the WebView.
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyUp) submit(!event.isShiftPressed)
                                true
                            }
                            Key.Escape -> {
                                if (event.type == KeyEventType.KeyUp) onClose()
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                "Find in page",
                                style = textStyle,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            val spoken = findCountSpoken(result)
            // Announce only the new search's own report, not the count
            // held over from the previous keystroke.
            val live = findCountLiveRegion(find.result)
            Text(
                text = findCountLabel(result),
                style = countStyle,
                textAlign = TextAlign.End,
                color = if (result != null && result.matches == 0) colors.error
                else colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(min = countMinWidth)
                    .semantics {
                        contentDescription = spoken
                        if (live) liveRegion = LiveRegionMode.Polite
                    },
            )
            val canStep = findStepEnabled(find.searching, result)
            FindBarButton(Icons.Filled.KeyboardArrowUp, "Previous match", canStep) { onStep(false) }
            FindBarButton(Icons.Filled.KeyboardArrowDown, "Next match", canStep) { onStep(true) }
            FindBarButton(Icons.Filled.Close, "Close find", true, onClose)
        }
        if (loading) {
            CapsuleLoadTrace(
                state = tab,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CapsuleRestingHeight),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FindBarButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
