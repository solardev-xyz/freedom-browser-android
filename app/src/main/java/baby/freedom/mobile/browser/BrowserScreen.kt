package baby.freedom.mobile.browser

import android.webkit.WebSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.PrivateTheme
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.node.NodeLogSource
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.NodeIdentitySync
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.TezosDomainsResolver
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
internal const val NODE_READY_TIMEOUT_MS: Long = 90_000L

/** Cross-fade of reserved mode's strip between two page colours (#66). */
private const val STRIP_FADE_MS = 250

/**
 * How long the IPFS phase line (#94) outlives the load going idle — long
 * enough to bridge the probe → WebView hand-off, where the tab can read
 * "not busy" for a frame or two, short enough that a finished page loses
 * the line at once.
 */
private const val IPFS_STATUS_LINGER_MS: Long = 250L

/** Air between the IPFS phase line and the capsule under it. */
private val IpfsStatusGap = 8.dp

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
 * suggestion, bookmark, history, home). The pill echoes it
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
 *
 * [External] is another app handing us a link, share or search (#268),
 * always into a tab of its own. The pill echoes it as for [User] — there
 * is no other page in that tab for it to be mistaken for — but it is not
 * a gesture in this browser: its load gets none of a user-named load's
 * credit (an app link at the end of its redirects, #173; a site's x402
 * allowance, #218), and it lifts no block a declined prompt left.
 */
internal enum class SubmitSource { User, Renderer, External }

/**
 * Whether [this] source names the destination itself, rather than a page
 * asking for it ([SubmitSource.Renderer]): the pill may show it before it
 * commits, and its probe isn't the page's to cancel.
 */
internal val SubmitSource.namesDestination: Boolean
    get() = this != SubmitSource.Renderer

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
    SubmitSource.User, SubmitSource.External -> submitted
    SubmitSource.Renderer -> current
}

/**
 * Whether a submit from [source] also ends the address-bar edit — hides
 * the keyboard, drops the field's focus (which re-seeds its buffer from
 * the tab's committed address) and clears the "user has typed" latch.
 *
 * Only a submit that names its destination does: the user's own, or a
 * link from another app ([SubmitSource.External]) opening in its own tab,
 * which the user just switched to. The teardown exists because the user
 * just hit Go (or opened the link): the destination is settled and the
 * editor has done its job. A *page* submitting through `shouldOverrideUrlLoading`
 * ([SubmitSource.Renderer]) settles nothing about the editor — the user
 * may be halfway through typing somewhere else entirely, and throwing
 * their keyboard, focus and half-typed URL away on a `location.href`
 * the page chose is the page editing the browser's chrome. Looped, it
 * wipes every keystroke as it lands (#35).
 */
internal fun submitEndsAddressEditing(source: SubmitSource): Boolean =
    source.namesDestination

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
 * always wins, including over their own earlier one. Another app's link
 * ([SubmitSource.External]) counts as the user's here: it too names its
 * destination, and a page must not cancel it.
 */
internal fun submitSupersedesPendingProbe(
    pending: SubmitSource?,
    incoming: SubmitSource,
): Boolean = pending?.namesDestination != true || incoming.namesDestination

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
 * How long after Ctrl+L moved focus from a page's field to the address
 * bar a keyboard that was already up for that field is still the page's.
 * A hide the focus change itself causes *starts* within a frame or two
 * of the focus landing; one that starts later is the user closing the
 * keyboard, and must close the editor with it (#307 R4-F1). Measured
 * against the start of the hide ([WindowInsets.imeAnimationTarget]),
 * not its end, so the exit animation doesn't count against it — and
 * kept short, because a user can reach for Back a fifth of a second
 * after Ctrl+L.
 */
internal const val PAGE_KEYBOARD_HANDOFF_SETTLE_MS = 150L

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
    // A name's own virtual origin (`https://<name>.ens.…`, what Reload
    // of a name-addressed page maps to via [BrowserState.effectiveFetchUrl])
    // displays as the bare name — not content to probe: the gateway
    // would be asked for `vitalik.eth/`. It loads as a plain URL, and
    // the interceptor's document re-check resolves the name (#99), and
    // holds a typed-scheme assertion (#97).
    if (!CONTENT_SCHEMES_FOR_SUBMIT.any { display.startsWith(it) }) return null
    return if (display != url) display else null
}

private val CONTENT_SCHEMES_FOR_SUBMIT = listOf("bzz://", "ipfs://", "ipns://")

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
    @androidx.annotation.StringRes val contentDescriptionRes: Int,
) {
    val contentDescription: String get() = Strings.get(contentDescriptionRes)
}

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
    contentDescriptionRes = R.string.browser_badge_via_swarm,
)

