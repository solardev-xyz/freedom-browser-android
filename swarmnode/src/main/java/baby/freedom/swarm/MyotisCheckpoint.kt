package baby.freedom.swarm

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

/*
 * Stale-anchor checkpoint recovery (#195), part 1: the checkpoint *policy*
 * — per-chain sources and thresholds, the persisted checkpoint record and
 * its validation rules, the error taxonomy. Pure, no I/O.
 *
 * Why this exists: the Myotis engine enforces a weak-subjectivity gate.
 * When both its embedded checkpoint and its saved snapshot are older than
 * the bound (13 sync-committee periods on mainnet ≈ 14.7 days, 3 on
 * Gnosis ≈ 34 h) it parks the chain in `beaconState: STALE_ANCHOR` and
 * refuses to sync — a forged continuation signed by since-exited committee
 * members would otherwise BLS-verify. The host has to obtain a fresh
 * checkpoint by its own means and bootstrap a new generation from it
 * (`myotis_create_with_checkpoint`). The "own means" is an external quorum
 * of checkpoint-sync authorities ([MyotisCheckpointQuorum]).
 *
 * Same sources, thresholds, freshness and clock rules and error codes as
 * the iOS port (`MyotisCheckpoint.swift`) of desktop's
 * `checkpoint-verifier.js`. Changing a source list or a threshold is a
 * trust-policy change and must stay in step with them.
 */

/** Why an attempt to obtain or use a checkpoint failed. [code] matches desktop / iOS. */
enum class MyotisCheckpointError(val code: String, @StringRes private val messageRes: Int) {
    /** Not enough checkpoint sources could confirm a recent checkpoint. */
    QuorumUnavailable("CHECKPOINT_QUORUM_UNAVAILABLE", R.string.swarmnode_checkpoint_quorum_unavailable),

    /** Checkpoint sources disagree — terminal, never retried automatically. */
    QuorumConflict("CHECKPOINT_QUORUM_CONFLICT", R.string.swarmnode_checkpoint_quorum_conflict),

    /** A service couldn't complete (transport, malformed body, deadline). Retryable. */
    Unavailable("CHECKPOINT_UNAVAILABLE", R.string.swarmnode_checkpoint_unavailable),

    /** The checkpoint evidence didn't pass verification. */
    Mismatch("CHECKPOINT_MISMATCH", R.string.swarmnode_checkpoint_mismatch),

    /**
     * The engine's own verified finalized root contradicted the agreed
     * checkpoint — at runtime, or recorded on the generation and refused
     * at load. The evidence passed; the chain disagreed with it.
     */
    AnchorMismatch("CHECKPOINT_ANCHOR_MISMATCH", R.string.swarmnode_checkpoint_anchor_mismatch),

    /** The checkpoint is too old (over an hour). Retryable: the next attempt asks for a fresher one. */
    Stale("CHECKPOINT_STALE", R.string.swarmnode_checkpoint_stale),

    /** The finalized checkpoint moved during verification. Retryable. */
    Race("CHECKPOINT_RACE", R.string.swarmnode_checkpoint_race),

    /** Checkpoint time disagrees with this device's clock. */
    Clock("CHECKPOINT_CLOCK", R.string.swarmnode_checkpoint_clock),

    /** Saved sync state is inconsistent (pointer, generation record, engine marker). */
    Storage("CHECKPOINT_STORAGE", R.string.swarmnode_checkpoint_storage),

    /** The filesystem refused (full, read-only, permissions). */
    StorageIO("CHECKPOINT_STORAGE_IO", R.string.swarmnode_checkpoint_storage_io),
    ;

    /** What the node page says about it. */
    val message: String get() = SwarmStrings.get(messageRes)

    /** Desktop's automatic-retry set; everything else stops and asks the user. */
    val retryable: Boolean
        get() = this == Unavailable || this == QuorumUnavailable || this == Race || this == Stale
}

/** How a fetch failed — diagnostics only; the verdict is always [MyotisCheckpointError.Unavailable]. */
enum class MyotisTransportFailure(val label: String) {
    Http("http"),
    Timeout("timeout"),
    Transport("transport"),
    BodyLimit("body-limit"),
    InvalidJson("invalid-json"),
}

/** Which request of a vote (or of a slot proposal) failed — diagnostics only. */
enum class MyotisCheckpointStage(val label: String) {
    Proposal("proposal"),
    BlockRoot("block-root"),
    Finality("finality"),
    History("history"),
}

/**
 * A checkpoint failure: the [error] verdict plus bounded diagnostics
 * (which stage, what kind of transport failure, the HTTP status). Never
 * carries a response body.
 */
