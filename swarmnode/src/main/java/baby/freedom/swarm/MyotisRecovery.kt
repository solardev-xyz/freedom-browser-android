package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/*
 * Stale-anchor checkpoint recovery (#195), part 4: the user-visible
 * recovery state and the policy tables behind it — port of iOS
 * `MyotisRecovery.swift` (desktop `myotis-manager.js`'s `recovery`
 * object, reason mapping and retry ladder). Pure values; [MyotisNode]
 * drives them.
 */

/** Why recovery stopped or is waiting. Names match desktop / iOS reason strings. */
enum class MyotisRecoveryReason(val code: String) {
    Unavailable("unavailable"),
    QuorumUnavailable("quorum-unavailable"),
    QuorumConflict("quorum-conflict"),
    /** Checkpoint evidence that didn't pass verification (acquisition or a saved record). */
    Mismatch("mismatch"),

    /**
     * The engine's own BLS-verified finalized root contradicted the agreed
     * checkpoint — seen at runtime, or recorded on the generation
     * (`rejected.json`) and found again at startup. Not the evidence
     * failing: the checkpoint passed, the chain disagreed with it.
     */
    AnchorMismatch("anchor-mismatch"),
    Clock("clock"),
    Storage("storage"),
    StorageIO("storage-io"),
    Stale("stale"),
    Startup("startup"),
    Stalled("stalled"),
    ;

    /**
     * A manual retry of these restarts the chain's current generation (no
     * new checkpoint): the engine's own stale gate asks for one again if
     * the anchor really expired.
     */
    val restartsOwnedState: Boolean
        get() = this == Startup || this == Stalled || this == Storage || this == StorageIO

    /** The manual action is "Repair sync data" (fresh generation, old data kept) rather than a retry. */
    val offersRepair: Boolean get() = this == Storage

    /** One-line explanation shown on the chain's row. */
    val explanation: String
        get() = when (this) {
            Unavailable -> MyotisCheckpointError.Unavailable.message
            QuorumUnavailable -> MyotisCheckpointError.QuorumUnavailable.message
            QuorumConflict -> MyotisCheckpointError.QuorumConflict.message
            Mismatch -> MyotisCheckpointError.Mismatch.message
            AnchorMismatch -> MyotisCheckpointError.AnchorMismatch.message
            Clock -> MyotisCheckpointError.Clock.message
            Storage -> MyotisCheckpointError.Storage.message
            StorageIO -> MyotisCheckpointError.StorageIO.message
            Stale -> MyotisCheckpointError.Stale.message
            Startup -> "The light client couldn't start."
            Stalled -> "Sync hasn't finished in a while."
        }

    companion object {
        fun of(error: MyotisCheckpointError): MyotisRecoveryReason = when (error) {
            MyotisCheckpointError.QuorumUnavailable -> QuorumUnavailable
            MyotisCheckpointError.QuorumConflict -> QuorumConflict
            MyotisCheckpointError.Mismatch -> Mismatch
            MyotisCheckpointError.AnchorMismatch -> AnchorMismatch
            MyotisCheckpointError.Clock -> Clock
            MyotisCheckpointError.Storage -> Storage
            MyotisCheckpointError.StorageIO -> StorageIO
            MyotisCheckpointError.Stale -> Stale
            MyotisCheckpointError.Unavailable, MyotisCheckpointError.Race -> Unavailable
        }
    }
}

/**
 * One chain's recovery, as the node page shows it; null on the chain's
 * row when nothing is recovering or blocked.
 */
