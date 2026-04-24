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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import baby.freedom.mobile.browser.BrowserScreen
import baby.freedom.mobile.browser.Gateways
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.node.INodeCallback
import baby.freedom.mobile.node.INodeService
import baby.freedom.mobile.node.NodeService
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.NodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the browser UI and brokers the bind/unbind lifecycle of the
 * out-of-process [NodeService]. Both the Swarm and IPFS nodes run
 * inside the `:node` process; flipping the UI's "run node" toggle off
 * tears that process down so bee-lite's LevelDB state-store lock is
 * released, allowing a future toggle-on to reopen the store cleanly.
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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val info by infoFlow.collectAsState()
                    val ipfsInfo by ipfsInfoFlow.collectAsState()
                    val runNodeEnabled by settings.runNodeEnabled
                        .collectAsState(initial = true)
                    BrowserScreen(
                        nodeInfo = info,
                        ipfsInfo = ipfsInfo,
                        runNodeEnabled = runNodeEnabled,
                        onToggleRunNode = ::onToggleRunNode,
                        onEnsureIpfsStarted = ::onEnsureIpfsStarted,
                    )
                }
            }
        }
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
     * first time a navigation needs IPFS — we don't pay the Kubo boot
     * + peer-discovery cost on app launch, only when the user actually
     * visits `ipfs://` / `ipns://` / an IPFS-resolved `ens://`.
     *
     * The AIDL stub in `:node` dedups repeat calls, so this is safe to
     * invoke on every such navigation.
     */
    private fun onEnsureIpfsStarted() {
        runCatching { binder?.ensureIpfsStarted() }
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
