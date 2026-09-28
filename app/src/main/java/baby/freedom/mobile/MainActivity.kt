package baby.freedom.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import baby.freedom.mobile.browser.BrowserScreen
import baby.freedom.mobile.browser.DeepLinkQueue
import baby.freedom.mobile.browser.Gateways
import baby.freedom.mobile.browser.HOME_URL
import baby.freedom.mobile.browser.Adblock
import baby.freedom.mobile.browser.PublicSuffixList
import baby.freedom.mobile.browser.OnchainApps
import baby.freedom.mobile.browser.RadicleControls
import baby.freedom.mobile.browser.UnverifiedOrigins
import baby.freedom.mobile.browser.VirtualOrigin
import baby.freedom.mobile.browser.statusBarIconsDark
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.node.IMyotisCallback
import baby.freedom.mobile.node.IMyotisService
import baby.freedom.mobile.node.INodeCallback
import baby.freedom.mobile.node.INodeService
import baby.freedom.mobile.node.MyotisService
import baby.freedom.mobile.node.NodeService
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.ui.isLight
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.RadicleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hosts the browser UI and brokers the bind/unbind lifecycle of the
 * out-of-process [NodeService]. Both the Swarm and IPFS nodes run
 * inside the `:node` process; flipping the UI's "run node" toggle off
 * tears that process down so no native state (ant's tokio runtime,
 * the freedom-ipfs block store) survives, letting a future toggle-on boot clean.
 *
 * IPFS state is hidden from the default UI — see `SettingsScreen`'s
 * "Other" section for the reveal gate — but the flow is plumbed all
 * the way through so `ipfs://` / `ens→ipfs` navigation works even
 * when the user has never opened the advanced settings panel.
 */
class MainActivity : ComponentActivity() {

    private val infoFlow = MutableStateFlow(NodeInfo())
    private val ipfsInfoFlow = MutableStateFlow(IpfsInfo())
    private val radicleInfoFlow = MutableStateFlow(RadicleInfo())

    /**
     * Serializes relaying the Radicle setting to `:node`: a toggle's
     * write-then-start/stop and a bind's read-then-start each run whole,
     * so the service hears them in the order the setting changed (#73).
     */
    private val radicleRelay = Mutex()
    private val myotisInfoFlow = MutableStateFlow(MyotisInfo())
    private lateinit var settings: NodeSettings

    /**
     * App Links that arrived after the UI was already composed (see
     * [onNewIntent]), waiting to be opened in tabs, oldest first. Cold-start links
     * don't use this — they're passed straight in as the initial URL.
     */
    private val deepLinkQueue = DeepLinkQueue()

    /** Publishes [onNewIntent] links into [deepLinkQueue] in arrival order. */
    private val deepLinks = OrderedDeepLinks(lifecycleScope, Dispatchers.Default) {
        deepLinkQueue.offer(it)
    }

    // The page theme colour the browser paints behind the status bar,
    // as ARGB, or null when it shows the app background there (#92).
    private var statusBarTint by mutableStateOf<Int?>(null)

    @Volatile
    private var binder: INodeService? = null
    private var bound = false

    private val callback = object : INodeCallback.Stub() {
        override fun onStateChanged(info: NodeInfo?) {
            if (info != null) infoFlow.value = info
        }

        override fun onIpfsStateChanged(info: IpfsInfo?) {
            if (info != null) {
                ipfsInfoFlow.value = info
                Gateways.setIpfsBase(info.gatewayUrl)
            }
        }

        override fun onRadicleStateChanged(info: RadicleInfo?) {
            if (info != null) radicleInfoFlow.value = info
        }
    }

    // The Myotis light client (#72) lives in its own `:myotis` process,
    // bound while [NodeSettings.myotisEnabled] is on — see [MyotisService].
    @Volatile
    private var myotisBinder: IMyotisService? = null

    // Read by [myotisCallback] on a binder thread.
    @Volatile
    private var myotisBound = false

    private val myotisCallback = object : IMyotisCallback.Stub() {
        override fun onMyotisStateChanged(info: MyotisInfo?) {
            if (info != null && myotisBound) myotisInfoFlow.value = info
        }
    }