class MyotisCheckpointException(
    val error: MyotisCheckpointError,
    val stage: MyotisCheckpointStage? = null,
    val failure: MyotisTransportFailure? = null,
    val httpStatus: Int? = null,
) : Exception(error.code) {
    fun at(stage: MyotisCheckpointStage) =
        if (this.stage != null) this else MyotisCheckpointException(error, stage, failure, httpStatus)

    companion object {
        fun transport(failure: MyotisTransportFailure, httpStatus: Int? = null) =
            MyotisCheckpointException(MyotisCheckpointError.Unavailable, failure = failure, httpStatus = httpStatus)
    }
}

/**
 * Per-chain checkpoint policy. Frozen constants, mirrored from desktop
 * `CHECKPOINT_NETWORKS` / iOS `MyotisCheckpointNetwork`.
 */
class MyotisCheckpointNetwork(
    val network: MyotisNetwork,
    /** Candidate authorities in stable order; replacement walks this order. */
    val sources: List<String>,
    /**
     * Standard Beacon API authorities (no Checkpointz history endpoint):
     * their vote needs the requested block's own `finalized: true` and
     * `execution_optimistic: false`.
     */
    val beaconSources: List<String> = emptyList(),
    /** Seats: how many candidates vote at once. */
    val participants: Int,
    /** Agreeing votes required. Never lowered because a host is missing. */
    val threshold: Int,
    /** Beacon genesis time, seconds since the epoch. */
    val genesis: Long,
    val secondsPerSlot: Long,
    val slotsPerEpoch: Long,
) {
    val chainId: Long get() = network.chainId

    /** Wall-clock time of [slot], ms since the epoch. */
    fun slotTimeMs(slot: Long): Long = (genesis + slot * secondsPerSlot) * 1000

    /** The slot the wall clock is in at [nowMs] (floor). */
    fun wallSlot(nowMs: Long): Long = (Math.floorDiv(nowMs, 1000L) - genesis) / secondsPerSlot

    /** The epoch boundary a finality certificate for [slot] must reach. */
    fun epochCeil(slot: Long): Long = (slot + slotsPerEpoch - 1) / slotsPerEpoch

    /** `epoch * slotsPerEpoch`, or null on overflow. */
    fun epochStartSlot(epoch: Long): Long? =
        try {
            Math.multiplyExact(epoch, slotsPerEpoch)
        } catch (_: ArithmeticException) {
            null
        }

    companion object {
        val Mainnet = MyotisCheckpointNetwork(
            network = MyotisNetwork.Mainnet,
            sources = listOf(
                "https://mainnet.checkpoint.sigp.io",
                "https://beaconstate.ethstaker.cc",
                "https://beaconstate-mainnet.chainsafe.io",
                "https://mainnet-checkpoint-sync.attestant.io",
                "https://sync-mainnet.beaconcha.in",
                "https://checkpointz.pietjepuk.net",
                "https://mainnet-checkpoint-sync.stakely.io",
            ),
            participants = 3,
            threshold = 2,
            genesis = 1_606_824_023L,
            secondsPerSlot = 12,
            slotsPerEpoch = 32,
        )

        /**
         * Three independent operators (Gnosis, DAppNode, PublicNode), two
         * must agree. DAppNode's `.io`/`.net` aliases are one authority.
         */
        val Gnosis = MyotisCheckpointNetwork(
            network = MyotisNetwork.Gnosis,
            sources = listOf(
                "https://checkpoint.gnosischain.com",
                "https://checkpoint-sync-gnosis.dappnode.net",
                "https://gnosis-beacon-api.publicnode.com",
            ),
            beaconSources = listOf("https://gnosis-beacon-api.publicnode.com"),
            participants = 3,
            threshold = 2,
            genesis = 1_638_993_340L,
            secondsPerSlot = 5,
            slotsPerEpoch = 16,
        )

        fun of(network: MyotisNetwork): MyotisCheckpointNetwork = when (network) {
            MyotisNetwork.Mainnet -> Mainnet
            MyotisNetwork.Gnosis -> Gnosis
        }

        fun forChain(chainId: Long): MyotisCheckpointNetwork =
            MyotisNetwork.forChain(chainId)?.let(::of)
                ?: throw MyotisCheckpointException(MyotisCheckpointError.Mismatch)
    }
}

/** Hex and number parsing shared by the quorum, the store and the node. */
object MyotisHex {
    private fun strip(value: String): String =
        if (value.startsWith("0x") || value.startsWith("0X")) value.substring(2) else value

    private fun isHex64(hex: String) =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** `0x` + 64 lowercase hex, non-zero; accepts an optional `0x` and any case. */
    fun root(value: Any?): String? {
        val hex = strip(value as? String ?: return null)
        if (!isHex64(hex)) return null
        val lower = hex.lowercase()
        return if (lower.any { it != '0' }) "0x$lower" else null
    }

