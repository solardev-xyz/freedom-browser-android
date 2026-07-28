package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NodeInfo(
    val status: NodeStatus = NodeStatus.Stopped,
    val connectedPeers: Long = 0L,
    /**
     * Agent string of the running embedded client (e.g. `ant-ffi/0.5.42`),
     * or `""` while the node isn't running.
     */
    val clientVersion: String = "",
    val errorMessage: String? = null,
) : Parcelable