    private val myotisConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = IMyotisService.Stub.asInterface(service) ?: return
            myotisBinder = b
            runCatching { b.registerCallback(myotisCallback) }
            // onStart/onStop may have run before the binding came up.
            runCatching {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) b.onAppForeground()
                else b.onAppBackground()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // `:myotis` died (or exited under a quick off → on); the
            // binding brings a fresh process back up.
            myotisBinder = null
            myotisInfoFlow.value = MyotisInfo(status = MyotisStatus.Starting)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = INodeService.Stub.asInterface(service) ?: return
            binder = b
            runCatching { b.registerCallback(callback) }
            runCatching { b.state?.let { infoFlow.value = it } }
            runCatching {
                b.ipfsState?.let {
                    ipfsInfoFlow.value = it
                    Gateways.setIpfsBase(it.gatewayUrl)
                }
            }
            runCatching { b.radicleState?.let { radicleInfoFlow.value = it } }
            // The Radicle on/off setting lives here, in the UI process's
            // DataStore; a freshly (re)started `:node` hears it on bind.
            // Under [radicleRelay], so a toggle landing at the same time
            // can't have its stop overtaken by a start this bind read
            // from the setting before the toggle wrote it.
            lifecycleScope.launch {
                radicleRelay.withLock {
                    if (settings.radicleEnabled.first()) runCatching { b.startRadicle() }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // `:node` died unexpectedly. A clean toggle-off goes through
            // [setRunNodeEnabled] instead, which sets Stopped explicitly.
            binder = null
            infoFlow.value = NodeInfo()
            ipfsInfoFlow.value = IpfsInfo()
            radicleInfoFlow.value = RadicleInfo()
            Gateways.setIpfsBase("")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = NodeSettings.get(this)
        // Name resolution reads the user's RPC settings for every
        // lookup, so a change in Settings applies to the next name.
        Gateways.ensRpcConfig = { settings.ensRpcConfig.first() }
        // The first read moves what an earlier build kept in the settings
        // file — API keys in plain text among them — to where they now
        // live (encrypted); do it now rather than at the first name.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                settings.ensRpcConfig.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "reading name-resolution settings failed (${e.javaClass.simpleName})")
            }
        }

        // Honor the persisted preference on cold start. If the user had
        // the node enabled, start + bind right away; otherwise leave
        // the :node process dormant so we don't hold the state store
        // open unnecessarily.
        lifecycleScope.launch {
            if (settings.runNodeEnabled.first()) startAndBindService()
        }

