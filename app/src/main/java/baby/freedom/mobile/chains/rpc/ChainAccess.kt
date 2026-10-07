package baby.freedom.mobile.chains.rpc

import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A tier [ChainDataRouter] can ask for chain state (#108), in the order
 * a chain's [ChainAccessPolicy] lists them. [key] is desktop's
 * `access.readOrder` entry (`chain-data-router.js`) and iOS's
 * `ChainSource` raw value.
 */
enum class ChainSource(val key: String, @StringRes private val labelRes: Int) {
    /** Embedded P2P light client (Ethereum, Gnosis), when the user runs it for that chain ([baby.freedom.mobile.chains.rpc.MyotisChainSource]). */
    MYOTIS("myotis", R.string.names_source_myotis),

    /** Remote prover with a sync-committee proof checked on the device (Ethereum, Gnosis; [baby.freedom.mobile.chains.rpc.ColibriChainSource]). */
    COLIBRI("colibri", R.string.names_source_colibri),

    /** [ChainAccessPolicy.quorumM] of the first [ChainAccessPolicy.quorumK] RPCs return the same bytes. */
    QUORUM("quorum", R.string.names_source_quorum),

    /** The first RPC that answers, on its own word. */
    DIRECT("direct", R.string.names_source_direct),
    ;

    /** The tier's name, as the chain page and the wallet show it. */
    val label: String get() = Strings.get(labelRes)

    /** Whether an answer from this tier carries a proof or an agreement. */
    val verifies: Boolean get() = this != DIRECT

    /** The proof tiers: a light client or a prover, not RPCs agreeing. */
    val proves: Boolean get() = this == MYOTIS || this == COLIBRI

    /** Tiers that can send a signed transaction. */
    val canBroadcast: Boolean get() = this == MYOTIS || this == DIRECT
}

/**
 * One chain's routing policy: which tiers reads and broadcasts walk, and
 * the quorum's numbers. Desktop's `network.access` / `network.quorum`,
 * iOS's `ChainAccessPolicy`, with the same defaults.
 */
data class ChainAccessPolicy(
    val readOrder: List<ChainSource>,
    val broadcastOrder: List<ChainSource>,
    val quorumK: Int = DEFAULT_QUORUM_K,
    val quorumM: Int = DEFAULT_QUORUM_M,
    /** Per-source and per-endpoint timeout. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * The policy as the router applies it to [chainId]: tiers the chain
     * can't use and repeats dropped, an empty order replaced by
     * [ChainSource.DIRECT] (a bad policy must never leave a chain
     * unreadable), `1 ≤ m ≤ k`, and at least [MIN_TIMEOUT_MS].
     */
    fun sanitized(chainId: Long): ChainAccessPolicy {
        fun clean(order: List<ChainSource>, allowed: (ChainSource) -> Boolean) =
            order.filter { allowed(it) && supports(it, chainId) }.distinct()
                .ifEmpty { listOf(ChainSource.DIRECT) }
        val k = quorumK.coerceAtLeast(1)
        return ChainAccessPolicy(
            readOrder = clean(readOrder) { true },
            broadcastOrder = clean(broadcastOrder) { it.canBroadcast },
            quorumK = k,
            quorumM = quorumM.coerceIn(1, k),
            timeoutMs = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
        )
    }

    companion object {
        const val DEFAULT_QUORUM_K = 3
        const val DEFAULT_QUORUM_M = 2
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** Desktop clamps every configured timeout to at least half a second. */
        const val MIN_TIMEOUT_MS = 500L

        /** Chains the light client and the Colibri prover cover: Ethereum and Gnosis. */
        val LIGHT_CLIENT_CHAIN_IDS = setOf(1L, 100L)

        fun supports(source: ChainSource, chainId: Long): Boolean = when (source) {
            ChainSource.MYOTIS, ChainSource.COLIBRI -> chainId in LIGHT_CLIENT_CHAIN_IDS
            ChainSource.QUORUM, ChainSource.DIRECT -> true
        }

        /**
         * Desktop's defaults: the whole ladder where a light client
         * exists, `quorum → direct` elsewhere — so any chain whose pool
         * has at least [DEFAULT_QUORUM_M] RPCs gets cross-checked reads.
         */
        fun default(chainId: Long): ChainAccessPolicy {
            val light = chainId in LIGHT_CLIENT_CHAIN_IDS
            return ChainAccessPolicy(
                readOrder = if (light) {
                    listOf(ChainSource.MYOTIS, ChainSource.COLIBRI, ChainSource.QUORUM, ChainSource.DIRECT)
                } else {
                    listOf(ChainSource.QUORUM, ChainSource.DIRECT)
                },
                broadcastOrder = if (light) {
                    listOf(ChainSource.MYOTIS, ChainSource.DIRECT)
                } else {
                    listOf(ChainSource.DIRECT)
                },
            )
        }
    }
}

/**
 * Who is asking. A site's read names the site's origin; the wallet's own
 * reads name none. Two separate things follow from it:
 *
 * - [site]: the read is of something a site chose, so it mustn't cost the
 *   wallet's own reads anything shared in the proof tiers — it gets a
 *   site's share of their slots, and its miss or failure never backs a
 *   tier off on its own ([VerifiedChainSource.request]).
 * - [interactive]: a page is waiting on a frame for it, so it trades
 *   verification for latency ([ChainDataRouter.INTERACTIVE_DEADLINE_MS]).
 *   Only a page-driven read ([forPage]: a dapp's `eth_call`, a `web3://`
 *   app) is; a read the wallet makes *about* a site's choice
 *   ([forSiteChoice]: an x402 offer's token contract) keeps the chain's
 *   full timeout for the quorum, since it needs a verified answer, not a
 *   fast one (#329 R5-F1); only its proof tiers' waits are capped.
 */
class RoutingContext private constructor(
    val origin: String?,
    val interactive: Boolean,
) {
    val site: Boolean get() = origin != null

    override fun equals(other: Any?) =
        other is RoutingContext && other.origin == origin && other.interactive == interactive
    override fun hashCode() = origin.hashCode() * 31 + interactive.hashCode()
    override fun toString() = when {
        origin == null -> "RoutingContext(wallet)"
        interactive -> "RoutingContext($origin)"
        else -> "RoutingContext($origin, not interactive)"
    }

    companion object {
        val WALLET = RoutingContext(null, interactive = false)

        /**
         * A page's own read. The origin is desktop's
         * `normalizeRoutingOrigin`: trimmed, non-empty, at most 2048
         * characters, no control characters — anything else counts as the
         * wallet's own read.
         */
        fun forPage(origin: String?): RoutingContext =
            normalize(origin)?.let { RoutingContext(it, interactive = true) } ?: WALLET

        /**
         * The wallet's read of something site [origin] chose (an x402
         * offer's token contract): the site's for the proof tiers' slots
         * and back-off, but not a page's latency trade — the quorum gets
         * the chain's full timeout, as for the wallet's own reads. Each
         * proof tier gets [ChainDataRouter.SITE_PROOF_DEADLINE_MS], since
         * a site's miss doesn't back it off (#329 R6-F1).
         */
        fun forSiteChoice(origin: String?): RoutingContext =
            normalize(origin)?.let { RoutingContext(it, interactive = false) } ?: WALLET

        private fun normalize(origin: String?): String? {
            val t = origin?.trim()
            if (t.isNullOrEmpty() || t.length > 2048 || t.any { it.code <= 31 || it.code == 127 }) return null
            return t
        }
    }
}

/**
 * How far a [ChainDataResult] was checked. Every field is required: a
 * path that forgets to say how it checked an answer mustn't get
 * "verified" by default.
 */
data class ChainTrust(
    val level: Level,
    /** The tier that produced the answer. */
    val source: ChainSource,
    /** Hosts that gave this answer (the prover, the light client's tag, …). */
    val agreed: List<String>,
    /** Hosts that answered something else. */
    val dissented: List<String>,
    /** Hosts asked. */
    val queried: List<String>,
    /** The quorum's size and threshold; 1 of 1 outside the quorum tier. */
    val k: Int,
    val m: Int,
    /** Block the answer was read at, when the source knows it. */
    val block: Long?,
) {
    enum class Level {
        /** A proof, or [m] independent RPCs agreeing byte for byte. */
        VERIFIED,

        /** One RPC's word, and that RPC is one the user added themselves. */
        USER_CONFIGURED,

        /** One public RPC's word. */
        UNVERIFIED,
    }
}

/**
 * Whether a read is good enough to act on without a second look: verified
 * (a proof, or a quorum agreeing), or one RPC's word when it is an RPC the
 * user added and none dissented. A lone public RPC's word is not.
 */
val ChainTrust.undisputed: Boolean
    get() = when (level) {
        ChainTrust.Level.VERIFIED -> true
        ChainTrust.Level.USER_CONFIGURED -> dissented.isEmpty()
        ChainTrust.Level.UNVERIFIED -> false
    }

/** A chain read with where it came from. [result] is the JSON-RPC `result` (`null` for JSON null). */
data class ChainDataResult(
    val result: Any?,
    val trust: ChainTrust,
)

/**
 * A light client or prover the router can ask ([ChainSource.MYOTIS]:
 * [MyotisChainSource], [ChainSource.COLIBRI]: [ColibriChainSource], #329).
 * The router skips a tier with no source registered, or one that isn't
 * [isAvailable], so a policy naming one still reads. Admission (how many
 * calls a source takes at once) is the source's own business — the
 * router only bounds how long it waits.
 */
interface VerifiedChainSource {
    /** Whether the source can answer for [chainId] right now (synced, reachable). Cheap: the chain page asks it while drawing. */
    fun isAvailable(chainId: Long): Boolean

    /**
     * Why the source can't answer for [chainId] right now, when it can't
     * ([isAvailable] false) — for the chain page's note. Cheap, like
     * [isAvailable]; `null` when it can answer.
     */
    fun gap(chainId: Long): ProofTierGap? = if (isAvailable(chainId)) null else ProofTierGap.NOT_READY

    /**
     * Answer [method] with the trust the source mints. [rpcs] is the
     * chain's RPC pool in the router's order, for a source that fetches
     * the state it proves from them. Throw [ChainRpcException.Rpc] with
     * `deterministic` set for an answer that is itself an error (a
     * revert); anything else thrown means "couldn't answer", and the walk
     * moves on. [context] says whose read it is: a site's
     * ([RoutingContext.site]) is one the site chose, and mustn't
     * cost the wallet's own reads anything shared (a back-off, slots).
     */
    suspend fun request(
        chainId: Long,
        method: String,
        params: org.json.JSONArray,
        rpcs: List<String>,
        context: RoutingContext = RoutingContext.WALLET,
    ): ChainDataResult

    /**
     * Whether [broadcast] is implemented. The router asks a source that
     * can't only on reads: its broadcast tier counts as not available,
     * never as "may have sent it".
     */
    val canBroadcast: Boolean get() = false

    /**
     * Send a signed transaction; its hash. Throw
     * [ChainRpcException.BroadcastUncertain] when it may already be on
     * its way — that ends the walk, the transaction is never re-sent.
     */
    suspend fun broadcast(chainId: Long, rawTransaction: String): String =
        throw UnsupportedOperationException("cannot broadcast")
}

/** Why a proof tier isn't answering a chain's reads ([VerifiedChainSource.gap]). */
enum class ProofTierGap {
    /** The source doesn't cover this chain. */
    NOT_SERVED,

    /** Not in this build, or its native library didn't load. */
    NOT_IN_BUILD,

    /** Switched off by the user (Colibri: *Colibri proofs*). */
    OFF,

    /** Backing off after its servers couldn't be reached. */
    UNREACHABLE,

    /** Not ready for this chain (the light client: off, syncing, parked on a stale anchor). */
    NOT_READY,
}

/**
 * The router's wait for one proof tier's answer, carried into the
 * source's call ([withRouterWait]) so the source can tell that wait
 * running out — a missed wait, which says something about the prover —
 * from its reader going away first: the user leaving the page, or a
 * caller's own shorter timeout, which says nothing (#329 R4-M1).
 */
internal class RouterWait : AbstractCoroutineContextElement(Key) {
    /** Set by the router's own timer just before it cancels the call: nothing else sets it. */
    @Volatile
    var ranOut = false
        private set

    internal fun expire() {
        ranOut = true
    }

    companion object Key : CoroutineContext.Key<RouterWait>
}

/**
 * [block] under the router's [ms] wait for a proof tier, as
 * `withTimeoutOrNull`: null when the wait runs out. [block] runs with a
 * [RouterWait] in its context whose [RouterWait.ranOut] is set before
 * the cancellation reaches it — and only then, never when the caller
 * itself is cancelled.
 */
internal suspend fun <T> withRouterWait(ms: Long, block: suspend () -> T): T? = coroutineScope {
    val wait = RouterWait()
    val work = async(wait) { block() }
    val timer = launch {
        delay(ms)
        wait.expire()
        work.cancel()
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        // Ours ran out: no answer. Anything else (the caller went away) goes on up.
        if (!wait.ranOut) throw e
        currentCoroutineContext().ensureActive()
        null
    } finally {
        timer.cancel()
    }
}
