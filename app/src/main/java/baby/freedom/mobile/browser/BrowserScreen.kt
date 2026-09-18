package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.UrlSuggestion
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
 * Width cap for the floating capsule so it doesn't stretch edge to edge
 * in landscape or on a tablet. On a phone in portrait the cap never
 * kicks in.
 */
private val CHROME_MAX_WIDTH = 640.dp

/**
 * Upper bound on how long a `bzz://` submit will sit in "spinner, node
 * warming up" mode before giving up and showing the error page. Covers
 * cold starts on slow devices plus the handful of seconds it takes the
 * bee-lite gateway socket to bind after [NodeStatus.Running] flips on.
 */
private const val NODE_READY_TIMEOUT_MS: Long = 90_000L

/**
 * Outcome of a node-readiness wait: Running, a terminal "give up"
 * state, or an expired deadline. Kept as an enum rather than a Boolean
 * so both Swarm and IPFS wait loops share the same taxonomy.
 */
private enum class NodeReadyOutcome { Running, Unrecoverable, TimedOut }

/**
 * Who asked for a navigation — and therefore whether the capsule may
 * rest on the destination *before* it commits.
 *
 * [User] is the user naming the destination themselves (typed URL,
 * suggestion, bookmark, history, home, deep link). The pill echoes it
 * straight away, the way Chrome's omnibox shows a typed URL while the
 * previous page is still on screen: the user supplied the string, so
 * reading it back vouches for nobody.
 *
 * [Renderer] is the *current page* asking — an in-page link click or a
 * bare `location.href = 'ens://…'` from page JS, routed here by
 * [BrowserWebView]'s `shouldOverrideUrlLoading`. Writing the
 * destination's label then would let any page park a trusted ENS name
 * over its own still-painted content for the whole
 * resolve → probe → navigate window (up to [NODE_READY_TIMEOUT_MS] on
 * a cold node, and re-armable since each submit cancels the previous
 * probe). So the committed address stays on the page the user is
 * actually looking at, and flips at navigation commit
 * (`onPageStarted`) — exactly like every renderer-initiated navigation
 * the WebView handles without us.
 */
internal enum class SubmitSource { User, Renderer }

/**
 * What a tab's committed address ([BrowserState.addressBarText], which
 * the capsule's bold resting label is derived from) should be once
 * [submitted] has been submitted but *not yet* navigated to: the
 * submitted display string when the user named it, the unchanged
 * [current] address — the page that is still on screen — when the
 * renderer did. See [SubmitSource].
 */
internal fun pendingAddressBarText(
    current: String,
    submitted: String,
    source: SubmitSource,
): String = when (source) {
    SubmitSource.User -> submitted
    SubmitSource.Renderer -> current
}

/**
 * Whether a submit from [source] also ends the address-bar edit — hides
 * the keyboard, drops the field's focus (which re-seeds its buffer from
 * the tab's committed address) and clears the "user has typed" latch.
 *
 * Only the user's own submit does. The teardown exists because the user
 * just hit Go: the destination is settled and the editor has done its
 * job. A *page* submitting through `shouldOverrideUrlLoading`
 * ([SubmitSource.Renderer]) settles nothing about the editor — the user
 * may be halfway through typing somewhere else entirely, and throwing
 * their keyboard, focus and half-typed URL away on a `location.href`
 * the page chose is the page editing the browser's chrome. Looped, it
 * wipes every keystroke as it lands (#35).
 */
internal fun submitEndsAddressEditing(source: SubmitSource): Boolean =
    source == SubmitSource.User

/**
 * Whether a submit from [incoming] may cancel the probe already in
 * flight on the tab, which [pending] asked for (`null` when the tab has
 * no probe running).
 *
 * A submit normally supersedes the probe before it — switching URL
 * mid-probe must not let the stale probe decide the navigation. The
 * exception is the page cancelling the *user*: an ENS resolve plus a
 * cold-node gateway probe is a window of up to
 * [NODE_READY_TIMEOUT_MS] plus the probe's own budget, and a page that
 * loops `location.href='ens://…'` lands inside it on every tick. If
 * each of those ticks cancelled the user's probe, the typed navigation
 * away from the page would never complete and the user would be pinned
 * there (#35). So a renderer submit waits its turn; a user submit
 * always wins, including over their own earlier one.
 */
internal fun submitSupersedesPendingProbe(
    pending: SubmitSource?,
    incoming: SubmitSource,
): Boolean = pending != SubmitSource.User || incoming == SubmitSource.User

/**
 * A tab's committed address ([BrowserState.addressBarText]) after the
 * user hits **Stop**.
 *
 * [pendingAddressBarText] lets a *user-named* destination into the bar
 * before it commits — the user supplied the string, so echoing it back
 * vouches for nobody. Stop cancels that navigation, and what the bar
 * would otherwise be left holding is the name of a site that never
 * loaded, in bold, over the previous site's content: the capsule
 * vouching for a page the user cannot see, with a reload control beside
 * it that would fetch the *old* page. Reverting to [committedUrl] —
 * which [BrowserWebView] writes in the same display form at navigation
 * commit, and which [finishedLoadIsCurrent] keeps an aborted load's
 * `onPageFinished` from overwriting a beat later — puts label, page and
 * reload target back in agreement, the way Chrome and Safari revert the
 * omnibox on stop.
 *
 * A blank [committedUrl] means there is nothing committed to fall back
 * to (a fresh tab's first navigation, stopped mid-resolve). The pending
 * address stays: it is the only thing left that says what the user asked
 * for and the only thing reload could re-try, and no *other* page's
 * content is on screen for it to misdescribe.
 *
 * After a commit this is a no-op by construction — `onPageStarted` has
 * already written the same string into both — so Stop needs no separate
 * "has it committed yet?" test.
 */
