package baby.freedom.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import baby.freedom.mobile.browser.BrowserScreen
import baby.freedom.mobile.browser.IncomingLinks
import baby.freedom.mobile.browser.EthereumProviders
import baby.freedom.mobile.browser.X402Payments
import baby.freedom.mobile.browser.Gateways
import baby.freedom.mobile.browser.Adblock
import baby.freedom.mobile.browser.AppUpdates
import baby.freedom.mobile.browser.PublicSuffixList
import baby.freedom.mobile.browser.OnchainApps
import baby.freedom.mobile.browser.PhraseClipboard
import baby.freedom.mobile.browser.RadApi
import baby.freedom.mobile.browser.RadicleClient
import baby.freedom.mobile.browser.Publisher
import baby.freedom.mobile.browser.StampClient
import baby.freedom.mobile.browser.RadicleProviders
import baby.freedom.mobile.browser.SwarmProviders
import baby.freedom.mobile.browser.RadicleControls
import baby.freedom.mobile.browser.TorControls
import baby.freedom.mobile.browser.TorRouting
import baby.freedom.mobile.browser.UnverifiedOrigins
import baby.freedom.mobile.browser.statusBarIconsDark
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.data.RadicleGrantStore
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.node.IMyotisCallback
import baby.freedom.mobile.node.IMyotisService
import baby.freedom.mobile.node.INodeCallback
import baby.freedom.mobile.node.INodeService
import baby.freedom.mobile.node.NodeLogSource
import baby.freedom.mobile.node.MyotisChains
import baby.freedom.mobile.node.MyotisLink
import baby.freedom.mobile.node.MyotisService
import baby.freedom.mobile.node.NodeService
import baby.freedom.mobile.node.SwarmRelay
import baby.freedom.mobile.node.swarmRelays
import baby.freedom.mobile.node.ITorCallback
import baby.freedom.mobile.node.ITorService
import baby.freedom.mobile.node.TorService
import baby.freedom.mobile.ui.Appearance
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.KeystoreVaultStore
import baby.freedom.mobile.wallet.NodeIdentitySync
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import baby.freedom.mobile.wallet.PhraseBackup
import baby.freedom.mobile.wallet.PhraseBackupJob
import baby.freedom.mobile.wallet.Vault
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.SwarmNode
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    // Shared with the Fund node page's Ledger ready check (#291 R4-M1).
    private val infoFlow = StampClient.node
    private val ipfsInfoFlow = MutableStateFlow(IpfsInfo())
    // Shared with the `rad://` browser and `window.radicle` (#124).
    private val radicleInfoFlow = RadicleClient.state
    private val radicleGrants by lazy { RadicleGrantStore.get(this) }

    /**
     * Serializes relaying the Radicle setting to `:node`: a toggle's
     * write-then-start/stop and a bind's read-then-start each run whole,
     * so the service hears them in the order the setting changed (#73).
     */
    private val radicleRelay = Mutex()
    private val myotisInfoFlow = MutableStateFlow(MyotisInfo())
    private val torInfoFlow = MutableStateFlow(TorInfo())
    private lateinit var settings: NodeSettings

    /**
     * Links, shares and searches from other apps (#268, handed on by
     * [IncomingLinkActivity]) waiting to be opened in tabs, oldest first:
     * the one the app was cold-started from, and each [onNewIntent]. In a
     * ViewModel, so a relaunch before one has its tab doesn't drop it.
     */
    private val incomingSession: IncomingSession by viewModels()

    // The page theme colour the browser paints behind the status bar,
    // as ARGB, or null when it shows the app background there (#92).
    private var statusBarTint by mutableStateOf<Int?>(null)

    // Whether an opaque full-screen panel (Settings, Wallet, the tab
    // switcher…) is over the page, so the navigation bar needs no
    // contrast scrim (#247).
    private var panelShown by mutableStateOf(false)

    @Volatile
    private var binder: INodeService? = null
    private var bound = false

    /**
     * The Swarm node's mode (#114) from the light-mode setting and the
     * Gnosis RPCs, with the Gnosis chain its reads go through (#273),
     * relayed to `:node` on every bind and every change; null until first
     * read. Main thread only.
     */
    private var swarmMode: SwarmRelay? = null

    private fun relaySwarmMode(b: INodeService?, relay: SwarmRelay?) {
        relay ?: return
        val gnosis = relay.gnosis
        runCatching { b?.setSwarmMode(relay.light, relay.gnosisRpc, gnosis?.userRpcUrls, gnosis?.rpcUrls) }
    }

    private val callback = object : INodeCallback.Stub() {
        override fun onStateChanged(info: NodeInfo?) {
            if (info != null) StampClient.publish(binder, info)
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
    // bound while at least one of its chains is switched on
    // ([MyotisChains], #274) — see [MyotisService].
    @Volatile
    private var myotisBinder: IMyotisService? = null

    /** The chains [MyotisChains] last asked for: relayed on every (re)connect. */
    private var myotisNetworks: Set<MyotisNetwork> = emptySet()

    // Read by [myotisCallback] on a binder thread.
    @Volatile
    private var myotisBound = false

    private val myotisCallback = object : IMyotisCallback.Stub() {
        override fun onMyotisStateChanged(info: MyotisInfo?) {
            if (info != null && myotisBound) {
                myotisInfoFlow.value = info
                MyotisLink.onState(this@MainActivity, info)
            }
        }
    }

    private val myotisConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = IMyotisService.Stub.asInterface(service) ?: return
            myotisBinder = b
            // Before registering: the first state arrives on registration.
            MyotisLink.connected(this@MainActivity, b)
            runCatching { b.registerCallback(myotisCallback) }
            // After registering, which reports Starting until the chains
            // are chosen: this call starts them.
            relayMyotisNetworks(b)
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
            MyotisLink.disconnected(this@MainActivity)
            myotisInfoFlow.value = MyotisInfo(status = MyotisStatus.Starting)
        }
    }

    // The Tor client (#143) lives in its own `:tor` process, bound while
    // Tor should run — see [TorService]. [torRunning] is what the node
    // page's switch shows; its state reaches [TorRouting], which owns the
    // `.onion` proxy override.
    @Volatile
    private var torBinder: ITorService? = null
    private var torBound = false
    private var torRunning by mutableStateOf(false)

    /**
     * A binding [unbindTor] let go of but hasn't unbound yet: it waits
     * for the WebView to move `.onion` off the Tor port first (R2-F1).
     * Identifies that one unbind, so a rebind in the meantime (which
     * keeps the binding) or the deadline can't act twice.
     */
    private var torUnbindPending: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val torCallback = object : ITorCallback.Stub() {
        override fun onTorStateChanged(info: TorInfo?) {
            info ?: return
            // Binder thread → main, where [TorRouting] is driven; a state
            // from a binding already let go is dropped there.
            runOnUiThread { if (torBound) publishTor(info) }
        }
    }

    private val torConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = ITorService.Stub.asInterface(service) ?: return
            torBinder = b
            runCatching { b.registerCallback(torCallback) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // `:tor` died (or exited under a quick off → on): its port is
            // gone, so stop routing to it at once; the binding brings a
            // fresh process back up, which reports its new port.
            torBinder = null
            if (torBound) publishTor(TorInfo(status = TorStatus.Starting))
        }
    }

    private fun publishTor(info: TorInfo) {
        torInfoFlow.value = info
        TorRouting.onState(this, info)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = INodeService.Stub.asInterface(service) ?: return
            binder = b
            RadicleClient.service = b
            StampClient.attach(b)
            runCatching { b.registerCallback(callback) }
            runCatching { b.state?.let { StampClient.publish(b, it) } }
            runCatching {
                b.ipfsState?.let {
                    ipfsInfoFlow.value = it
                    Gateways.setIpfsBase(it.gatewayUrl)
                }
            }
            runCatching { b.radicleState?.let { radicleInfoFlow.value = it } }
            // The mode (#114) first, so the identity check below already
            // compares against it rather than restarting the node twice.
            relaySwarmMode(b, swarmMode)
            // A wallet change made while unbound (#77).
            runCatching { b.reloadIdentity() }
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
            StampClient.detach(binder)
            binder = null
            RadicleClient.service = null
            ipfsInfoFlow.value = IpfsInfo()
            radicleInfoFlow.value = RadicleInfo()
            Gateways.setIpfsBase("")
        }
    }

    /** Restarts a bound `:node`'s Swarm node on an identity change (#77); see onCreate. */
    private val identityChanged: (NodeIdentitySync.Change) -> Unit = {
        runCatching { binder?.reloadIdentity() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = NodeSettings.get(this)
        // Name resolution reads the user's RPC settings for every
        // lookup, so a change in Settings applies to the next name.
        Gateways.ensRpcConfig = { settings.ensRpcConfig.first() }
        Gateways.colibriStatesDir = File(filesDir, "colibri")
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

        // A publish staged in the cache (#118) by a run that ended mid-upload.
        lifecycleScope.launch(Dispatchers.IO) { Publisher.sweepStaging(this@MainActivity) }

        // The nodes follow the wallet's identity (#77): a wallet created,
        // imported or removed changes what the Swarm node boots as, and a
        // bound `:node` restarts it. (Unbound, it reads it at its next start.)
        // The sync outlives this activity, so the listener is taken back
        // in onDestroy rather than left holding it.
        NodeIdentitySync.get(this).apply {
            setOnChanged(identityChanged)
            start()
        }
        // The wallet's accounts (#104) follow it the same way: verified on
        // every unlock, forgotten on Remove wallet.
        WalletAccounts.get(this).start()
        // A send the last process left unresolved resumes, and mined abandoned sends are swept (#105);
        // a stamp the wallet bought for the node (#115) is connected once its call is mined.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                WalletSender.resumeAtLaunch(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "resuming the wallet's send failed (${e.javaClass.simpleName})")
            }
            try {
                baby.freedom.mobile.browser.SwarmFunding.resumeAtLaunch(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "resuming the node's funding failed (${e.javaClass.simpleName})")
            }
        }

        // Settings → Appearance (#269), followed live: the choice becomes
        // the app's night mode, which re-themes the chrome and every
        // page's `prefers-color-scheme` without a restart (see
        // [Appearance]). Android keeps that mode across launches too, so
        // this only writes it again, which changes nothing. A read error
        // doesn't end this: [NodeSettings.appearance] logs it and reads
        // again, so a later choice is still applied.
        lifecycleScope.launch {
            settings.appearance
                .distinctUntilChanged()
                .collect { Appearance.apply(this@MainActivity, it) }
        }

        // The home page's first-run introduction (#278): decided once, at
        // the first start with this build, before a page of this session
        // can land in history — an install that already has pages,
        // bookmarks, a wallet or a changed setting predates the
        // introduction and isn't on a first launch.
        lifecycleScope.launch {
            try {
                val repo = baby.freedom.mobile.data.BrowsingRepository.get(this@MainActivity)
                settings.settleIntro {
                    repo.bookmarks.first().isNotEmpty() ||
                        repo.recentDistinct(1).first().isNotEmpty() ||
                        withContext(Dispatchers.IO) { KeystoreVaultStore(this@MainActivity).exists() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stays undecided, so not shown; asked again next start.
                android.util.Log.w("MainActivity", "deciding the introduction failed (${e.javaClass.simpleName})")
            }
        }

        // Honor the persisted preference on cold start. If the user had
        // the node enabled, start + bind right away; otherwise leave
        // the :node process dormant so we don't hold the state store
        // open unnecessarily.
        lifecycleScope.launch {
            if (settings.runNodeEnabled.first()) startAndBindService()
        }

        // The Swarm node's mode (#114) follows its setting and the Gnosis
        // RPCs live: `:node` restarts the node when it changes. A chain
        // store read error relays the last readable RPCs, never the
        // shipped ones in their place ([swarmRelays]).
        lifecycleScope.launch {
            swarmRelays(settings.swarmLightMode, ChainStore.get(this@MainActivity).chainsOrUnreadable)
                .collect { mode ->
                    swarmMode = mode
                    relaySwarmMode(binder, mode)
                }
        }

        // The Myotis light client (#72, off by default) follows its
        // per-chain switches live (#274), independent of the Swarm node's:
        // bound while any chain is on, told which.
        MyotisChains.init(settings)
        lifecycleScope.launch {
            MyotisChains.running.filterNotNull().distinctUntilChanged().collect { chains ->
                myotisNetworks = chains
                if (chains.isEmpty()) {
                    unbindMyotis()
                } else {
                    bindMyotis()
                    myotisBinder?.let(::relayMyotisNetworks)
                }
            }
        }

        // Tor (#143): the `.onion` proxy override goes in before any page
        // loads, refusing onion hosts until Tor listens. Settings → Tor is
        // followed live (off stops Tor); Tor itself starts at launch only
        // with "Start Tor at launch", else from the node page.
        TorRouting.init(this)
        lifecycleScope.launch {
            if (settings.torEnabled.first() && settings.torStartOnLaunch.first()) bindTor()
            settings.torEnabled.distinctUntilChanged().collect { enabled ->
                TorRouting.setEnabled(this@MainActivity, enabled)
                if (!enabled) unbindTor()
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
        // Radicle (#124): the repository browser's page files, and the
        // `window.radicle` provider behind every tab's page object.
        RadApi.init(this)
        RadicleProviders.init(this)
        // `window.ethereum` (#110): the dApp provider behind every normal tab.
        EthereumProviders.init(this)
        // `window.swarm` (#120): publishing, chunks and feeds.
        SwarmProviders.init(this)
        X402Payments.init(this)
        lifecycleScope.launch {
            settings.radicleEnabled.collect {
                RadicleClient.enabled = it
                RadicleProviders.setEnabled(it)
            }
        }
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

        // A newer Freedom release (#272): checked on GitHub at most daily
        // while Settings → About → Check for updates is on.
        AppUpdates.start(this)

        // A cold start from a link (#268) opens straight at it instead of
        // the home surface: queued before the first composition, which
        // puts it in the first tab. A Unicode ENS link (`xn--…` host)
        // needs the ENSIP-15 tables to map back to its name, and the
        // warm-up above has only just started — so it's parsed on Default
        // once they're decoded, and the UI composed then, rather than
        // decode them on Main here (every later main-thread parse is cheap
        // once the tables are warm).
        //
        // Only a launch that is the link's own: not a relaunch that
        // restores tabs (the same intent comes back after process death),
        // nor a relaunch from Recents, which replays the intent the task
        // was first started with.
        val ownLaunch = savedInstanceState == null &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        // A relaunch while that link is still parsing ([IncomingSession])
        // waits for it the same way, so it still gets the first tab.
        val coldLink = if (ownLaunch) {
            submitIncoming(intent).also { incomingSession.coldStart = it }
        } else {
            incomingSession.coldStart
        }
        if (coldLink != null) {
            lifecycleScope.launch {
                coldLink.join()
                showBrowser()
            }
        } else {
            showBrowser()
        }
    }

    private fun showBrowser() {
        // Below Android 12 there's no app night mode for [Appearance.apply]
        // to set, so the chrome follows the choice only through the
        // Compose theme below — and composing with System as a placeholder
        // until DataStore answers would draw the first frame in the
        // system's theme and then flip. Wait (briefly) for the stored
        // choice there instead; above, the configuration already agrees
        // with it, so there is nothing to wait for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                val stored = withTimeoutOrNull(APPEARANCE_WAIT_MS) { settings.appearance.first() }
                composeBrowser(stored ?: Appearance.System)
            }
        } else {
            composeBrowser(Appearance.System)
        }
    }

    private fun composeBrowser(initialAppearance: Appearance) {
        setContent {
            // Below Android 12 this is what makes the chrome follow the
            // choice (see [showBrowser]); above, the configuration already
            // agrees with it.
            val appearance by settings.appearance.collectAsState(initial = initialAppearance)
            FreedomTheme(darkTheme = appearance.isDark(isSystemInDarkTheme())) {
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
                    val radicleGrants by radicleGrants.all.collectAsState(initial = emptyList())
                    val runNodeEnabled by settings.runNodeEnabled
                        .collectAsState(initial = true)
                    val myotisInfo by myotisInfoFlow.collectAsState()
                    val myotisRunning by MyotisChains.running.collectAsState()
                    val pendingLinks by incomingSession.queue.pending.collectAsState()
                    val torInfo by torInfoFlow.collectAsState()
                    val torEnabled by settings.torEnabled.collectAsState(initial = false)
                    BrowserScreen(
                        nodeInfo = info,
                        ipfsInfo = ipfsInfo,
                        runNodeEnabled = runNodeEnabled,
                        onToggleRunNode = ::onToggleRunNode,
                        myotisInfo = myotisInfo,
                        myotisRunning = myotisRunning,
                        onRunMyotisChain = MyotisChains::set,
                        onMyotisRecovery = ::onMyotisRecovery,
                        onEnsureIpfsStarted = ::onEnsureIpfsStarted,
                        onIpfsToggle = ::onIpfsToggle,
                        radicle = RadicleControls(
                            info = radicleInfo,
                            enabled = radicleEnabled,
                            onToggle = ::onRadicleToggle,
                            onSeed = ::onRadicleSeed,
                            onUnseed = ::onRadicleUnseed,
                            grants = radicleGrants,
                            onRevoke = ::onRadicleRevoke,
                        ),
                        tor = TorControls(
                            info = torInfo,
                            enabled = torEnabled,
                            running = torRunning,
                            supported = TorRouting.supported != false,
                            onRun = ::onToggleTor,
                        ),
                        deepLink = pendingLinks.firstOrNull(),
                        onDeepLinkHandled = incomingSession.queue::handled,
                        onRecoverNodes = ::onRecoverNodes,
                        ipfsProgressSnapshot = ::ipfsProgressSnapshot,
                        ipfsCounters = ::ipfsCounters,
                        onStatusBarTint = { statusBarTint = it },
                        onPanelShown = { panelShown = it },
                        readNodeLogs = ::readNodeLogs,
                        clearNodeLogs = ::clearNodeLogs,
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
     *
     * With 3-button navigation Android also lays a translucent contrast
     * scrim over the (transparent) navigation bar, because it can't know
     * what the app draws there. Over a web page that's right — the page
     * can be any colour — but over a full-screen panel it's the app's
     * own scheme background, which the icons above already match, and
     * the scrim just paints a grey band that isn't the panel's colour
     * (#247; a dark panel got a grey bar, rgb(42,42,43) over
     * rgb(20,18,24), on the API 36 AVD). So the scrim is dropped while a
     * panel is up ([panelShown]) and comes back when the page does.
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
        LaunchedEffect(panelShown) {
            window.isNavigationBarContrastEnforced = !panelShown
        }
    }

    /**
     * A link from another app while the browser was already running
     * ([IncomingLinkActivity] starts us `singleTop` in our own task), so
     * it lands here instead of in a second activity instance — the
     * user's open tabs survive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submitIncoming(intent)
    }

    /**
     * Queue what [intent] asks to open (#268), read through the same
     * gate [IncomingLinkActivity] used — any app can start this exported
     * activity directly — and mapped to its address-bar form by
     * [IncomingSession.submit].
     *
     * Returns the job still parsing it, or null once it's queued (or
     * there was nothing to queue).
     */
    private fun submitIncoming(intent: Intent?): Job? =
        IncomingLinks.from(intent)?.let(incomingSession::submit)

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
        // The wallet's auto-lock (#76): back within the 1-minute grace,
        // it stays open; later than that, it locks now.
        Vault.get(this).onAppForeground()
        // Google backup (#231): if encryption went away while we were out
        // (the screen lock was removed), take the cloud copy down; back on
        // once it's there again. Whatever the wallet says: an entry kept
        // after Remove wallet, or one a new phone received, needs it too.
        lifecycleScope.launch {
            val status = PhraseBackup.get(this@MainActivity).reconcileQuietly()
            PhraseBackupJob.sync(this@MainActivity, status)
        }
        runCatching { binder?.onAppForeground() }
        runCatching { myotisBinder?.onAppForeground() }
        // A daily update check that fell due while the phone slept (#272).
        AppUpdates.onAppForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A copied recovery phrase (#78) whose clear is owed and hadn't
        // run yet (e.g. a late inexact alarm): catch up now.
        if (hasFocus) PhraseClipboard.clearIfDue(this)
    }

    override fun onStop() {
        Vault.get(this).onAppBackground()
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
        NodeIdentitySync.get(this).clearOnChanged(identityChanged)
        unbindFromService()
        unbindMyotis()
        unbindTor()
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

    /**
     * A node's recent log lines (#276) from the process it runs in, or null
     * while the node is off: its process isn't bound, or — Swarm, IPFS and
     * Radicle share `:node` — the process runs for another node but this
     * one is stopped. Blocking binder call.
     */
    private fun readNodeLogs(source: NodeLogSource): String? = runCatching {
        val off = when (source) {
            NodeLogSource.Swarm -> infoFlow.value.status == NodeStatus.Stopped
            NodeLogSource.Ipfs -> ipfsInfoFlow.value.status == IpfsStatus.Stopped
            NodeLogSource.Radicle -> radicleInfoFlow.value.status == RadicleStatus.Stopped
            NodeLogSource.Tor, NodeLogSource.LightClient -> false
        }
        if (off) return@runCatching null
        when (source) {
            NodeLogSource.Swarm, NodeLogSource.Ipfs, NodeLogSource.Radicle -> binder?.getLogs(source.ordinal)
            NodeLogSource.Tor -> torBinder?.logs
            NodeLogSource.LightClient -> myotisBinder?.logs
        }
    }.getOrNull()

    /**
     * Part of *Clear cookies & site data* (#276): every node process that's
     * running forgets the log lines it kept. One-way calls — nothing waits.
     * A process that isn't bound isn't running, and keeps no lines.
     */
    private fun clearNodeLogs() {
        runCatching { binder?.clearLogs() }
        runCatching { torBinder?.clearLogs() }
        runCatching { myotisBinder?.clearLogs() }
    }

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

    /** Tell `:myotis` which chains to run ([MyotisChains]); it starts and stops them one by one. */
    private fun relayMyotisNetworks(binder: IMyotisService) {
        val chainIds = myotisNetworks.map { it.chainId }.toLongArray()
        runCatching { binder.setNetworks(chainIds) }
    }

    /** A chain row's Retry / Repair sync data (#195); the service ignores it unless it applies. */
    private fun onMyotisRecovery(chainId: Long, repair: Boolean) {
        runCatching {
            val binder = myotisBinder ?: return
            if (repair) binder.repairSyncData(chainId) else binder.retryRecovery(chainId)
        }
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

    /**
     * The node page's Tor switch (#143). Not persisted: Tor runs from
     * here until switched off (or Settings → Tor is turned off), and at
     * launch only with Settings → Tor → Start Tor at launch.
     */
    private fun onToggleTor(run: Boolean) {
        if (run) {
            lifecycleScope.launch { if (settings.torEnabled.first()) bindTor() }
        } else {
            unbindTor()
        }
    }

    /**
     * Bound through the application context, so the deferred unbind in
     * [unbindTor] still works when it outlives this Activity.
     */
    private fun bindTor() {
        if (torBound) return
        if (torUnbindPending != null) {
            // Switched back on before the last unbind went through: the
            // service is still bound and running, keep it.
            torUnbindPending = null
            torBound = true
            torRunning = true
            publishTor(TorInfo(status = TorStatus.Starting))
            torBinder?.let { b -> runCatching { b.registerCallback(torCallback) } }
            return
        }
        torBound = applicationContext.bindService(
            Intent(this, TorService::class.java),
            torConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (torBound) {
            torRunning = true
            publishTor(TorInfo(status = TorStatus.Starting))
        } else {
            runCatching { applicationContext.unbindService(torConnection) }
            publishTor(TorInfo(status = TorStatus.Error, errorMessage = "Couldn't start the Tor service"))
        }
    }

    /**
     * Unbinding the only client destroys [TorService], which stops the
     * client and exits `:tor`. Routing stops first: the port is about to
     * close. And the unbind waits for the WebView to confirm the override
     * that refuses `.onion` ([TorRouting.afterRefusing]), so the Tor port
     * isn't freed — for another app to bind — while the WebView may still
     * send onion requests to it (R2-F1); bounded by
     * [TOR_UNBIND_TIMEOUT_MS], since the user asked for Tor to stop.
     */
    private fun unbindTor() {
        torRunning = false
        if (!torBound) {
            if (torInfoFlow.value.status == TorStatus.Error) publishTor(TorInfo())
            return
        }
        torBound = false
        publishTor(TorInfo(version = torInfoFlow.value.version))
        runCatching { torBinder?.unregisterCallback(torCallback) }
        val token = Any()
        torUnbindPending = token
        val finish = {
            if (torUnbindPending === token) {
                torUnbindPending = null
                runCatching { applicationContext.unbindService(torConnection) }
                torBinder = null
            }
        }
        TorRouting.afterRefusing(finish)
        mainHandler.postDelayed(finish, TOR_UNBIND_TIMEOUT_MS)
    }

    /** Unbinding the only client destroys [MyotisService], which stops the engines and exits `:myotis`. */
    private fun unbindMyotis() {
        if (!myotisBound) return
        runCatching { myotisBinder?.unregisterCallback(myotisCallback) }
        runCatching { unbindService(myotisConnection) }
        myotisBinder = null
        myotisBound = false
        MyotisLink.disconnected(this@MainActivity)
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

    /** The user disconnected a site from `window.radicle` on the Radicle page (#124). */
    private fun onRadicleRevoke(origin: String) {
        lifecycleScope.launch {
            if (radicleGrants.revoke(origin)) RadicleProviders.revoked(origin)
        }
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
        // A postage spend still inside ant (#116) must finish first: the
        // service then stops itself once it has, rather than exit mid-spend.
        val deferred = runCatching { binder?.stopWhenIdle() }.getOrNull() == true
        unbindFromService()
        if (deferred) {
            Toast.makeText(this, R.string.node_stop_after_spend, Toast.LENGTH_LONG).show()
        } else {
            NodeService.stop(this)
        }
        ipfsInfoFlow.value = IpfsInfo()
        radicleInfoFlow.value = RadicleInfo()
        Gateways.setIpfsBase("")
    }

    private fun unbindFromService() {
        if (!bound) return
        runCatching { binder?.unregisterCallback(callback) }
        runCatching { unbindService(connection) }
        // Unbound, the callback no longer moves the process-wide node
        // state, so it goes back to Stopped rather than stay at the last
        // report for the next Activity to start from (#291 R5-M1) — keyed
        // to this instance's own binder, and before [binder] is cleared so
        // a late report from it is dropped too (#291 R6-M1, R6-M2).
        StampClient.detach(binder)
        binder = null
        RadicleClient.service = null
        bound = false
    }
}

/**
 * How long [MainActivity]'s Tor unbind waits for the WebView to confirm
 * `.onion` is refused before letting `:tor` stop regardless.
 */
private const val TOR_UNBIND_TIMEOUT_MS = 2_000L

/**
 * How long [MainActivity] waits, below Android 12, for the stored
 * Settings → Appearance choice before composing (see `showBrowser`):
 * long enough for a normal DataStore read, short enough that a stuck one
 * only costs the placeholder theme, not the browser.
 */
private const val APPEARANCE_WAIT_MS = 1_000L
