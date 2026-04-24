package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Snapshot of the embedded Kubo (IPFS) node's runtime state.
 *
 * Parallel to [NodeInfo] for the Bee node. Marshalled across the UI ↔
 * `:node` AIDL boundary so the browser process can gate `ipfs://` /
 * `ipns://` navigation on IPFS peer availability the same way it does
 * for Swarm.
 */
@Parcelize
data class IpfsInfo(
    val status: IpfsStatus = IpfsStatus.Stopped,
    val peerId: String = "",
    val connectedPeers: Long = 0L,
    /**
     * Fully-qualified HTTP gateway base URL (e.g. `http://127.0.0.1:58312`),
     * or `""` while the node isn't running. Used by the browser as the
     * `/ipfs/` + `/ipns/` loadable origin; the trailing `/ipfs/<cid>` is
     * appended per-navigation by `IpfsGateway.toLoadable`.
     */
    val gatewayUrl: String = "",
    val errorMessage: String? = null,
) : Parcelable
