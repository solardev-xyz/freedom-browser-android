package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.UrlSuggestion
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sentinel URL for the home tab. We load `about:blank` into the
 * underlying [android.webkit.WebView] so it stops rendering whatever
 * page was there before, and overlay a native Compose [HomeScreen] on
 * top whenever [BrowserState.url] is empty — which is how the WebView's
 * `onPageStarted` / `onPageFinished` early-returns leave it after an
 * `about:blank` load.
 */
const val HOME_URL: String = "about:blank"

/**
 * Upper bound on how long a `bzz://` submit will sit in "spinner, node
 * warming up" mode before giving up and showing the error page. Covers
 * cold starts on slow devices plus the handful of seconds it takes the
 * bee-lite gateway socket to bind after [NodeStatus.Running] flips on.
 */
private const val NODE_READY_TIMEOUT_MS: Long = 90_000L

/**
 * Suspend until the embedded node is [NodeStatus.Running], or until we
 * hit a terminal state that won't recover on its own:
 *   - [NodeStatus.Error] → give up immediately
 *   - [NodeStatus.Stopped] while the user has the "run node" toggle
 *     off → give up immediately (the node will never start on its own)
 *   - Overall [timeoutMs] budget elapsed → give up
 *
 * Returns `true` if the node reached [NodeStatus.Running] within the
 * budget, `false` otherwise. Caller-supplied [currentNodeInfoProvider]
 * lets the loop observe fresh Compose-snapshot reads of the node info
 * state on each iteration without plumbing a Flow.
 */