internal fun addressBarTextAfterStop(committedUrl: String, pending: String): String =
    if (committedUrl.isNotBlank()) committedUrl else pending

/**
 * Whether a keyboard that has just gone away should take the address
 * bar's focus — and with it the capsule's editing morph — with it.
 *
 * The IME swallows the back press (or swipe-down) that closes it, so
 * the app never sees the gesture; all it observes is the IME inset
 * dropping to zero while the field still holds focus. Without this the
 * capsule would stay in its 64 dp editor form, chrome-less, with the
 * keyboard already down.
 *
 * [keyboardWasSeen] is what keeps it from firing on the *way in*:
 * focus arrives several frames before the IME animates up, and on a
 * device with a hardware keyboard it may never come up at all. Only a
 * keyboard that was observed open counts as one the user dismissed.
 */
internal fun imeDismissalEndsEditing(
    addressFocused: Boolean,
    keyboardVisible: Boolean,
    keyboardWasSeen: Boolean,
): Boolean = addressFocused && !keyboardVisible && keyboardWasSeen

/**
 * Classify a submitted URL as a content-addressed (bzz / ipfs / ipns)
 * destination that should go through the probe-gated navigation path,
 * or return `null` for "treat as a plain URL". Accepts both the
 * canonical scheme form (e.g. `ipfs://bafy…`) and a pre-rewritten
 * gateway URL (e.g. `http://127.0.0.1:58312/ipfs/bafy…`) — the latter
 * happens when a user pastes a gateway link from another browser or
 * when an in-page click routes through [BrowserWebView]'s
 * `shouldOverrideUrlLoading`.
 */
private fun contentUriForSubmit(url: String): String? {
    if (url.startsWith("bzz://") ||
        url.startsWith("ipfs://") ||
        url.startsWith("ipns://")
    ) return url
    val display = Gateways.toDisplay(url)
    return if (display != url) display else null
}

/**
 * Which gateway logo (if any) to show in the leading end of the
 * address-bar pill. Mirrors the CSS selector matrix in
 * `freedom-browser/src/renderer/styles/toolbar.css`:
 *
 *   `data-protocol='swarm'` → Swarm hex logo
 *   `data-protocol='ipfs' | 'ipns'` → IPFS cube logo
 *
 * For `bzz://` / `ipfs://` / `ipns://` URLs the protocol is obvious
 * from [BrowserState.url]. For an ENS-displayed page (bare `name.eth`,
 * or an `ens://` string from an older session's history) we look at the
 * active display override — its `baseUrl` is the loopback gateway that
 * actually served the page, so we can tell a Swarm-resolved name from
 * an IPFS-resolved one without re-running the ENS lookup.
 */
internal data class ProtocolBadge(
    @androidx.annotation.DrawableRes val drawableRes: Int,
    val contentDescription: String,
)

internal fun protocolBadgeFor(state: BrowserState): ProtocolBadge? {
    val url = state.url
    if (url.startsWith("bzz://")) return SWARM_BADGE
    if (url.startsWith("ipfs://") || url.startsWith("ipns://")) return IPFS_BADGE
    if (url.startsWith("ens://") || EnsInput.looksLikeEns(url)) {
        // The session registry knows which protocol the name's
        // contenthash resolved to — recorded by the submit flow before
        // any ENS navigation reaches the WebView.
        val name = EnsInput.parse(url)?.name ?: return SWARM_BADGE
        return when (KnownEnsNames.protocolFor(name)) {
            "ipfs", "ipns" -> IPFS_BADGE
            else -> SWARM_BADGE
        }
    }
    return null
}

private val SWARM_BADGE = ProtocolBadge(
    drawableRes = baby.freedom.mobile.R.drawable.ic_swarm,
    contentDescription = "via Swarm",
)

private val IPFS_BADGE = ProtocolBadge(
    drawableRes = baby.freedom.mobile.R.drawable.ic_ipfs,
    contentDescription = "via IPFS",
)

/**
 * Suspend until the Swarm node is [NodeStatus.Running], or until we hit
 * a terminal state that won't recover on its own:
 *   - [NodeStatus.Error] → [NodeReadyOutcome.Unrecoverable]
 *   - [NodeStatus.Stopped] while the user has the "run node" toggle
 *     off → [NodeReadyOutcome.Unrecoverable]
 *   - Overall [timeoutMs] budget elapsed → [NodeReadyOutcome.TimedOut]
 *
 * Caller-supplied [currentNodeInfoProvider] lets the loop observe fresh
 * Compose-snapshot reads of the node info state on each iteration
 * without plumbing a Flow.
 */
private suspend fun awaitSwarmRunning(
    currentNodeInfoProvider: () -> NodeInfo,
    runNodeEnabled: Boolean,
    timeoutMs: Long,
): NodeReadyOutcome {
    val started = System.currentTimeMillis()
    while (true) {
        val info = currentNodeInfoProvider()
        when (info.status) {
            NodeStatus.Running -> return NodeReadyOutcome.Running
            NodeStatus.Error -> return NodeReadyOutcome.Unrecoverable
            NodeStatus.Stopped -> if (!runNodeEnabled) return NodeReadyOutcome.Unrecoverable
            NodeStatus.Starting -> { /* keep waiting */ }
        }
        if (System.currentTimeMillis() - started >= timeoutMs) return NodeReadyOutcome.TimedOut
        delay(200)
    }
}