@Parcelize
data class MyotisRecovery(
    val phase: Phase,
    val mode: Mode = Mode.Checkpoint,
    val reason: MyotisRecoveryReason? = null,
    /** Automatic attempts in this episode (0 for an owned-state restart). */
    val attempt: Int = 0,
    /**
     * When the next automatic attempt runs while [Phase.Waiting], on
     * [android.os.SystemClock.elapsedRealtime] — the same clock in every
     * process, so the UI can count down to it.
     */
    val nextRetryAt: Long? = null,
) : Parcelable {
    enum class Phase {
        /** Fetching a fresh checkpoint and having the quorum vote on it. */
        Checking,

        /** Checkpoint agreed (or an owned restart asked for): engine restarting, until it has synced from it. */
        Restarting,

        /** An automatic retry is scheduled ([nextRetryAt]). */
        Waiting,

        /** A terminal failure: needs the user ([canRetry] / [canRepair]). */
        Blocked,
    }

    enum class Mode {
        /** Recovery from a new quorum-agreed checkpoint. */
        Checkpoint,

        /** Restart of the chain's current (or, repairing, a fresh) generation — no new checkpoint. */
        Restart,
    }

    /** Offered while blocked and while waiting — nobody needs to sit out a five-minute wait. */
    val canRetry: Boolean get() = phase == Phase.Blocked || phase == Phase.Waiting

    /** "Repair sync data" instead of a plain retry. */
    val canRepair: Boolean get() = canRetry && reason?.offersRepair == true

    /** The chain row's one-line state. */
    val label: String
        get() = when (phase) {
            Phase.Blocked -> if (reason == MyotisRecoveryReason.Stalled) "Syncing slowly" else "Sync paused"
            else -> "Updating checkpoint"
        }

    /** The chain row's explanation; [nowElapsed] on the same clock as [nextRetryAt]. */
    fun message(nowElapsed: Long): String = when (phase) {
        Phase.Checking ->
            if (reason == MyotisRecoveryReason.AnchorMismatch) {
                "${MyotisCheckpointError.AnchorMismatch.message} Asking checkpoint services for a fresh one…"
            } else {
                "This chain's checkpoint is too old to sync from. Asking checkpoint services for a fresh one…"
            }
        Phase.Restarting ->
            if (mode == Mode.Restart) "Restarting the light client…" else "Fresh checkpoint agreed. Syncing from it…"
        Phase.Waiting -> {
            val why = (reason ?: MyotisRecoveryReason.Unavailable).explanation
            val seconds = ((nextRetryAt ?: nowElapsed) - nowElapsed).coerceAtLeast(0).let { (it + 999) / 1000 }
            "$why Trying again in ${seconds}s."
        }
        Phase.Blocked -> (reason ?: MyotisRecoveryReason.Unavailable).explanation
    }
}

/** Retry ladder, timers and the post-bootstrap anchor checks (desktop / iOS constants). */
object MyotisRecoveryPolicy {
    /** Attempt 1 fails → 15 s, attempt 2 → 60 s, every later failure → [BACKGROUND_RETRY_MS]. */
    val RETRY_DELAYS_MS = listOf(15_000L, 60_000L)

    /** The steady retry cadence after the ladder: an outage never parks recovery for good. */
    const val BACKGROUND_RETRY_MS = 5L * 60_000L

    /** Not finished this long after a restart (foreground time) → blocked as [MyotisRecoveryReason.Stalled]. */
    const val STALL_MS = 5L * 60_000L

    fun retryDelay(afterAttempt: Int): Long? = when {
        afterAttempt < 1 -> null
        afterAttempt <= RETRY_DELAYS_MS.size -> RETRY_DELAYS_MS[afterAttempt - 1]
        else -> BACKGROUND_RETRY_MS
    }

    /**
     * Desktop `canFinishRecovery`: the engine has synced past the anchored
     * checkpoint — and, while it's still exactly at the checkpoint slot,
     * with the anchored root.
     */
    fun canFinish(status: MyotisChainStatus, checkpoint: MyotisCheckpointRecord?): Boolean {
        if (!status.synced) return false
        checkpoint ?: return true
        val root = MyotisHex.bytes32(status.finalizedRootHex) ?: return false
        if (status.finalizedSlot < checkpoint.slot) return false
        return status.finalizedSlot != checkpoint.slot || root == checkpoint.root
    }

    /**
     * Desktop `observeSync`'s runtime guard: synced at exactly the
     * checkpoint slot with a different finalized root — the engine's own
     * BLS-verified view contradicts what the quorum agreed on.
     */
    fun isAnchorMismatch(status: MyotisChainStatus, checkpoint: MyotisCheckpointRecord?): Boolean {
        checkpoint ?: return false
        if (!status.synced || status.finalizedSlot != checkpoint.slot) return false
        val root = MyotisHex.bytes32(status.finalizedRootHex) ?: return false
        return root != checkpoint.root
    }
}