private suspend fun awaitNodeRunning(
    currentNodeInfoProvider: () -> NodeInfo,
    runNodeEnabled: Boolean,
    timeoutMs: Long,
): Boolean {
    val started = System.currentTimeMillis()
    while (true) {
        val info = currentNodeInfoProvider()
        when (info.status) {
            NodeStatus.Running -> return true
            NodeStatus.Error -> return false
            NodeStatus.Stopped -> if (!runNodeEnabled) return false
            NodeStatus.Starting -> { /* keep waiting */ }
        }
        if (System.currentTimeMillis() - started >= timeoutMs) return false
        delay(200)
    }
}

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
    val gatewayProbe = remember { GatewayProbe() }
    val context = LocalContext.current
    val repo = remember(context) { BrowsingRepository.get(context) }
    val scope = rememberCoroutineScope()
    // Keep a stable reference to the latest nodeInfo for probe-gating
    // closures launched from submit(). Without rememberUpdatedState, a
    // probe job that outlives the recomposition would capture stale
    // NodeStatus and falsely treat a now-running node as stopped.
    val currentNodeInfo by rememberUpdatedState(nodeInfo)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNode by rememberSaveable { mutableStateOf(false) }
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

    // Gate the hardware back button on "is the user somewhere other
    // than the home overlay?" — exactly mirroring the condition that
    // renders [HomeScreen] below. Keying off `canGoBack` alone isn't
    // safe because that flag is only refreshed in the WebView client's
    // async callbacks: a user who taps an `ens://` bookmark and hits
    // back while the resolver is still running (WebView hasn't even
    // been told to navigate yet) would fall through the disabled
    // handler and minimize the app.
    //
    // When fired we prefer the WebView's own history stack; if there's
    // nothing to pop (mid-probe, or a direct typed URL that failed
    // before the WebView ever navigated), cancel any in-flight resolver
    // work and fall back to a clean home state.
    val isHomeTab = state.url.isBlank() && state.addressBarText.isBlank()
    BackHandler(enabled = !isHomeTab) {
        if (state.canGoBack) {
            state.loadUrl("javascript:history.back();void(0);")
        } else {
            state.cancelPendingProbe()
            resolvingEns = false
            state.navigateHome()
        }
    }

    // Run the peer-warmup probe against a bzz URL, then either load it
    // or fall back to the in-app error page. Returns nothing; any
    // spinner-state bookkeeping is the caller's job (so both the
    // direct-bzz and ens→bzz paths can share the same routine).
    suspend fun gateBzzNavigation(
        target: BrowserState,
        bzzUri: String,
        ensName: String?,
        displayUrl: String,
    ) {
        val loadable = SwarmResolver.toLoadable(bzzUri)
        val headUrl = GatewayUrls.extractBase(loadable)?.prefix ?: loadable

        // Wait for the node to finish booting before firing the probe.
        // Entering a `bzz://` address while the node is still in
        // `Starting` should keep the tab spinner running, not bail
        // straight to the "content unavailable" page. We only give up
        // if the node enters a terminal non-running state (`Error`, or
        // `Stopped` while the user has the "run node" toggle off) or
        // the overall wait budget expires.
        val nodeReady = awaitNodeRunning(
            currentNodeInfoProvider = { currentNodeInfo },
            runNodeEnabled = runNodeEnabled,
            timeoutMs = NODE_READY_TIMEOUT_MS,
        )
        if (!nodeReady) {
            target.clearEnsOverride()
            target.loadUrl(
                ErrorPage.url(
                    errorCode = "ERR_CONNECTION_REFUSED",
                    displayUrl = displayUrl,
                    protocol = "swarm",
                    retryUrl = bzzUri,
                ),
            )
            return
        }

        when (val outcome = gatewayProbe.probe(headUrl)) {
            GatewayProbe.Outcome.Ok -> target.loadUrl(bzzUri, ensName = ensName)
            GatewayProbe.Outcome.Aborted -> { /* superseded by a later submit */ }
            is GatewayProbe.Outcome.Unreachable -> {
                target.clearEnsOverride()
                target.loadUrl(
                    ErrorPage.url(
                        errorCode = "ERR_CONNECTION_REFUSED",
                        displayUrl = displayUrl,
                        protocol = "swarm",
                        retryUrl = bzzUri,
                    ),
                )
            }
            GatewayProbe.Outcome.NotFound, is GatewayProbe.Outcome.Other -> {
                val detail = if (outcome is GatewayProbe.Outcome.Other) {
                    "swarm_content_not_found_${outcome.status}"
                } else "swarm_content_not_found"
                target.clearEnsOverride()
                target.loadUrl(
                    ErrorPage.url(
                        errorCode = detail,
                        displayUrl = displayUrl,
                        protocol = "swarm",
                        retryUrl = bzzUri,
                    ),
                )
            }
        }
    }

    fun submit(target: BrowserState, raw: String) {
        keyboard?.hide()
        // Drop focus synchronously so the IME's input connection is torn
        // down before we overwrite the text below. Otherwise the IME
        // still thinks the committed text is what the user typed ("be")
        // and will clobber our newly-assigned URL on its next
        // round-trip.
        //
        // For the keyboard-Go path this must be bounced via a
        // coroutine delay (see the onGo handler) — clearing focus
        // synchronously while the Enter key event is still in flight
        // lets Compose route it to the next focusable (the Home button)
        // and fire it as a synthetic click.
        focusManager.clearFocus()
        addressBarEdited = false

        // Any new submit supersedes a probe that was still in flight on
        // this tab — otherwise switching URL mid-probe would let the
        // stale probe decide the navigation.
        target.cancelPendingProbe()

        val trimmed = raw.trim()
        // Let the user type the friendly form and re-submit to reload.
        val canonical = target.effectiveFetchUrl(trimmed)

        val ens = EnsInput.parse(canonical)
        if (ens != null) {
            target.addressBarText = "ens://${ens.name}${ens.suffix}"
            resolvingEns = true
            target.pendingProbeJob = scope.launch {
                try {
                    val result = ensResolver.resolveContenthash(ens.name)
                    when (result) {
                        is EnsResult.Ok -> {
                            // Remember hash/cid → name for the whole session
                            // (cross-tab address-bar preservation). Safe for
                            // every protocol; only bzz is actually loadable
                            // today, the rest will plug in under port-plan §2.
                            KnownEnsNames.record(result.uri, ens.name)
                            if (result.protocol == "bzz") {
                                val bzzUri = result.uri + ens.suffix
                                val display = "ens://${ens.name}${ens.suffix}"
                                gateBzzNavigation(target, bzzUri, ens.name, display)
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
                    target.pendingProbeJob = null
                }
            }
            return
        }

        val url = UrlParser.toUrl(canonical)
        // Home is a special non-URL destination — clear the tab, blank
        // the WebView, and let the Compose [HomeScreen] overlay take
        // over. Fall-through into the gateway-probe / plain-load paths
        // below would pointlessly push `about:blank` through them.
        if (url == HOME_URL) {
            target.navigateHome()
            return
        }
        target.addressBarText = url
        target.clearEnsOverride()

        // Direct bzz:// / gateway URLs get the same probe-gated
        // treatment as an ens:// resolution, so a cold node shows the
        // tab spinner instead of Bee's raw 404 JSON.
        val isBzz = url.startsWith("bzz://") ||
            url.startsWith("${SwarmNode.GATEWAY_URL}/bzz/")
        if (isBzz) {
            val bzzUri = SwarmResolver.toDisplay(url)
            resolvingEns = true
            target.pendingProbeJob = scope.launch {
                try {
                    gateBzzNavigation(target, bzzUri, ensName = null, displayUrl = bzzUri)
                } finally {
                    resolvingEns = false
                    target.pendingProbeJob = null
                }
            }
            return
        }

        target.loadUrl(url)
    }

    // Wire the WebView layer's "route this URL through submit" hook up
    // to this screen's [submit] function. The callback lives on
    // [TabsState] so BrowserWebView (which is composed under us) can
    // bounce bzz:// / ens:// link-clicks + error-page "Try Again"
    // back through the same probe gate the top address bar uses.
    DisposableEffect(tabs) {
        tabs.requestSubmit = { tab, url -> submit(tab, url) }
        onDispose { tabs.requestSubmit = null }
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
                peerCount = nodeInfo.connectedPeers,
                resolvingEns = resolvingEns,
                isBookmarked = isBookmarked,
                addressFocused = addressFocused,
                addressBarEdited = addressBarEdited,
                onAddressFocusChanged = { focused ->
                    addressFocused = focused
                    // Losing focus always resets the "has the user typed?"
                    // latch so the next tap starts clean (select-all, no
                    // dropdown) regardless of what was typed last time.
                    if (!focused) addressBarEdited = false
                },
                onAddressEditedChanged = { addressBarEdited = it },
                onSubmit = { text ->
                    // Called from the TextField's IME Go action. Bounce
                    // through a short coroutine delay so the in-flight
                    // Enter key event is delivered to the TextField
                    // (and consumed there) before submit() clears
                    // focus. Otherwise the Enter propagates to the
                    // Home icon button and fires it as a synthetic
                    // click.
                    scope.launch {
                        delay(50)
                        submit(state, text)
                    }
                },
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
                onOpenSettings = { showSettings = true },
                onOpenNode = { showNode = true },
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
                    focusAddressTrigger++
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
                // Home overlay. Rendered whenever the tab hasn't loaded
                // a real page (fresh tab, or user navigated home). The
                // WebView keeps `about:blank` under us — see
                // [BrowserState.navigateHome] — so there's nothing for
                // the user to see through this layer.
                //
                // The check keys off [BrowserState.url], which stays
                // empty while the `about:blank` load is in flight
                // thanks to the ABOUT_BLANK early-returns in
                // [BrowserWebView]. Additionally guarding on
                // `addressBarText.isBlank()` hides the overlay the
                // moment the user hits Go on a typed URL, before the
                // WebView has a chance to fire onPageStarted and
                // populate `state.url`.
                if (isHomeTab) {
                    HomeScreen(
                        repo = repo,
                        onOpen = { submit(state, it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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

    if (showSettings) {
        SettingsScreen(
            repo = repo,
            onClearWebViewData = { tabs.clearWebViewData?.invoke() },
            onDismiss = { showSettings = false },
        )
    }

    // NodeScreen is placed *after* SettingsScreen so it overlays it when
    // the user drills in from Settings → Node details. Back / × dismisses
    // only the node screen and returns them to Settings.
    if (showNode) {
        NodeScreen(
            nodeInfo = nodeInfo,
            runNodeEnabled = runNodeEnabled,
            onToggleRunNode = onToggleRunNode,
            onDismiss = { showNode = false },
        )
    }

    if (showTabSwitcher) {
        TabSwitcherScreen(
            tabs = tabs,
            onDismiss = { showTabSwitcher = false },
            onNewTab = {
                val fresh = tabs.newTab()
                submit(fresh, tabs.homepageUrl)
                focusAddressTrigger++
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
    peerCount: Long,
    resolvingEns: Boolean,
    isBookmarked: Boolean,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    focusAddressTrigger: Int,
    onToggleBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNode: () -> Unit,
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
            // Park the cursor at position 0 so long URLs horizontally
            // scroll to their *start* rather than their tail — the
            // domain is what the user cares about, so keeping e.g.
            // `https://example.com/...` visible beats showing the end
            // of a deep query string with the scheme pushed off-screen.
            fieldValue = TextFieldValue(
                text = state.addressBarText,
                selection = TextRange.Zero,
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

    // Select-all on focus, park cursor at 0 on focus loss.
    //
    // Running the select-all in a LaunchedEffect (rather than from
    // `onFocusChanged`) makes sure we apply *after* any tap-to-place-
    // cursor selection the framework might set during the focus-
    // granting gesture — otherwise the cursor can land wherever the
    // user happened to tap inside the pill.
    //
    // On focus loss we reset the selection to position 0 so long URLs
    // horizontally scroll to their *start* rather than their tail. The
    // domain is what the user cares about, so keeping e.g.
    // `https://example.com/...` visible beats showing the end of a
    // deep path with the scheme pushed off-screen.
    LaunchedEffect(addressFocused) {
        fieldValue = if (addressFocused && fieldValue.text.isNotEmpty()) {
            fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        } else {
            fieldValue.copy(selection = TextRange.Zero)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 1.dp),
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
                    // "via Swarm" badge: the pill grows a Swarm hex mark on
                    // the left whenever the *loaded* page origin is the
                    // local bee-lite gateway — i.e. either a raw `bzz://`
                    // URL or an `ens://` name that resolved to a Swarm
                    // contenthash. Mirrors the `protocol-icon[data-protocol='swarm']`
                    // behavior in freedom-browser's desktop address bar.
                    val showSwarmBadge = state.url.startsWith("bzz://") ||
                        state.url.startsWith("ens://")
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = if (showSwarmBadge) 10.dp else 16.dp,
                                end = 4.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showSwarmBadge) {
                            Icon(
                                painter = painterResource(
                                    baby.freedom.mobile.R.drawable.ic_swarm,
                                ),
                                contentDescription = "via Swarm",
                                tint = Color(0xFFF7931A),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
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
                        //   user is actively editing (focused + typed something) → Clear (×)
                        //   loading                                              → spinner
                        //   otherwise                                            → nothing
                        // The `addressBarEdited` guard matters once the user
                        // submits: submit() resets that flag (to dismiss the
                        // suggestions panel) but intentionally leaves focus
                        // alone, so without this check the × would stay
                        // visible while the page is already loading.
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                addressFocused && addressBarEdited && fieldValue.text.isNotEmpty() -> {
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
            modifier = Modifier.padding(start = 10.dp),
        )

        // We hand-roll the anchor positioning rather than rely on
        // Material3's [DropdownMenu]: its Popup mis-anchors on the very
        // first open (appears pinned to the top of the content area
        // instead of below the hamburger) and only recovers on
        // subsequent opens. Tracking the IconButton's window bounds
        // ourselves via [onGloballyPositioned] and feeding them to a
        // [Popup] + custom [PopupPositionProvider] produces a stable
        // anchor from the first frame.
        var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                anchorBounds = IntRect(
                    r.left.toInt(), r.top.toInt(),
                    r.right.toInt(), r.bottom.toInt(),
                )
            },
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
            if (menuExpanded && anchorBounds != null) {
                Popup(
                    popupPositionProvider = AnchoredBelowRightProvider(anchorBounds!!),
                    onDismissRequest = { menuExpanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp,
                    ) {
                        // [IntrinsicSize.Max] makes the Column size to
                        // its widest child's natural width. Without
                        // this, [DropdownMenuItem] uses fillMaxWidth
                        // internally and the popup grows to the window.
                        Column(
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .padding(vertical = 8.dp),
                        ) {
                DropdownMenuItem(
                    text = { MenuItemLabel(if (isBookmarked) "Remove bookmark" else "Add bookmark") },
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
                    text = { MenuItemLabel("Forward") },
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
                    text = { MenuItemLabel("New tab") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onNewTab()
                    },
                )
                DropdownMenuItem(
                    text = { MenuItemLabel("Reload") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onReload()
                    },
                )
                DropdownMenuItem(
                    text = { MenuItemLabel("History") },
                    leadingIcon = {
                        Icon(Icons.Filled.History, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenHistory()
                    },
                )
                DropdownMenuItem(
                    text = { MenuItemLabel("Bookmarks") },
                    leadingIcon = {
                        Icon(Icons.Filled.Bookmark, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenBookmarks()
                    },
                )
                DropdownMenuItem(
                    text = { MenuItemLabel("Settings") },
                    leadingIcon = {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
                DropdownMenuItem(
                    text = {
                        MenuItemLabel(
                            if (peerCount == 1L) "1 peer" else "$peerCount peers",
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(baby.freedom.mobile.R.drawable.ic_nodes),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenNode()
                    },
                )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Text label for a [DropdownMenuItem] that carries its own trailing
 * padding. Every label wears the same end-inset, so [IntrinsicSize.Max]
 * on the parent Column grows the whole popup past the bare-text width
 * and keeps long labels like "Bookmark" from hugging the right edge.
 */
@Composable
private fun MenuItemLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(end = 32.dp),
    )
}

/**
 * Places a [Popup] flush against an anchor's bottom edge and its right
 * edge (LTR) / left edge (RTL), clamping to the window so the popup
 * never runs off-screen on smaller devices. The anchor bounds are
 * captured by the caller via [Modifier.onGloballyPositioned]; we
 * deliberately ignore the [anchorBounds] argument the framework hands
 * in, since that's the very value that mis-fires on the first open for
 * Material3's default [androidx.compose.material3.DropdownMenu].
 */
private class AnchoredBelowRightProvider(
    private val anchor: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> anchor.right - popupContentSize.width
            LayoutDirection.Rtl -> anchor.left
        }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchor.bottom
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
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

