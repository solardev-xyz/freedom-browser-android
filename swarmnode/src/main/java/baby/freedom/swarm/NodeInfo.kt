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
    /** The Swarm account's Ethereum address (`0x…`) while running, else `""`. */
    val accountAddress: String = "",
    /** The node's overlay address (hex) while running, else `""`. */
    val overlay: String = "",
    /**
     * True while the node runs as the identity derived from the wallet's
     * recovery phrase (#77); false while it runs as its own device identity.
     */
    val walletIdentity: Boolean = false,
) : Parcelable