/**
 * IPFS analogue of [awaitSwarmRunning]. The IPFS node lives on the same
 * `:node` process as Swarm — so if the user has turned the process off
 * (`runNodeEnabled` false), the only honest answer is "unrecoverable".
 */
private suspend fun awaitIpfsRunning(
    currentIpfsInfoProvider: () -> IpfsInfo,
    runNodeEnabled: Boolean,
    timeoutMs: Long,
): NodeReadyOutcome {
    val started = System.currentTimeMillis()
    while (true) {
        val info = currentIpfsInfoProvider()
        when (info.status) {
            IpfsStatus.Running -> return NodeReadyOutcome.Running
            IpfsStatus.Error -> return NodeReadyOutcome.Unrecoverable
            IpfsStatus.Stopped -> if (!runNodeEnabled) return NodeReadyOutcome.Unrecoverable
            IpfsStatus.Starting -> { /* keep waiting */ }
        }
        if (System.currentTimeMillis() - started >= timeoutMs) return NodeReadyOutcome.TimedOut
        delay(200)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserScreen(
    nodeInfo: NodeInfo,
    ipfsInfo: IpfsInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    onEnsureIpfsStarted: () -> Unit,
    onIpfsToggle: (Boolean) -> Unit,
    initialUrl: String = HOME_URL,
    deepLinkUrl: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    onRecoverNodes: () -> Unit = {},
) {
    val tabs = remember { TabsState(homepage = initialUrl) }
    // Shared with the request interceptor (which resolves
    // `<name>.ens.…` virtual hosts) so both sides use one cache.
    val ensResolver = Gateways.ensResolver
    val gatewayProbe = remember { GatewayProbe() }
    val context = LocalContext.current
    val repo = remember(context) { BrowsingRepository.get(context) }
    val scope = rememberCoroutineScope()
    // Keep a stable reference to the latest nodeInfo for probe-gating
    // closures launched from submit(). Without rememberUpdatedState, a
    // probe job that outlives the recomposition would capture stale
    // NodeStatus and falsely treat a now-running node as stopped.
    val currentNodeInfo by rememberUpdatedState(nodeInfo)
    val currentIpfsInfo by rememberUpdatedState(ipfsInfo)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNode by rememberSaveable { mutableStateOf(false) }
    var showTabSwitcher by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    // Intentionally NOT `rememberSaveable`: rotation and the other
    // declared `configChanges` don't recreate the Activity (see the
    // manifest), but process death or an undeclared config change still
    // does. In that case `tabs` is rebuilt as a fresh blank tab and we
    // need to re-submit the homepage into it. If this survived
    // recreation the load would be suppressed and the tab would render
    // blank.
    var didInitialLoad by remember { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    // Suggestions should only appear once the user has actively changed
    // the address-bar text. Tapping the pill (which select-alls the
    // current URL) must NOT flash a dropdown — the user just wants to
    // replace the URL with a fresh one.
    var addressBarEdited by remember { mutableStateOf(false) }
    // What the user has typed into the pill during the current edit.
    // Deliberately *not* [BrowserState.addressBarText]: that field is
    // the tab's committed address (what the WebView loaded, or what was
    // submitted) and everything that describes the current site — the
    // resting domain label, the home overlay, reload — reads it. Typed
    // text lives here until it is submitted.
    var addressQuery by remember { mutableStateOf("") }
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
            state.navigateHome()
        }
    }

    // Run the peer-warmup probe against a bzz:// / ipfs:// / ipns://
    // URL, then either load it or fall back to the in-app error page.
    // Returns nothing; any spinner-state bookkeeping is the caller's
    // job (so the direct-content and ens→content paths can share the
    // same routine).
    //
    // For the demo surprise — "look, vitalik.eth also works" — the
    // error page's `protocol` hint always resolves to whichever of the
    // three schemes the URI actually used, so a failure still signals
    // clearly to the user which network we were trying. A *successful*
    // IPFS load leaves no visible IPFS trace in the chrome (the swarm
    // badge logic below deliberately doesn't differentiate).
    // [contentUri] is what the probe checks against the local gateway
    // (`bzz://<hash>…` etc.); [loadUri] is what the tab ultimately
    // navigates to — for ENS names that's the `ens://<name>…` form, so
    // the WebView loads the *name-derived* virtual origin and the
    // site's storage survives contenthash updates.
    suspend fun gateGatewayNavigation(
        target: BrowserState,
        contentUri: String,
        displayPrefix: String?,
        displayUrl: String,
        loadUri: String = contentUri,
    ) {
        val isIpfs = contentUri.startsWith("ipfs://") || contentUri.startsWith("ipns://")
        val protocolHint = when {
            contentUri.startsWith("bzz://") -> "swarm"
            contentUri.startsWith("ipns://") -> "ipns"
            contentUri.startsWith("ipfs://") -> "ipfs"
            else -> "swarm"
        }

        fun showError(errorCode: String) {
            target.clearEnsOverride()
            target.loadUrl(
                ErrorPage.url(
                    errorCode = errorCode,
                    displayUrl = displayUrl,
                    protocol = protocolHint,
                    retryUrl = contentUri,
                ),
            )
        }

        // Wait for the right node to finish booting before firing the
        // probe. Entering an address while the node is still in
        // `Starting` should keep the tab spinner running, not bail
        // straight to the "content unavailable" page.
        //
        // The IPFS node is lazy-started — the first IPFS navigation
        // in a given `:node` process kicks off `ensureIpfsStarted()`
        // here so we don't pay the IPFS bootstrap cost on cold app
        // launch. Idempotent on the service side; safe to call on
        // every IPFS navigation.
        val readiness = if (isIpfs) {
            onEnsureIpfsStarted()
            awaitIpfsRunning(
                currentIpfsInfoProvider = { currentIpfsInfo },
                runNodeEnabled = runNodeEnabled,
                timeoutMs = NODE_READY_TIMEOUT_MS,
            )
        } else {
            awaitSwarmRunning(
                currentNodeInfoProvider = { currentNodeInfo },
                runNodeEnabled = runNodeEnabled,
                timeoutMs = NODE_READY_TIMEOUT_MS,
            )
        }
        if (readiness != NodeReadyOutcome.Running) {
            showError("ERR_CONNECTION_REFUSED")
            return
        }

        // Probe the gateway directly (`http://127.0.0.1:…`) — the
        // WebView gets the virtual-origin URL, but readiness is a
        // question for the node itself. Resolved after the node flips
        // to Running, in case ipfsBase was still empty before.
        val resolved = Gateways.toGatewayUrl(contentUri)
        val headUrl = GatewayUrls.extractBase(resolved)?.prefix ?: resolved

        when (val outcome = gatewayProbe.probe(headUrl)) {
            GatewayProbe.Outcome.Ok -> target.loadUrl(loadUri, displayPrefix = displayPrefix)
            GatewayProbe.Outcome.Aborted -> { /* superseded by a later submit */ }
            is GatewayProbe.Outcome.Unreachable -> showError("ERR_CONNECTION_REFUSED")
            GatewayProbe.Outcome.NotFound, is GatewayProbe.Outcome.Other -> {
                val detail = if (outcome is GatewayProbe.Outcome.Other) {
                    "swarm_content_not_found_${outcome.status}"
                } else "swarm_content_not_found"
                showError(detail)
            }
        }
    }

    fun submit(
        target: BrowserState,
        raw: String,
        source: SubmitSource = SubmitSource.User,
    ) {
        // A page submitting on top of a navigation the *user* asked for
        // is ignored outright: it may neither cancel their probe nor
        // start one of its own on the same tab (#35, see
        // [submitSupersedesPendingProbe]). Checked before anything else
        // so a looping `location.href` touches no tab state at all.
        if (!submitSupersedesPendingProbe(target.pendingProbeSource, source)) return

        // The editor is the user's, and only their own submit closes it
        // (#35, see [submitEndsAddressEditing]).
        if (submitEndsAddressEditing(source)) {
            keyboard?.hide()
            // Drop focus synchronously so the IME's input connection is
            // torn down before we overwrite the text below. Otherwise
            // the IME still thinks the committed text is what the user
            // typed ("be") and will clobber our newly-assigned URL on
            // its next round-trip.
            //
            // For the keyboard-Go path this must be bounced via a
            // coroutine delay (see the onGo handler) — clearing focus
            // synchronously while the Enter key event is still in flight
            // lets Compose route it to the next focusable (the Home
            // button) and fire it as a synthetic click.
            focusManager.clearFocus()
            addressBarEdited = false
        }

        // Any new submit supersedes a probe that was still in flight on
        // this tab — otherwise switching URL mid-probe would let the
        // stale probe decide the navigation.
        target.cancelPendingProbe()

        val trimmed = raw.trim()
        // Let the user type the friendly form and re-submit to reload.
        val canonical = target.effectiveFetchUrl(trimmed)

        // Generic ENS (`name.eth`, or the `ens://` compat alias which is
        // normalized away) follows whatever the contenthash points at;
        // scheme-constrained ENS (`bzz://name.eth`, `ipfs://name.eth`,
        // `ipns://name.eth`) also resolves the name but *requires* the
        // contenthash to match the scheme — ENS has a single contenthash,
        // so the scheme asserts rather than selects. Raw ids
        // (`bzz://<hex>`, `ipfs://<cid>`) never parse as either and stay
        // on the direct gateway path below.
        val ens = EnsInput.parse(canonical)
        val constrained = if (ens == null) EnsInput.parseConstrained(canonical) else null
        if (ens != null || constrained != null) {
            val name = ens?.name ?: constrained!!.name
            val suffix = ens?.suffix ?: constrained!!.suffix
            val requiredProtocol = constrained?.protocol
            // Canonical display: bare `name.eth` for generic ENS, the
            // typed scheme form for constrained ENS (it carries intent).
            // Error-page retries always use the routable scheme forms —
            // a bare `name.eth` inside the file:// error page would
            // resolve as a relative path.
            val displayPrefix =
                if (requiredProtocol != null) "$requiredProtocol://$name" else name
            val ensDisplay = "$displayPrefix$suffix"
            val retryDisplay =
                if (requiredProtocol != null) ensDisplay else "ens://$name$suffix"
            // Only a destination the *user* named earns the pill before
            // it commits — a page that navigated us here doesn't get to
            // dress itself in the name it is resolving. See
            // [SubmitSource].
            target.addressBarText =
                pendingAddressBarText(target.addressBarText, ensDisplay, source)
            target.resolving = true

            fun ensError(errorCode: String, detail: String, retryUrl: String = retryDisplay) {
                target.clearEnsOverride()
                target.loadUrl(
                    ErrorPage.url(
                        errorCode = errorCode,
                        displayUrl = ensDisplay,
                        protocol = "ens",
                        retryUrl = retryUrl,
                        detail = detail,
                    ),
                )
            }

            val ensProbe = scope.launch {
                try {
                    val result = ensResolver.resolveContenthash(name)
                    // Everything below this line writes tab state —
                    // `loadUrl` alone cancels whatever probe the tab is
                    // waiting on now, which is how a cancelled probe
                    // would walk straight past
                    // [submitSupersedesPendingProbe] and navigate on
                    // behalf of a submit the user already superseded
                    // (#51). The resolver propagates cancellation
                    // itself; this is the tab's own last word on it.
                    ensureActive()
                    when (result) {
                        is EnsResult.Ok -> {
                            // Remember hash/cid → name for the whole session
                            // (cross-tab address-bar preservation). Safe for
                            // every protocol — bzz, ipfs, and ipns all round-
                            // trip through [Gateways] + [DisplayUrl] now.
                            KnownEnsNames.record(result.uri, name)
                            if (requiredProtocol != null && result.protocol != requiredProtocol) {
                                // Retry with the generic ens:// form: the
                                // same constrained URL would fail forever,
                                // but the content itself is loadable.
                                ensError(
                                    errorCode = "ens_wrong_protocol",
                                    detail = "$name resolves to ${result.protocol}:// content, " +
                                        "not $requiredProtocol://",
                                    retryUrl = "ens://$name$suffix",
                                )
                            } else if (result.protocol == "bzz" ||
                                result.protocol == "ipfs" ||
                                result.protocol == "ipns"
                            ) {
                                gateGatewayNavigation(
                                    target = target,
                                    contentUri = result.uri + suffix,
                                    displayPrefix = displayPrefix,
                                    displayUrl = ensDisplay,
                                    // Navigate the *name-derived* origin —
                                    // the interceptor re-resolves the name
                                    // (via the registry entry recorded
                                    // above), and per-site storage sticks
                                    // to the name across content updates.
                                    loadUri = "ens://$name$suffix",
                                )
                            } else {
                                ensError(
                                    errorCode = "ens_unsupported_codec",
                                    detail = "${result.protocol}:// (${result.decoded.take(16)}…)",
                                )
                            }
                        }
                        is EnsResult.NotFound ->
                            ensError("ens_not_found", detail = result.reason)
                        is EnsResult.Unsupported ->
                            ensError("ens_unsupported_codec", detail = "codec ${result.codec}")
                        is EnsResult.Error ->
                            ensError("ens_lookup_failed", detail = result.reason)
                    }
                } finally {
                    target.resolving = false
                    target.finishPendingProbe(coroutineContext.job)
                }
            }
            target.beginPendingProbe(ensProbe, source)
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
        // Same rule as the ENS branch above: a renderer-initiated
        // `bzz://` / `ipfs://` click waits for the commit before the
        // pill describes where it is going.
        target.addressBarText = pendingAddressBarText(target.addressBarText, url, source)
        target.clearEnsOverride()

        // Direct content-addressed URLs (bzz://, ipfs://, ipns://, or
        // their loaded gateway form) get the same probe-gated treatment
        // as an ens:// resolution — a cold node shows the tab spinner
        // instead of the raw gateway's 404 page.
        val contentUri = contentUriForSubmit(url)
        if (contentUri != null) {
            target.resolving = true
            val contentProbe = scope.launch {
                try {
                    gateGatewayNavigation(
                        target = target,
                        contentUri = contentUri,
                        displayPrefix = null,
                        displayUrl = contentUri,
                    )
                } finally {
                    target.resolving = false
                    target.finishPendingProbe(coroutineContext.job)
                }
            }
            target.beginPendingProbe(contentProbe, source)
            return
        }

        target.loadUrl(url)
    }

    // Wire the WebView layer's "route this URL through submit" hook up
    // to this screen's [submit] function. The callback lives on
    // [TabsState] so BrowserWebView (which is composed under us) can
    // bounce bzz:// / ens:// link-clicks + error-page "Try Again"
    // back through the same probe gate the top address bar uses.
    //
    // Everything arriving on this hook comes out of
    // `shouldOverrideUrlLoading`, i.e. the page asked — never the user
    // directly — so it submits as [SubmitSource.Renderer] and the pill
    // keeps describing the page still on screen until the new one
    // commits.
    DisposableEffect(tabs) {
        tabs.requestSubmit = { tab, url -> submit(tab, url, SubmitSource.Renderer) }
        tabs.requestNodeRecovery = onRecoverNodes
        onDispose {
            tabs.requestSubmit = null
            tabs.requestNodeRecovery = null
        }
    }

    // Kick off the homepage on the initial tab as soon as we're composed.
    // The home page is now a native Compose overlay (see [HomeScreen]),
    // so this just primes the tab state without actually loading
    // anything over the network. We deliberately don't auto-focus the
    // address bar here — the home surface should be the first thing
    // the user sees, not an already-open keyboard.
    LaunchedEffect(Unit) {
        if (!didInitialLoad) {
            didInitialLoad = true
            submit(tabs.active, tabs.homepageUrl)
        }
    }

    // An App Link that arrives while we're already running (see
    // MainActivity.onNewIntent). Cold start doesn't come through here —
    // it's the [initialUrl] above — so a link tapped now is a second
    // destination and gets its own tab rather than replacing whatever
    // the user was reading. [onDeepLinkHandled] clears the pending URL
    // so a config change doesn't re-open it.
    LaunchedEffect(deepLinkUrl) {
        val url = deepLinkUrl ?: return@LaunchedEffect
        // Whatever full-screen overlay was up would otherwise hide the
        // tab we just opened.
        showSettings = false
        showNode = false
        showTabSwitcher = false
        showHistory = false
        showBookmarks = false
        submit(tabs.newTab(), url)
        onDeepLinkHandled()
    }

    // The chrome is a floating capsule layered *over* an edge-to-edge
    // page — it no longer takes a horizontal slice out of the layout.
    //
    // [chromeInsets] keeps the capsule clear of the navigation / gesture
    // inset, any display cutout, and the keyboard, so it rides up above
    // the IME when the address field takes focus (the manifest asks for
    // `adjustResize`; with edge-to-edge the window itself never
    // resizes, Compose's inset padding does the work).
    //
    // [contentInsets] is deliberately *not* the same set: the page runs
    // behind the navigation bar (that's what makes the chrome read as
    // floating) but still starts below the status bar and stops above
    // the keyboard. Keeping the IME inset on the content is what shrinks
    // the WebView when the keyboard opens, which is the signal
    // [BrowserWebViewHost]'s scroll-into-view listener keys off.
    val chromeInsets = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    val contentInsets = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .union(WindowInsets.ime)

    // While the keyboard is up we stop the content short of the capsule
    // instead of letting it run underneath.
    //
    // This is what keeps PR #25's WebView scroll-into-view fix working.
    // Chromium scrolls a focused field just clear of the *viewport*
    // bottom, and `android.webkit.WebView` gives an embedder no way to
    // inset that viewport (it ignores View padding outright — verified
    // on the freedom AVD, see the note on the page layer below). With a
    // full-bleed WebView a field at the very end of a document has no
    // scroll room left and would sit behind the capsule, unreadable
    // while typing. Shrinking the WebView for the capsule whenever the
    // IME is open restores exactly the geometry PR #25 verified, and
    // costs nothing visually: the strip between keyboard and capsule is
    // the one place where seeing the page through the chrome buys
    // least.
    val density = LocalDensity.current
    val navInsetPx = WindowInsets.systemBars.getBottom(density)
    val imeInsetPx = WindowInsets.ime.getBottom(density)
    val keyboardVisible = imeInsetPx > 0

    // Dismissing the keyboard is dismissing the editor.
    //
    // The system back press (and the swipe-down gesture) that closes an
    // open IME is consumed by the IME itself, so neither the
    // [BackHandler] above nor the address field hears about it: focus
    // survives, and the capsule would stay stretched into its full
    // editing morph — no Back, no tab counter, no overflow, because at
    // `editProgress == 1` those controls aren't composed at all — with
    // the keyboard already gone. The next back press would then reach
    // the [BackHandler] and navigate page history underneath a still-
    // open editor. Dropping focus when the keyboard goes puts the
    // capsule back at rest on that first press, which is also what the
    // tap-outside catcher below already does.
    //
    // Latched on having actually *seen* the keyboard: focus lands
    // several frames before the IME animates in, and a device driven by
    // a hardware keyboard may never raise one at all — in neither case
    // may we bounce the focus the user just asked for (see
    // [imeDismissalEndsEditing]).
    var keyboardSeenWhileEditing by remember { mutableStateOf(false) }
    LaunchedEffect(addressFocused, keyboardVisible) {
        if (imeDismissalEndsEditing(
                addressFocused = addressFocused,
                keyboardVisible = keyboardVisible,
                keyboardWasSeen = keyboardSeenWhileEditing,
            )
        ) {
            focusManager.clearFocus()
        }
        keyboardSeenWhileEditing = addressFocused && keyboardVisible
    }

    // The capsule's geometry is driven by exactly two 0→1 fractions,
    // and they are one model rather than two (see [capsuleDrawnHeight]):
    // the capsule has three heights — 32 dp compact, 56 dp resting,
    // 64 dp editing — and these two numbers say which.
    //
    // Both spring on the expressive motion scheme's spatial spec rather
    // than a hand-rolled curve: it's the one every other M3 Expressive
    // component in the app moves on, and it overshoots very slightly, so
    // the bar reads as settling rather than snapping.

    // The editing morph. Focusing the field doesn't swap in a different
    // composable — the same capsule interpolates size and position,
    // which is what makes the editor read as the bar transforming rather
    // than a new screen. It drives the capsule's height and the address
    // pill's height inside [BottomToolbar], and its side margins here.
    val editProgress by animateFloatAsState(
        targetValue = if (addressFocused) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "capsuleEditMorph",
    )

    // Compact-on-scroll (#30). The tab's WebView feeds
    // [CapsuleCollapseState] its own scroll deltas — Chromium's WebView
    // doesn't report scrolling up Compose's nested-scroll chain, so
    // there is nothing else to key off — and the chrome interpolates
    // between its resting and compact geometry from the Boolean that
    // comes out.
    //
    // Three states override it back to resting-or-editing, because in
    // all three the bar is the thing the user is dealing with rather
    // than the page: the address field has focus (the capsule is
    // morphing into the editor, and **editing always wins over
    // compact**), the keyboard is up, or the tab is on the home surface
    // (nothing is scrolling). Holding it at 0 under focus is also what
    // lets [capsuleDrawnHeight] compose the two fractions instead of
    // arbitrating between them.
    val capsuleCollapsed =
        state.capsuleCollapse.collapsed && !addressFocused && !keyboardVisible && !isHomeTab
    val collapseFraction by animateFloatAsState(
        targetValue = if (capsuleCollapsed) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "capsuleCollapse",
    )

    // The slot the capsule occupies — resting height, growing only for
    // the editing morph. Compacting shrinks the capsule *inside* this,
    // so a flick moves the bar and nothing else: not the snackbar below,
    // not the page behind it. That was stage 2's rule when a progress
    // strip still sat above the capsule, and it holds more strictly now
    // that the strip is gone and progress is drawn on the capsule's own
    // edge.
    val capsuleSlot = capsuleSlotHeight(editProgress)
    val capsuleSideMargin =
        lerp(CapsuleSideMargin, CapsuleEditingSideMargin, editProgress)

    // Keyed on the *address bar's* focus, not on the keyboard: the IME
    // also comes up for a form field inside the page, and the capsule
    // stays at its resting height for that — reserving the editing
    // height there would leave an 8 dp band of background between the
    // WebView and the capsule.
    //
    // Deliberately the *settled* editing height rather than the animated
    // one: this padding shrinks the WebView, and re-laying Chromium out
    // on every frame of the morph is far more expensive than the 8 dp it
    // would buy — and the keyboard is on its way over that strip anyway.
    val capsuleFootprint =
        (if (addressFocused) CapsuleEditingHeight else CapsuleHeight) + CapsuleBottomMargin
    val contentBottomReserve = if (keyboardVisible) capsuleFootprint else 0.dp

    // How much of the content area the capsule still covers once that
    // reserve is applied — zero while the keyboard is up, its own
    // footprint plus the navigation inset the content draws behind
    // otherwise. Native surfaces ([HomeScreen], [SuggestionsPanel]) pad
    // by it so their last row stays clear of the chrome.
    val capsuleOverlap = if (keyboardVisible) 0.dp
    else capsuleFootprint + with(density) { navInsetPx.toDp() }

    // "Tap anywhere outside the floating toolbar to dismiss the
    // keyboard". We intercept presses on the Initial pass so we see
    // them before the WebView/HomeScreen children, but we never
    // consume — the child still receives the tap normally. Clearing
    // focus is a no-op when nothing is focused, so the common case
    // (address bar idle) pays only the cost of the gesture loop.
    //
    // Applied to every band of the screen that isn't the pill itself:
    // the page area, the progress strip, and the chrome background
    // around the pill (side gutters + the padding under it).
    val dismissKeyboardOnTap = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The page fills the whole content area and keeps drawing
        // underneath the capsule, so the site is visible around and
        // faintly beneath it.
        //
        // The brief also wants the page's *viewport* inset by the
        // capsule's footprint (the way Chrome insets for its bottom
        // controls). Chromium's `android.webkit.WebView` gives an
        // embedder no way to do that: it ignores `View` padding
        // outright — neither the layout viewport, the scroll extent
        // nor the clip rect move (verified on the freedom AVD with a
        // 600 px bottom padding and a `position: fixed; bottom: 0`
        // probe page: the render was pixel-identical). Browser-
        // controls insets exist inside Chromium but aren't exposed.
        // So the inset is applied to the surfaces we *do* control —
        // [HomeScreen] and [SuggestionsPanel] below, plus the
        // WebView itself whenever the keyboard is up (see
        // [contentBottomReserve]) — and page-footer reachability at
        // rest is left to the compact-on-scroll state from stage 2
        // (#30), which is how Safari handles it too.
        //
        // When the address bar is focused we overlay the suggestions
        // panel on top of it rather than unmounting the WebView — that
        // keeps the underlying page alive (scroll position, JS timers,
        // media) across focus changes.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets)
                .padding(bottom = contentBottomReserve)
                .then(dismissKeyboardOnTap),
        ) {
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
                    bottomContentPadding = capsuleOverlap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (addressFocused && addressBarEdited && addressQuery.isNotEmpty()) {
                SuggestionsPanel(
                    repo = repo,
                    query = addressQuery,
                    onPick = { submit(state, it) },
                    bottomContentPadding = capsuleOverlap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The floating chrome overlay: just the capsule now. Page-load
        // progress used to be a wavy strip in its own fixed-height slot
        // directly above it; it is drawn along the capsule's own edge
        // instead (see [BottomToolbar]), which is both what the brief
        // asks for and strictly better at "no layout shifts" — an
        // overlay on fixed geometry can't move anything, whereas the
        // strip's reserved slot was 14 dp of permanently dead band.
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            // Tap-to-dismiss catcher for the whole chrome band — the
            // capsule's own gutters, the side margins and the padding
            // beneath it. *Behind* the capsule,
            // not around it: taps that land on the address pill hit it
            // first and never reach the catcher, so tapping inside the
            // field doesn't bounce its own focus.
            //
            // Armed only while the keyboard is up. With the keyboard
            // down this band is page — the capsule floats over live
            // content now — and an always-on catcher would swallow taps
            // on links sitting under the chrome.
            if (keyboardVisible) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(dismissKeyboardOnTap),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(chromeInsets)
                    // The side margins interpolate with the morph, so
                    // the capsule *reaches* towards the screen edges as
                    // it opens into the editor instead of a wider bar
                    // being swapped in underneath the old one.
                    .padding(
                        start = capsuleSideMargin,
                        end = capsuleSideMargin,
                        bottom = CapsuleBottomMargin,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BottomToolbar(
                    state = state,
                    tabCount = tabs.tabs.size,
                    nodeInfo = nodeInfo,
                    isBookmarked = isBookmarked,
                    addressFocused = addressFocused,
                    addressBarEdited = addressBarEdited,
                    editProgress = editProgress,
                    collapseFraction = collapseFraction,
                    onAddressFocusChanged = { focused ->
                        addressFocused = focused
                        // Losing focus always resets the "has the user typed?"
                        // latch so the next tap starts clean (select-all, no
                        // dropdown) regardless of what was typed last time.
                        // The abandoned query goes with it — the pill is back
                        // to showing the tab's committed address.
                        if (!focused) {
                            addressBarEdited = false
                            addressQuery = ""
                        }
                    },
                    onAddressEditedChanged = { addressBarEdited = it },
                    onAddressQueryChanged = { addressQuery = it },
                    onSubmit = { text ->
                        // Called from the TextField's IME Go action. Bounce
                        // through a short coroutine delay so the in-flight
                        // Enter key event is delivered to the TextField
                        // (and consumed there) before submit() clears
                        // focus. Otherwise the Enter propagates to the next
                        // focusable icon button and fires it as a synthetic
                        // click.
                        scope.launch {
                            delay(50)
                            submit(state, text)
                        }
                    },
                    onBack = { state.loadUrl("javascript:history.back();void(0);") },
                    onForward = { state.loadUrl("javascript:history.forward();void(0);") },
                    onHome = {
                        submit(state, tabs.homepageUrl)
                    },
                    onToggleBookmark = {
                        val url = state.url
                        if (url.isBlank()) return@BottomToolbar
                        if (isBookmarked) repo.unbookmark(url)
                        else repo.bookmark(url, state.title)
                    },
                    // Step one of the two-step tap: a tap on the compact
                    // capsule restores the resting bar and stops there.
                    // It goes through [CapsuleCollapseState.expand], the
                    // same door a navigation or a scroll back to the top
                    // of the page uses, so the accumulated scroll travel
                    // is cleared too — the bar the user just asked for
                    // doesn't collapse again on the next few pixels of
                    // drift, and it re-collapses only on a fresh
                    // downward gesture.
                    onExpandCapsule = { state.capsuleCollapse.expand() },
                    onOpenSettings = { showSettings = true },
                    onOpenNode = { showNode = true },
                    onOpenTabs = { showTabSwitcher = true },
                    onOpenHistory = { showHistory = true },
                    onOpenBookmarks = { showBookmarks = true },
                    onReload = {
                        val url = state.url.ifBlank { state.addressBarText }
                        if (url.isNotBlank()) submit(state, url)
                    },
                    // Stop covers both halves of a load: the WebView's
                    // own fetch, and the indeterminate phase in front of
                    // it (ENS resolve / gateway warm-up) that runs on a
                    // coroutine before the WebView is ever handed a URL.
                    // Clearing the counters here as well as cancelling
                    // means the capsule's edge trace goes out on the
                    // frame of the tap rather than whenever Chromium
                    // gets round to its final progress callback.
                    onStop = {
                        state.cancelPendingProbe()
                        tabs.stopLoading?.invoke(state)
                        state.stopProgress()
                        // …and give the label back to the page that is
                        // actually on screen if the cancelled navigation
                        // never got to commit (#39).
                        state.addressBarText = addressBarTextAfterStop(
                            committedUrl = state.url,
                            pending = state.addressBarText,
                        )
                    },
                    onNewTab = {
                        val fresh = tabs.newTab()
                        submit(fresh, tabs.homepageUrl)
                    },
                    modifier = Modifier
                        .widthIn(max = CHROME_MAX_WIDTH)
                        .fillMaxWidth(),
                )
            }
        }

        // Snackbars pop up above the capsule rather than under it —
        // tracking the capsule's *slot* rather than its drawn height, so
        // they follow the editing morph as the bar inflates but sit
        // perfectly still when it compacts on scroll.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(chromeInsets)
                .padding(bottom = capsuleSlot + CapsuleBottomMargin),
        ) { data -> Snackbar(snackbarData = data) }
    }

    if (showSettings) {
        SettingsScreen(
            repo = repo,
            ipfsInfo = ipfsInfo,
            onIpfsToggle = onIpfsToggle,
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

    // HTML5 fullscreen. Last, so it paints over every overlay above.
    tabs.fullscreen?.let { session ->
        FullscreenCustomView(
            session = session,
            onExit = { tabs.exitFullscreen() },
        )
    }
}

/**
 * Opaque panel that overlays the WebView while the address bar is
 * focused, showing bookmarks + recent history that match what the user
 * has typed so far. The list is reversed so the best match sits right
 * above the (bottom) address bar and the thumb, with weaker matches
 * stacking upwards. Picking a row dispatches the canonical URL back to
 * the browser's `submit` path, which hides the keyboard and clears
 * focus (and therefore dismisses this panel).
 */
@Composable
private fun SuggestionsPanel(
    repo: BrowsingRepository,
    query: String,
    onPick: (String) -> Unit,
    bottomContentPadding: Dp,
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
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
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
