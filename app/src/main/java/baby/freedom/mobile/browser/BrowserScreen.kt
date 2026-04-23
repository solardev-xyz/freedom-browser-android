package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.UrlSuggestion
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.launch

/**
 * Local home page shipped inside the app's assets. Kept in sync with
 * the home page used by freedom-browser. See
 * `app/src/main/assets/home/home.html`.
 */
const val HOME_URL: String = "file:///android_asset/home/home.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    nodeInfo: NodeInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    initialUrl: String = HOME_URL,
) {
    val tabs = remember { TabsState(homepage = initialUrl) }
    val ensResolver = remember { EnsResolver() }
    val context = LocalContext.current
    val repo = remember(context) { BrowsingRepository.get(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showNodeSheet by rememberSaveable { mutableStateOf(false) }
    var showTabSwitcher by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var resolvingEns by remember { mutableStateOf(false) }
    // Intentionally NOT `rememberSaveable`: on rotation the Activity is
    // recreated, `tabs` is rebuilt as a fresh blank tab, and we need to
    // re-submit the homepage into it. If this survived config changes
    // the load would be suppressed and the tab would render blank.
    var didInitialLoad by remember { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    // Suggestions should only appear once the user has actively changed
    // the address-bar text. Tapping the pill (which select-alls the
    // current URL) must NOT flash a dropdown — the user just wants to
    // replace the URL with a fresh one.
    var addressBarEdited by remember { mutableStateOf(false) }
    // Bumped when we want the address bar to grab focus (launch, Home).
    // TopBar reacts by calling FocusRequester.requestFocus() whenever
    // this value changes.
    var focusAddressTrigger by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val state = tabs.active
    val isBookmarked by repo.isBookmarked(state.url).collectAsState(initial = false)

    BackHandler(enabled = state.canGoBack) {
        state.loadUrl("javascript:history.back();void(0);")
    }

    fun submit(target: BrowserState, raw: String) {
        keyboard?.hide()
        focusManager.clearFocus()

        val trimmed = raw.trim()
        // Let the user type the friendly form and re-submit to reload.
        val canonical = target.effectiveFetchUrl(trimmed)

        val ens = EnsInput.parse(canonical)
        if (ens != null) {
            target.addressBarText = "ens://${ens.name}${ens.suffix}"
            resolvingEns = true
            scope.launch {
                try {
                    val result = ensResolver.resolveContenthash(ens.name)
                    when (result) {
                        is EnsResult.Ok -> {
                            if (result.protocol == "bzz") {
                                val t = result.uri + ens.suffix
                                target.loadUrl(t, ensName = ens.name)
                            } else {
                                snackbarHostState.showSnackbar(
                                    "${ens.name} → ${result.protocol}:// (${result.decoded.take(12)}…) — not supported yet",
                                )
                            }
                        }
                        is EnsResult.NotFound -> snackbarHostState.showSnackbar(
                            "${ens.name}: no contenthash (${result.reason})",
                        )
                        is EnsResult.Unsupported -> snackbarHostState.showSnackbar(
                            "${ens.name}: unsupported contenthash codec ${result.codec}",
                        )
                        is EnsResult.Error -> snackbarHostState.showSnackbar(
                            "ENS lookup failed: ${result.reason}",
                        )
                    }
                } finally {
                    resolvingEns = false
                }
            }
            return
        }

        val url = UrlParser.toUrl(canonical)
        // The home page is intentionally shown as a blank address bar so
        // the ugly `file:///android_asset/...` URL never surfaces; skip
        // the usual echo-to-address-bar for that one case.
        target.addressBarText = if (url == HOME_URL) "" else url
        target.clearEnsOverride()
        target.loadUrl(url)
    }

    // Kick off the homepage on the initial tab as soon as we're composed.
    // The home page lives in local assets, so it loads without waiting
    // for the embedded node.
    LaunchedEffect(Unit) {
        if (!didInitialLoad) {
            didInitialLoad = true
            submit(tabs.active, tabs.homepageUrl)
        }
        focusAddressTrigger++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                state = state,
                tabCount = tabs.tabs.size,
                resolvingEns = resolvingEns,
                isBookmarked = isBookmarked,
                addressFocused = addressFocused,
                onAddressFocusChanged = { focused ->
                    addressFocused = focused
                    // Losing focus always resets the "has the user typed?"
                    // latch so the next tap starts clean (select-all, no
                    // dropdown) regardless of what was typed last time.
                    if (!focused) addressBarEdited = false
                },
                onAddressEditedChanged = { addressBarEdited = it },
                onSubmit = { submit(state, it) },
                onForward = { state.loadUrl("javascript:history.forward();void(0);") },
                onHome = {
                    submit(state, tabs.homepageUrl)
                    focusAddressTrigger++
                },
                focusAddressTrigger = focusAddressTrigger,
                onToggleBookmark = {
                    val url = state.url
                    if (url.isBlank()) return@TopBar
                    if (isBookmarked) repo.unbookmark(url)
                    else repo.bookmark(url, state.title)
                },
                onOpenNodePanel = { showNodeSheet = true },
                onOpenTabs = { showTabSwitcher = true },
                onOpenHistory = { showHistory = true },
                onOpenBookmarks = { showBookmarks = true },
                onReload = {
                    val url = state.url.ifBlank { state.addressBarText }
                    if (url.isNotBlank()) submit(state, url)
                },
                onNewTab = {
                    val fresh = tabs.newTab()
                    submit(fresh, tabs.homepageUrl)
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                if (state.progress in 0..99 || resolvingEns) {
                    if (resolvingEns) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { state.progress / 100f },
                        )
                    }
                }
            }

            // The WebView fills whatever space is left under the top chrome.
            // When the address bar is focused we overlay the suggestions
            // panel on top of it rather than unmounting the WebView — that
            // keeps the underlying page alive (scroll position, JS timers,
            // media) across focus changes.
            Box(modifier = Modifier.fillMaxSize()) {
                BrowserWebViewHost(
                    tabs = tabs,
                    modifier = Modifier.fillMaxSize(),
                )
                if (addressFocused && addressBarEdited && state.addressBarText.isNotEmpty()) {
                    SuggestionsPanel(
                        repo = repo,
                        query = state.addressBarText,
                        onPick = { submit(state, it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        NodeStatusDot(
            nodeInfo = nodeInfo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(top = 4.dp, end = 6.dp),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) { data -> Snackbar(snackbarData = data) }
    }

    if (showNodeSheet) {
        NodeDetailsDialog(
            nodeInfo = nodeInfo,
            runNodeEnabled = runNodeEnabled,
            onToggleRunNode = onToggleRunNode,
            onDismiss = { showNodeSheet = false },
        )
    }

    if (showTabSwitcher) {
        TabSwitcherScreen(
            tabs = tabs,
            onDismiss = { showTabSwitcher = false },
            onNewTab = {
                val fresh = tabs.newTab()
                submit(fresh, tabs.homepageUrl)
            },
        )
    }

    if (showHistory) {
        HistoryScreen(
            repo = repo,
            onDismiss = { showHistory = false },
            onOpen = { url ->
                showHistory = false
                submit(state, url)
            },
        )
    }

    if (showBookmarks) {
        BookmarksScreen(
            repo = repo,
            onDismiss = { showBookmarks = false },
            onOpen = { url ->
                showBookmarks = false
                submit(state, url)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: BrowserState,
    tabCount: Int,
    resolvingEns: Boolean,
    isBookmarked: Boolean,
    addressFocused: Boolean,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    focusAddressTrigger: Int,
    onToggleBookmark: () -> Unit,
    onOpenNodePanel: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // Local [TextFieldValue] so we can steer the selection (e.g. select
    // all on focus). We keep it in sync with [state.addressBarText],
    // which is the source of truth for submit / external updates
    // (navigation events, ENS resolution).
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.addressBarText,
                selection = TextRange(state.addressBarText.length),
            ),
        )
    }

    // External → internal sync. Fires when the webview updates the
    // displayed URL, when the user hits × (see below), or when submit()
    // rewrites the bar with a canonical / ENS form.
    LaunchedEffect(state.addressBarText) {
        if (fieldValue.text != state.addressBarText) {
            fieldValue = TextFieldValue(
                text = state.addressBarText,
                selection = TextRange(state.addressBarText.length),
            )
        }
    }

    // Grab focus whenever the host bumps the trigger (launch / Home).
    // Skip the very first composition — the initial `0` is just the
    // starting value, not a user-initiated request.
    LaunchedEffect(focusAddressTrigger) {
        if (focusAddressTrigger > 0) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Select-all on focus. Running this in a LaunchedEffect (rather
    // than from `onFocusChanged`) makes sure we apply *after* any
    // tap-to-place-cursor selection the framework might set during the
    // focus-granting gesture — otherwise the cursor can land wherever
    // the user happened to tap inside the pill.
    LaunchedEffect(addressFocused) {
        if (addressFocused && fieldValue.text.isNotEmpty()) {
            fieldValue = fieldValue.copy(
                selection = TextRange(0, fieldValue.text.length),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHome) {
            Icon(Icons.Filled.Home, contentDescription = "Home")
        }

        // Custom-built address pill. We can't use M3's `TextField` here
        // because its filled variant's content padding shifts by a couple
        // of dp between focused / unfocused, which makes the pill appear
        // to grow when tapped. `BasicTextField` + a fixed-height Box gives
        // us a rock-steady 40 dp bubble.
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    val textChanged = newValue.text != fieldValue.text
                    fieldValue = newValue
                    if (textChanged) {
                        state.addressBarText = newValue.text
                        onAddressEditedChanged(true)
                    }
                },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onAddressFocusChanged(it.isFocused) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmit(fieldValue.text) },
                ),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    text = "Search or enter address",
                                    color = onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        // Trailing action — sized to the pill, never pushes it taller.
                        // State machine:
                        //   focused + non-empty text → Clear (×)
                        //   loading                  → spinner
                        //   otherwise                → nothing (bookmark lives in the overflow menu)
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                addressFocused && fieldValue.text.isNotEmpty() -> {
                                    IconButton(
                                        onClick = {
                                            fieldValue = TextFieldValue("")
                                            state.addressBarText = ""
                                            // × is a "start over" gesture — drop the
                                            // suggestions panel and wait for the next
                                            // keystroke before showing it again.
                                            onAddressEditedChanged(false)
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Clear,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                resolvingEns || state.progress in 0..99 -> {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }

        TabsCountButton(
            count = tabCount,
            onClick = onOpenTabs,
            modifier = Modifier.padding(start = 2.dp),
        )

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(if (isBookmarked) "Remove bookmark" else "Add bookmark") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Star
                            else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    enabled = state.url.isNotBlank(),
                    onClick = {
                        menuExpanded = false
                        onToggleBookmark()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Forward") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                        )
                    },
                    enabled = state.canGoForward,
                    onClick = {
                        menuExpanded = false
                        onForward()
                    },
                )
                DropdownMenuItem(
                    text = { Text("New tab") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onNewTab()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reload") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onReload()
                    },
                )
                DropdownMenuItem(
                    text = { Text("History") },
                    leadingIcon = {
                        Icon(Icons.Filled.History, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenHistory()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Bookmarks") },
                    leadingIcon = {
                        Icon(Icons.Filled.Bookmark, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenBookmarks()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Node") },
                    leadingIcon = { Icon(Icons.Filled.Router, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenNodePanel()
                    },
                )
            }
        }
    }
}

/**
 * Opaque panel that overlays the WebView while the address bar is
 * focused, showing bookmarks + recent history that match what the user
 * has typed so far. Picking a row dispatches the canonical URL back to
 * the browser's `submit` path, which hides the keyboard and clears
 * focus (and therefore dismisses this panel).
 */
@Composable
private fun SuggestionsPanel(
    repo: BrowsingRepository,
    query: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-subscribe when the query changes; Room's Flow keeps emitting
    // fresh results if the underlying tables change too.
    val suggestionsFlow = remember(repo, query) { repo.suggestions(query) }
    val suggestions by suggestionsFlow.collectAsState(initial = emptyList())

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        if (suggestions.isEmpty()) {
            Text(
                text = if (query.isBlank()) "No history or bookmarks yet"
                else "No matches for \"$query\"",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp, start = 16.dp, end = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun SuggestionRow(
    suggestion: UrlSuggestion,
    highlight: String,
    onClick: () -> Unit,
) {
    val icon = when (suggestion.source) {
        UrlSuggestion.Source.BOOKMARK -> Icons.Filled.Bookmark
        UrlSuggestion.Source.HISTORY -> Icons.Filled.History
    }
    val displayTitle = suggestion.title.ifBlank { suggestion.url }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (suggestion.source) {
                UrlSuggestion.Source.BOOKMARK -> "Bookmark"
                UrlSuggestion.Source.HISTORY -> "History"
            },
            tint = when (suggestion.source) {
                UrlSuggestion.Source.BOOKMARK -> MaterialTheme.colorScheme.primary
                UrlSuggestion.Source.HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightedText(displayTitle, highlight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = highlightedText(suggestion.url, highlight),
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
 * drop straight into a [Text] composable.
 */
private fun highlightedText(text: String, needle: String): AnnotatedString {
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val haystack = text.lowercase()
        val q = needle.lowercase()
        var i = 0
        while (i <= haystack.length - q.length) {
            val found = haystack.indexOf(q, i)
            if (found < 0) break
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                found,
                found + q.length,
            )
            i = found + q.length
        }
    }
}

@Composable
private fun NodeStatusDot(
    nodeInfo: NodeInfo,
    modifier: Modifier = Modifier,
) {
    val color = when (nodeInfo.status) {
        NodeStatus.Running -> Color(0xFF22C55E)
        NodeStatus.Starting -> Color(0xFFF59E0B)
        NodeStatus.Stopped -> Color(0xFF94A3B8)
        NodeStatus.Error -> Color(0xFFEF4444)
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
private fun NodeDetailsDialog(
    nodeInfo: NodeInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val (color, icon, label) = statusTriple(nodeInfo.status)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Swarm node — $label")
            }
        },
        text = {
            Column {
                // Primary control: run-node on/off. Persisted via
                // DataStore; the service reacts by calling start()/stop().
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Run node", fontWeight = FontWeight.Medium)
                        Text(
                            if (runNodeEnabled) "On — serving bzz://" else "Off — gateway disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = runNodeEnabled,
                        onCheckedChange = onToggleRunNode,
                    )
                }
                Spacer(Modifier.height(8.dp))
                NodeDetailRow("Mode", "ultra-light")
                NodeDetailRow("Peers", nodeInfo.connectedPeers.toString())
                if (nodeInfo.walletAddress.isNotEmpty()) {
                    NodeDetailRow("Wallet", nodeInfo.walletAddress, mono = true)
                }
                val err = nodeInfo.errorMessage
                if (!err.isNullOrBlank()) {
                    NodeDetailRow("Error", err, singleLine = false)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Gateway: ${SwarmNode.GATEWAY_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private data class StatusTriple(
    val color: Color,
    val icon: ImageVector,
    val label: String,
)

private fun statusTriple(status: NodeStatus): StatusTriple = when (status) {
    NodeStatus.Running -> StatusTriple(Color(0xFF22C55E), Icons.Filled.CheckCircle, "Running")
    NodeStatus.Starting -> StatusTriple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…")
    NodeStatus.Stopped -> StatusTriple(Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Stopped")
    NodeStatus.Error -> StatusTriple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error")
}

@Composable
private fun NodeDetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    singleLine: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
