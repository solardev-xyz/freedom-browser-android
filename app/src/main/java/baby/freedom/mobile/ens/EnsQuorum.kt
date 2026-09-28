package baby.freedom.mobile.ens

import baby.freedom.mobile.chains.rpc.ChainDataRouter

/**
 * The vote counting behind [EnsResolver]'s cross-check (#96), kept pure
 * so every rule is unit-tested on its own. Same algorithm as desktop's
 * `ens-resolver.js` (`getPinnedBlock` / `runConsensusWave`) and iOS's
 * `AnchorCorroboration` / `QuorumWave`:
 *
 * 1. **Anchor.** Every server is asked for its head block; the median
 *    of at least [MIN_PROVIDERS] heads, minus [SAFETY_DEPTH], is the
 *    block everyone reads at. The servers then report that block's hash,
 *    and the most common hash must also be a strict majority
 *    ([hashVote]). Honest servers a block or two apart would otherwise
 *    give "different" answers that are really just different moments.
 * 2. **Wave.** [K] servers read the name's record at that block. The
 *    first answer [M] of them return byte for byte is the verified
 *    answer; one server alone is unverified; two or more that differ
 *    are a conflict ([waveVote]).
 */
internal object EnsQuorum {
    /** Fewest servers a median can take one liar out of. */
    const val MIN_PROVIDERS = 3

    /** Servers asked for the record in the first wave. */
    const val K = 3

    /** Identical answers that make one verified. */
    const val M = 2

    /**
     * Blocks behind the median head the anchor sits: past the usual
     * reorg depth, and old enough that every synced server has it
     * (~1.5 min at 12 s blocks — desktop's and iOS's `latest` anchor).
     */
    const val SAFETY_DEPTH = 8L

    /**
     * The block number to anchor at, from the heads the servers reported:
     * their median minus [SAFETY_DEPTH]. A median of three or more is
     * immune to one server lying high or low — the minimum is the
     * lowest liar's pick, the maximum the highest's. `null` with fewer
     * than [MIN_PROVIDERS] heads: nothing to corroborate against.
     */
    fun anchorNumber(heads: List<Long>): Long? {
        if (heads.size < MIN_PROVIDERS) return null
        val median = heads.sorted()[heads.size / 2]
        return (median - SAFETY_DEPTH).coerceAtLeast(0)
    }

    /**
     * The servers of [pool] that each get a vote, in order: the first
     * endpoint of every provider ([ChainDataRouter.providerOf] —
     * registrable domain, every loopback spelling as one device, a
     * keyed provider as its public twin's operator). Two URLs one
     * operator answers (`https://eth.drpc.org/?x=1` and
     * `https://eth.drpc.org`, `https://1rpc.io//eth` and
     * `https://1rpc.io/eth`, a keyed DRPC and the public one) are one
     * voter, whatever the spelling: the one listed first — yours ahead
     * of a built-in twin — takes the seat and the rest are skipped.
     * [MIN_PROVIDERS], [K] and [M] all count these.
     */
    fun voters(pool: List<String>): List<String> = pool.distinctBy(ChainDataRouter::providerOf)

    /** Whether [pool] has enough different providers ([voters]) to cross-check at all. */
    fun canCrossCheck(pool: List<String>): Boolean = voters(pool).size >= MIN_PROVIDERS

    /**
     * The order the record is read in (#102): the user's [pool] order
     * (own endpoints, keyed providers, public ones — see `EnsRpcConfig`),
     * keeping only the servers that [reported] a head for the anchor.
     * The first [K] of it are the first wave; the rest widen it, in the
     * same order. Arrival order would let whichever servers happen to be
     * fastest crowd the user's own node out of the first wave; the vote
     * itself is unchanged — [M] identical answers whatever the order, so
     * a single server, however high the user ranks it, never decides
     * alone. One server per provider ([voters]), so no spelling of an
     * endpoint gets its operator a second vote — the first of that
     * provider's servers *that reported*: a keyed endpoint whose key is
     * wrong (HTTP 401) hands the seat to its public twin rather than
     * taking its provider's vote down with it.
     */
    fun waveOrder(pool: List<String>, reported: Collection<String>): List<String> {
        val set = reported.toSet()
        return voters(pool.filter { it in set })
    }

    sealed class HashVote {
        data class Agreed(val hash: String, val hosts: List<String>) : HashVote()

        /** Too few hashes to decide anything — not a warning sign. */
        data object Insufficient : HashVote()

        /** Two or more hashes and none with the majority needed. */
        data class Disagreed(val byHash: Map<String, List<String>>) : HashVote()
    }

