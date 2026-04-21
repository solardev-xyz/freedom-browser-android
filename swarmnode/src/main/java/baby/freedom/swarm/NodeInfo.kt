package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NodeInfo(
    val status: NodeStatus = NodeStatus.Stopped,
    val walletAddress: String = "",
    val connectedPeers: Long = 0L,
    val errorMessage: String? = null,
) : Parcelable