private val IPFS_BADGE = ProtocolBadge(
    drawableRes = baby.freedom.mobile.R.drawable.ic_ipfs,
    contentDescriptionRes = R.string.browser_badge_via_ipfs,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    nodeInfo: NodeInfo,
    ipfsInfo: IpfsInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    myotisInfo: MyotisInfo = MyotisInfo(),
    /** The light client's chains switched on (#274); null until the launch choice is read. */
    myotisRunning: Set<MyotisNetwork>? = emptySet(),
    onRunMyotisChain: (MyotisNetwork, Boolean) -> Unit = { _, _ -> },
    /** A light-client chain's Retry (`repair = false`) or Repair sync data (`true`), by chain id. */
    onMyotisRecovery: (chainId: Long, repair: Boolean) -> Unit = { _, _ -> },
    onEnsureIpfsStarted: () -> Unit,
    onIpfsToggle: (Boolean) -> Unit,
    radicle: RadicleControls = RadicleControls(),
    tor: TorControls = TorControls(),
    deepLink: DeepLink? = null,
    onDeepLinkHandled: (DeepLink) -> Unit = {},
    onRecoverNodes: () -> Unit = {},
    ipfsProgressSnapshot: () -> String? = { null },
    ipfsCounters: () -> LongArray? = { null },
    onStatusBarTint: (Int?) -> Unit = {},
    onPanelShown: (Boolean) -> Unit = {},
    /** A node's recent log lines (#276), or null while its process isn't running. Blocking. */
    readNodeLogs: (NodeLogSource) -> String? = { null },
    /** Every running node forgets its kept log lines (part of Delete browsing data → Cookies and site data). */
    clearNodeLogs: () -> Unit = {},
    /** Hardware-keyboard shortcuts (#270): this screen is their target while composed. */
    shortcuts: KeyboardShortcutRouter? = null,
) {
    // Outside composition, so the tabs survive an Activity relaunch
    // (#183, see [TabsSession]) — and on disk, so they survive the app
    // being closed (#400).
    val tabsStore = TabsStore.get(LocalContext.current)
    val tabsSession = viewModel { TabsSession(HOME_URL, createSavedStateHandle(), tabsStore) }
    val tabs = tabsSession.tabs
    // Going to the background is the last word the app may get before
    // it's swiped away or killed: the tab list goes to disk now.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { tabsSession.persistNow() }
    // Shared with the request interceptor (which resolves
    // `<name>.ens.…` virtual hosts) so both sides use one cache.
    val ensResolver = Gateways.ensResolver
    val gatewayProbe = remember { GatewayProbe() }
    val context = LocalContext.current
    val pageZoom = remember(context) { PageZoom.get(context) }
    val desktopSites = remember(context) { DesktopSites.get(context) }
    val repo = remember(context) { BrowsingRepository.get(context) }
    // The search engine chosen in Settings (#87). Read at submit time
    // through the State, so a change in Settings applies to the next
    // search without re-creating [submit].
    val searchTemplate by remember(context) { NodeSettings.get(context).searchTemplate }
        .collectAsState(initial = SearchEngines.DEFAULT.template)
    // Settings → Search → Search suggestions (#443): off by default.
    val searchSuggestionsOn by remember(context) { NodeSettings.get(context).searchSuggestions }
        .collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    // Keep a stable reference to the latest nodeInfo for probe-gating
    // closures launched from submit(). Without rememberUpdatedState, a
    // probe job that outlives the recomposition would capture stale
    // NodeStatus and falsely treat a now-running node as stopped.
    val currentNodeInfo by rememberUpdatedState(nodeInfo)
    val currentRunNodeEnabled by rememberUpdatedState(runNodeEnabled)
    val currentIpfsInfo by rememberUpdatedState(ipfsInfo)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Settings opened straight at one of its cards (#416), from a node
    // page; null from the menu. [settingsSectionRequest] moves a Settings
    // that's already open (under the overview) to a card.
    var settingsInitialSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var settingsSectionRequest by remember { mutableStateOf<SettingsSection?>(null) }
    // The Nodes & networks overview (#416), and the node page over it (or
    // over Settings, or straight from the home warm-up row).
    var showNodes by rememberSaveable { mutableStateOf(false) }
    var nodeDetail by rememberSaveable { mutableStateOf<NodeDestination?>(null) }
    // What a Settings opened from a node page returns to on Back.
    var settingsBackToNodes by rememberSaveable { mutableStateOf(false) }
    var settingsBackToDetail by rememberSaveable { mutableStateOf<NodeDestination?>(null) }
    // The same, for a Settings already open under the overview that a
    // node page moved to a card: Back from that card's page comes here.
    // One per request Settings holds, by depth (a request made from an
    // earlier one's page stacks on it); [settingsRequestPending] is the
    // one asked for, until Settings says at which depth it took it.
    var settingsRequestBackTo by rememberSaveable { mutableStateOf(emptyList<NodeReturn>()) }
    var settingsRequestPending by remember { mutableStateOf<NodeReturn?>(null) }
    // The node logs page (#276), at this node's; over whichever node card opened it.
    var showLogs by rememberSaveable { mutableStateOf<NodeLogSource?>(null) }
    var showWallet by rememberSaveable { mutableStateOf(false) }
    // A feature asking for an identity (#75: created lazily, never forced)
    // opens the wallet page over whatever is up; see [Vault.requireUnlocked].
    val vault = remember(context) { Vault.get(context) }
    val walletRequest by vault.setupRequest.collectAsState()
    // A payment link's Send page (#317), over the page the link was on.
    // Plain `remember`: an ask left waiting by a relaunch is withdrawn
    // with the tab's WebView anyway.
    var linkSend by remember { mutableStateOf<LinkSend?>(null) }
    var showTabSwitcher by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    // The bookmark the "Bookmark added" snackbar's Edit opened (#264),
    // and whether it was added from a private tab.
    var editBookmark by rememberSaveable { mutableStateOf<Long?>(null) }
    var editBookmarkPrivate by rememberSaveable { mutableStateOf(false) }
    // Whether the Bookmarks list was opened from a private tab, fixed at
    // that moment, so its Edit dialog keeps the keyboard from learning
    // what's typed there (#296 R1-M1).
    var bookmarksPrivate by rememberSaveable { mutableStateOf(false) }
    // …and the same for History, where it decides that a new tab opened
    // from the list is a private one (#321).
    var historyPrivate by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    // Ctrl+L (#270): the address field takes focus once it's composed.
    var addressFocusRequested by remember { mutableStateOf(false) }
    // …and a keyboard already up for a page's own field when it did is
    // that page's, not one opened for the address bar: it doesn't count
    // as seen until it has gone down once, or not started going down
    // within the handoff's first moments
    // (see [imeDismissalEndsEditing], [PAGE_KEYBOARD_HANDOFF_SETTLE_MS]).
    var pageKeyboardHandoff by remember { mutableStateOf(false) }
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
    // A suggestion row's arrow: text for the address field to take (#443).
    var addressFill by remember { mutableStateOf<AddressFill?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // The Undo notice of the switcher's last bulk close (#320).
    var tabsClosedNotice by remember { mutableStateOf<Job?>(null) }
    // The tabs the last restore crashed the app with (#400): not loaded,
    // but one tap away, and held on disk until answered. Run in the
    // effect's own scope and cleared only by an answer: an Activity
    // relaunch while it's up cancels it, and the next screen shows it
    // again; Reopen closed tab or Delete browsing data ends the offer, and the
    // notice with it. It waits on a host of its own: on the shared one
    // its Indefinite notice would hold every later notice (a Close all's
    // Undo, downloads, permission recovery) in the queue until answered.
    // It's drawn only while the shared host has nothing up, so it steps
    // aside for each of those notices and comes back after.
    val heldTabsHostState = remember { SnackbarHostState() }
    val heldTabs = tabsSession.heldTabs
    LaunchedEffect(heldTabs) {
        val held = heldTabs ?: return@LaunchedEffect
        val n = held.group.tabs.size
        val result = heldTabsHostState.showSnackbar(
            message = Strings.plural(
                if (held.afterCrash) R.plurals.browser_tabs_not_restored else R.plurals.browser_tabs_not_reopened,
                n,
                n,
            ),
            actionLabel = Strings.get(R.string.browser_tabs_restore),
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) tabsSession.restoreHeld() else tabsSession.dismissHeld()
    }

    val sitePermissions = remember(context) { SitePermissionBroker.get(context) }
    SitePermissionAndroidBridge(sitePermissions, snackbarHostState)
    ClientCertificateBridge()
    // Why a payment link didn't open Send (#317) — a private tab, no
    // wallet, a link Send can't pay — instead of an error page.
    DisposableEffect(snackbarHostState) {
        val notice: (String) -> Unit = { text ->
            scope.launch { snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Long) }
        }
        EthereumLinks.onNotice = notice
        // A link the user's own address redirected to (R1-M1): Send with
        // no page's ask behind it, as for one typed in — for the tab on
        // screen only; one the user has since left is dropped, and says
        // so, since the address bar has already gone back (R2-M2).
        val open: (BrowserState, SendPrefill) -> Unit = { tab, prefill ->
            if (tabs.active.id == tab.id) {
                linkSend?.closed()
                linkSend = LinkSend(prefill, prompt = null)
            } else {
                notice(Strings.get(R.string.send_link_tab_left))
            }
        }
        EthereumLinks.onOpenSend = open
        onDispose {
            if (EthereumLinks.onNotice === notice) EthereumLinks.onNotice = null
            if (EthereumLinks.onOpenSend === open) EthereumLinks.onOpenSend = null
        }
    }
    // Any full-screen panel over the browser (they're all opaque).
    val overlayShown = showSettings || showNodes || nodeDetail != null || showLogs != null || showWallet || walletRequest != null ||
        linkSend != null ||
        showTabSwitcher ||
        showHistory || showBookmarks || showDownloads
    // The menu's Nodes & networks sub-line (#416): only while a node has
    // failed, read off the overview's own rows. Remembered, so the menu
    // gets the same (usually null) note however often the peers change.
    val externalSwarmBase by Gateways.externalSwarmBaseFlow.collectAsState()
    val externalIpfsBase by Gateways.externalIpfsBaseFlow.collectAsState()
    val nodesNote = remember(
        nodeInfo.status, externalSwarmBase, ipfsInfo.status, externalIpfsBase,
        radicle.info.status, radicle.enabled, tor.info.status, tor.enabled, tor.running,
        myotisInfo, myotisRunning,
    ) {
        nodesMenuNote(
            nodeOverviewRows(
                NodeOverviewInput(
                    nodeInfo = nodeInfo,
                    externalSwarm = externalSwarmBase,
                    ipfsInfo = ipfsInfo,
                    externalIpfs = externalIpfsBase,
                    radicleInfo = radicle.info,
                    radicleEnabled = radicle.enabled,
                    tor = tor,
                    myotisInfo = myotisInfo,
                    myotisRunning = myotisRunning,
                ),
            ),
        )
    }
    val downloads = remember(context) { DownloadManager.get(context) }

    // Download notices (#79): the start, and the end with an action —
    // Open for a finished file, the Downloads list for a failed one.
    // Collected for the screen's lifetime, so a download that finishes
    // while another panel (Settings, History…) is up still reports.
    // Not while the Downloads list itself is up, though: the list shows
    // the same start and end live, "Details" would open what's already
    // open, and a Long snackbar would sit over the bottom row's Retry
    // and × for ten seconds.
    val downloadNotices = remember { DownloadNotices() }
    // The switcher's pane (#418): it opens on the active tab's kind, and
    // only its Private pane shows private tabs' cards.
    // Saveable like [showTabSwitcher], so a rotation keeps the pane.
    var switcherPrivatePane by rememberSaveable(showTabSwitcher) { mutableStateOf(tabs.active.private) }
    // Is anything from a private session (#86) on screen? A private
    // download's notice (negative id) names its file, so it's only shown
    // while that's the case, and withdrawn when the screen goes back to
    // normal content — before [PrivateScreenGuard] drops FLAG_SECURE.
    val privateOnScreen = privateContentOnScreen(
        activePrivate = tabs.active.private,
        anyPrivate = tabs.tabs.any { it.private },
        switcherShown = showTabSwitcher,
        switcherPrivatePane = switcherPrivatePane,
        downloadsShown = showDownloads,
    )
    val privateOnScreenNow by rememberUpdatedState(privateOnScreen)
    LaunchedEffect(privateOnScreen) {
        if (!privateOnScreen) downloadNotices.cancelPrivate()
    }
    LaunchedEffect(downloads) {
        downloads.events.collect { event ->
            if (showDownloads || (event.id < 0 && !privateOnScreenNow)) {
                downloadNotices.supersedeStart(event.id)
                return@collect
            }
            when (event) {
                is DownloadEvent.Started -> downloadNotices.show(this, event.id, start = true) {
                    snackbarHostState.showSnackbar(
                        Strings.get(R.string.browser_download_started, event.fileName),
                        duration = SnackbarDuration.Short,
                    )
                }
                is DownloadEvent.Completed -> {
                    downloadNotices.supersedeStart(event.id)
                    downloadNotices.show(this, event.id) {
                        val result = snackbarHostState.showSnackbar(
                            Strings.get(R.string.browser_download_completed, event.fileName),
                            actionLabel = Strings.get(R.string.common_open),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            downloads.entry(event.id)?.let { entry ->
                                downloads.open(context, entry)?.let { snackbarHostState.showSnackbar(it) }
                            }
                        }
                    }
                }
                is DownloadEvent.Failed -> {
                    downloadNotices.supersedeStart(event.id)
                    downloadNotices.show(this, event.id) {
                        val result = snackbarHostState.showSnackbar(
                            Strings.get(R.string.browser_download_failed, event.reason),
                            actionLabel = Strings.get(R.string.browser_download_details),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) showDownloads = true
                    }
                }
            }
        }
    }

    // A connected site switched itself to a built-in network, no sheet
    // (#440), while its tab had the turn on screen: say so, with Undo. One
    // at a time — a newer notice replaces this one — and closed when it's
    // down, which lets that tab's next switch through (#446 R1-F1). On the
    // screen's scope, so the next notice arriving can't cancel an Undo
    // already running. Closed by the bridge instead (its tab closed, or the
    // notice ran past its longest hold), it comes down too (#446 R2-M1).
    //
    // A switch it reports is already made, so it isn't dropped (#446 R4-M2,
    // R5-M3): it waits for its tab to be the active one, however long that
    // takes, and if the user leaves the tab before it's on screen it waits
    // for them to come back again. Once it has been on screen, leaving the
    // tab takes it down, so it never sits over another one. It takes its
    // place in the snackbar queue like any other notice: it doesn't push
    // aside one already up — a Tab closed · Undo, a download's Open — and
    // with it that notice's action (#446 R5-F1). It tells the bridge once
    // it's actually on screen, which is when its hold starts (R4-M1).
    //
    // A newer notice replaces only its own tab's, or one already seen: one
    // still waiting for another tab is kept, so that switch is still named
    // when the user gets back to it (#446 R6-M2). Anything over its page
    // covers it ([switchNoticeUncovered]): a sheet the tab puts up (the
    // sign or send that usually follows a switch, a Swarm or Radicle
    // sheet), a permission prompt or download offer, a full-screen panel,
    // HTML5 fullscreen, or a window of its own (Android's permission
    // dialog, a Ledger conversation, a remote-signing sheet).
    // It comes down while covered and goes back up, with a fresh timer and
    // its Undo, once the page is clear, so it doesn't run out unseen behind
    // it (#446 R6-M1, R1-M1 of round 1007) — and, covered, it stops holding
    // the tab's next switch ([EthereumProviders.SwitchNotice.covered]).
    val chainSwitchNotices = remember { mutableMapOf<Long, ChainSwitchNoticeUi>() }
    // The tab whose page nothing covers right now: set below, once the
    // prompt turn and panels are known.
    var switchNoticeClearTab by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        EthereumProviders.chainSwitches.collect { notice ->
            notice.received()
            switchNoticesReplaced(chainSwitchNotices.values, notice.tabId, { it.notice.tabId }, { it.shown })
                .forEach { it.job?.cancel() }
            val ui = ChainSwitchNoticeUi(notice)
            ui.job = scope.launch {
                try {
                    val switch = notice.switch
                    val visuals = ChainSwitchVisuals(
                        Strings.get(R.string.send_eth_switched, permissionOriginDisplay(switch.origin), switch.to.name),
                        Strings.get(R.string.send_undo),
                    )
                    // On its tab, with nothing covering its page.
                    fun visible(): Boolean = tabs.active.id == notice.tabId && switchNoticeClearTab == notice.tabId
                    var result: SnackbarResult? = null
                    while (result == null) {
                        // Covered after it was seen, then the user left the tab: down, as below.
                        snapshotFlow { visible() || (ui.shown && tabs.active.id != notice.tabId) }.first { it }
                        if (!visible()) return@launch
                        result = coroutineScope {
                            val showing = async { snackbarHostState.showSnackbar(visuals) }
                            val onScreen = launch {
                                snapshotFlow { snackbarHostState.currentSnackbarData?.visuals }.first { it === visuals }
                                ui.shown = true
                                notice.shown()
                            }
                            val left = async { snapshotFlow { visible() }.first { !it } }
                            select<SnackbarResult?> {
                                showing.onAwait { it }
                                left.onAwait { null }
                            }.also {
                                showing.cancel()
                                onScreen.cancel()
                                left.cancel()
                            }
                        }
                        // Left the tab after it was seen: down for good, no Undo. Only
                        // covered, on its tab: back up once the page is clear, holding
                        // nothing meanwhile.
                        if (result == null && ui.shown && tabs.active.id != notice.tabId) return@launch
                        if (result == null && ui.shown) notice.covered()
                    }
                    if (result == SnackbarResult.ActionPerformed) {
                        notice.close(undo = true)
                        scope.launch {
                            val failed = when (EthereumProviders.undoSwitch(notice)) {
                                EthereumProvider.UndoResult.UNDONE -> null
                                EthereumProvider.UndoResult.MOVED -> R.string.send_undo_failed
                                EthereumProvider.UndoResult.FAILED -> R.string.send_undo_save_failed
                            }
                            if (failed != null) snackbarHostState.showSnackbar(Strings.get(failed), duration = SnackbarDuration.Long)
                        }
                    }
                } finally {
                    notice.close(undo = false)
                }
            }
            scope.launch {
                notice.awaitClosed()
                ui.job?.cancel()
                if (chainSwitchNotices[notice.tabId] === ui) chainSwitchNotices.remove(notice.tabId)
            }
            chainSwitchNotices[notice.tabId] = ui
        }
    }

    // The node identity switched with the wallet (#77, decision 10: the
    // user sees a notice and the node restarts). Names no page, so it
    // needs no private-tab handling.
    LaunchedEffect(Unit) {
        NodeIdentitySync.get(context).notices.collect { change ->
            val restarting = currentRunNodeEnabled && currentNodeInfo.status != NodeStatus.Stopped
            // Radicle too (#328), when the user has it on.
            val radicleOn = currentRunNodeEnabled && RadicleClient.enabled
            // Like Swarm's: the restart may already be under way by now.
            val radicleRestarting = radicleOn && RadicleClient.state.value.status.let {
                it != RadicleStatus.Stopped && it != RadicleStatus.Error
            }
            val notice = nodeIdentityNotice(change, restarting, radicleOn, radicleRestarting) ?: return@collect
            snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Long)
        }
    }

    // …and when the list opens, every download notice goes — the one
    // on screen and those queued behind it: the list has the same news,
    // they'd cover its bottom row, and they'd hold up the list's own
    // messages. Other snackbars aren't download news and stay.
    LaunchedEffect(showDownloads) {
        if (showDownloads) downloadNotices.cancelAll()
    }

    val state = tabs.active
    // Private pages stay out of the Recents snapshot and screenshots
    // (#86) — and so does a private download's notice (it names the
    // file) until it has left the screen.
    PrivateScreenGuard(privateOnScreen || downloadNotices.privateShowing)
    val isBookmarked by remember(repo, state.url) { repo.isBookmarked(state.url) }
        .collectAsState(initial = false)

    // The page's own site permissions (#266): what the site on screen —
    // and any frame in it that asked — holds, for the menu's row and
    // its sheet; and the camera/microphone its document is using now,
    // for the indicator over the page. Keyed to the site the broker
    // decides this document's requests under ([BrowserState.permissionTop]),
    // so a popup's blank document lists what it was granted in its
    // opener's name.
    val pageOrigin = state.permissionTop
    val pagePermissions by remember(sitePermissions, state, pageOrigin) {
        sitePermissions.pageEntries(state, pageOrigin)
    }.collectAsState(initial = emptyList())
    val pageDocument by remember(sitePermissions, state.id) {
        sitePermissions.documentPermissions(state.id)
    }.collectAsState(initial = null)
    val mediaInUse by remember(sitePermissions, state.id) {
        sitePermissions.mediaInUse(state.id)
    }.collectAsState(initial = emptySet())
    // The sheet, pinned to the tab and document it was opened over: it
    // closes when either changes, so a × can't act on a page the user
    // didn't open it for.
    var sitePermissionsSheet by remember { mutableStateOf<PageSheetTarget?>(null) }
    val openSitePermissions: () -> Unit = {
        sitePermissionsSheet = PageSheetTarget(state.id, pageOrigin, pageDocument?.doc)
    }
    val sheetTarget = sitePermissionsSheet?.takeIf { t ->
        t.tabId == state.id && t.origin == pageOrigin && t.doc == pageDocument?.doc
    }
    // Page info (#442), from the address bar's badge: pinned the same
    // way, to the site its Site data is about ([BrowserState.siteDataOrigin]):
    // a content gateway's own origin holds no permission but does hold
    // data (#457 R5-M1).
    val pageSiteOrigin = state.siteDataOrigin
    var pageInfoSheet by remember { mutableStateOf<PageSheetTarget?>(null) }
    val openPageInfo: () -> Unit = {
        pageInfoSheet = PageSheetTarget(state.id, pageSiteOrigin, pageDocument?.doc)
    }
    val pageInfoTarget = pageInfoSheet?.takeIf { t ->
        t.tabId == state.id && t.origin == pageSiteOrigin && t.doc == pageDocument?.doc
    }

    // IPFS load progress (#94): while the active tab is busy on content
    // the IPFS node serves, poll the node's retrieval-progress snapshot
    // and show which phase the fetch is in above the capsule — the
    // phone's version of the desktop status line. The capsule's own
    // trace says *that* it is loading; on a slow CID this says *why* it
    // is taking so long (finding providers, searching the DHT, …).
    //
    // [ipfsStatusTab] pins a message to the tab it was polled for, so a
    // tab switch never shows one tab's phase over another. Going idle
    // clears it after [IPFS_STATUS_LINGER_MS] rather than at once: the
    // hand-off from the probe (`resolving`) to the WebView's first
    // progress callback can read "not busy" for a frame, and the line
    // shouldn't blink out and back in across it. The counter baseline
    // (see [IpfsProgress.fromCounters]) survives that blink for the same
    // reason — it is the load's, not the poll loop's: it is keyed on the
    // tab and its [BrowserState.loadGeneration], so a new navigation that
    // supersedes one still loading (and so never lets the tab go idle)
    // starts a fresh [IpfsProgress.LoadMeter], which sets aside the
    // counter growth of every poll during which the superseded load
    // was still busy in the node, and skips the node-wide snapshot while
    // it has any request open ([BrowserState.gatewayWork]).
    // The counters are the embedded node's; an external gateway (#125)
    // serves the load without them moving. Observed, so switching the
    // source mid-load starts / stops the polling straight away.
    val externalIpfsGateway by Gateways.externalIpfsBaseFlow.collectAsState()
    val pollIpfsProgress = state.ipfsLoad &&
        isCapsuleLoading(state) &&
        ipfsInfo.status == IpfsStatus.Running &&
        externalIpfsGateway.isEmpty()
    val ipfsLoadKey = state.id to state.loadGeneration
    var ipfsStatus by remember { mutableStateOf<String?>(null) }
    var ipfsStatusTab by remember { mutableStateOf<Long?>(null) }
    var ipfsMeter by remember { mutableStateOf<IpfsProgress.LoadMeter?>(null) }
    var ipfsMeterKey by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(ipfsLoadKey, pollIpfsProgress) {
        if (!pollIpfsProgress) {
            delay(IPFS_STATUS_LINGER_MS)
            ipfsStatus = null
            ipfsMeter = null
            return@LaunchedEffect
        }
        val meter = ipfsMeter?.takeIf { ipfsMeterKey == ipfsLoadKey }
            ?: IpfsProgress.LoadMeter().also {
                ipfsMeter = it
                ipfsMeterKey = ipfsLoadKey
            }
        val generation = state.loadGeneration
        while (true) {
            val (snapshot, counters) = withContext(Dispatchers.IO) {
                runCatching { ipfsProgressSnapshot() }.getOrNull() to
                    IpfsProgress.Counters.of(runCatching { ipfsCounters() }.getOrNull())
            }
            ipfsStatus = meter.poll(
                snapshot,
                counters,
                supersededActive = state.gatewayWork.activeBefore(generation),
                supersededOpen = state.gatewayWork.openBefore(generation),
            )
            ipfsStatusTab = state.id
            delay(IpfsProgress.POLL_INTERVAL_MS)
        }
    }

    // Gate the hardware back button on [backHandledFor]: enabled
    // whenever Back has somewhere to go — off the home overlay, or on it
    // with WebView history (Home from the menu loads `about:blank` on top
    // of the last page). Keying off `canGoBack` alone isn't safe because
    // that flag is only refreshed in the WebView client's async
    // callbacks: a user who taps an `ens://` bookmark and hits back while
    // the resolver is still running (WebView hasn't even been told to
    // navigate yet) would fall through the disabled handler and minimize
    // the app. Keying off "not home" alone isn't either: on home with
    // history the bar's Back would go back while the gesture minimized.
    //
    // When fired we prefer the WebView's own history stack; if there's
    // nothing to pop (mid-probe, or a direct typed URL that failed
    // before the WebView ever navigated), cancel any in-flight resolver
    // work and fall back to a clean home state.
    //
    // The bar's Back button runs this same [goBack] and is enabled off
    // the same [backHandledFor] rule, so the two never disagree.
    val isHomeTab = state.isHome
    val goBack: () -> Unit = {
        when (backActionFor(state.canGoBack, state.isHome)) {
            BackAction.History -> {
                // The user's own: lifts a JavaScript dialog block once it commits (#466).
                state.jsDialogGate.allow()
                // A navigation of its own (#94, see [BrowserState.loadGeneration]).
                state.beginLoad()
                state.loadUrl(HISTORY_BACK_JS)
            }
            BackAction.Home -> {
                state.cancelPendingProbe()
                state.navigateHome()
            }
            BackAction.None -> Unit
        }
    }
    BackHandler(enabled = backHandledFor(state.canGoBack, state.isHome), onBack = goBack)

    // The bar's Forward and Alt+→ (#270). The bar only shows it with
    // somewhere to go ([navControlsFor]); the key checks the same flag.
    val goForward: () -> Unit = {
        if (state.canGoForward) {
            state.jsDialogGate.allow()
            state.beginLoad()
            state.loadUrl(HISTORY_FORWARD_JS)
        }
    }

    // Find in page (#83). The bar stands in for the capsule while the
    // active tab's session is open — never on the home surface, which has
    // nothing to search. Registered after the history handler so that,
    // while the bar is up, Back closes it rather than leaving the page
    // (the IME, when showing, still takes the first press itself).
    val findOpen = state.find.open && !isHomeTab
    val closeFind: () -> Unit = {
        tabs.find?.invoke(state, FindAction.Clear)
        keyboard?.hide()
        focusManager.clearFocus()
    }
    BackHandler(enabled = findOpen, onBack = closeFind)

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
        namedByUser: Boolean = false,
        bypassCache: Boolean = false,
    ) {
        val generation = target.loadGeneration
        val isIpfs = contentUri.startsWith("ipfs://") || contentUri.startsWith("ipns://")
        // The one place that knows where an ENS name leads: the
        // chrome's IPFS progress line follows this load from here (#94).
        target.ipfsLoad = isIpfs
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
        //
        // An external endpoint (#125) replaces the embedded node, so
        // there's no node to wait for (or start): the probe below
        // tells whether the endpoint answers.
        Gateways.awaitExternalEndpoints()
        val external = if (isIpfs) Gateways.externalIpfsBase else Gateways.externalSwarmBase
        val readiness = if (external.isNotEmpty()) {
            NodeReadyOutcome.Running
        } else if (isIpfs) {
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

        // Probe the gateway directly (`http://127.0.0.1:…`, or the
        // external endpoint) — the WebView gets the virtual-origin URL,
        // but readiness is a question for the node itself. Resolved
        // after the node flips to Running, in case ipfsBase was still
        // empty before.
        val resolved = Gateways.toGatewayUrl(contentUri)
        val headUrl = GatewayUrls.extractBase(resolved)?.prefix ?: resolved

        // Open gateway work of this load while the probe's HEADs run —
        // a later submit's IPFS phase line reads it to tell this load is
        // still busy in the node (#94, see [GatewayWork]). The HEAD
        // itself is blocking, so the probe returns only once it has.
        val probeWork = target.gatewayWork.start(generation)
        val outcome = try {
            gatewayProbe.probe(headUrl)
        } finally {
            target.gatewayWork.finish(probeWork)
        }
        when (outcome) {
            GatewayProbe.Outcome.Ok ->
                target.loadUrl(
                    loadUri,
                    displayPrefix = displayPrefix,
                    namedByUser = namedByUser,
                    bypassCache = bypassCache,
                )
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

    /**
     * The submit flow's `web3://` branch (#123, ERC-8244): read the
     * app's `html()` through the chain-data router and load it — at once
     * when the read was verified (a proof or an RPC quorum) or came from
     * the user's own RPC, or when these exact bytes were already let
     * through this session. Code only one public RPC returned gets the
     * *not cross-checked* warning, whose "Continue once" comes back here
     * with its hash as [approvedUri] and runs exactly the bytes the
     * warning described, without another read; code the RPCs disagreed
     * about gets a warning with no way on.
     */
    fun submitOnchainApp(
        target: BrowserState,
        input: String,
        source: SubmitSource,
        approvedUri: String?,
        namedByUser: Boolean,
        bypassCache: Boolean = false,
    ) {
        target.clearEnsOverride()
        target.ipfsLoad = false
        val parsed = OnchainAppRef.parse(input)
        if (parsed == null) {
            target.addressBarText = pendingAddressBarText(target.addressBarText, input.trim(), source)
            target.loadUrl(
                ErrorPage.url(
                    errorCode = "web3_invalid",
                    displayUrl = input.trim(),
                    protocol = "web3",
                    detail = Strings.get(R.string.browser_web3_invalid_detail),
                ),
            )
            return
        }
        val (app, tail) = parsed
        val display = app.displayUrl(tail)
        target.addressBarText = pendingAddressBarText(target.addressBarText, display, source)
        target.resolving = true

        fun onchainError(code: String, detail: String, continueUrl: String? = null) {
            target.loadUrl(
                ErrorPage.url(
                    errorCode = code,
                    displayUrl = display,
                    protocol = "web3",
                    // A form Chromium lets the page navigate to
                    // ([OnchainAppRef.linkUrl]).
                    retryUrl = app.linkUrl(tail),
                    detail = detail,
                    continueUrl = continueUrl,
                ),
            )
        }

        val probe = scope.launch {
            try {
                val pending = approvedUri?.let { target.onchain.takePending(app, it) }
                val load = if (pending != null) {
                    OnchainLoad.Loaded(pending)
                } else {
                    OnchainApps.init(context)
                    // Off the main thread: decoding and hashing a
                    // document of megabytes is real work.
                    withContext(Dispatchers.Default) { OnchainApps.loader!!.load(app) }
                }
                // As in the ENS branch: a superseded read writes nothing.
                ensureActive()
                when (load) {
                    is OnchainLoad.Failed -> onchainError(load.code, load.detail)
                    is OnchainLoad.Loaded -> {
                        val doc = load.document
                        val approvals = OnchainApps.approvalsFor(target.private)
                        val approved = approvedUri != null && doc.hash.equals(approvedUri, ignoreCase = true)
                        when {
                            doc.conflict -> onchainError("web3_conflict", doc.conflictDetail())
                            doc.trusted || approved || approvals.contains(doc) -> {
                                approvals.add(doc)
                                target.onchain.handOff(doc)
                                target.loadUrl(app.virtualUrl(tail), namedByUser = namedByUser, bypassCache = bypassCache)
                            }
                            else -> {
                                target.onchain.offer(doc)
                                val gate = EnsGate.create(display, doc.hash, app.linkUrl(tail))
                                target.ensGate = gate
                                onchainError(
                                    "web3_unverified",
                                    doc.unverifiedDetail(),
                                    continueUrl = EnsGate.continueUrl(gate),
                                )
                            }
                        }
                    }
                }
            } finally {
                target.resolving = false
                target.finishPendingProbe(coroutineContext.job)
            }
        }
        target.beginPendingProbe(probe, source, target = app.virtualUrl(tail))
    }

    fun submit(
        target: BrowserState,
        raw: String,
        source: SubmitSource = SubmitSource.User,
        // An unverified ENS answer the user chose to load (#96): let
        // through if the resolver still gives exactly this one.
        approvedUri: String? = null,
        // The user's Hard reload (#262): the load this submit schedules
        // goes out with the caches bypassed. Handed to that load's own
        // `loadUrl`, like [namedByUser] — never an error page's.
        bypassCache: Boolean = false,
    ) {
        // "Continue once" on the tab's not-cross-checked warning (#96):
        // the one navigation it was shown for, again, with its answer
        // let through. Its token is the tab's own, so a page can't
        // fake one ([EnsGate]); anything else is dropped here.
        EnsGate.continueToken(raw)?.let { token ->
            val gate = target.ensGate?.takeIf { it.token == token } ?: return
            target.ensGate = null
            submit(target, gate.retryUrl, SubmitSource.User, approvedUri = gate.uri)
            return
        }

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

        // A payment link typed or pasted in (#317) isn't an address to
        // load: it opens Send, filled in, over the page, which stays. Only
        // the user's own: a page's goes through the WebView's link gate.
        addressBarEthereumLink(raw, source, target.private, EthereumLinks.walletReady(context))?.let { route ->
            when (route) {
                is EthereumLinkRoute.Refuse -> scope.launch {
                    snackbarHostState.showSnackbar(route.reason, duration = SnackbarDuration.Long)
                }
                is EthereumLinkRoute.OpenSend -> {
                    linkSend?.closed()
                    linkSend = LinkSend(route.prefill, prompt = null)
                }
                EthereumLinkRoute.Drop -> Unit
            }
            return
        }

        // The user navigating the tab themselves (an address, a reload)
        // lifts a download block a declined offer left on it
        // ([DownloadOffers]); a page's own navigation doesn't.
        if (source == SubmitSource.User) downloads.allowOffers(target.id)
        // Likewise a refused `window.radicle` prompt's block (#124).
        if (source == SubmitSource.User) RadicleProviders.allowPrompts(target.id)
        // And a rejected `window.ethereum` sheet's (#110).
        if (source == SubmitSource.User) EthereumProviders.allowPrompts(target.id)
        // And a rejected `window.swarm` sheet's (#120).
        if (source == SubmitSource.User) SwarmProviders.allowPrompts(target.id)
        // And a JavaScript dialog block (#466), once its load commits.
        if (source == SubmitSource.User) target.jsDialogGate.allow()
        // Nor is it a load a restore put back over its page (#185 R4-F1).
        if (source == SubmitSource.User) target.userNavigated()
        // And the load it schedules is theirs: its redirects may end in
        // an app link without a tap on a page (#173). Handed to that
        // load's own `loadUrl` below, never left for whichever load
        // comes next (R2-F2).
        val namedByUser = source == SubmitSource.User

        // Any new submit supersedes a probe that was still in flight on
        // this tab — otherwise switching URL mid-probe would let the
        // stale probe decide the navigation.
        target.cancelPendingProbe()
        target.beginLoad()

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
            // Not IPFS until the contenthash says so (see
            // [gateGatewayNavigation]) — the resolve itself is ENS's.
            target.ipfsLoad = false

            fun ensError(
                errorCode: String,
                detail: String,
                retryUrl: String = retryDisplay,
                continueUrl: String? = null,
                resolvedProtocol: String? = null,
            ) {
                target.clearEnsOverride()
                target.loadUrl(
                    ErrorPage.url(
                        errorCode = errorCode,
                        displayUrl = ensDisplay,
                        protocol = "ens",
                        retryUrl = retryUrl,
                        detail = detail,
                        continueUrl = continueUrl,
                        resolvedProtocol = resolvedProtocol,
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
                        // A typed scheme is an assertion (#97): content
                        // on another transport is a "resolves to X, not
                        // Y" page, never a silent switch. Checked before
                        // the cross-check gate — asking the user to
                        // accept an answer that couldn't load anyway
                        // would be a question with no good answer; the
                        // page says whose word it is instead. Its button
                        // opens the name under the transport it does
                        // resolve to: the user switching, by name.
                        is EnsResult.Ok if requiredProtocol != null &&
                            result.protocol != requiredProtocol -> {
                            ensError(
                                errorCode = "ens_wrong_protocol",
                                detail = EnsGate.withTrustNote(
                                    Strings.get(
                                        R.string.browser_ens_wrong_protocol_detail,
                                        name,
                                        result.protocol,
                                        requiredProtocol,
                                    ),
                                    result.trust,
                                ),
                                // A `.tez` website record has no name-
                                // addressed scheme of its own: the generic
                                // form follows it onto the web.
                                retryUrl = if (result.protocol == "http" || result.protocol == "https") {
                                    "ens://$name$suffix"
                                } else {
                                    "${result.protocol}://$name$suffix"
                                },
                                resolvedProtocol = result.protocol,
                            )
                        }
                        // Only one RPC server's word for it (#96): ask
                        // first. The user's "Continue once" comes back
                        // here with this very answer approved.
                        is EnsResult.Ok if !result.trust.verified && result.uri != approvedUri -> {
                            val gate = EnsGate.create(name, result.uri, retryDisplay)
                            target.ensGate = gate
                            ensError(
                                errorCode = "ens_unverified",
                                detail = EnsGate.unverifiedDetail(result),
                                continueUrl = EnsGate.continueUrl(gate),
                            )
                        }
                        is EnsResult.Ok -> {
                            val webRecord = result.protocol == "http" || result.protocol == "https"
                            // Remember hash/cid → name for the whole session
                            // (cross-tab address-bar preservation). Safe for
                            // every protocol — bzz, ipfs, and ipns all round-
                            // trip through [Gateways] + [DisplayUrl] now.
                            // With how it was checked, for the bar's
                            // trust shield (#97). A `.tez` name's http(s)
                            // website is not content the name's origin
                            // serves, so it isn't recorded.
                            if (!webRecord) KnownEnsNames.record(result.uri, name, result.trust)
                            if (webRecord) {
                                // A `.tez` website record on the ordinary
                                // web: navigate there directly, as desktop
                                // does. A redirect record is the whole
                                // destination; a content URL keeps the
                                // typed path.
                                val web = if (result.redirect) {
                                    result.uri
                                } else {
                                    TezosDomainsResolver.appendWebsiteSuffix(result.uri, suffix)
                                }
                                target.clearEnsOverride()
                                target.addressBarText =
                                    pendingAddressBarText(target.addressBarText, web, source)
                                target.loadUrl(web, bypassCache = bypassCache)
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
                                    namedByUser = namedByUser,
                                    bypassCache = bypassCache,
                                )
                            } else {
                                ensError(
                                    errorCode = "ens_unsupported_codec",
                                    detail = "${result.protocol}:// (${result.decoded.take(16)}…)",
                                )
                            }
                        }
                        // Nothing to load either way, but say when it's
                        // only one server's word for it (#96).
                        is EnsResult.NotFound ->
                            ensError(
                                "ens_not_found",
                                detail = EnsGate.withTrustNote(result.reason, result.trust),
                            )
                        is EnsResult.Unsupported ->
                            ensError(
                                "ens_unsupported_codec",
                                // A `.tez` record's "codec" is the reason
                                // its website URI was refused.
                                detail = EnsGate.withTrustNote(
                                    if (name.endsWith(".tez")) result.codec else Strings.get(R.string.browser_ens_codec_detail, result.codec),
                                    result.trust,
                                ),
                            )
                        // The name itself was refused: no lookup ran, so
                        // "couldn't reach an RPC endpoint" would be a lie.
                        is EnsResult.Error -> refusedNameErrorCode(result.reason)?.let {
                            ensError(it, detail = result.error)
                        } ?: ensError(
                            "ens_lookup_failed",
                            // `.tez` says what failed.
                            detail = if (name.endsWith(".tez")) "${result.reason}: ${result.error}" else result.reason,
                        )
                        // RPC servers disagreed (#96): nothing to load.
                        is EnsResult.Conflict ->
                            ensError("ens_conflict", detail = EnsGate.conflictDetail(result))
                    }
                } finally {
                    target.resolving = false
                    target.finishPendingProbe(coroutineContext.job)
                }
            }
            // The destination this probe navigates to if it resolves —
            // so its own commit isn't mistaken for the navigation that
            // superseded it (#54, see [commitCancelsPendingProbe]).
            target.beginPendingProbe(ensProbe, source, target = "ens://$name$suffix")
            return
        }

        // A contract-hosted app (#123): its document is read from the
        // chain here, gated on how it was read, and handed to the
        // interceptor with the navigation.
        if (OnchainAppRef.isWeb3Scheme(canonical)) {
            submitOnchainApp(target, canonical, source, approvedUri, namedByUser, bypassCache)
            return
        }

        val url = UrlParser.toUrl(canonical, searchTemplate)
        // What was typed, for "Search for … instead" if it isn't found (#419); a Reload keeps it.
        target.typedAddress = (if (source == SubmitSource.User) typedAddressFor(canonical, url, searchTemplate) else null)
            ?: target.typedAddress?.takeIf { it.isFor(url) }
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
        // A Reload, or an edited path, of the name on screen maps onto
        // its own origin ([BrowserState.effectiveFetchUrl]) and keeps the
        // tab's override — generic stays generic, a typed scheme keeps
        // asserting — instead of the next Reload re-reading the shown
        // `ipfs://name.eth` as a fresh assertion (#97).
        if (!target.isUnderOverride(url)) target.clearEnsOverride()

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
                        namedByUser = namedByUser,
                        bypassCache = bypassCache,
                    )
                } finally {
                    target.resolving = false
                    target.finishPendingProbe(coroutineContext.job)
                }
            }
            target.beginPendingProbe(contentProbe, source, target = contentUri)
            return
        }

        target.loadUrl(url, namedByUser = namedByUser, bypassCache = bypassCache)
    }

    // The bar's Reload and the Reload on a tab whose renderer went away
    // (#260). That tab has no WebView: it's rebuilt from what it was
    // parked with, which loads its page again with its history.
    val reloadPage: () -> Unit = {
        if (state.rendererGone != null) {
            state.recoverRenderer()
        } else {
            val url = state.reloadUrl()
            if (url.isNotBlank()) submit(state, url)
        }
    }

    // The per-site ad-blocking switch (#126), in the page menu and in
    // Page info: whether blocking is really on for the page — not
    // allowlisted, an engine loaded, no list exempting it — re-read
    // whenever the allowlist or the engine changes.
    val adblockRevision by Adblock.revision.collectAsState()
    val adblockState = remember(adblockRevision, state.url, state.private, state.showsErrorPage) {
        // An error page has no ads to block: no switch (#419).
        if (state.showsErrorPage) null else Adblock.siteState(state.url, state.private)
    }
    val toggleAdblock: () -> Unit = toggle@{
        val site = adblockSiteFor(state.url) ?: return@toggle
        val current = Adblock.siteState(state.url, state.private) ?: return@toggle
        if (!current.toggleable) return@toggle
        Adblock.setAllowlisted(site, allowed = current.checked, private = state.private)
        if (dropsMemoryCache(current)) tabs.dropMemoryCache?.invoke(state)
        // Already-loaded ads (or already-blocked content)
        // only change with the next load of the page.
        val url = state.url.ifBlank { state.addressBarText }
        if (url.isNotBlank()) submit(state, url)
    }

    // The menu's Hard reload (#262): the same reload, with the HTTP cache
    // bypassed for its load and that load's subresources, and — on a
    // dweb page — the interceptor's own caches skipped for its document.
    // A tab whose renderer went away has nothing cached in a page to
    // bypass: its menu row is disabled ([BrowserState.hasPageToActOn]).
    val hardReloadPage: () -> Unit = {
        if (state.rendererGone == null) {
            val url = state.reloadUrl()
            if (url.isNotBlank()) submit(state, url, bypassCache = true)
        }
    }

    // "New private tab" (#86), from the menu and the tab switcher —
    // null, so neither offers it, where the WebView can't run private
    // tabs (no multi-profile support).
    val privateTabsSupported = remember { PrivateProfile.isSupported() }
    val newPrivateTab: (() -> Unit)? = if (privateTabsSupported) {
        {
            val fresh = tabs.newTab(private = true)
            submit(fresh, tabs.homepageUrl)
        }
    } else {
        null
    }

    // The menu's Add to Home screen (#400): a launcher shortcut to the
    // page on screen, through the incoming-link path. Address, title and
    // the site's cached favicon are taken at the tap, so a navigation
    // landing while the icon is drawn can't retarget it. Null where it
    // isn't offered — a private tab above all ([homeScreenShortcutTarget]).
    val addToHomeScreen: (() -> Unit)? = homeScreenShortcutTarget(state.url, state.private, state.showsErrorPage)?.let { target ->
        {
            val label = homeScreenShortcutLabel(state.title, target)
            val favicons = repo.favicon(target)
            scope.launch {
                val favicon = runCatching { withTimeoutOrNull(1_000) { favicons.first() } }.getOrNull()
                val icon = withContext(Dispatchers.Default) { HomeScreenShortcuts.icon(target, label, favicon) }
                if (!HomeScreenShortcuts.request(context, target, label, icon)) {
                    snackbarHostState.showSnackbar(Strings.get(R.string.browser_add_to_home_screen_failed))
                }
            }
        }
    }
    // The menu's Wallet row (#400) says "Not set up" while that holds.
    val walletState by remember(context) { Vault.get(context) }.state.collectAsState()

    // The bar's New tab, and Ctrl+T (#270).
    val openNewTab: () -> Unit = {
        val fresh = tabs.newTab()
        submit(fresh, tabs.homepageUrl)
    }

    // The menu's zoom row, and Ctrl+=/−/0 (#270). Same rule as Find in
    // page: nothing to zoom on the home surface — nor on a document that
    // isn't a site (an error page), which has no zoomSite.
    val zoomableSite = state.zoomSite?.takeIf { state.url.isNotBlank() }
    val zoomPage: (ZoomAction) -> Unit = { action ->
        zoomableSite?.let { pageZoom.apply(it, action, state.private) }
    }

    // Hardware-keyboard shortcuts (#270), each doing what its button or
    // menu row does, under the same conditions. Not while a full-screen
    // panel is up: its own fields and buttons get the keys, and nothing
    // changes behind it.
    //
    // Over HTML5 fullscreen (#307 R2-F1) a shortcut that acts first
    // leaves it, as Back and Esc do: every one of them either changes what the screen
    // shows — another tab, a panel, the address bar, the find bar — or
    // acts on the page the fullscreen view stands in front of, and none
    // of that may happen unseen behind a view that stays up. One that
    // would do nothing leaves the video playing (#307 R4-M1).
    val onShortcut = ShortcutTarget { shortcut, repeat ->
        if (overlayShown) return@ShortcutTarget false
        // Belt and braces for [Shortcut.caretKey]: the address field keeps
        // Alt+←/→ even if the focused view didn't say it's an editor.
        if (shortcut.caretKey && addressFocused) return@ShortcutTarget false
        // Ctrl+L, and a new tab the user will type an address into.
        val requestAddressFocus = {
            pageKeyboardHandoff = true
            addressFocusRequested = true
        }
        // What the shortcut does right now, or null where its button or
        // menu row would do nothing — one tab to cycle through, no
        // forward history, nothing to reopen, nothing to zoom (#307 R4-M1).
        val action: (() -> Unit)? = when (shortcut) {
            Shortcut.NewTab -> {
                {
                    openNewTab()
                    requestAddressFocus()
                }
            }
            Shortcut.NewPrivateTab -> newPrivateTab?.let { open ->
                {
                    open()
                    requestAddressFocus()
                }
            }
            Shortcut.CloseTab -> {
                {
                    focusManager.clearFocus()
                    tabs.closeTab(tabs.activeIndex)
                }
            }
            Shortcut.ReopenClosedTab -> if (tabs.canReopenClosedTab) {
                {
                    focusManager.clearFocus()
                    tabs.reopenClosedTab()
                }
            } else {
                null
            }
            Shortcut.NextTab, Shortcut.PreviousTab -> if (tabs.tabs.size > 1) {
                {
                    val step = if (shortcut == Shortcut.NextTab) 1 else -1
                    focusManager.clearFocus()
                    tabs.switchTo(Math.floorMod(tabs.activeIndex + step, tabs.tabs.size))
                }
            } else {
                null
            }
            Shortcut.FocusAddressBar -> {
                {
                    if (findOpen) closeFind()
                    state.capsuleCollapse.expand()
                    requestAddressFocus()
                }
            }
            Shortcut.Reload -> reloadPage
            Shortcut.HardReload -> if (state.hasPageToActOn) hardReloadPage else null
            Shortcut.FindInPage -> if (state.hasPageToActOn) {
                { state.find.show() }
            } else {
                null
            }
            Shortcut.ZoomIn, Shortcut.ZoomOut, Shortcut.ZoomReset -> zoomableSite?.let {
                {
                    zoomPage(
                        when (shortcut) {
                            Shortcut.ZoomIn -> ZoomAction.In
                            Shortcut.ZoomOut -> ZoomAction.Out
                            else -> ZoomAction.Reset
                        },
                    )
                }
            }
            Shortcut.Back ->
                if (backActionFor(state.canGoBack, state.isHome) != BackAction.None) goBack else null
            Shortcut.Forward -> if (state.canGoForward) goForward else null
            Shortcut.History -> {
                {
                    focusManager.clearFocus()
                    historyPrivate = state.private
                    showHistory = true
                }
            }
            Shortcut.Downloads -> {
                {
                    focusManager.clearFocus()
                    showDownloads = true
                }
            }
        }
        // Where the WebView can't run private tabs there is no such
        // command at all: Ctrl+Shift+N goes on to the focused view as an
        // unbound key would (#307 R4-M2). Any other idle shortcut is
        // still the browser's, and is taken without effect — and without
        // leaving fullscreen for nothing.
        if (action == null) return@ShortcutTarget shortcut != Shortcut.NewPrivateTab
        if (repeat && !shortcut.repeats) return@ShortcutTarget true
        tabs.exitFullscreen()
        action()
        true
    }
    val currentOnShortcut by rememberUpdatedState(onShortcut)
    DisposableEffect(shortcuts) {
        val target = ShortcutTarget { shortcut, repeat -> currentOnShortcut.onShortcut(shortcut, repeat) }
        shortcuts?.target = target
        onDispose { if (shortcuts?.target === target) shortcuts.target = null }
    }
    val fullscreenPage = tabs.fullscreen?.view
    DisposableEffect(shortcuts, fullscreenPage) {
        shortcuts?.fullscreenPage = fullscreenPage
        onDispose {
            if (shortcuts != null && shortcuts.fullscreenPage === fullscreenPage) shortcuts.fullscreenPage = null
        }
    }

    // A new tab for [url], submitted as the user's own choice. A
    // background tab says so in a snackbar whose Switch brings it
    // forward — otherwise nothing on screen would change — and then
    // runs [onSwitch] (a panel it was opened from closes, so the tab
    // is what's on screen, #321).
    fun openInNewTab(url: String, background: Boolean, private: Boolean, onSwitch: () -> Unit = {}) {
        val fresh = tabs.newTab(activate = !background, private = private)
        submit(fresh, url)
        if (background) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = if (private) {
                        Strings.get(R.string.browser_opened_in_new_private_tab)
                    } else {
                        Strings.get(R.string.browser_opened_in_new_tab)
                    },
                    actionLabel = Strings.get(R.string.browser_opened_switch),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val index = tabs.tabs.indexOf(fresh)
                    if (index >= 0) {
                        tabs.switchTo(index)
                        onSwitch()
                    }
                }
            }
        }
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
        // The page context menu's "Open in new tab" and the selection
        // toolbar's "Search" (#84). Both are the user's own choice, so
        // they submit as [SubmitSource.User] ([openInNewTab]).
        tabs.requestOpenInNewTab = { url, background, private -> openInNewTab(url, background, private) }
        // Read [searchTemplate] when the search runs, so a change of
        // engine in Settings applies to the next one.
        tabs.requestSearchInNewTab = { query, private ->
            tabs.requestOpenInNewTab?.invoke(UrlParser.searchUrl(query, searchTemplate), false, private)
        }
        onDispose {
            tabs.requestSubmit = null
            tabs.requestNodeRecovery = null
            tabs.requestOpenInNewTab = null
            tabs.requestSearchInNewTab = null
        }
    }

    // A link, share or search from another app (#268, see
    // IncomingLinkActivity), as its address-bar form or the search URL
    // for the engine chosen in Settings — read from the store rather than
    // [searchTemplate], which on a cold start is still its initial value.
    // Submitted as [SubmitSource.External]: the pill shows it, but it
    // earns none of a user-named load's gesture credit.
    suspend fun deepLinkUrl(link: DeepLink): String =
        if (link.search) {
            val template = try {
                NodeSettings.get(context).searchTemplate.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchTemplate
            }
            UrlParser.searchUrl(link.url, template)
        } else {
            link.url
        }

    // The first load of the tab list, then every link from another app
    // (MainActivity queues them all, the one the app was cold-started
    // from included — even while the nodes are still starting, which
    // [submit]'s probes wait out). [deepLink] is the head of that queue
    // (several links can arrive in one frame); [onDeepLinkHandled] pops it
    // so a config change doesn't re-open it and the next link gets its
    // turn.
    LaunchedEffect(deepLink) {
        val link = deepLink
        // Once per tab list ([TabsState.initialLoadDone]): not again into
        // the active tab of tabs that outlived a relaunch (#183). The home
        // page is a native Compose overlay (see [HomeScreen]), so this
        // just primes the tab without loading anything over the network;
        // the address bar isn't focused — the home surface should be the
        // first thing the user sees, not an already-open keyboard. A cold
        // start from a link opens it in that first tab instead, so no
        // empty Home tab is left behind it.
        //
        // The search engine is read before anything is touched: popping
        // the link ([onDeepLinkHandled]) restarts this effect, so that
        // comes last, after the link has its tab.
        val url = link?.let { deepLinkUrl(it) }
        // The tabs the app had, if it's coming back from being closed
        // (#400): read from disk first, so the homepage isn't submitted
        // into a tab they replace, and a link opens beside them.
        tabsSession.ready.await()
        if (!tabs.initialLoadDone) {
            tabs.initialLoadDone = true
            if (link == null || url == null) {
                submit(tabs.active, tabs.homepageUrl)
            } else {
                submit(tabs.active, url, SubmitSource.External)
                onDeepLinkHandled(link)
            }
            return@LaunchedEffect
        }
        if (link == null || url == null) return@LaunchedEffect
        // Arriving while we're running: a second destination, so it gets
        // its own tab rather than replacing whatever the user was reading.
        // Whatever full-screen overlay was up would otherwise hide it.
        showSettings = false
        showNodes = false
        nodeDetail = null
        showLogs = null
        showWallet = false
        showTabSwitcher = false
        showHistory = false
        showBookmarks = false
        showDownloads = false
        submit(tabs.newTab(), url, SubmitSource.External)
        onDeepLinkHandled(link)
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
    // A page's keyboard handed over by Ctrl+L either goes down (the
    // WebView hid it on blur, or a hardware keyboard means the field
    // never asks for one back) — which ends the handoff below — or it
    // simply stays up, now serving the address field: moving focus from
    // one editor to another needn't hide the IME at all (on API 36 it
    // doesn't). In that second case the handoff must end too, or the
    // keyboard is never counted as seen and dismissing it leaves the
    // editor stuck open (#307 R1-F1) — and it must end *before* the user
    // can have dismissed it, which is a fraction of a second, not the
    // time the keyboard has stayed up (#307 R4-F1). So: a hide that
    // starts within [PAGE_KEYBOARD_HANDOFF_SETTLE_MS] of the focus
    // landing is the focus change's; the target flips at the start of a
    // hide, and restarts this effect before the delay runs out. A
    // keyboard still headed up once it has is the address bar's.
    val imeTargetVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    LaunchedEffect(pageKeyboardHandoff, addressFocused, imeTargetVisible) {
        if (pageKeyboardHandoff && addressFocused && imeTargetVisible) {
            delay(PAGE_KEYBOARD_HANDOFF_SETTLE_MS)
            pageKeyboardHandoff = false
        }
    }
    LaunchedEffect(addressFocused, keyboardVisible, pageKeyboardHandoff) {
        if (imeDismissalEndsEditing(
                addressFocused = addressFocused,
                keyboardVisible = keyboardVisible,
                keyboardWasSeen = keyboardSeenWhileEditing,
            )
        ) {
            focusManager.clearFocus()
        }
        keyboardSeenWhileEditing = addressFocused && keyboardVisible && !pageKeyboardHandoff
        if (!keyboardVisible) pageKeyboardHandoff = false
    }

    // The capsule's geometry is driven by exactly two 0→1 fractions,
    // and they are one model rather than two (see [capsuleDrawnHeight]):
    // the capsule has three drawn heights — 32 dp compact, 44 dp
    // resting, 64 dp editing, all inside a 48 dp slot — and these two
    // numbers say which.
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
    // Reserved mode (#66): a page with its own bottom nav gets the band
    // under the capsule to itself instead of running beneath it. The
    // reserve is the capsule's *resting* footprint and changes only when
    // the mode or the keyboard does — never with `collapseFraction` or
    // `editProgress` — so the compact and editing morphs never resize the
    // WebView (see [contentBottomReserve] and #63).
    val chromeMode = effectiveBottomChromeMode(state.bottomChromeMode, isHomeTab)
    // Reserved (#66) or revealed (#65): the page area stops above the
    // capsule and the strip fills the band under it.
    val reserved = chromeMode.shortensPage
    // The tab's WebView reads this at touch-down: no reveal while the
    // chrome is busy with the address bar or the keyboard (#65), and
    // the surface colour a reveal falls back to without a tint sample.
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    SideEffect {
        state.chromeEditing = addressFocused || keyboardVisible
        state.surfaceArgb = surfaceArgb
    }
    val navInsetDp = with(density) { navInsetPx.toDp() }
    val imeInsetDp = with(density) { imeInsetPx.toDp() }
    val contentBottomReserve = contentBottomReserve(
        mode = chromeMode,
        keyboardVisible = keyboardVisible,
        capsuleFootprint = capsuleFootprint,
        navInset = navInsetDp,
        imeInset = imeInsetDp,
    )

    // How much of the content area the capsule still covers once that
    // reserve is applied — zero while the keyboard is up, its own
    // footprint plus the navigation inset the content draws behind in
    // overlay, and in reserved only the editing morph's growth past the
    // reserved band. Native surfaces ([HomeScreen], [SuggestionsPanel])
    // pad by it so their last row stays clear of the chrome.
    val capsuleOverlap = capsuleOverlap(
        mode = chromeMode,
        keyboardVisible = keyboardVisible,
        capsuleFootprint = capsuleFootprint,
        navInset = navInsetDp,
    )

    // The strip under the capsule in reserved mode: a solid fill in the
    // page's own nav colour (else its theme-color, else its background,
    // else the theme surface — see [bottomStripArgb]). Colour changes
    // while reserved (an SPA route into a view with a differently
    // coloured nav) cross-fade; entering reserved, or switching tabs,
    // takes the colour in the same frame the page moves up, so a light
    // nav never fades in from the dark app background. Only this strip
    // animates — the WebView's size never does. The colour is read in
    // the draw phase, so a fade redraws the strip and nothing else, and
    // a settled strip draws nothing new.
    val stripTarget = Color(
        bottomStripArgb(state.bottomStripRgb, MaterialTheme.colorScheme.surface.toArgb()),
    )
    val stripColor = remember { Animatable(stripTarget) }
    var stripShownFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(stripTarget, reserved, state.id) {
        if (reserved && stripShownFor == state.id) {
            stripColor.animateTo(stripTarget, tween(STRIP_FADE_MS))
        } else {
            stripColor.snapTo(stripTarget)
        }
        stripShownFor = if (reserved) state.id else null
    }

    // The band behind the status bar (#92): the page's theme colour, or
    // the app background (the colour this screen has always shown there)
    // when it has none or the tab is home. Same fade rules as the strip:
    // a colour change on the tab on screen cross-fades, a tab switch
    // takes the new tab's colour in the same frame as its page. Read in
    // the draw phase, so a fade redraws the band and nothing else.
    val tint = statusBarTint(state.themeColorArgb, isHomeTab)
    val bandTarget = tint?.let(::Color) ?: MaterialTheme.colorScheme.background
    val bandColor = remember { Animatable(bandTarget) }
    var bandShownFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(bandTarget, state.id) {
        if (bandShownFor == state.id) {
            bandColor.animateTo(bandTarget, tween(STRIP_FADE_MS))
        } else {
            bandColor.snapTo(bandTarget)
        }
        bandShownFor = state.id
    }
    // The status-bar icons follow the band — but only while it is what's
    // under them: a full-screen panel paints the app background there.
    val iconTint = tint.takeIf { !overlayShown }
    LaunchedEffect(iconTint) { onStatusBarTint(iconTint) }
    // …and the navigation bar drops its contrast scrim over a panel
    // (#247), so the panel's own background shows there.
    LaunchedEffect(overlayShown) { onPanelShown(overlayShown) }

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
    val dismissKeyboardOnTap = Modifier.endEditOnPress(focusManager, keyboard)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(contentInsets)
                .drawBehind {
                    drawRect(if (bandShownFor == state.id) bandColor.value else bandTarget)
                },
        )
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
        // rest comes from a push past the end of the page, which
        // shortens the page area the same way (scroll-to-reveal, #65,
        // [ScrollRevealSlot]). Re-checked for
        // #66 on WebView 133: View padding, a fake 400 px system-bar /
        // display-cutout inset dispatched to the WebView, and
        // `setOverScrollMode` all leave `innerHeight`, `scrollHeight`
        // and `env(safe-area-inset-bottom)` untouched. A page with its
        // own bottom nav therefore gets the band shrunk out of the
        // page area instead (reserved mode, [contentBottomReserve]).
        //
        // When the address bar is focused we overlay the suggestions
        // panel on top of it rather than unmounting the WebView — that
        // keeps the underlying page alive (scroll position, JS timers,
        // media) across focus changes. The suggestions are the page layer's sibling, outside
        // [dismissKeyboardOnTap] — inside it, a press on a row cleared
        // focus and unmounted the panel before the click landed (#170,
        // see [PageWithSuggestions]).
        val suggestionsShown = addressFocused && addressBarEdited && addressQuery.isNotEmpty()
        PageWithSuggestions(
            dismissKeyboardOnTap = dismissKeyboardOnTap,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets)
                .padding(bottom = contentBottomReserve),
            suggestions = if (!suggestionsShown) null else {
                {
                    SuggestionsPanel(
                        repo = repo,
                        query = addressQuery,
                        searchTemplate = searchTemplate,
                        tabs = tabs.tabs.map { t ->
                            TabCandidate(t.id, t.addressBarText.ifBlank { t.url }, t.title, t.private)
                        },
                        currentTabId = state.id,
                        private = state.private,
                        searchSuggestionsOn = searchSuggestionsOn,
                        onPick = { submit(state, it) },
                        onSwitchToTab = { id ->
                            // End the edit first, as a submit does, then
                            // bring the tab up.
                            focusManager.clearFocus()
                            keyboard?.hide()
                            val index = tabs.tabs.indexOfFirst { it.id == id }
                            if (index >= 0) tabs.switchTo(index)
                        },
                        onFill = { addressFill = AddressFill(it) },
                        bottomContentPadding = capsuleOverlap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        ) {
            BrowserWebViewHost(
                tabs = tabs,
                modifier = Modifier.fillMaxSize(),
                covered = overlayShown,
                // Closing the last private tab clears the nodes' logs (#276).
                onPrivateSessionEnded = clearNodeLogs,
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
            if (isHomeTab && state.private) {
                PrivateTheme(private = true) {
                    PrivateHomeScreen(
                        bottomContentPadding = capsuleOverlap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (isHomeTab) {
                val appUpdate by AppUpdates.state.collectAsState()
                HomeScreen(
                    repo = repo,
                    onOpen = { submit(state, it) },
                    onOpenInNewTab = { url, private -> openInNewTab(url, background = true, private = private) },
                    nodeInfo = nodeInfo,
                    runNodeEnabled = runNodeEnabled,
                    // The warm-up row is about the Swarm node: its page, straight.
                    onOpenNode = { nodeDetail = NodeDestination.Swarm },
                    bottomContentPadding = capsuleOverlap,
                    modifier = Modifier.fillMaxSize(),
                    update = appUpdate.notice,
                    onDismissUpdate = AppUpdates::dismiss,
                )
            }
            // The tab's renderer went away (#260) while it was on
            // screen: why, and Reload. (One on the home surface is
            // rebuilt at once, behind the home overlay.)
            val rendererGone = state.rendererGone
            if (rendererGone != null && !isHomeTab) {
                RendererGoneScreen(
                    gone = rendererGone,
                    onReload = reloadPage,
                    bottomContentPadding = capsuleOverlap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The strip of reserved mode (#66) and of a reveal (#65):
        // everything below the page area — the reserve itself plus
        // whatever the IME inset takes.
        if (reserved) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(contentBottomReserve + imeInsetDp)
                    // Until the effect above has caught up with a strip
                    // that just appeared, the target itself: the
                    // Animatable is snapped a frame late, and that frame
                    // showed the previous colour (seen frame by frame in a
                    // screen recording of a reveal, #65).
                    .drawBehind {
                        drawRect(if (stripShownFor == state.id) stripColor.value else stripTarget)
                    },
            )
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
                // A private tab's chrome wears the private scheme (#86).
                PrivateTheme(state.private) {
                // …and its text fields (address bar, find bar) keep the
                // keyboard from learning what is typed in them.
                TabTextInput(state.private) {
                if (findOpen) {
                    // Keyed on the tab: each tab's bar is its own field,
                    // seeded from that tab's query.
                    key(state.id) {
                        FindBar(
                            tab = state,
                            onQueryChange = { tabs.find?.invoke(state, FindAction.Search(it)) },
                            onStep = { tabs.find?.invoke(state, FindAction.Step(it)) },
                            onClose = closeFind,
                            modifier = Modifier
                                .widthIn(max = CHROME_MAX_WIDTH)
                                .fillMaxWidth(),
                        )
                    }
                } else BottomToolbar(
                    state = state,
                    tabCount = tabs.tabs.size,
                    nodesNote = nodesNote,
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
                    onBack = goBack,
                    onForward = goForward,
                    onHome = {
                        submit(state, tabs.homepageUrl)
                    },
                    onToggleBookmark = {
                        val url = state.url
                        if (url.isBlank()) return@BottomToolbar
                        if (isBookmarked) {
                            repo.unbookmark(url)
                        } else {
                            // Saved under the page's title; the snackbar
                            // offers to name it (#264).
                            val added = repo.bookmark(url, state.title)
                            val private = state.private
                            scope.launch {
                                val saved = added.await() ?: return@launch
                                val id = saved.id
                                // The star can still show the last page's
                                // state for a moment; a page that turns out
                                // to be bookmarked already isn't "added".
                                val result = snackbarHostState.showSnackbar(
                                    if (saved.added) {
                                        Strings.get(R.string.browser_bookmark_added)
                                    } else {
                                        Strings.get(R.string.browser_bookmark_already)
                                    },
                                    actionLabel = Strings.get(R.string.common_edit),
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    editBookmark = id
                                    editBookmarkPrivate = private
                                }
                            }
                        }
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
                    onOpenSettings = {
                        settingsInitialSection = null
                        showSettings = true
                    },
                    onOpenNode = { showNodes = true },
                    onOpenTabs = { showTabSwitcher = true },
                    onOpenHistory = {
                        historyPrivate = state.private
                        showHistory = true
                    },
                    onOpenBookmarks = {
                        bookmarksPrivate = state.private
                        showBookmarks = true
                    },
                    onOpenDownloads = { showDownloads = true },
                    onOpenWallet = { showWallet = true },
                    walletNote = walletMenuNote(walletState),
                    onAddToHomeScreen = addToHomeScreen,
                    onReload = reloadPage,
                    onHardReload = hardReloadPage,
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
                    onNewTab = openNewTab,
                    onNewPrivateTab = newPrivateTab,
                    onFindInPage = { state.find.show() },
                    // Same rule as Find in page: nothing to zoom on the
                    // home surface — nor on a document that isn't a
                    // site (an error page), which has no zoomSite.
                    zoomLevel = zoomableSite?.let { pageZoom.levelFor(it, state.private) },
                    onZoom = zoomPage,
                    // Request desktop site (#180): per site, like zoom,
                    // but never for a dweb page (no key). Toggling asks
                    // for the page again, as Reload does, and the load
                    // picks the user agent for its site.
                    // Nor on an error page, which has no site's page
                    // to ask for again ([menuDesktopSite]).
                    desktopSite = menuDesktopSite(state.zoomSite, state.url, state.showsErrorPage)
                        ?.let { desktopSites.isDesktop(it, state.private) },
                    onToggleDesktopSite = {
                        menuDesktopSite(state.zoomSite, state.url, state.showsErrorPage)?.let { site ->
                            desktopSites.toggle(site, state.private)
                            val url = state.url.ifBlank { state.addressBarText }
                            if (url.isNotBlank()) submit(state, url)
                        }
                    },
                    onPrint = { tabs.printPage?.invoke(state) },
                    onOpenPageInfo = openPageInfo,
                    adblockState = adblockState,
                    onToggleAdblock = toggleAdblock,
                    modifier = Modifier
                        .widthIn(max = CHROME_MAX_WIDTH)
                        .fillMaxWidth(),
                    addressFocusRequested = addressFocusRequested,
                    onAddressFocusRequestHandled = { addressFocusRequested = false },
                    addressFill = addressFill,
                    onAddressFillHandled = { addressFill = null },
                )
                }
                }
            }
        }

        // Camera/microphone in use (#266): the lowest thing over the
        // capsule, at its end, with everything else stacked above it.
        // Not while the address bar is open (the editor owns the band)
        // nor under a panel — Android's own indicator still shows then.
        val mediaIndicatorShown = mediaInUse.isNotEmpty() && !addressFocused && !overlayShown
        var mediaIndicatorHeightPx by remember { mutableIntStateOf(0) }
        if (mediaIndicatorShown) {
            MediaInUseIndicator(
                inUse = mediaInUse,
                onClick = openSitePermissions,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(chromeInsets)
                    .padding(
                        end = CapsuleSideMargin,
                        bottom = capsuleSlot + CapsuleBottomMargin + IpfsStatusGap,
                    )
                    .onSizeChanged { mediaIndicatorHeightPx = it.height },
            )
        }
        val mediaLift = if (mediaIndicatorShown) {
            with(density) { mediaIndicatorHeightPx.toDp() } + IpfsStatusGap
        } else {
            0.dp
        }

        // The IPFS phase line (#94) sits where a snackbar would: above
        // the capsule's slot, clear of the page's own bottom edge. Not
        // while the address bar is open — the editor owns that band.
        val ipfsLine = ipfsStatus?.takeIf { ipfsStatusTab == state.id && !addressFocused }
        // The pill's measured height, so a snackbar raised while it is up
        // stacks above it instead of drawing over it.
        var ipfsLineHeightPx by remember { mutableIntStateOf(0) }
        AnimatedVisibility(
            visible = ipfsLine != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(chromeInsets)
                .padding(
                    start = CapsuleSideMargin,
                    end = CapsuleSideMargin,
                    bottom = capsuleSlot + CapsuleBottomMargin + IpfsStatusGap + mediaLift,
                ),
        ) {
            // Keep drawing the last line while it fades out.
            var shown by remember { mutableStateOf("") }
            if (ipfsLine != null) shown = ipfsLine
            IpfsStatusLine(
                text = shown,
                modifier = Modifier.onSizeChanged { ipfsLineHeightPx = it.height },
            )
        }
        val ipfsLift = mediaLift + if (ipfsLine != null) {
            with(density) { ipfsLineHeightPx.toDp() } + IpfsStatusGap * 2
        } else {
            0.dp
        }

        // The pop-up blocker's notice (#261): the active tab's blocked
        // pop-ups, above the IPFS line when that is up. Only over the
        // page — not while the address bar is open, nor under a panel,
        // nor under a page's fullscreen view.
        val blockedPopups = state.blockedPopups
        val popupNoticeShown = blockedPopupNoticeShown(
            hasEntries = blockedPopups.entries.isNotEmpty(),
            addressFocused = addressFocused,
            overlayShown = overlayShown,
            fullscreen = tabs.fullscreen != null,
        )
        var popupNoticeHeightPx by remember { mutableIntStateOf(0) }
        val popupNoticeTopInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top)
        if (popupNoticeShown) {
            BlockedPopupNotice(
                popups = blockedPopups,
                private = state.private,
                displayUrl = { displayFor(it, state) },
                onOpen = { entry, url ->
                    blockedPopups.remove(entry)
                    tabs.requestOpenInNewTab?.invoke(url, false, state.private)
                },
                onAlwaysAllow = { origin ->
                    sitePermissions.allowPopups(state, origin)
                    blockedPopups.markAllowed()
                },
                onClose = { blockedPopups.clear() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(chromeInsets)
                    // Its height is capped to what's left under the status
                    // bar; past that its rows scroll (#292 R5-F1).
                    .windowInsetsPadding(popupNoticeTopInsets)
                    .padding(
                        start = CapsuleSideMargin,
                        end = CapsuleSideMargin,
                        top = CapsuleBottomMargin,
                        bottom = capsuleSlot + CapsuleBottomMargin + IpfsStatusGap + ipfsLift,
                    )
                    .widthIn(max = CHROME_MAX_WIDTH)
                    .fillMaxWidth()
                    .onSizeChanged { popupNoticeHeightPx = it.height },
            )
        }
        val snackbarLift = ipfsLift + if (popupNoticeShown) {
            with(density) { popupNoticeHeightPx.toDp() } + IpfsStatusGap * 2
        } else {
            0.dp
        }

        // Snackbars pop up above the capsule rather than under it —
        // tracking the capsule's *slot* rather than its drawn height, so
        // they follow the editing morph as the bar inflates but sit
        // perfectly still when it compacts on scroll.
        // …unless a full-screen panel is up: it's opaque and composed
        // after this Box, so a host here would draw every snackbar under
        // it. The host then moves above the panels (see below).
        if (!overlayShown) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(chromeInsets)
                    .padding(bottom = capsuleSlot + CapsuleBottomMargin + snackbarLift),
            ) { data -> Snackbar(snackbarData = data) }
            if (snackbarHostState.currentSnackbarData == null) {
                SnackbarHost(
                    hostState = heldTabsHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(chromeInsets)
                        .padding(bottom = capsuleSlot + CapsuleBottomMargin + snackbarLift),
                ) { data -> Snackbar(snackbarData = data) }
            }
        }
    }

    // Delete browsing data (#400), from Settings or History (#418).
    val deleteBrowsingData: (DeleteChoice) -> Unit = { choice ->
        // The reopen stack keeps closed tabs' pages, titles and
        // back/forward lists — history by any other name. It has
        // no dates, so a ranged delete takes all of it. So do the
        // tabs a crashed restore held back (#402): the saved tab
        // list is rewritten from the open tabs alone, now.
        if (choice.forgetsClosedTabs) {
            tabs.forgetClosedTabs()
            tabsSession.forgetHeld()
            tabsSession.persistNow()
        }
        if (choice.siteData || choice.cache) tabs.clearWebViewData?.invoke(choice.siteData, choice.cache)
        // The nodes' logs can name what was browsed (#276).
        if (choice.siteData) clearNodeLogs()
    }

    if (showSettings) {
        SettingsScreen(
            repo = repo,
            ipfsInfo = ipfsInfo,
            onIpfsToggle = onIpfsToggle,
            radicle = radicle,
            onOpenRadicle = { nodeDetail = NodeDestination.Radicle },
            onOpenWallet = { showWallet = true },
            // Settings → Nodes & networks → Node status: the same overview
            // the menu opens (#416), over Settings; Back returns there.
            onOpenNodes = { showNodes = true },
            // A newer release's page (#272): a new tab in front, never a
            // private one, with Settings closed so it's on screen.
            onOpenUrl = { url ->
                showSettings = false
                tabs.requestOpenInNewTab?.invoke(url, false, false)
            },
            onDeleteBrowsingData = deleteBrowsingData,
            onDismiss = {
                showSettings = false
                settingsInitialSection = null
                // Opened from a node page: Back goes back to it.
                if (settingsBackToNodes || settingsBackToDetail != null) {
                    showNodes = settingsBackToNodes
                    nodeDetail = settingsBackToDetail
                    settingsBackToNodes = false
                    settingsBackToDetail = null
                }
            },
            onOpenIpfsLogs = { showLogs = NodeLogSource.Ipfs },
            // Settings search's "Delete all bookmarks is on the Bookmarks
            // page" hint (#400): Settings closes and Bookmarks opens.
            onOpenBookmarks = {
                showSettings = false
                bookmarksPrivate = state.private
                showBookmarks = true
            },
            initialSection = settingsInitialSection,
            sectionRequest = settingsSectionRequest,
            onSectionRequestTaken = { depth ->
                settingsSectionRequest = null
                settingsRequestBackTo = settingsRequestBackTo.take(depth) +
                    listOfNotNull(settingsRequestPending)
                settingsRequestPending = null
            },
            // Back from the card a node page moved this Settings to: the
            // overview and node page come back over it.
            onRequestedPageLeft = { depth ->
                settingsRequestBackTo.getOrNull(depth)?.let {
                    showNodes = it.nodes
                    nodeDetail = it.detail
                }
                settingsRequestBackTo = settingsRequestBackTo.take(depth)
            },
        )
    }

    // Settings at one of its cards, from a node page (#416). Settings is
    // composed under the node pages, so they close while it's up and
    // come back when it's dismissed — or, when Settings is already open
    // under them (the overview came from its Node status row), that
    // Settings goes to the card and they come back on Back from its page.
    val openSettingsAt: (SettingsSection) -> Unit = { target ->
        if (showSettings) {
            settingsRequestPending = NodeReturn(showNodes, nodeDetail)
            settingsSectionRequest = target
        } else {
            settingsBackToNodes = showNodes
            settingsBackToDetail = nodeDetail
            settingsInitialSection = target
            showSettings = true
        }
        showNodes = false
        nodeDetail = null
    }
    // However Settings closed (its Back, or a page it opened in a new tab
    // or the Bookmarks page), what it was opened at and from is done with.
    LaunchedEffect(showSettings) {
        if (!showSettings) {
            settingsInitialSection = null
            settingsSectionRequest = null
            settingsBackToNodes = false
            settingsBackToDetail = null
            settingsRequestBackTo = emptyList()
            settingsRequestPending = null
        }
    }

    // The overview is placed after SettingsScreen, so Settings' Node
    // status opens it over Settings, and before the node pages, which
    // open over it; Back from each returns to the one below.
    if (showNodes) {
        NodesOverviewScreen(
            input = NodeOverviewInput(
                nodeInfo = nodeInfo,
                externalSwarm = externalSwarmBase,
                ipfsInfo = ipfsInfo,
                externalIpfs = externalIpfsBase,
                radicleInfo = radicle.info,
                radicleEnabled = radicle.enabled,
                tor = tor,
                myotisInfo = myotisInfo,
                myotisRunning = myotisRunning,
            ),
            onOpen = { destination ->
                when (destination) {
                    NodeDestination.Rpc -> openSettingsAt(SettingsSection.Rpc)
                    NodeDestination.Gateways -> openSettingsAt(SettingsSection.Nodes)
                    else -> nodeDetail = destination
                }
            },
            onDismiss = { showNodes = false },
        )
    }

    // A node's page, over the overview (or Settings, for Radicle); Back /
    // ← dismisses only the node page.
    val detail = nodeDetail
    if (detail == NodeDestination.Swarm || detail == NodeDestination.Tor || detail == NodeDestination.LightClient) {
        NodeScreen(
            page = detail,
            nodeInfo = nodeInfo,
            runNodeEnabled = runNodeEnabled,
            onToggleRunNode = onToggleRunNode,
            myotisInfo = myotisInfo,
            myotisRunning = myotisRunning,
            onRunMyotisChain = onRunMyotisChain,
            tor = tor,
            onMyotisRecovery = onMyotisRecovery,
            // Publish setup's identity step (#114): the wallet page opens
            // over the node page (it's composed after it).
            onOpenWallet = { showWallet = true },
            // A published page (#118), or the fund-and-buy transaction's
            // explorer page (#115): a new tab in front, never a private one,
            // with the pages the node page was opened over closed too.
            onOpenUrl = { url ->
                nodeDetail = null
                showNodes = false
                showSettings = false
                tabs.requestOpenInNewTab?.invoke(url, false, false)
            },
            onDismiss = { nodeDetail = null },
            onOpenLogs = { showLogs = it },
            // At the Tor card, its switch in view.
            onOpenTorSettings = { openSettingsAt(SettingsSection.Tor) },
        )
    }

    if (detail == NodeDestination.Ipfs) {
        IpfsScreen(
            ipfsInfo = ipfsInfo,
            onIpfsToggle = onIpfsToggle,
            onOpenLogs = { showLogs = NodeLogSource.Ipfs },
            onDismiss = { nodeDetail = null },
        )
    }

    // The Radicle node (#73): from the overview, or Settings → Nodes & networks.
    if (detail == NodeDestination.Radicle) {
        RadicleScreen(
            radicle = radicle.copy(
                // A seeded repository, in the `rad://` browser (#124).
                onOpen = { rid ->
                    nodeDetail = null
                    showNodes = false
                    showSettings = false
                    submit(state, "rad://" + rid.removePrefix("rad:"))
                },
            ),
            runNodeEnabled = runNodeEnabled,
            onDismiss = { nodeDetail = null },
            onOpenLogs = { showLogs = NodeLogSource.Radicle },
        )
    }

    // Over the node card that opened it; Back returns there.
    showLogs?.let { source ->
        NodeLogsScreen(
            initial = source,
            read = readNodeLogs,
            onDismiss = { showLogs = null },
            externalTor = tor.proxy != null,
        )
    }

    if (showTabSwitcher) {
        TabSwitcherScreen(
            tabs = tabs,
            onDismiss = { showTabSwitcher = false },
            onNewTab = openNewTab,
            onNewPrivateTab = newPrivateTab,
            privatePane = switcherPrivatePane,
            onPrivatePaneChange = { switcherPrivatePane = it },
            onTabsClosed = { closed ->
                // Close all / Close other tabs (#320): say how many went,
                // with an Undo that brings back the ones that are kept
                // (none of a private tab's). One tab's × (#418): "Tab
                // closed" with its Undo — and no notice at all when there
                // is nothing to undo (a private tab, #86, or an empty
                // one), leaving an earlier Undo up. A newer close
                // replaces the notice of the last one. Gone from disk at
                // once (#400): an app killed while the notice is up
                // doesn't bring them back. Undo writes them again.
                tabsSession.persistNow()
                if (closed.count > 0 && !(closed.single && closed.undo == null)) {
                    tabsClosedNotice?.cancel()
                    tabsClosedNotice = scope.launch {
                        val undo = closed.undo
                        try {
                            val result = snackbarHostState.showSnackbar(
                                message = if (closed.single) {
                                    Strings.get(R.string.browser_tab_closed)
                                } else {
                                    Strings.plural(R.plurals.browser_tabs_closed, closed.count, closed.count)
                                },
                                actionLabel = undo?.let { Strings.get(R.string.browser_tabs_undo) },
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed && undo != null) tabs.reopenClosed(undo)
                        } finally {
                            // Held on the reopen stack only while its Undo is up.
                            undo?.let(tabs::undoWithdrawn)
                        }
                    }
                }
            },
        )
    }

    if (showHistory) {
        HistoryScreen(
            repo = repo,
            private = historyPrivate,
            onDismiss = { showHistory = false },
            onOpen = { url ->
                showHistory = false
                submit(state, url)
            },
            // Behind the list, which stays up for the next one; the
            // snackbar's Switch closes it (#321).
            onOpenInNewTab = { url, private ->
                openInNewTab(url, background = true, private = private, onSwitch = { showHistory = false })
            },
            onDeleteBrowsingData = deleteBrowsingData,
        )
    }

    // The "Bookmark added" snackbar's Edit (#264), over the page.
    editBookmark?.let { id ->
        BookmarkEditDialog(
            repo = repo,
            id = id,
            private = editBookmarkPrivate,
            onDismiss = { editBookmark = null },
        )
    }

    if (showBookmarks) {
        BookmarksScreen(
            repo = repo,
            private = bookmarksPrivate,
            onDismiss = { showBookmarks = false },
            onOpen = { url ->
                showBookmarks = false
                submit(state, url)
            },
            onOpenInNewTab = { url, private ->
                openInNewTab(url, background = true, private = private, onSwitch = { showBookmarks = false })
            },
        )
    }

    // Nothing a page asks to download is saved without a yes here: the
    // listener fires for script-driven downloads too, with no tap.
    // Only the tab in view asks — a background tab's offers wait until
    // the user switches to it — and a no blocks that tab's pages from
    // asking again until the user navigates it ([DownloadOffers]), so a
    // page firing downloads in a loop can't hold the browser behind
    // this modal prompt.
    val downloadOffers by downloads.offers.collectAsState()
    val droppedOffers by downloads.droppedOffers.collectAsState()
    val activeTabId = tabs.active.id
    val tabOffers = downloadOffers.filter { it.tabId == activeTabId }
    // It takes turns with the site-permission prompt (#81) on the same
    // tab — they never stack; see [modalPromptTurn] for the order.
    // Every one of them waits while a full-screen panel covers the
    // page (the Downloads list included, via [overlayShown]).
    val pageUncovered = !overlayShown
    val androidDialogUp by sitePermissions.androidDialogUp.collectAsState()
    var offerHasTurn by remember(activeTabId) { mutableStateOf(false) }
    var radicleHasTurn by remember(activeTabId) { mutableStateOf(false) }
    var ethereumHasTurn by remember(activeTabId) { mutableStateOf(false) }
    var swarmHasTurn by remember(activeTabId) { mutableStateOf(false) }
    var jsDialogHasTurn by remember(activeTabId) { mutableStateOf(false) }
    // The turn as of the last composition: a long-press menu is only
    // let in while the page is on screen and no prompt was up
    // ([contextMenuAdmitted]), so it can't un-show a prompt the user is
    // already reading, nor open when a panel that covered it closes.
    var lastPromptTurn by remember(activeTabId) { mutableStateOf(PromptTurn.None) }
    val contextMenuAdmitted = contextMenuAdmitted(lastPromptTurn, pageUncovered)
    val promptTurn = modalPromptTurn(
        permissionWaiting = state.permissionPrompt != null,
        offerWaiting = tabOffers.isNotEmpty(),
        offerHasTurn = offerHasTurn,
        androidDialogUp = androidDialogUp,
        // The `window.radicle` consent prompt (#124).
        radicleWaiting = state.radiclePrompt != null,
        radicleHasTurn = radicleHasTurn,
        // The `window.ethereum` approval sheets (#110).
        ethereumWaiting = state.ethereumPrompt != null,
        ethereumHasTurn = ethereumHasTurn,
        // The `window.swarm` approval sheets (#120).
        swarmWaiting = state.swarmPrompt != null,
        swarmHasTurn = swarmHasTurn,
        // The page's `alert`/`confirm`/`prompt`/`beforeunload` (#246).
        jsDialogWaiting = state.jsDialog != null,
        jsDialogHasTurn = jsDialogHasTurn,
        // The long-press link/image menu (#84), the user's own.
        contextMenuWaiting = contextMenuAdmitted && tabs.pageContextMenu?.tabId == activeTabId,
        // All of them, the download offer included, only over the page.
        pageUncovered = pageUncovered,
    )
    SideEffect {
        offerHasTurn = promptTurn == PromptTurn.DownloadOffer
        radicleHasTurn = promptTurn == PromptTurn.Radicle
        ethereumHasTurn = promptTurn == PromptTurn.Ethereum
        swarmHasTurn = promptTurn == PromptTurn.Swarm
        jsDialogHasTurn = promptTurn == PromptTurn.JsDialog
        lastPromptTurn = promptTurn
    }
    // A page waiting on a JavaScript dialog blocks the renderer every
    // tab shares (the tab in view freezes too), so a dialog only waits
    // for its turn while its page is on screen: another tab's is
    // answered straight away ([JsDialogRequest.withdraw]: `alert`
    // returns, `confirm` is false, a `beforeunload` nobody saw lets the
    // navigation go), as Chrome does for a background tab — and so is
    // one waiting in the tab in view while a full-screen panel covers
    // it, and the one a tab had when the user switches away from it.
    // A dialog already up keeps its turn over a panel ([modalPromptTurn]).
    val pageCovered by rememberUpdatedState(overlayShown)
    val jsDialogUp by rememberUpdatedState(jsDialogHasTurn)
    LaunchedEffect(tabs) {
        snapshotFlow {
            val activeId = tabs.active.id
            val activeUnseen = pageCovered && !jsDialogUp
            tabs.tabs.mapNotNull { tab ->
                tab.jsDialog?.takeIf { tab.id != activeId || activeUnseen }
            }
        }.collect { stale -> stale.forEach { it.withdraw() } }
    }
    // Long-press menu for a link / image on the page (#84). Dropped the
    // moment it stops describing what is on screen: the tab navigated,
    // closed, or another tab came to the front — or when it arrives
    // while one of the page's prompts is up (#246): it takes turns with
    // them rather than stacking, and one shown only once that prompt is
    // answered would open out of nowhere. Likewise while a full-screen
    // panel covers the page (the page's verdict can take a moment to
    // land, and the user may have opened the tab switcher meanwhile):
    // it's not kept for when the panel closes.
    tabs.pageContextMenu?.let { request ->
        val owner = tabs.tabs.firstOrNull { it.id == request.tabId }
        if (pageContextMenuIsStale(request, tabs.active.id, owner?.url, owner?.navCounter) ||
            !contextMenuAdmitted
        ) {
            LaunchedEffect(request) {
                if (tabs.pageContextMenu === request) tabs.pageContextMenu = null
            }
        } else if (owner != null && promptTurn == PromptTurn.ContextMenu) {
            fun withImage(url: String, action: suspend (FetchedImage, Long) -> Boolean, @androidx.annotation.StringRes failure: Int) {
                // Taken now, not once the fetch is back: a private image
                // fetched after its session ended is dropped (#86).
                val session = privateImageSession()
                scope.launch {
                    // The sheet is already gone: a refetch that isn't back
                    // almost at once says so, rather than leaving the user
                    // with nothing until the result (or failure) toast.
                    // The fetch itself is bounded by IMAGE_FETCH_DEADLINE_MS.
                    val progress = launch {
                        delay(IMAGE_FETCH_PROGRESS_DELAY_MS)
                        Toast.makeText(context, R.string.browser_image_loading, Toast.LENGTH_SHORT).show()
                    }
                    val image = try {
                        fetchImage(url, request.pageUrl, WebSettings.getDefaultUserAgent(context), owner.private)
                    } finally {
                        progress.cancel()
                    }
                    val ok = image != null && action(image, session)
                    if (!ok) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
                }
            }
            key(request) {
                PageContextMenuSheet(
                    target = request.target,
                    displayUrl = { displayFor(it, owner) },
                    onOpenInNewTab = { tabs.requestOpenInNewTab?.invoke(displayFor(it, owner), true, owner.private) },
                    onCopyLink = { copyUrlToClipboard(context, it) },
                    onShareLink = { url, title -> shareUrl(context, url, title) },
                    onOpenImage = { tabs.requestOpenInNewTab?.invoke(displayFor(it, owner), true, owner.private) },
                    onCopyImage = { url ->
                        withImage(url, { image, session -> copyImageToClipboard(context, image, url, owner.private, session) }, R.string.browser_image_copy_failed)
                    },
                    onSaveImage = { url ->
                        withImage(url, { image, _ ->
                            saveImage(context, image, url).also { saved ->
                                if (saved) {
                                    Toast.makeText(context, R.string.browser_image_saved, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, R.string.browser_image_save_failed)
                    },
                    onShareImage = { url ->
                        withImage(url, { image, session -> shareImage(context, image, url, owner.private, session) }, R.string.browser_image_share_failed)
                    },
                    onDismiss = {
                        if (tabs.pageContextMenu === request) tabs.pageContextMenu = null
                    },
                )
            }
        }
    }

    // The download notification (#265) carries Pause / Resume; on API
    // 33+ it needs a permission nothing else asks for, so the first
    // download the user accepts asks for it — once. Not a private one:
    // it never gets a notification, so the one ask would be spent on a
    // download that can't show what it's for.
    val downloadNotificationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    // Called once a (non-private) download is really accepted — after
    // the Save as picker when there is one, so the two don't stack.
    val askForDownloadNotifications = {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !DownloadNotifications.askedForPermission(context)
        ) {
            DownloadNotifications.markAskedForPermission(context)
            downloadNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // *Ask where to save each file* (#322): Download opens the system's
    // Save as picker, and the download starts only with the document it
    // answers. Backing out of the picker leaves the prompt up, to pick
    // again or cancel. The offer being picked for rides in saved state,
    // so an activity rebuilt while the picker is up still starts it.
    val askWhereToSave by remember(context) { NodeSettings.get(context).askWhereToSave }
        .collectAsState(initial = false)
    var savingOffer by rememberSaveable { mutableStateOf<Long?>(null) }
    val saveAsPicker = rememberLauncherForActivityResult(SaveAsContract()) { uri ->
        val key = savingOffer
        savingOffer = null
        when {
            uri == null -> Unit
            // A result nothing is waiting for: its document goes again.
            key == null -> downloads.abandonSaveTo(uri)
            else -> {
                downloads.accept(key, uri) {
                    // No lasting grant: the prompt stays up to pick elsewhere.
                    android.widget.Toast.makeText(
                        context, R.string.browser_download_save_to_no_access, android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                askForDownloadNotifications()
            }
        }
    }
    tabOffers.firstOrNull()?.takeIf { promptTurn == PromptTurn.DownloadOffer }?.let { offer ->
        DownloadOfferDialog(
            offer = offer,
            othersWaiting = tabOffers.size - 1,
            dropped = droppedOffers[activeTabId] ?: 0,
            onAccept = accept@{
                // A picker already up (a double tap on Download): one is
                // enough, and a second's document would have nobody to
                // save into it.
                if (savingOffer != null) return@accept
                val picked = asksWhereToSave(askWhereToSave, offer.private) && try {
                    saveAsPicker.launch(SaveAsRequest(offer.fileName, offer.mimeType))
                    savingOffer = offer.key
                    true
                } catch (_: android.content.ActivityNotFoundException) {
                    // No documents UI on this device: save as before.
                    false
                }
                if (!picked) {
                    downloads.accept(offer.key)
                    if (!offer.private) askForDownloadNotifications()
                }
            },
            onDecline = { downloads.decline(offer.key) },
            onDeclineAll = { downloads.declineAll(activeTabId) },
        )
    }
    // A background tab that has filled its own queue and is still
    // asking is invisible from here (its prompt waits until it's in
    // view), so say so once per episode instead of dropping silently.
    // The active tab needs no snackbar: its prompt carries the note.
    // It's a download notice, so opening the Downloads list withdraws
    // it and none is shown while the list is up (a tab that starts
    // dropping then, or whose notice the list withdrew, is announced
    // once it closes — the list itself doesn't show drops). Launched in the
    // screen's [scope], not this effect's, so another tab starting to
    // drop (which restarts the effect) can't cancel it.
    LaunchedEffect(droppedOffers.keys, showDownloads) {
        if (showDownloads) return@LaunchedEffect
        downloadNotices.announceDrops(scope, droppedOffers.keys, tabs.active.id) { tabId ->
            val result = snackbarHostState.showSnackbar(
                Strings.get(R.string.browser_download_background_dropped),
                actionLabel = Strings.get(R.string.browser_download_show),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val index = tabs.tabs.indexOfFirst { it.id == tabId }
                if (index >= 0) tabs.switchTo(index)
            }
        }
    }
    // A closed tab's offers (and block) go with it.
    val openTabIds = tabs.tabs.map { it.id }.toSet()
    LaunchedEffect(openTabIds) { downloads.retainOfferTabs(openTabIds) }

    if (showDownloads) {
        DownloadsScreen(
            downloads = downloads,
            onDismiss = { showDownloads = false },
            onOpen = { entry ->
                scope.launch {
                    downloads.open(context, entry)?.let { snackbarHostState.showSnackbar(it) }
                }
            },
        )
    }

    // Settings → Wallet (#75, #76), or a feature's request for one.
    // Composed after every other full-screen panel (Settings, Node, the
    // tab switcher, History, Bookmarks, Downloads) so a request arriving
    // while one of them is up opens on top of it rather than hidden
    // underneath, leaving its caller waiting on a page nobody can see.
    if (showWallet || walletRequest != null || linkSend != null) {
        // A new link's Send page starts from its own values.
        androidx.compose.runtime.key(linkSend) {
            WalletScreen(
                request = walletRequest,
                // The site the user came from, for Publisher identities (#119);
                // never a private tab's, which leaves nothing behind.
                currentSite = tabs.active.takeUnless { it.private }?.providerOrigin,
                // A transaction's explorer page (#105): a new tab in front, never a
                // private one — with the pages the wallet was opened over closed too.
                onOpenUrl = { url ->
                    linkSend?.closed()
                    linkSend = null
                    showWallet = false
                    showSettings = false
                    showNodes = false
                    nodeDetail = null
                    tabs.requestOpenInNewTab?.invoke(url, false, false)
                },
                onDismiss = {
                    // Answered before the page is uncovered: the ask is
                    // done with by the time it could have its turn again.
                    linkSend?.closed()
                    linkSend = null
                    showWallet = false
                },
                sendLink = linkSend?.prefill,
                onSendStarted = { linkSend?.started = true },
                onSendShown = { linkSend?.shown = true },
            )
        }
    }

    // Desktop Freedom's signing requests (#113): a dialog over whatever is up,
    // the scan page it was connected from included.
    RemoteSigningHost()

    // Snackbars over a full-screen panel (Downloads' open() failures
    // and retry outcomes, a download finishing while Settings is up):
    // composed after the panels so they're drawn on top, at the bottom
    // edge clear of the navigation bar. The Box doesn't take touches.
    if (overlayShown) {
        Box(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 8.dp),
            ) { data -> Snackbar(snackbarData = data) }
            if (snackbarHostState.currentSnackbarData == null) {
                SnackbarHost(
                    hostState = heldTabsHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(bottom = 8.dp),
                ) { data -> Snackbar(snackbarData = data) }
            }
        }
    }

    // Site-permission prompt (#81) — only ever the active tab's, and
    // only while its page is what's on screen: a background tab's
    // request waits until the user switches to it, any request waits
    // while a full-screen panel ([overlayShown]: Settings, Node, tabs,
    // History, Bookmarks, Downloads) covers the page, so the user
    // always sees the page that is asking, and it waits its turn with
    // the tab's download offer ([modalPromptTurn]).
    val pageOnScreen = pageUncovered && (promptTurn == PromptTurn.None || promptTurn == PromptTurn.SitePermission)
    state.permissionPrompt?.takeIf { promptTurn == PromptTurn.SitePermission }?.let { prompt ->
        androidx.compose.runtime.key(prompt) { SitePermissionPrompt(prompt) }
    }
    // The page's Site permissions sheet (#266): the user's own doing,
    // over the page only. A prompt the page raises meanwhile takes the
    // screen from it rather than stacking on it — the sheet can be
    // opened again once that's answered.
    val sheetShown = sheetTarget?.takeIf { pageUncovered && promptTurn == PromptTurn.None }
    LaunchedEffect(sitePermissionsSheet, sheetShown) {
        if (sheetShown == null) sitePermissionsSheet = null
    }
    sheetShown?.let { target ->
        PageSitePermissionsSheet(
            pageOrigin = target.origin,
            entries = pagePermissions,
            inUse = mediaInUse,
            document = pageDocument,
            private = state.private,
            onRevoke = { entry -> sitePermissions.revokeOnTab(state, entry) },
            onReload = reloadPage,
            onDismiss = { sitePermissionsSheet = null },
        )
    }
    // Page info (#442): like the Site permissions sheet, the user's own
    // doing, over the page only, and gone when the document changes.
    val pageInfoShown = pageInfoTarget?.takeIf { pageUncovered && promptTurn == PromptTurn.None }
    LaunchedEffect(pageInfoSheet, pageInfoShown) {
        if (pageInfoShown == null) pageInfoSheet = null
    }
    pageInfoShown?.let { target ->
        val connection = pageConnectionFor(state.url, state.showsErrorPage, protocolBadgeFor(state))
        // Read once, when the sheet opens over this document.
        val certificate = remember(target) {
            if (connection?.hasCertificate == true) tabs.pageCertificate?.invoke(state) else null
        }
        // The site's data, where it has a site that can hold any: not on
        // an error page, which stands in for a load that never arrived.
        val dataOrigin = target.origin?.takeIf { !state.showsErrorPage }
        val dataUrl = dataOrigin?.let { origin ->
            state.url.takeIf { permissionOriginKey(it) == origin } ?: "$origin/"
        }
        PageInfoSheet(
            site = AddressLabel.resting(state.addressBarText).ifEmpty { state.url },
            connection = connection,
            certificate = certificate,
            nameTrust = state.nameTrust,
            permissions = pagePermissions,
            inUse = mediaInUse,
            document = pageDocument,
            private = state.private,
            onRevokePermission = { entry -> sitePermissions.revokeOnTab(state, entry) },
            adblock = adblockState,
            onToggleAdblock = toggleAdblock,
            siteDataOrigin = dataOrigin,
            siteDataUrl = dataUrl,
            siteDataGateway = siteDataGatewayLabel(dataOrigin),
            onDeleteSiteData = {
                if (dataOrigin != null && dataUrl != null) {
                    val tab = state
                    pageInfoSheet = null
                    scope.launch {
                        SiteData.delete(context, dataOrigin, dataUrl, tab.private)
                        // What the page holds in memory goes with a reload,
                        // whose document is the cleanup page first: it
                        // reaches the tab's own sessionStorage, and what
                        // the old page wrote on its way out. The tab's, by
                        // id, whether or not it is still the one on screen
                        // (R2-F2) — only a tab that has since closed is
                        // left alone.
                        if (tab !in tabs.tabs) return@launch
                        if (tab.rendererGone != null) {
                            tab.recoverRenderer()
                            return@launch
                        }
                        // The site's service workers are only reachable
                        // from a document of its own: the page's, which
                        // unregisters them and then reloads itself — so a
                        // worker doesn't answer the reload. If it doesn't
                        // (a page that stops it), the tab is reloaded from
                        // here, once its clearing is done (R2-F3).
                        SiteData.cleanAndReload(
                            tabId = tab.id,
                            origin = dataOrigin,
                            clean = { key ->
                                val ask = tabs.cleanSiteInPage
                                ask != null && withTimeoutOrNull(1_000) {
                                    suspendCancellableCoroutine { cont ->
                                        ask(tab, dataOrigin, key) { started -> if (cont.isActive) cont.resume(started) }
                                    }
                                } == true
                            },
                            cleaned = { key ->
                                val ask = tabs.siteCleanedInPage
                                if (ask == null) {
                                    null
                                } else {
                                    withTimeoutOrNull(1_000) {
                                        suspendCancellableCoroutine<InPageCleaned?> { cont ->
                                            ask(tab, key) { done -> if (cont.isActive) cont.resume(done) }
                                        }
                                    }
                                }
                            },
                            // Still on the site Delete was for (R3-F1).
                            onOrigin = { tab in tabs.tabs && SiteData.committedOrigin(tab.id) == dataOrigin },
                            reload = {
                                if (tab in tabs.tabs && tabs.reloadDocument?.invoke(tab, dataOrigin) != true &&
                                    tabs.active === tab
                                ) {
                                    reloadPage()
                                }
                            },
                        )
                    }
                }
            },
            onReload = reloadPage,
            onDismiss = { pageInfoSheet = null },
        )
    }
    state.radiclePrompt?.takeIf { promptTurn == PromptTurn.Radicle }?.let { prompt ->
        androidx.compose.runtime.key(prompt) { RadiclePromptDialog(prompt) }
    }
    state.ethereumPrompt?.takeIf { promptTurn == PromptTurn.Ethereum }?.let { prompt ->
        val ask = prompt.ask
        if (ask is EthAsk.SwitchNotice) {
            // A built-in network switch with no sheet (#440): its turn is
            // the tab on screen, uncovered, with no sheet before it — the
            // switch goes ahead and its notice comes up (#446 R1-F1).
            LaunchedEffect(prompt) { prompt.respond(EthAnswer.Approved()) }
        } else if (ask is EthAsk.SendLink) {
            // A payment link (#317) isn't a sheet: its turn opens the Send
            // page, filled in, which answers the ask when it's left.
            LaunchedEffect(prompt) {
                if (!prompt.answer.isCompleted && linkSend?.prompt !== prompt) linkSend = LinkSend(ask.prefill, prompt)
            }
        } else {
            androidx.compose.runtime.key(prompt) { EthereumApprovalSheet(prompt) }
        }
    }
    state.swarmPrompt?.takeIf { promptTurn == PromptTurn.Swarm }?.let { prompt ->
        androidx.compose.runtime.key(prompt) { SwarmPromptSheet(prompt) }
    }
    state.jsDialog?.takeIf { promptTurn == PromptTurn.JsDialog }?.let { request ->
        // Taken down if it loses its turn — its tab closed or left the
        // screen, this screen leaving composition. Taking it down
        // doesn't itself answer the page; whatever took it down does
        // ([JsDialogRequest.withdraw]): the tab closing, the user
        // switching away from it, or the WebView host going away —
        // the Activity finishing or being relaunched alike, since every
        // WebView goes with the host — so it isn't shown again later.
        DisposableEffect(request) {
            val dialog = showJsDialog(context, request)
            onDispose { dialog?.dismiss() }
        }
    }
    // A Ledger conversation (#142) — a site's signature, a send, reading accounts — over whatever is up.
    LedgerActivityDialog()
    // The same gate for Android's own runtime-permission dialog, which
    // the broker raises only over the tab named here — plus the app
    // itself being in the foreground: WebViews aren't paused in the
    // background, so page JS can still ask, and launching the system
    // dialog from a stopped Activity would either pop it over another
    // app or be blocked with no result ever delivered (stranding the
    // broker's dialog lock). RESUMED, not STARTED, so the paused sliver
    // on the way to the background doesn't count either.
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        .currentStateFlow.collectAsState()
    val onScreenTabId = state.id.takeIf {
        pageOnScreen && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }
    androidx.compose.runtime.SideEffect { sitePermissions.onScreenTab.value = onScreenTabId }
    // Where a dApp's "switched to" notice may be up (#446 R1-M1, round 1007).
    // Windows of their own over the page count too (#446 R2-M1, round
    // 1007): HTML5 fullscreen (drawn over both snackbar hosts), Android's
    // permission dialog (its turn reads as None), a Ledger conversation, a
    // remote-signing sheet, or the app not in the foreground at all.
    val ledgerActivity by remember(context) { Ledger.get(context) }.activity.collectAsState()
    val remoteApproval by remember(context) { OpenLvSession.get(context) }.approval.collectAsState()
    val switchNoticeClear = switchNoticeUncovered(
        pageUncovered = pageUncovered,
        promptTurn = promptTurn,
        switchTurn = state.ethereumPrompt?.ask is EthAsk.SwitchNotice,
        // The page's Site permissions sheet, or its Page info sheet (#442).
        pageSheetUp = sheetShown != null || pageInfoShown != null,
        windowOver = tabs.fullscreen != null || androidDialogUp || ledgerActivity != null ||
            remoteApproval != null || !lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),
    )
    androidx.compose.runtime.SideEffect { switchNoticeClearTab = state.id.takeIf { switchNoticeClear } }
    DisposableEffect(sitePermissions) {
        onDispose { sitePermissions.onScreenTab.value = null }
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
 * The IPFS phase line (#94): the IPFS mark and the node's current
 * retrieval phase ("IPFS: Finding providers…") in a small pill above the
 * capsule. Wraps rather than ellipsises — the phase is the whole point,
 * and a narrow screen or a large font scale must still show all of it.
 */
@Composable
private fun IpfsStatusLine(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = modifier
            .widthIn(max = CHROME_MAX_WIDTH)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(baby.freedom.mobile.R.drawable.ic_ipfs),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The tab and document a page's Site permissions sheet (#266) was opened over. */
private data class PageSheetTarget(val tabId: Long, val origin: String?, val doc: Int?)

/**
 * Whether a dApp's "switched to" notice (#440) may be up over the active
 * tab: nothing covers its page — no full-screen panel ([pageUncovered]), no
 * prompt or sheet of any kind having the tab's turn ([promptTurn]: the
 * site-permission prompt, a download offer, a Radicle, Ethereum or Swarm
 * sheet, a page's JS dialog, the long-press menu), and not the page's Site
 * permissions or Page info sheet ([pageSheetUp]), and no window of its own over it
 * ([windowOver]: HTML5 fullscreen, Android's permission dialog, a Ledger
 * conversation, a remote-signing sheet, or the app not resumed). The one
 * turn that doesn't cover it is a no-sheet switch's own ([switchTurn]),
 * which is answered at once and brings the next notice (#446 R1-M1, R2-M1,
 * round 1007).
 */
internal fun switchNoticeUncovered(
    pageUncovered: Boolean,
    promptTurn: PromptTurn,
    switchTurn: Boolean,
    pageSheetUp: Boolean,
    windowOver: Boolean = false,
): Boolean = pageUncovered && !pageSheetUp && !windowOver &&
    (promptTurn == PromptTurn.None || (promptTurn == PromptTurn.Ethereum && switchTurn))

/**
 * Which of the "switched to" notices the screen has are replaced by a new
 * one for [tabId] (#446 R6-M2): its own tab's, and any already seen. One
 * still waiting for another tab, never shown, is kept, so that switch is
 * still named when the user gets back to its tab.
 */
internal fun <T> switchNoticesReplaced(
    notices: Collection<T>,
    tabId: Long,
    tabOf: (T) -> Long,
    shown: (T) -> Boolean,
): List<T> = notices.filter { tabOf(it) == tabId || shown(it) }

/** The "<site> switched to <chain>" notice's job on screen (#440, #446). */
private class ChainSwitchNoticeUi(val notice: EthereumProviders.SwitchNotice) {
    var job: Job? = null

    /** It has been on screen at least once. */
    var shown = false
}

/** Its own instance per notice, so the screen can tell when that one is the snackbar up. */
private class ChainSwitchVisuals(override val message: String, override val actionLabel: String) : SnackbarVisuals {
    override val withDismissAction = true
    override val duration = SnackbarDuration.Long
}