    /**
     * Which hash the anchor block has, from [answers] (`server → hash`, in
     * arrival order) out of [asked] servers asked.
     *
     * The winner must be the plurality *and* reach both [M] and a strict
     * majority. Plurality alone would let two colluding servers win
     * against three honest ones that happened not all to answer — the
     * threat model allows one liar, not an attacker plurality.
     *
     * Decided early (before [settled]) once a hash has a strict majority
     * of everyone *asked*: no answers still to come can outvote it. `null`
     * = not decided yet. A provider counts once ([onePerProvider]).
     */
    fun hashVote(answers: Map<String, String>, asked: Int, settled: Boolean): HashVote? {
        val byHash = LinkedHashMap<String, MutableList<String>>()
        for ((host, hash) in onePerProvider(answers)) byHash.getOrPut(hash.lowercase()) { mutableListOf() } += host
        val leader = byHash.maxByOrNull { it.value.size }
        if (leader != null && leader.value.size >= maxOf(M, asked / 2 + 1)) {
            return HashVote.Agreed(leader.key, leader.value)
        }
        if (!settled) return null
        if (leader != null && leader.value.size >= maxOf(M, answers.size / 2 + 1) &&
            byHash.values.count { it.size == leader.value.size } == 1
        ) {
            return HashVote.Agreed(leader.key, leader.value)
        }
        return if (byHash.size >= 2) HashVote.Disagreed(byHash) else HashVote.Insufficient
    }

    /** One server's reading of the record. */
    sealed class Leg {
        /** An answer: [key] is its exact bytes (result or revert data). */
        data class Answer(val key: String) : Leg()

        /**
         * No answer: transport, RPC or block-hash failure. [ccip]: the
         * server was fine but the name's offchain gateway wasn't — no
         * point asking more servers, they'd all ask the same gateway.
         */
        data class Failed(val ccip: Boolean = false) : Leg()
    }

    sealed class WaveVote {
        /** [M] or more agreed on [key]; [dissented] said something else. */
        data class Agreed(
            val key: String,
            val agreed: List<String>,
            val dissented: List<String>,
        ) : WaveVote()

        /** Every answer was [key], but fewer than [M] of them. */
        data class Unverified(val key: String, val hosts: List<String>) : WaveVote()

        /** Different answers and none with [M]: key → servers, largest first. */
        data class Conflict(val byKey: Map<String, List<String>>) : WaveVote()

        /** Nobody answered. [ccip]: every failure was the gateway's. */
        data class AllFailed(val ccip: Boolean) : WaveVote()
    }

    /** Whether [legs] already hold an answer [M] servers agree on. */
    fun waveDecided(legs: Map<String, Leg>): Boolean =
        bucketsOf(legs).values.any { it.size >= M }

    /** The verdict over [legs] (`server → leg`, in arrival order). */
    fun waveVote(legs: Map<String, Leg>): WaveVote {
        val buckets = bucketsOf(legs)
        if (buckets.isEmpty()) {
            val failed = legs.values.filterIsInstance<Leg.Failed>()
            return WaveVote.AllFailed(ccip = failed.isNotEmpty() && failed.all { it.ccip })
        }
        val sorted = buckets.entries.sortedByDescending { it.value.size }
        val top = sorted.first()
        val tied = sorted.size > 1 && sorted[1].value.size == top.value.size
        if (top.value.size >= M && !tied) {
            return WaveVote.Agreed(
                key = top.key,
                agreed = top.value,
                dissented = sorted.drop(1).flatMap { it.value },
            )
        }
        if (buckets.size == 1) return WaveVote.Unverified(top.key, top.value)
        return WaveVote.Conflict(sorted.associate { it.key to it.value })
    }

    /**
     * Whether a finished wave of [asked] servers should be widened to the
     * servers not yet asked: only when it has no verdict to give and more
     * servers could change that — nobody answered, too few did, or the
     * answers conflict only because a wave member gave no vote (a 1-vs-1
     * tie beside a failed third server, which the rest of the pool can
     * break). A conflict among every server asked is already a verdict,
     * and a gateway failure repeats on every server.
     */
    fun worthWidening(vote: WaveVote, asked: Int): Boolean = when (vote) {
        is WaveVote.AllFailed -> !vote.ccip
        is WaveVote.Unverified -> true
        is WaveVote.Conflict -> vote.byKey.values.sumOf { it.size } < asked
        is WaveVote.Agreed -> false
    }

    /**
     * [votes] with only the first server of each provider ([voters]).
     * The resolver already asks one server per provider; this keeps a
     * second one from ever being counted if it didn't.
     */
    private fun <V> onePerProvider(votes: Map<String, V>): Map<String, V> {
        val seen = HashSet<String>()
        return votes.filterKeys { seen.add(ChainDataRouter.providerOf(it)) }
    }

    private fun bucketsOf(legs: Map<String, Leg>): Map<String, List<String>> {
        val buckets = LinkedHashMap<String, MutableList<String>>()
        for ((host, leg) in onePerProvider(legs)) {
            if (leg is Leg.Answer) buckets.getOrPut(leg.key) { mutableListOf() } += host
        }
        return buckets
    }
}
