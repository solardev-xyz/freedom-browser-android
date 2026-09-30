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
    /**
     * Detail for the phase (a peer, a failure reason), or `""`, in
     * English or as the native library gave it: this is what pages get
     * from `window.radicle` (#280), so it never follows the app
     * language. The node page shows [shown] instead.
     */
    val detail: String = "",
    /** False once the fetch has settled, whichever way. */
    val active: Boolean = true,
    /**
     * Which of this module's own texts [detail] is ([DETAIL_CANDIDATES]
     * and the rest), or `""` when it's the native library's own words;
     * [shown] resolves it in the app language from this and [detailArg].
     */
    val detailKey: String = "",
    /** [detailKey]'s argument: a candidate count or a node ID. */
    val detailArg: String = "",
) : Parcelable {
    /** [detail] for the user, in the app language as it is now. */
    val shown: String get() = detailText(detailKey, detailArg, english = false) ?: detail

    companion object {
        const val DETAIL_CANDIDATES = "candidates"
        const val DETAIL_FETCHING_FROM = "fetching_from"
        const val DETAIL_INVALID_RID = "invalid_rid"
        const val DETAIL_STALE_FETCH = "stale_fetch"
        const val DETAIL_UNREADABLE_FETCH = "unreadable_fetch"

        /** A line for a failure or progress this module words itself: [detail] English, [shown] localised. */
        internal fun ofKey(rid: String, phase: String, key: String, arg: String = "", active: Boolean = true) =
            RadicleSeed(rid, phase, detailText(key, arg, english = true).orEmpty(), active, key, arg)

        internal fun detailText(key: String, arg: String, english: Boolean): String? {
            fun get(id: Int, vararg args: Any?) = if (english) SwarmStrings.english(id, *args) else SwarmStrings.get(id, *args)
            return when (key) {
                DETAIL_CANDIDATES -> (arg.toIntOrNull() ?: 0).let {
                    if (english) SwarmStrings.englishPlural(R.plurals.swarmnode_radicle_candidate_seeds, it, it)
                    else SwarmStrings.plural(R.plurals.swarmnode_radicle_candidate_seeds, it, it)
                }
                DETAIL_FETCHING_FROM -> get(R.string.swarmnode_radicle_fetching_from, arg)
                DETAIL_INVALID_RID -> get(R.string.swarmnode_radicle_invalid_rid)
                DETAIL_STALE_FETCH -> get(R.string.swarmnode_radicle_stale_fetch)
                DETAIL_UNREADABLE_FETCH -> get(R.string.swarmnode_radicle_unreadable_fetch)
                else -> null
            }
        }
    }
}

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