        // The Myotis light client (#72, off by default) follows its
        // switch live, independent of the Swarm node's.
        lifecycleScope.launch {
            settings.myotisEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) bindMyotis() else unbindMyotis()
            }
        }

        // External Swarm endpoint / IPFS gateway (#125), followed live
        // so switching in Settings applies to the next request. Until
        // the first value lands, the interceptor and the navigation gate
        // wait for it (so a cold-start deep link or restored tab can't
        // reach the embedded node's gateway first) — the main thread
        // doesn't.
        // Onchain apps (#123): a restored tab's app document is read by
        // the interceptor, which needs the chain-data router wired first.
        OnchainApps.init(this)
        Gateways.expectExternalEndpoints()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { UnverifiedOrigins.init(this@MainActivity) }
            combine(
                settings.externalSwarmEndpoint,
                settings.externalIpfsGateway,
            ) { swarm, ipfs -> swarm to ipfs }.collect { (swarm, ipfs) ->
                // What an unverified gateway left on the virtual origins
                // goes before anything else is served there. The switch
                // itself lands under the sweep's lock, so no request can
                // record against the old gateway once it's swept.
                UnverifiedOrigins.sweep(
                    ipfs,
                    apply = { Gateways.setExternalEndpoints(swarm, ipfs) },
                    wipe = UnverifiedOrigins::wipeWebData,
                )
            }
        }

        // The address label's resting form needs the vendored Public
        // Suffix List, and the first label that asks for it is composed
        // on a navigation-commit frame — a bad frame to spend a 150 KB
        // resource read and a 10k-entry set build on. Build it here
        // instead, off the main thread (hence the explicit dispatcher:
        // lifecycleScope defaults to Main). [PublicSuffixList.warm] is
        // idempotent and thread-safe, so a label that arrives first
        // just does the load itself, exactly as it does today.
        lifecycleScope.launch(Dispatchers.Default) { PublicSuffixList.warm() }
        // Same for ENSIP-15's spec tables (a few hundred ms on a cold
        // ART): the first non-ASCII name must not decode them on Main.
        lifecycleScope.launch(Dispatchers.Default) { EnsNormalize.warm() }

        // Ad and tracker blocking (#126): compile the enabled filter
        // lists off the main thread and follow Settings from here on.
        // Until the first build lands, requests wait for it (bounded,
        // see [FirstBuildGate]) — a restored tab loads straight away.
        Adblock.start(this)

        // A cold start from an App Link opens straight at the shared
        // content instead of the home surface. A Unicode ENS link
        // (`xn--…` host) needs the ENSIP-15 tables to map back to its
        // name, and the warm-up above has only just started — so parse
        // it on Default once they're decoded and compose then, rather
        // than decode them on Main here (every later main-thread parse
        // is cheap once the tables are warm).
        val link = intent
        if (!EnsNormalize.isWarm && VirtualOrigin.needsEnsTables(deepLinkData(link))) {
            lifecycleScope.launch {
                val startUrl = withContext(Dispatchers.Default) {
                    EnsNormalize.warm()
                    displayUrlForDeepLink(link)
                }
                showBrowser(startUrl ?: HOME_URL)
            }
        } else {
            showBrowser(displayUrlForDeepLink(link) ?: HOME_URL)
        }
    }

    private fun showBrowser(startUrl: String) {
        setContent {
            FreedomTheme {
                SystemBarsForScheme()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val info by infoFlow.collectAsState()
                    val ipfsInfo by ipfsInfoFlow.collectAsState()
                    val radicleInfo by radicleInfoFlow.collectAsState()
                    val radicleEnabled by settings.radicleEnabled
                        .collectAsState(initial = false)
                    val runNodeEnabled by settings.runNodeEnabled
                        .collectAsState(initial = true)
                    val myotisInfo by myotisInfoFlow.collectAsState()
                    val myotisEnabled by settings.myotisEnabled
                        .collectAsState(initial = false)
                    val pendingLinks by deepLinkQueue.pending.collectAsState()
                    BrowserScreen(
                        nodeInfo = info,
                        ipfsInfo = ipfsInfo,
                        runNodeEnabled = runNodeEnabled,
                        onToggleRunNode = ::onToggleRunNode,
                        myotisInfo = myotisInfo,
                        myotisEnabled = myotisEnabled,
                        onToggleMyotis = ::onToggleMyotis,
                        onEnsureIpfsStarted = ::onEnsureIpfsStarted,
                        onIpfsToggle = ::onIpfsToggle,
                        radicle = RadicleControls(
                            info = radicleInfo,
                            enabled = radicleEnabled,
                            onToggle = ::onRadicleToggle,
                            onSeed = ::onRadicleSeed,
                            onUnseed = ::onRadicleUnseed,
                        ),
                        initialUrl = startUrl,
                        deepLink = pendingLinks.firstOrNull(),
                        onDeepLinkHandled = deepLinkQueue::handled,
                        onRecoverNodes = ::onRecoverNodes,
                        ipfsProgressSnapshot = ::ipfsProgressSnapshot,
                        ipfsCounters = ::ipfsCounters,
                        onStatusBarTint = { statusBarTint = it },
                    )
                }
            }
        }
    }

    /**
     * Tint the (transparent, edge-to-edge) system bars' icons for the
     * scheme currently being drawn under them: dark icons on the light
     * scheme, light icons on the dark one.
     *
     * `values/themes.xml` and `values-night/themes.xml` already state
     * this, but only for the *first* frame — the manifest keeps
     * `uiMode` in `configChanges`, so flipping the system theme while
     * the app is running re-themes Compose without recreating the
     * window, and the window's own attributes stay on whatever they
     * were created with. That left white status-bar icons on a
     * near-white surface (verified on the freedom AVD with
     * `cmd uimode night no`). Driving them from the active
     * [androidx.compose.material3.ColorScheme] instead keeps them
     * right across a live switch, and keys them off the same thing
     * every other light/dark decision in the app reads.
     *
     * The status bar has a second input since #92: while the browser
     * paints a page's theme colour behind it ([statusBarTint]), its icons
     * follow that colour instead of the scheme ([statusBarIconsDark]).
     * One effect decides both, so a live theme switch can't race a tint
     * change into the wrong icons.
     */
    @Composable
    private fun SystemBarsForScheme() {
        val lightScheme = MaterialTheme.colorScheme.isLight
        val darkStatusIcons = statusBarIconsDark(statusBarTint, lightScheme)
        val view = LocalView.current
        LaunchedEffect(lightScheme, darkStatusIcons, view) {
            if (view.isInEditMode) return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkStatusIcons
                isAppearanceLightNavigationBars = lightScheme
            }
        }
    }

    /**
     * An App Link tapped while the app was already running. The
     * manifest declares `singleTop` so the link lands here instead of
     * spawning a second activity instance — the user's open tabs
     * survive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Same off-Main parse as a cold-start link if the ENSIP-15
        // tables are still decoding (a link tapped right after launch);
        // [deepLinks] keeps a later ASCII link from overtaking it.
        val slow = !EnsNormalize.isWarm && VirtualOrigin.needsEnsTables(deepLinkData(intent))
        deepLinks.submit(slow) {
            if (slow) EnsNormalize.warm()
            displayUrlForDeepLink(intent)
        }
    }

    private fun deepLinkData(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString

    /**
     * The user-facing display URL for an incoming `VIEW` intent, or
     * `null` if it isn't one of our virtual origins.
     *
     * The translation goes through [VirtualOrigin] — the label
     * encodings are never parsed here, so the deep-link path can't
     * drift from the mapping the WebView and the redirector use. A
     * `null` return also acts as the gate: an intent aimed at some
     * other https host (a stale filter, an explicit `am start`) is
     * ignored rather than loaded.
     */
    private fun displayUrlForDeepLink(intent: Intent?): String? {
        return deepLinkData(intent)?.let { VirtualOrigin.displayUrlFor(it) }
    }

    /**
     * Foreground / background transitions are relayed to the `:node`
     * process so the embedded nodes can re-warm their peer sockets
     * (after Android froze the process the swarm otherwise sits on dead
     * connections while still reporting Running — see
     * freedom-hq/ant#12) and quiesce on the way out. If the service
     * isn't bound yet these are no-ops; the node boots fresh anyway.
     */
    override fun onStart() {
        super.onStart()
        runCatching { binder?.onAppForeground() }
        runCatching { myotisBinder?.onAppForeground() }
    }

    override fun onStop() {
        runCatching { binder?.onAppBackground() }
        runCatching { myotisBinder?.onAppBackground() }
        super.onStop()
    }

    /**
     * A dweb fetch failed while the node reports Running: ask the
     * nodes to drop stale connections and redial before the WebView
     * layer retries once. Manual equivalent: toggling the node off
     * and on in Settings.
     */
    private fun onRecoverNodes() {
        runCatching { binder?.recoverNetwork() }
    }

    override fun onDestroy() {
        unbindFromService()
        unbindMyotis()
        super.onDestroy()
    }

    private fun onToggleRunNode(enabled: Boolean) {
        lifecycleScope.launch {
            settings.setRunNodeEnabled(enabled)
            if (enabled) startAndBindService() else stopAndUnbindService()
        }
    }

    /**
     * Lazy-start hook for the IPFS node. Called by [BrowserScreen] the
     * first time a navigation needs IPFS — we don't pay the IPFS boot
     * + peer-discovery cost on app launch, only when the user actually
     * visits `ipfs://` / `ipns://` / an IPFS-resolved `ens://`.
     *
     * The AIDL stub in `:node` dedups repeat calls, so this is safe to
     * invoke on every such navigation.
     */
    private fun onEnsureIpfsStarted() {
        runCatching { binder?.ensureIpfsStarted() }
    }

    /**
     * The `:node` IPFS node's retrieval-progress snapshot (JSON), for
     * the chrome's IPFS phase line (#94). Null while unbound or while
     * the node isn't running. A blocking binder call — the caller polls
     * it from `Dispatchers.IO`.
     */
    private fun ipfsProgressSnapshot(): String? =
        runCatching { binder?.ipfsProgress }.getOrNull()

    /** The IPFS node's retrieval / routing counters, same terms as above. */
    private fun ipfsCounters(): LongArray? =
        runCatching { binder?.ipfsCounters }.getOrNull()

    /**
     * Handle the user flipping the "IPFS" toggle in Settings. Starts
     * or stops the freedom-ipfs node live — there's no persisted "run IPFS"
     * flag; each cold launch begins with IPFS off, and the toggle
     * state is derived from the live [IpfsInfo.status] broadcast.
     */
    private fun onIpfsToggle(enabled: Boolean) {
        if (enabled) runCatching { binder?.ensureIpfsStarted() }
        else runCatching { binder?.stopIpfs() }
    }

    /** The light-client switch on the node page (#72): persisted, and followed in [onCreate]. */
    private fun onToggleMyotis(enabled: Boolean) {
        lifecycleScope.launch { settings.setMyotisEnabled(enabled) }
    }

    private fun bindMyotis() {
        if (myotisBound) return
        myotisInfoFlow.value = MyotisInfo(status = MyotisStatus.Starting)
        myotisBound = bindService(
            Intent(this, MyotisService::class.java),
            myotisConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!myotisBound) {
            runCatching { unbindService(myotisConnection) }
            myotisInfoFlow.value = MyotisInfo(
                status = MyotisStatus.Error,
                errorMessage = "Couldn't start the light client service",
            )
        }
    }

    /** Unbinding the only client destroys [MyotisService], which stops the engines and exits `:myotis`. */
    private fun unbindMyotis() {
        if (!myotisBound) return
        runCatching { myotisBinder?.unregisterCallback(myotisCallback) }
        runCatching { unbindService(myotisConnection) }
        myotisBinder = null
        myotisBound = false
        myotisInfoFlow.value = MyotisInfo()
    }

    /**
     * The user turned the embedded Radicle node on or off (#73). Unlike
     * IPFS this is persisted, and relayed again on every bind; the node
     * itself lives in `:node`, so while the node service is off the
     * setting just waits for it.
     */
    private fun onRadicleToggle(enabled: Boolean) {
        lifecycleScope.launch {
            radicleRelay.withLock {
                settings.setRadicleEnabled(enabled)
                runCatching { if (enabled) binder?.startRadicle() else binder?.stopRadicle() }
            }
        }
    }

    /** Stop seeding a repository from the Radicle page's list. */
    private fun onRadicleUnseed(rid: String) {
        runCatching { binder?.unseedRadicleRepo(rid) }
    }

    /** Seed-by-RID from the Radicle page; progress comes back on the callback. */
    private fun onRadicleSeed(rid: String) {
        runCatching { binder?.seedRadicleRepo(rid) }
    }

    private fun startAndBindService() {
        NodeService.start(this)
        if (!bound) {
            bindService(
                Intent(this, NodeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            bound = true
        }
    }

    private fun stopAndUnbindService() {
        unbindFromService()
        NodeService.stop(this)
        infoFlow.value = NodeInfo()
        ipfsInfoFlow.value = IpfsInfo()
        radicleInfoFlow.value = RadicleInfo()
        Gateways.setIpfsBase("")
    }

    private fun unbindFromService() {
        if (!bound) return
        runCatching { binder?.unregisterCallback(callback) }
        runCatching { unbindService(connection) }
        binder = null
        bound = false
    }
}
