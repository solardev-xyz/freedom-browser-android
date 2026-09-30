package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONException
import org.json.JSONObject

/**
 * One chain's engine state: the subset of `myotis_status_json` the app
 * shows, plus [error] when that chain's engine failed to come up.
 *
 * Two different peer-to-peer networks feed a chain: [peerCount] is the
 * consensus-layer (beacon light-client, libp2p) side, [snapPeers] the
 * execution-layer (devp2p snap) pool that serves state proofs.
 */
@Parcelize
data class MyotisChainStatus(
    val chainId: Long,
    /** Engine sync state: `STARTING`, `SYNCING`, `CATCHING_UP`, `SYNCED`, `STALE_ANCHOR`; `""` if unknown. */
    val beaconState: String = "",
    val peerCount: Int = 0,
    val snapPeers: Int = 0,
    /**
     * Pooled state peers that can answer a read at the verified head now
     * (engine ABI 31+). A pool of still-syncing peers keeps [snapPeers]
     * positive while every read fails, so this is the count that gates
     * [ready].
     */
    val snapServingPeers: Int = 0,
    /** Latest verified execution block (`optimisticBlockNumber`), 0 until known. */
    val headBlock: Long = 0L,
    /** Execution block of the beacon-finalized checkpoint, 0 until known. */
    val finalizedBlock: Long = 0L,
    val running: Boolean = false,
    val paused: Boolean = false,
    /** Execution-layer reader up (false: it failed to start — consensus-only). */
    val elReaderAvailable: Boolean = false,
    /** Execution-layer reader hunting for peers at the head; reads fail meanwhile. */
    val elHunting: Boolean = false,
    /** Hunting for light-client servers. Display only: a synced chain still serves. */
    val lcHunting: Boolean = false,
    /** Sync-committee period of the trust anchor (the refused one while [staleAnchor]). */
    val currentPeriod: Long = 0L,
    /** Sync-committee period of the wall clock. */
    val targetPeriod: Long = 0L,
    /** How many periods old an anchor may be before the engine refuses it (weak subjectivity). */
    val wsBoundPeriods: Long = 0L,
    /** Beacon slot of the finalized header the engine has verified, 0 until known. */
    val finalizedSlot: Long = 0L,
    /** Beacon root of that header (64 hex, no `0x`), `""` until known. */
    val finalizedRootHex: String = "",
    /** Why this chain's engine isn't running, when it failed to start. */
    val error: String? = null,
    /**
     * Stale-anchor checkpoint recovery in progress or blocked (#195); set by
     * [MyotisNode], never by [decode]. A chain in recovery is never [ready].
     */
    val recovery: MyotisRecovery? = null,
) : Parcelable {

    val synced: Boolean get() = beaconState == SYNCED

    /** The trust anchor is past the weak-subjectivity bound; the engine refuses to sync from it. */
    val staleAnchor: Boolean get() = beaconState == STALE_ANCHOR

    /**
     * The engine got past judging its trust anchor: it's catching up or
     * synced, which it never is from an anchor it refused.
     */
    val anchorAccepted: Boolean get() = beaconState == CATCHING_UP || beaconState == SYNCED

    /**
     * Whether a verified read attempted now has a realistic chance of an
     * answer: synced, a state peer at the head, and the execution reader
     * up and not hunting — the same gate as the iOS and desktop hosts —
     * and no checkpoint recovery in flight or blocked.
     */
    val ready: Boolean
        get() = running && !paused && synced && snapServingPeers >= 1 &&
            elReaderAvailable && !elHunting && recovery == null

    /**
     * Why a synced chain isn't [ready] yet, or `""` when it is (or isn't
     * synced, which says enough on its own).
     */
    val notServingReason: String
        get() {
            if (!running || paused || !synced || ready || recovery != null) return ""
            val reasons = mutableListOf<String>()
            if (snapPeers < 1) {
                reasons += SwarmStrings.get(R.string.swarmnode_not_serving_no_state_peer)
            } else if (snapServingPeers < 1) {
                reasons += SwarmStrings.get(R.string.swarmnode_not_serving_no_state_peer_at_head)
            }
            if (!elReaderAvailable) reasons += SwarmStrings.get(R.string.swarmnode_not_serving_reader_down)
            if (elHunting) reasons += SwarmStrings.get(R.string.swarmnode_not_serving_hunting)
            return reasons.joinToString(", ")
        }

    companion object {
        const val SYNCED = "SYNCED"
        const val CATCHING_UP = "CATCHING_UP"
        const val STALE_ANCHOR = "STALE_ANCHOR"

        /**
         * Decode the engine's status JSON for [chainId]. `"{}"` (unknown
         * handle), a malformed body, or a missing key decodes to the
         * defaults, which are never [ready] (fail closed).
         */
        fun decode(chainId: Long, json: String): MyotisChainStatus {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return MyotisChainStatus(chainId)
            }
            return MyotisChainStatus(
                chainId = chainId,
                beaconState = o.optString("beaconState", ""),
                peerCount = o.optInt("peerCount", 0),
                snapPeers = o.optInt("snapPeers", 0),
                snapServingPeers = o.optInt("snapServingPeers", 0),
                headBlock = o.optLong("optimisticBlockNumber", 0L),
                finalizedBlock = o.optLong("finalizedBlockNumber", 0L),
                running = o.optBoolean("running", false),
                paused = o.optBoolean("paused", false),
                elReaderAvailable = o.optBoolean("elReaderAvailable", false),
                elHunting = o.optBoolean("elHunting", false),
                lcHunting = o.optBoolean("lcHunting", false),
                currentPeriod = o.optLong("currentPeriod", 0L),
                targetPeriod = o.optLong("targetPeriod", 0L),
                wsBoundPeriods = o.optLong("wsBoundPeriods", 0L),
                finalizedSlot = o.optLong("finalizedSlot", 0L),
                finalizedRootHex = o.optString("finalizedRootHex", ""),
            )
        }
    }
}
