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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import baby.freedom.mobile.browser.BrowserScreen
import baby.freedom.mobile.browser.Gateways
import baby.freedom.mobile.browser.HOME_URL
import baby.freedom.mobile.browser.PublicSuffixList
import baby.freedom.mobile.browser.VirtualOrigin
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.node.INodeCallback
import baby.freedom.mobile.node.INodeService
import baby.freedom.mobile.node.NodeService
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.ui.isLight
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.NodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private lateinit var settings: NodeSettings

    /**
     * An App Link that arrived after the UI was already composed (see
     * [onNewIntent]), waiting to be opened in a tab. Cold-start links
     * don't use this — they're passed straight in as the initial URL.
     */
    private val deepLinkFlow = MutableStateFlow<String?>(null)

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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // `:node` died unexpectedly. A clean toggle-off goes through
            // [setRunNodeEnabled] instead, which sets Stopped explicitly.
            binder = null
            infoFlow.value = NodeInfo()
            ipfsInfoFlow.value = IpfsInfo()
            Gateways.setIpfsBase("")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = NodeSettings.get(this)

        // Honor the persisted preference on cold start. If the user had
        // the node enabled, start + bind right away; otherwise leave
        // the :node process dormant so we don't hold the state store
        // open unnecessarily.
        lifecycleScope.launch {
            if (settings.runNodeEnabled.first()) startAndBindService()
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

        // A cold start from an App Link opens straight at the shared
        // content instead of the home surface.
        val startUrl = displayUrlForDeepLink(intent) ?: HOME_URL

        setContent {
            FreedomTheme {
                SystemBarsForScheme()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val info by infoFlow.collectAsState()
                    val ipfsInfo by ipfsInfoFlow.collectAsState()
                    val runNodeEnabled by settings.runNodeEnabled
                        .collectAsState(initial = true)
                    val deepLink by deepLinkFlow.collectAsState()
                    BrowserScreen(
                        nodeInfo = info,
                        ipfsInfo = ipfsInfo,
                        runNodeEnabled = runNodeEnabled,
                        onToggleRunNode = ::onToggleRunNode,
                        onEnsureIpfsStarted = ::onEnsureIpfsStarted,
                        onIpfsToggle = ::onIpfsToggle,
                        initialUrl = startUrl,
                        deepLinkUrl = deepLink,
                        onDeepLinkHandled = { deepLinkFlow.value = null },
                        onRecoverNodes = ::onRecoverNodes,
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
     */
    @Composable
    private fun SystemBarsForScheme() {
        val lightScheme = MaterialTheme.colorScheme.isLight
        val view = LocalView.current
        LaunchedEffect(lightScheme, view) {
            if (view.isInEditMode) return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightScheme
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
        displayUrlForDeepLink(intent)?.let { deepLinkFlow.value = it }
    }

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
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.let { VirtualOrigin.displayUrlFor(it) }
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
    }

    override fun onStop() {
        runCatching { binder?.onAppBackground() }
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
     * Handle the user flipping the "IPFS" toggle in Settings. Starts
     * or stops the freedom-ipfs node live — there's no persisted "run IPFS"
     * flag; each cold launch begins with IPFS off, and the toggle
     * state is derived from the live [IpfsInfo.status] broadcast.
     */
    private fun onIpfsToggle(enabled: Boolean) {
        if (enabled) runCatching { binder?.ensureIpfsStarted() }
        else runCatching { binder?.stopIpfs() }
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
