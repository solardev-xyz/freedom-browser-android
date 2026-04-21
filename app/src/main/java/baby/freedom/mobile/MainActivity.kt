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
import baby.freedom.mobile.browser.BrowserScreen
import baby.freedom.mobile.node.NodeService
import baby.freedom.swarm.NodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    /**
     * Which [StateFlow] we render. Swapped atomically when the service
     * binds/unbinds. Because this itself is a [StateFlow], Compose's
     * [collectAsState] transparently resubscribes when the inner flow changes.
     */
    private val activeFlow: MutableStateFlow<StateFlow<NodeInfo>> =
        MutableStateFlow(MutableStateFlow(NodeInfo()).let { it })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            (service as? NodeService.LocalBinder)?.let { activeFlow.value = it.state }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            activeFlow.value = MutableStateFlow(NodeInfo())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NodeService.start(this)
        bindService(
            Intent(this, NodeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val current by activeFlow.collectAsState()
                    val info by current.collectAsState()
                    BrowserScreen(nodeInfo = info)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(connection) }
    }
}
