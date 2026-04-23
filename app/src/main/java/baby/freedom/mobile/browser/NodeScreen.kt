package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode

/**
 * Full-screen node-details page: live status, peer count, wallet,
 * gateway URL, and the run-node on/off toggle. Shares the same
 * [FullScreenScaffold] chrome as Settings / History / Bookmarks.
 */
@Composable
fun NodeScreen(
    nodeInfo: NodeInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val triple = nodeStatusTriple(nodeInfo.status)

    FullScreenScaffold(
        title = "Swarm node",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                StatusSection(
                    triple = triple,
                    runNodeEnabled = runNodeEnabled,
                    onToggleRunNode = onToggleRunNode,
                )
            }
            item("details") {
                DetailsSection(nodeInfo = nodeInfo)
            }
            item("gateway") {
                GatewaySection()
            }
        }
    }
}

@Composable
private fun StatusSection(
    triple: NodeStatusTriple,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
) {
    SectionCard(title = "Status") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(triple.icon, contentDescription = null, tint = triple.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(triple.label, fontWeight = FontWeight.Medium)
                Text(
                    if (runNodeEnabled) "Serving bzz:// via local gateway"
                    else "Gateway disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = runNodeEnabled,
                onCheckedChange = onToggleRunNode,
            )
        }
    }
}

@Composable
private fun DetailsSection(nodeInfo: NodeInfo) {
    SectionCard(title = "Details") {
        DetailRow("Mode", "ultra-light")
        DetailRow("Peers", nodeInfo.connectedPeers.toString())
        if (nodeInfo.walletAddress.isNotEmpty()) {
            DetailRow("Wallet", nodeInfo.walletAddress, mono = true)
        }
        val err = nodeInfo.errorMessage
        if (!err.isNullOrBlank()) {
            DetailRow("Error", err, singleLine = false)
        }
    }
}

@Composable
private fun GatewaySection() {
    SectionCard(title = "Gateway") {
        DetailRow("URL", SwarmNode.GATEWAY_URL, mono = true)
    }
}

internal data class NodeStatusTriple(
    val color: Color,
    val icon: ImageVector,
    val label: String,
)

internal fun nodeStatusTriple(status: NodeStatus): NodeStatusTriple = when (status) {
    NodeStatus.Running -> NodeStatusTriple(
        Color(0xFF22C55E), Icons.Filled.CheckCircle, "Running",
    )
    NodeStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…",
    )
    NodeStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Stopped",
    )
    NodeStatus.Error -> NodeStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error",
    )
}
