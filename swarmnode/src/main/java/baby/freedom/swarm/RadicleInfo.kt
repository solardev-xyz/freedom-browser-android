package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class RadicleStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

/** A repository the embedded Radicle node seeds. */
@Parcelize
data class RadicleRepo(
    /** Full `rad:z…` repository ID. */
    val rid: String,
    /** The project name, or `""` while the repo awaits its first fetch. */
    val name: String = "",
) : Parcelable

/** Where the most recent seed-by-RID request stands. */
@Parcelize
data class RadicleSeed(
    val rid: String,
    /**
     * The fetch's latest phase, as libradicle names it: `resolving`,
     * `connecting`, `fetching`, `peer-failed`, then one of `done`,
     * `failed`, `cancelled`.
     */
    val phase: String,
    /** Human-readable detail for the phase (a peer, a failure reason), or `""`. */
    val detail: String = "",
    /** False once the fetch has settled, whichever way. */
    val active: Boolean = true,
) : Parcelable

/**
 * Snapshot of the embedded Radicle node, marshalled across the UI ↔
 * `:node` AIDL boundary like [NodeInfo] / [IpfsInfo].
 */
@Parcelize
data class RadicleInfo(
    val status: RadicleStatus = RadicleStatus.Stopped,
    /** `did:key:z6Mk…` of the node's profile, or `""` until it has started. */
    val did: String = "",
    /** The node ID (`z6Mk…`), or `""` until it has started. */
    val nid: String = "",
    val alias: String = "",
    val connectedPeers: Int = 0,
    /** Explicitly seeded repositories, including ones still awaiting a first fetch. */
    val seededRepos: List<RadicleRepo> = emptyList(),
    val seed: RadicleSeed? = null,
    val errorMessage: String? = null,
) : Parcelable
