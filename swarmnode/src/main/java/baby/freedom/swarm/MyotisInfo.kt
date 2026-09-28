package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Lifecycle of the whole light client (every chain's engine together). */
enum class MyotisStatus {
    Stopped,
    Starting,
    Running,
    Error,
}

/**
 * The chains the light client follows. [engineName] is the engine's
 * canonical network name (`myotis_create` input), [chainId] what the
 * rest of the app keys chains on.
 */
enum class MyotisNetwork(val engineName: String, val chainId: Long, val displayName: String) {
    Mainnet("mainnet", 1L, "Ethereum"),
    Gnosis("gnosis", 100L, "Gnosis"),
    ;

    companion object {
        fun forChain(chainId: Long): MyotisNetwork? = entries.firstOrNull { it.chainId == chainId }
    }
}

/**
 * Snapshot of the embedded Myotis light client. Parallel to [NodeInfo] /
 * [IpfsInfo]; marshalled from the `:myotis` process to the UI.
 */
@Parcelize
data class MyotisInfo(
    val status: MyotisStatus = MyotisStatus.Stopped,
    /** One entry per [MyotisNetwork] once a start was attempted, in enum order. */
    val chains: List<MyotisChainStatus> = emptyList(),
    val errorMessage: String? = null,
) : Parcelable {
    fun chain(network: MyotisNetwork): MyotisChainStatus? =
        chains.firstOrNull { it.chainId == network.chainId }
}
