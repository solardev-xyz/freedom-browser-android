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
    /**
     * True while the node runs in light mode (#114): its gateway reports
     * `beeMode: light` and reads Gnosis. False while ultra-light, or not running.
     */
    val lightMode: Boolean = false,
    /**
     * True while a restart the node owes (a mode or identity change) is
     * waiting because the wallet's identity couldn't be read when it was
     * asked for: no restart is underway, and it's retried when the app
     * next comes to the foreground. Set by the app's node service.
     */
    val reloadOwed: Boolean = false,
) : Parcelable