    /** Any 32-byte hex (zero allowed), normalized to `0x` + lowercase. */
    fun bytes32(value: Any?): String? {
        val hex = strip(value as? String ?: return null)
        return if (isHex64(hex)) "0x${hex.lowercase()}" else null
    }

    /**
     * A non-negative integer from a JSON number or a decimal / `0x`
     * string (the Beacon API sends quoted decimals). Null for anything
     * else, including fractions and values past `Long.MAX_VALUE`.
     */
    fun uint(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        is Double -> value.takeIf { it >= 0 && it == Math.rint(it) && it <= 9_007_199_254_740_991.0 }?.toLong()
        is String -> when {
            value.startsWith("0x") || value.startsWith("0X") ->
                value.substring(2).takeIf { it.isNotEmpty() && it.length <= 15 }?.toLongOrNull(16)
            value.isNotEmpty() && value.length <= 18 && value.all { it in '0'..'9' } -> value.toLongOrNull()
            else -> null
        }
        else -> null
    }
}

/**
 * The persisted checkpoint record (desktop / iOS schema v2). [verifiedAt]
 * is ms since the epoch; [sources] are the quorum members that endorsed
 * [root] at [slot] — provenance, not a trust claim about any one host.
 */
data class MyotisCheckpointRecord(
    val chainId: Long,
    val network: String,
    val root: String,
    val slot: Long,
    val verifiedAt: Long,
    val sources: List<String>,
    val finalizedEpoch: Long,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /**
     * Desktop `validateCheckpoint`: the record as it must be to be used.
     * [fresh] applies the acquisition-time rules (clock agreement, at most
     * [MAX_AGE_MS] old); a reloaded generation is validated with `fresh =
     * false` because the engine re-judges saved state against its own
     * weak-subjectivity bound.
     */
    fun validated(chainId: Long, nowMs: Long, fresh: Boolean = true): MyotisCheckpointRecord {
        val mismatch = MyotisCheckpointException(MyotisCheckpointError.Mismatch)
        val config = MyotisCheckpointNetwork.forChain(chainId)
        val quorum = schemaVersion == SCHEMA_VERSION &&
            sources.size >= config.threshold &&
            sources.size <= config.participants &&
            sources.toSet().size == sources.size &&
            sources.all { it in config.sources }
        val normalized = MyotisHex.root(root)
        if (!quorum || this.chainId != chainId || network != config.network.engineName ||
            normalized == null || normalized != root || slot <= 0 || verifiedAt <= 0
        ) throw mismatch
        val slotTime = config.slotTimeMs(slot)
        val epochSlot = config.epochStartSlot(finalizedEpoch) ?: throw mismatch
        if (finalizedEpoch < 0 || epochSlot < slot || verifiedAt < slotTime) throw mismatch
        if (fresh) {
            if (slotTime > nowMs || epochSlot > config.wallSlot(nowMs) || verifiedAt > nowMs) {
                throw MyotisCheckpointException(MyotisCheckpointError.Clock)
            }
            if (nowMs - slotTime > MAX_AGE_MS) throw MyotisCheckpointException(MyotisCheckpointError.Stale)
        }
        return this
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("chainId", chainId)
        .put("network", network)
        .put("root", root)
        .put("slot", slot)
        .put("verifiedAt", verifiedAt)
        .put("sources", JSONArray(sources))
        .put("finalizedEpoch", finalizedEpoch)

    companion object {
        const val SCHEMA_VERSION = 2

        /** A checkpoint must be at most this old when it's acquired. */
        const val MAX_AGE_MS = 60L * 60L * 1000L

        /** Null for anything that isn't a well-formed record (validation is separate). */
        fun fromJson(o: JSONObject): MyotisCheckpointRecord? {
            val sources = o.optJSONArray("sources") ?: return null
            val list = (0 until sources.length()).map { sources.opt(it) as? String ?: return null }
            return MyotisCheckpointRecord(
                chainId = MyotisHex.uint(o.opt("chainId")) ?: return null,
                network = o.opt("network") as? String ?: return null,
                root = o.opt("root") as? String ?: return null,
                slot = MyotisHex.uint(o.opt("slot")) ?: return null,
                verifiedAt = MyotisHex.uint(o.opt("verifiedAt")) ?: return null,
                sources = list,
                finalizedEpoch = MyotisHex.uint(o.opt("finalizedEpoch")) ?: return null,
                schemaVersion = (MyotisHex.uint(o.opt("schemaVersion")) ?: return null).toInt(),
            )
        }
    }
}
