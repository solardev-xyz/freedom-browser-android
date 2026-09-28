package baby.freedom.mobile.ens

import android.util.Log
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Resolve an ENS name to its content-addressed URI (`bzz://`, `ipfs://`,
 * `ipns://`) by calling the ENS Universal Resolver over public RPC.
 *
 * Ported from `freedom-browser/src/main/ens-resolver.js` — same algorithm,
 * same caching TTL, same reason codes. We avoid pulling in ethers / web3j:
 *
 *   - ABI encoding: two dynamic `bytes` args → hand-rolled (≈15 LoC)
 *   - namehash: bottom-up keccak(node || keccak(label)) (ENSIP-1)
 *   - DNS-encoded name: length-prefixed labels + `\x00`
 *   - Keccak-256: [Keccak256] (pure Kotlin, legacy padding)
 *   - JSON-RPC: [HttpURLConnection] + `org.json.JSONObject`
 *
 * CCIP-Read (EIP-3668) is followed for offchain resolvers — subnames
 * under `base.eth`, `cb.id`, NameStone-managed names and the like. The
 * Universal Resolver reverts with `OffchainLookup`; we fetch from the
 * gateway it names, then call its callback with the gateway's answer
 * and the revert's `extraData`, repeating if the callback reverts with
 * another lookup. Gateway fetches are bounded (https only, 15 s, 4 MB)
 * because the URLs come from the contract, not from us; see
 * [ccipFetch].
 *
 * No single RPC server is taken at its word (#96): with three or more
 * endpoints, servers first agree on a block ([EnsQuorum]'s anchor),
 * then [EnsQuorum.K] of them read the record at it and the answer
 * needs [EnsQuorum.M] byte-identical copies to be verified
 * ([EnsTrust]). Servers that disagree give an [EnsResult.Conflict];
 * an answer only one server gave — or any answer, when too few servers
 * are reachable to fix a block — comes back unverified, for the browser
 * to ask about before loading it.
 *
 * The servers are the user's (#102): [Settings.endpoints], in the
 * order Settings lists them (own endpoints, keyed providers, public
 * ones), is the pool the quorum draws from. Every one of them is asked
 * for its head; the first [EnsQuorum.K] of them *in that order* that
 * reported one form the first wave, and the rest widen it in the same
 * order ([EnsQuorum.waveOrder]) — so the user's own node always gets a
 * vote, but never decides alone: [EnsQuorum.M] stays 2 however the
 * pool is ordered. Servers are counted by provider ([EnsQuorum.voters]):
 * two endpoints one operator runs are one vote. With fewer than
 * [EnsQuorum.MIN_PROVIDERS] different providers enabled no cross-check is possible at all, and every answer is one
 * server's word, labelled [EnsTrust.tooFewServers].
 *
 * WNS (`.wei`) and GNS (`.gwei`) names take the same path with a
 * different call target: `contenthash(namehash)` straight on the
 * system's NameNFT registry contract instead of `resolve()` on the
 * Universal Resolver (see [NameSystem]). Same namehash, same record
 * decoding, same cache and quorum; no CCIP-Read.
 *
 * Tezos Domains (`.tez`) names are delegated to [TezosDomainsResolver]
 * — a different chain entirely — so every caller of this resolver
 * (the submit flow, the request interceptor) covers them too.
 *
 * Names are ENSIP-15 normalized ([EnsNormalize.fastNormalize], desktop's
 * `fastNormalize` over `@adraffy/ens-normalize`) before they are hashed,
 * so emoji and non-ASCII labels hash to the same node as in every other
 * client; a non-ASCII name ENSIP-15 rejects is an `INVALID_NAME` error,
 * never a lookup. Plain `[a-z0-9.-]` names skip the pass, as on desktop,
 * so legacy `xn--…`/`ab--c` registrations still resolve. A `.tez` name
 * isn't ENS and skips it too ([EnsNormalize.appliesTo]): it gets Tezos
 * Domains' own UTS-46 form ([EnsNormalize.tezosForm]; plain lowercase for
 * an ASCII name) and is never refused here.
 */
class EnsResolver internal constructor(
    private val settings: suspend () -> Settings,
    private val http: EnsHttp,
    private val tezos: TezosDomainsResolver = TezosDomainsResolver(),
) {
    /**
     * What the user configured (#102): the RPC endpoints to ask, in
     * order, and whether to follow CCIP-Read. Read afresh for every
     * lookup, so a change in Settings applies to the next one without
     * a restart; a change also drops the cache (see [Epoch]).
     */
    data class Settings(
        val endpoints: List<String>,
        val ccipRead: Boolean = true,
    )

    constructor(settings: suspend () -> Settings) : this(settings, EnsHttp.Default)

    constructor(rpcEndpoints: List<String> = DEFAULT_RPC_ENDPOINTS) :
        this({ Settings(rpcEndpoints) }, EnsHttp.Default)

    internal constructor(
        rpcEndpoints: List<String>,
        http: EnsHttp,
        tezos: TezosDomainsResolver = TezosDomainsResolver(),
    ) : this({ Settings(rpcEndpoints) }, http, tezos)

    private data class Cached(val result: EnsResult, val expiresAt: Long)

    /**
     * Everything a lookup learns about the servers — answer cache,
     * anchor block, recent failures — together with the [settings] it
     * was learnt under. A settings change swaps in a fresh [Epoch]
     * rather than clearing shared state, and every lookup reads and
     * writes only the epoch it started under: a lookup still waiting on
     * an endpoint the user has since removed finishes into the discarded
     * epoch, never into the one later lookups read.
     */
    private class Epoch(val settings: Settings) {
        val cache = ConcurrentHashMap<String, Cached>()

        /**
         * When each endpoint last failed a one-server lookup. One that
         * failed within [FAILED_ENDPOINT_COOLDOWN_MS] is tried after the
         * others, so an outage costs one timeout rather than one per
         * lookup, while the configured order comes back once it passes.
         */
        val failedAt = ConcurrentHashMap<String, Long>()

        @Volatile
        var anchor: AnchorRound? = null

        @Volatile
        var anchorInFlight: Deferred<AnchorRound?>? = null
    }

    @Volatile
    private var epoch: Epoch? = null

    private fun epochFor(config: Settings): Epoch = synchronized(this) {
        epoch?.takeIf { it.settings == config } ?: Epoch(config).also { epoch = it }
    }

    /**
     * Where the blocking RPC requests run. Detached from the caller on
     * purpose: a wave that has its answer stops *waiting* for the
     * stragglers at once, while their sockets finish (or time out) here
     * — `HttpURLConnection` can't be interrupted.
     */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolve [rawName] (e.g. `swarm.eth`) to a content-addressed URI.
     * Thread-safe; cached per normalized name for as long as the answer
     * deserves ([ttlFor]).
     *
     * Cancellation-honest: a caller whose coroutine was cancelled while
     * we were resolving gets a `CancellationException`, never an
     * [EnsResult]. Callers *act* on what comes back — navigate the tab,
     * show an error page, cancel whatever probe the tab is now waiting
     * on — and a probe the user has already superseded must do none of
     * that (#51).
     */
    suspend fun resolveContenthash(rawName: String): EnsResult {
        val result = resolve(rawName)
        // Not every cancellation arrives as a `CancellationException`:
        // tearing down the RPC in flight can surface as an ordinary
        // `IOException`, which the retry loop maps to a PROVIDER_ERROR
        // like any other transport failure. One check on the way out
        // covers every return path above.
        coroutineContext.ensureActive()
        return result
    }

    /** An answer, and whether a quorum of servers stands behind it. */
    private class Verdict(val result: EnsResult, val verified: Boolean)

    private suspend fun resolve(rawName: String): EnsResult {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) {
            return EnsResult.Error(name = "", reason = "INVALID_NAME", error = "empty name")
        }
        val normalized = try {
            EnsNormalize.fastNormalize(trimmed)
        } catch (e: EnsNormalize.InvalidNameException) {
            return EnsResult.Error(name = trimmed, reason = "INVALID_NAME", error = e.message.orEmpty())
        }

        val system = NameSystem.forName(normalized)
        // `.tez` isn't Ethereum: its own resolver, quorum and TTL cache.
        if (system == NameSystem.TEZOS) return tezos.resolve(normalized)

        val config = settings()
        // Answers from endpoints the user has since dropped (or got
        // with CCIP-Read on) must not outlive the change by the cache
        // TTL: a new configuration starts a new, empty epoch.
        val epoch = epochFor(config)
        val cache = epoch.cache
        if (config.endpoints.isEmpty()) {
            return EnsResult.Error(normalized, "NO_RPC_ENDPOINTS", "no RPC endpoints configured")
        }

        cache[normalized]?.let {
            if (System.currentTimeMillis() < it.expiresAt) return it.result
        }

        val contract = system.contractAddress
        val target = contract ?: UNIVERSAL_RESOLVER
        val callData = if (contract != null) {
            CONTENTHASH_SELECTOR + namehash(normalized)
        } else {
            try {
                buildResolveCallData(normalized)
            } catch (e: IllegalArgumentException) {
                // A label past the DNS encoding's 255 bytes is a valid
                // ENSIP-15 name the Universal Resolver just can't be
                // asked about — its own reason, so the error page doesn't
                // blame the naming rules. Anything else dnsEncode refuses
                // (an empty label from the ASCII fast path) is one ENSIP-15
                // refuses too.
                val tooLong = normalized.split('.').any { it.toByteArray(Charsets.UTF_8).size > MAX_DNS_LABEL_BYTES }
                return EnsResult.Error(
                    name = normalized,
                    reason = if (tooLong) "NAME_TOO_LONG" else "INVALID_NAME",
                    error = if (tooLong) "a label is longer than $MAX_DNS_LABEL_BYTES bytes" else e.message.orEmpty(),
                )
            }
        }

        // Cross-checked across servers whenever there are enough of them
        // to (#96); one server's word otherwise, labelled as such.
        val quorumPossible = EnsQuorum.canCrossCheck(config.endpoints)
        val verdict =
            (if (quorumPossible) resolveByQuorum(epoch, normalized, target, callData, contract) else null)
                ?: resolveSingleSource(epoch, normalized, target, callData, contract)
        val ttl = ttlFor(verdict)
        if (ttl > 0) cache[normalized] = Cached(verdict.result, System.currentTimeMillis() + ttl)
        Log.i(TAG, "[$normalized] → ${verdict.result}")
        return verdict.result
    }

    /**
     * How long an answer is reused. A cross-checked one for as long as
     * resolutions always were; one server's word briefly, so the next
     * visit soon gets the chance to verify it; a disagreement only for a
     * moment (it may be a server catching up); a failure not at all.
     */
    private fun ttlFor(verdict: Verdict): Long = when (verdict.result) {
        is EnsResult.Error -> 0
        is EnsResult.Conflict -> CONFLICT_TTL_MS
        else -> if (verdict.verified) CACHE_TTL_MS else UNVERIFIED_TTL_MS
    }

    // ---- Quorum (#96) ----

    /**
     * One corroborated block to read at — [EnsQuorum]'s anchor — as it
     * is being settled. [number] is known once the heads are in; the
     * hash vote may still be running, so the first wave's reads can
     * start at [number] straight away and only be *counted* once [vote]
     * says which hash that block has — and each server has said its
     * block [number] is that one ([hashes]). That keeps a cold lookup
     * at two round trips (heads, then block + record together) instead
     * of three.
     */
    private class AnchorRound(
        val number: Long,
        /** Servers that reported a head, in the configured order. */
        val order: List<String>,
        /** Each of those servers' hash for block [number]. */
        val hashes: Map<String, Deferred<String>>,
        val vote: Deferred<EnsQuorum.HashVote>,
        val startedAt: Long,
    ) {
        val tag: String get() = "0x" + number.toString(16)
    }

    /**
     * The anchor to read at: the last one while it's younger than
     * [ANCHOR_TTL_MS] and its vote hasn't failed, else a new one —
     * shared by concurrent lookups. `null` when too few servers report a
     * head to take a median.
     */
    private suspend fun anchorRound(epoch: Epoch): AnchorRound? {
        epoch.anchor?.let { round ->
            val fresh = System.currentTimeMillis() - round.startedAt < ANCHOR_TTL_MS
            val usable = !round.vote.isCompleted || round.vote.await() is EnsQuorum.HashVote.Agreed
            if (fresh && usable) return round
        }
        val job = synchronized(epoch) {
            epoch.anchorInFlight?.takeIf { it.isActive }
                ?: io.async { newAnchorRound(epoch) }.also { epoch.anchorInFlight = it }
        }
        return job.await()
    }

    private suspend fun newAnchorRound(epoch: Epoch): AnchorRound? {
        val startedAt = System.currentTimeMillis()
        // One server per provider ([EnsQuorum.voters]): a second endpoint
        // of the same operator would count its head, and its hash, twice.
        val pool = EnsQuorum.voters(epoch.settings.endpoints)
        val probes = pool.associateWith { rpc -> io.async { blockNumber(rpc) } }
        // Every server, not just a wave's worth: the more heads, the
        // harder the median is to move. Waits for all of them — or,
        // once enough are in for a median, a moment longer for the rest
        // rather than for the slowest server's timeout.
        val heads = gather<Long>(
            tasks = probes.mapValues { (_, probe) -> suspend { probe.await() } },
            timeoutMs = QUORUM_TIMEOUT_MS.toLong(),
            graceMs = HEAD_GRACE_MS,
            graceFrom = { got -> got.values.count { it != null } >= EnsQuorum.MIN_PROVIDERS },
        ).filterValues { it != null }.mapValues { it.value!! }
        val number = EnsQuorum.anchorNumber(heads.values.toList())
        if (number == null) {
            Log.w(TAG, "anchor infeasible: ${heads.size} of ${pool.size} servers reported a head")
            return null
        }
        val tag = "0x" + number.toString(16)
        // The user's order, not arrival order (#102): see [EnsQuorum.waveOrder].
        val order = EnsQuorum.waveOrder(pool, heads.keys)
        val hashes = order.associateWith { rpc -> io.async { blockHash(rpc, tag) } }
        val vote = io.async {
            var decided: EnsQuorum.HashVote? = null
            val answers = gather<String>(
                tasks = hashes.mapValues { (_, h) -> suspend { h.await() } },
                timeoutMs = QUORUM_TIMEOUT_MS.toLong(),
                done = { got ->
                    decided = EnsQuorum.hashVote(got.answered(), order.size, settled = false)
                    decided != null
                },
            )
            val result = decided ?: EnsQuorum.hashVote(answers.answered(), order.size, settled = true)!!
            if (result !is EnsQuorum.HashVote.Agreed) Log.w(TAG, "anchor #$number: ${scrub(result, pool)}")
            result
        }
        return AnchorRound(number, order, hashes, vote, startedAt).also { epoch.anchor = it }
    }

    /**
     * Resolve by [EnsQuorum]: a wave of [EnsQuorum.K] servers reads the
     * record at the anchor, widened to the rest if it has no verdict.
     * `null` when a quorum can't be formed at all — too few servers
     * answering to fix a block — which falls back to one server's word.
     */
    private suspend fun resolveByQuorum(
        epoch: Epoch,
        name: String,
        target: String,
        callData: ByteArray,
        contract: String?,
    ): Verdict? {
        val round = anchorRound(epoch) ?: return null
        val ccipRead = epoch.settings.ccipRead
        val outcomes = HashMap<String, CallOutcome>()
        val legs = LinkedHashMap<String, EnsQuorum.Leg>()
        val first = round.order.take(EnsQuorum.K)
        // Reading before the block hash is settled; see [AnchorRound].
        val calls = first.associateWith { startCall(it, target, callData, contract, round.tag, ccipRead) }
        val hash = when (val vote = round.vote.await()) {
            is EnsQuorum.HashVote.Agreed -> vote.hash
            is EnsQuorum.HashVote.Disagreed -> {
                calls.values.forEach { it.cancel() }
                val groups = vote.byHash.map { (h, rpcs) -> EnsResult.Conflict.Group(h, rpcs.map(::hostOf)) }
                return Verdict(
                    EnsResult.Conflict(name, EnsResult.Conflict.Subject.BLOCK, groups, round.number),
                    verified = false,
                )
            }
            EnsQuorum.HashVote.Insufficient -> {
                calls.values.forEach { it.cancel() }
                return null
            }
        }
        collectLegs(calls, round, hash, legs, outcomes)
        var vote = EnsQuorum.waveVote(legs)
        val rest = round.order.drop(EnsQuorum.K)
        if (EnsQuorum.worthWidening(vote, asked = first.size) && rest.isNotEmpty()) {
            Log.i(TAG, "[$name] widening the wave to ${rest.map(::hostOf)} after ${scrub(vote, round.order)}")
            val more = rest.associateWith { startCall(it, target, callData, contract, round.tag, ccipRead) }
            collectLegs(more, round, hash, legs, outcomes)
            vote = EnsQuorum.waveVote(legs)
        }
        Log.i(TAG, "[$name] block #${round.number}: ${scrub(vote, round.order)}")
        return when (vote) {
            is EnsQuorum.WaveVote.Agreed -> Verdict(
                decode(name, outcomes.getValue(vote.agreed.first()), contract).withTrust(
                    EnsTrust(
                        verified = true,
                        agreed = vote.agreed.map(::hostOf),
                        dissented = vote.dissented.map(::hostOf),
                        block = round.number,
                    ),
                ),
                verified = true,
            )
            is EnsQuorum.WaveVote.Unverified -> Verdict(
                decode(name, outcomes.getValue(vote.hosts.first()), contract).withTrust(
                    EnsTrust(verified = false, agreed = vote.hosts.map(::hostOf), block = round.number),
                ),
                verified = false,
            )
            is EnsQuorum.WaveVote.Conflict -> {
                val groups = vote.byKey.values.map { rpcs ->
                    EnsResult.Conflict.Group(
                        describe(decode(name, outcomes.getValue(rpcs.first()), contract)),
                        rpcs.map(::hostOf),
                    )
                }
                Verdict(
                    EnsResult.Conflict(name, EnsResult.Conflict.Subject.RECORD, groups, round.number),
                    verified = false,
                )
            }
            is EnsQuorum.WaveVote.AllFailed -> Verdict(
                EnsResult.Error(
                    name = name,
                    reason = if (vote.ccip) "CCIP_GATEWAY_FAILED" else "PROVIDER_ERROR",
                    error = "no RPC server answered",
                    retryable = true,
                ),
                verified = false,
            )
        }
    }

    /**
     * [value] for the log with every endpoint URL of [pool] in it
     * redacted: a vote names its servers by URL, and a keyed provider's
     * URL carries the user's API key (#102).
     */
    private fun scrub(value: Any, pool: List<String>): String =
        pool.sortedByDescending { it.length }
            .fold(value.toString()) { text, url -> text.replace(url, EnsRpcConfig.redact(url)) }

    /** A gateway failure inside one server's read: not that server's fault. */
    private class CcipFailure(cause: Throwable) : Exception(cause.message, cause)

    /**
     * One server's read of the record at [block], CCIP-Read followed —
     * unless [ccipRead] is off (#102). Then the `OffchainLookup` revert
     * itself is the server's answer: byte-identical from every honest
     * server, so it is voted on like any other and, agreed, [decode]s to
     * a `CCIP_DISABLED` refusal. Not a failed leg — a refusal must not
     * look like a server outage and widen the wave.
     */
    private fun startCall(
        rpc: String,
        target: String,
        callData: ByteArray,
        contract: String?,
        block: String,
        ccipRead: Boolean,
    ): Deferred<CallOutcome> = io.async {
        val call = ethCall(rpc, target, callData, block, QUORUM_TIMEOUT_MS)
        // CCIP-Read is a Universal Resolver affair; a NameNFT registry
        // is called directly and never defers offchain.
        if (ccipRead && contract == null && call.revertData != null && isOffchainLookup(call.revertData)) {
            try {
                followOffchainLookup(rpc, call.revertData, block, QUORUM_TIMEOUT_MS)
            } catch (e: Exception) {
                throw CcipFailure(e)
            }
        } else {
            call
        }
    }

    /**
     * Wait for [calls] (adding each to [legs] / [outcomes] as it lands)
     * until [EnsQuorum.M] agree or all are in. A read only counts if its
     * server also put block [AnchorRound.number] at [hash]: a server on
     * another chain, or one that won't say, has no vote.
     */
    private suspend fun collectLegs(
        calls: Map<String, Deferred<CallOutcome>>,
        round: AnchorRound,
        hash: String,
        legs: LinkedHashMap<String, EnsQuorum.Leg>,
        outcomes: MutableMap<String, CallOutcome>,
    ) {
        val judged = gather<EnsQuorum.Leg>(
            tasks = calls.mapValues { (rpc, call) -> suspend { judge(rpc, call, round, hash, outcomes) } },
            timeoutMs = LEG_TIMEOUT_MS,
            done = { got -> EnsQuorum.waveDecided(legs + got.answered()) },
        )
        for ((rpc, leg) in judged) legs[rpc] = leg ?: EnsQuorum.Leg.Failed()
        calls.values.forEach { if (it.isActive) it.cancel() }
    }

    /** One server's read as its vote — see [collectLegs]. */
    private suspend fun judge(
        rpc: String,
        call: Deferred<CallOutcome>,
        round: AnchorRound,
        hash: String,
        outcomes: MutableMap<String, CallOutcome>,
    ): EnsQuorum.Leg {
        // A CCIP failure only means "the gateway, not the server" once the
        // server has shown it's on the agreed block: one that isn't has no
        // vote at all, and mustn't count toward a gateway-only failure.
        val outcome = try {
            call.await()
        } catch (e: CcipFailure) {
            Log.w(TAG, "${hostOf(rpc)}: CCIP-Read failed: ${e.message}")
            null
        }
        val theirs = (round.hashes[rpc] ?: io.async { blockHash(rpc, round.tag) }).await()
        if (!theirs.equals(hash, ignoreCase = true)) {
            Log.w(TAG, "${hostOf(rpc)}: block #${round.number} is $theirs, not $hash")
            return EnsQuorum.Leg.Failed()
        }
        if (outcome == null) return EnsQuorum.Leg.Failed(ccip = true)
        val key = keyOf(outcome) ?: return EnsQuorum.Leg.Failed()
        synchronized(outcomes) { outcomes[rpc] = outcome }
        return EnsQuorum.Leg.Answer(key)
    }

    /** The exact bytes a read returned, as its vote; `null` = no answer. */
    private fun keyOf(outcome: CallOutcome): String? {
        outcome.revertData?.let { return "revert:" + it.lowercase() }
        val data = outcome.data?.lowercase()
        return data?.takeIf { it.length > 2 && it.startsWith("0x") }
    }

    /** What an answer says, for a warning listing the disagreeing servers. */
    private fun describe(result: EnsResult): String = when (result) {
        is EnsResult.Ok -> result.uri
        is EnsResult.NotFound -> "no content (${result.reason})"
        is EnsResult.Unsupported -> "unsupported contenthash 0x${result.rawContentHash}"
        is EnsResult.Error -> result.error
        is EnsResult.Conflict -> "conflict"
    }

    /**
     * Run [tasks] concurrently and collect what each returns in arrival
     * order (`null` for a failure or one past [timeoutMs]), until they're
     * all in or [done] says the rest don't matter. Once [graceFrom] holds,
     * the rest get [graceMs] more at most.
     */
    private suspend fun <T : Any> gather(
        tasks: Map<String, suspend () -> T>,
        timeoutMs: Long,
        graceMs: Long = 0,
        graceFrom: (Map<String, T?>) -> Boolean = { false },
        done: (Map<String, T?>) -> Boolean = { false },
    ): LinkedHashMap<String, T?> = coroutineScope {
        val arrivals = Channel<Pair<String, T?>>(Channel.UNLIMITED)
        val waiters = tasks.map { (key, task) ->
            launch {
                val value = try {
                    withTimeoutOrNull(timeoutMs) { task() }
                } catch (t: Throwable) {
                    // Ours cancelled: unwind. A task's own failure
                    // (including a request cancelled under it): no answer.
                    ensureActive()
                    // [key] is an endpoint URL; keyed ones carry the API key.
                    if (t !is CancellationException) Log.w(TAG, "${EnsRpcConfig.redact(key)}: ${t.message}")
                    null
                }
                arrivals.send(key to value)
            }
        }
        val got = LinkedHashMap<String, T?>()
        var graceEnd = Long.MAX_VALUE
        while (got.size < tasks.size && !done(got)) {
            val next = if (graceEnd == Long.MAX_VALUE) {
                arrivals.receive()
            } else {
                withTimeoutOrNull((graceEnd - System.currentTimeMillis()).coerceAtLeast(1)) {
                    arrivals.receive()
                } ?: break
            }
            got[next.first] = next.second
            if (graceEnd == Long.MAX_VALUE && graceFrom(got)) {
                graceEnd = System.currentTimeMillis() + graceMs
            }
        }
        waiters.forEach { it.cancel() }
        got
    }

    private fun <T : Any> Map<String, T?>.answered(): Map<String, T> =
        entries.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(LinkedHashMap())

    // ---- One server's word ----

    /**
     * The pre-#96 resolution: ask one server at `latest`, rotating to the
     * next on transport failure. Used when a quorum can't be formed; its
     * answers are unverified ([EnsTrust.verified] false).
     */
    private suspend fun resolveSingleSource(
        epoch: Epoch,
        normalized: String,
        target: String,
        callData: ByteArray,
        contract: String?,
    ): Verdict {
        val config = epoch.settings
        val failedAt = epoch.failedAt
        // Too few providers enabled for any cross-check (#102) — as
        // opposed to too few of them reachable right now.
        val tooFew = !EnsQuorum.canCrossCheck(config.endpoints)
        fun trustOf(rpc: String) = EnsTrust(verified = false, agreed = listOf(hostOf(rpc)), tooFewServers = tooFew)
        var lastError: EnsResult.Error? = null
        // Each endpoint once, in the configured order — except that the
        // ones that failed recently go last (see [Epoch.failedAt]). No
        // "last one that worked" pinning: with a user-ordered list that
        // would stick every later lookup to a public endpoint after the
        // user's own node was slow once.
        val now = System.currentTimeMillis()
        val order = config.endpoints.sortedBy { rpc ->
            if (failedAt[rpc]?.let { now - it < FAILED_ENDPOINT_COOLDOWN_MS } == true) 1 else 0
        }
        for (rpc in order) {
            val rpcResult = runCatchingCancellable {
                withContext(Dispatchers.IO) { ethCall(rpc, target, callData) }
            }
            if (rpcResult.isFailure) {
                val err = rpcResult.exceptionOrNull()!!
                failedAt[rpc] = System.currentTimeMillis()
                // Keyed endpoints carry the API key in their path.
                Log.w(TAG, "[$normalized] rpc=${EnsRpcConfig.redact(rpc)} failed: ${err.message}")
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "PROVIDER_ERROR",
                    error = err.message.orEmpty(),
                    retryable = true,
                )
                continue
            }

            var call = rpcResult.getOrThrow()
            // CCIP-Read is a Universal Resolver affair; a NameNFT
            // registry is called directly and never defers offchain.
            if (contract == null && call.revertData != null && isOffchainLookup(call.revertData)) {
                failedAt.remove(rpc)
                if (!config.ccipRead) {
                    // Following it would tell a third-party gateway the
                    // name; the user has said no. Not retryable, and
                    // not cached, so turning it back on works at once.
                    return Verdict(ccipDisabled(normalized), verified = false)
                }
                // Offchain resolver: run the CCIP-Read loop against the
                // same RPC. Gateway failures are retryable transport
                // errors, not "no such name", and aren't cached.
                val followed = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        followOffchainLookup(rpc, call.revertData!!)
                    }
                }
                val err = followed.exceptionOrNull()
                if (err != null) {
                    Log.w(TAG, "[$normalized] CCIP-Read failed: ${err.message}")
                    return Verdict(
                        EnsResult.Error(
                            name = normalized,
                            reason = "CCIP_GATEWAY_FAILED",
                            error = err.message.orEmpty(),
                            retryable = true,
                        ),
                        verified = false,
                    )
                }
                call = followed.getOrThrow()
            }
            if (call.revertData != null) {
                val mapped = if (contract == null) mapRevert(normalized, call.revertData) else null
                if (mapped != null) {
                    failedAt.remove(rpc)
                    return Verdict(mapped.withTrust(trustOf(rpc)), verified = false)
                }
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "RESOLUTION_ERROR",
                    error = "revert: ${call.revertData}",
                )
                break
            }

            if (call.data == null) {
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "RESOLUTION_ERROR",
                    error = "empty eth_call result",
                )
                continue
            }

            failedAt.remove(rpc)
            return Verdict(decode(normalized, call, contract).withTrust(trustOf(rpc)), verified = false)
        }

        return Verdict(
            lastError ?: EnsResult.Error(
                name = normalized,
                reason = "PROVIDER_ERROR",
                error = "all RPC providers failed",
                retryable = true,
            ),
            verified = false,
        )
    }

    /**
     * What a read of the record says, decoded. An `OffchainLookup`
     * still standing here is one [startCall] didn't follow because
     * CCIP-Read is off.
     */
    private fun decode(name: String, call: CallOutcome, contract: String?): EnsResult {
        call.revertData?.let { revert ->
            if (contract == null && isOffchainLookup(revert)) return ccipDisabled(name)
            return (if (contract == null) mapRevert(name, revert) else null)
                ?: EnsResult.Error(name = name, reason = "RESOLUTION_ERROR", error = "revert: $revert")
        }
        val raw = call.data.orEmpty()
        return if (contract != null) {
            // The registry's own `contenthash(bytes32)` return: the
            // ABI `bytes` the UR would have wrapped in its tuple.
            decodeContenthashBytes(name, raw)
        } else {
            decodeContenthashResponse(name, raw)
        }
    }

    private fun ccipDisabled(name: String) = EnsResult.Error(
        name = name,
        reason = "CCIP_DISABLED",
        error = "name needs an off-chain lookup (CCIP-Read), which is off",
    )

    private fun EnsResult.withTrust(trust: EnsTrust): EnsResult = when (this) {
        is EnsResult.Ok -> copy(trust = trust)
        is EnsResult.NotFound -> copy(trust = trust)
        is EnsResult.Unsupported -> copy(trust = trust)
        is EnsResult.Conflict, is EnsResult.Error -> this
    }

    // ---- ABI / name encoding ----

    private fun buildResolveCallData(normalizedName: String): ByteArray {
        val dnsName = dnsEncode(normalizedName)
        val node = namehash(normalizedName)
        val innerCallData = CONTENTHASH_SELECTOR + node
        return RESOLVE_SELECTOR + abiEncodeTwoBytes(dnsName, innerCallData)
    }

    // ---- CCIP-Read (EIP-3668) ----

    /**
     * Decoded `OffchainLookup(address sender, string[] urls, bytes
     * callData, bytes4 callbackFunction, bytes extraData)`.
     */
    internal class OffchainLookup(
        val sender: String,
        val urls: List<String>,
        val callData: ByteArray,
        val callback: ByteArray,
        val extraData: ByteArray,
    )

    /**
     * Drive the lookup to completion: fetch the gateway's answer, feed it
     * to the sender's callback, and repeat while that reverts with a
     * further `OffchainLookup`. Returns the final call outcome — a
     * result to decode as usual, or a non-CCIP revert for [mapRevert].
     * Throws on gateway failure, a sender other than the Universal
     * Resolver, malformed revert data, or too many rounds.
     */
    private fun followOffchainLookup(
        rpc: String,
        firstRevert: String,
        block: String = "latest",
        timeoutMs: Int = RPC_TIMEOUT_MS,
    ): CallOutcome {
        var revert = firstRevert
        repeat(MAX_CCIP_ROUNDS) {
            val lookup = decodeOffchainLookup(revert)
                ?: throw IllegalStateException("malformed OffchainLookup revert")
            // Only follow lookups issued by the contract we called. A
            // resolver can't redirect us into calling back some other
            // contract with gateway-supplied bytes.
            if (!lookup.sender.equals(UNIVERSAL_RESOLVER, ignoreCase = true)) {
                throw IllegalStateException("OffchainLookup sender is not the Universal Resolver")
            }
            val response = ccipFetch(lookup.sender, lookup.urls, lookup.callData)
                ?: throw IllegalStateException("CCIP gateways unavailable or returned invalid data")
            val callbackData = lookup.callback + abiEncodeTwoBytes(response, lookup.extraData)
            // At the same block as the call that deferred: the callback
            // checks the gateway's answer against that state.
            val outcome = ethCall(rpc, lookup.sender, callbackData, block, timeoutMs)
            val next = outcome.revertData
            if (next == null || !isOffchainLookup(next)) return outcome
            revert = next
        }
        throw IllegalStateException("CCIP-Read recursion limit exceeded")
    }

    /**
     * Ask the gateways, in order, for the answer to [callData]. Follows
     * EIP-3668 exactly: `{sender}` / `{data}` are substituted into the
     * URL template, a template containing `{data}` is fetched with GET,
     * anything else gets a JSON `{sender, data}` POST, and the reply is
     * JSON with a hex `data` field. A gateway that fails any check is
     * skipped and the next one tried; `null` once they're exhausted.
     *
     * Bounds are deliberate — the URLs are chosen by the resolver
     * contract, not by us: https only, no redirects, no credentials or
     * bare-IP / local hosts, [CCIP_TIMEOUT_MS] wall clock and
     * [CCIP_MAX_RESPONSE_BYTES] body per gateway. Non-URL entries such
     * as the Universal Resolver's `x-batch-gateway:true` hint are
     * skipped like any other non-https string.
     */
    internal fun ccipFetch(sender: String, urls: List<String>, callData: ByteArray): ByteArray? {
        val senderLower = sender.lowercase()
        val dataHex = "0x" + callData.toHex()
        for (template in urls) {
            val url = template.replace("{sender}", senderLower).replace("{data}", dataHex)
            val parsed = runCatching { URL(url) }.getOrNull() ?: continue
            val host = parsed.host.orEmpty().trim('[', ']').trimEnd('.').lowercase()
            if (parsed.protocol != "https" || parsed.userInfo != null || !isPublicHostname(host)) {
                continue
            }
            val get = template.contains("{data}")
            val reply = runCatching {
                http.request(
                    method = if (get) "GET" else "POST",
                    url = url,
                    headers = if (get) {
                        mapOf("accept" to "application/json")
                    } else {
                        mapOf("accept" to "application/json", "content-type" to "application/json")
                    },
                    body = if (get) {
                        null
                    } else {
                        JSONObject().put("sender", senderLower).put("data", dataHex).toString()
                    },
                    timeoutMs = CCIP_TIMEOUT_MS,
                    maxBytes = CCIP_MAX_RESPONSE_BYTES,
                    followRedirects = false,
                )
            }.getOrNull() ?: continue
            if (reply.code !in 200..299) continue
            val data = runCatching { JSONObject(reply.body).optString("data", "") }.getOrNull() ?: continue
            if (!isHexBytes(data)) continue
            return data.hexToBytes()
        }
        return null
    }

    private fun isPublicHostname(host: String): Boolean {
        if (host.isEmpty() || !host.contains('.')) return false
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        // Bare IPv4 / IPv6 literals: the point of a CCIP gateway is a
        // named, certificated service.
        if (host.all { it.isDigit() || it == '.' }) return false
        if (host.contains(':')) return false
        return true
    }

    // ---- Response decoding ----

    private fun decodeContenthashResponse(name: String, rawHex: String): EnsResult {
        // Outer tuple: (bytes result, address resolver). The resolver address
        // is informational — we just extract `result`.
        val outer = decodeDynamicBytesAt(rawHex, pointerSlot = 0)
            ?: return EnsResult.Error(name, "RESOLUTION_ERROR", "malformed UR outer response")

        if (outer.isEmpty()) {
            return EnsResult.NotFound(name, "EMPTY_CONTENTHASH", EnsTrust.UNCHECKED)
        }

        // Inner: the bytes returned by contenthash(bytes32) — themselves
        // an ABI-encoded dynamic bytes wrapper around the raw EIP-1577
        // contenthash.
        return decodeContenthashBytes(name, "0x" + outer.toHex())
    }

    /**
     * Decode the return of `contenthash(bytes32)` — ABI `bytes` wrapping
     * the raw EIP-1577 contenthash. Reached through the Universal
     * Resolver's tuple for ENS, and directly for NameNFT registries.
     */
    private fun decodeContenthashBytes(name: String, abiHex: String): EnsResult {
        val inner = decodeDynamicBytesAt(abiHex, pointerSlot = 0)
            ?: return EnsResult.Error(
                name, "UNSUPPORTED_CONTENTHASH_FORMAT", "inner decode failed"
            )
        if (inner.isEmpty()) {
            return EnsResult.NotFound(name, "EMPTY_CONTENTHASH", EnsTrust.UNCHECKED)
        }

        return parseContentHash(name, inner)
            ?: EnsResult.Unsupported(
                name = name,
                codec = inner.take(8).toByteArray().toHex(),
                rawContentHash = inner.toHex(),
                trust = EnsTrust.UNCHECKED,
            )
    }

    private fun parseContentHash(name: String, bytes: ByteArray): EnsResult? {
        // Swarm: 0xe40101fa011b20 + 32 bytes
        val swarmPrefix = byteArrayOf(
            0xe4.toByte(), 0x01, 0x01, 0xfa.toByte(), 0x01, 0x1b, 0x20,
        )
        if (bytes.size == swarmPrefix.size + 32 && bytes.startsWith(swarmPrefix)) {
            val hash = bytes.copyOfRange(swarmPrefix.size, bytes.size).toHex()
            return EnsResult.Ok(
                name = name,
                protocol = "bzz",
                uri = "bzz://$hash",
                decoded = hash,
                trust = EnsTrust.UNCHECKED,
            )
        }

        // IPFS: 0xe3 0x01 (varint for ipfs-ns multicodec 0xe3) + CID.
        // The CID that follows is either:
        //   • CIDv0: raw multihash, always dag-pb + sha2-256. Starts with
        //     0x12 0x20 (sha2-256, 32 bytes). Rendered as Base58BTC
        //     ("Qm…").
        //   • CIDv1: <0x01 version><codec><multihash>. Rendered as
        //     multibase Base32 with a 'b' prefix ("bafy…"). This is what
        //     modern ENS names (vitalik.eth and friends) actually use.
        val ipfsNs = byteArrayOf(0xe3.toByte(), 0x01)
        if (bytes.size > ipfsNs.size && bytes.startsWith(ipfsNs)) {
            val cidBytes = bytes.copyOfRange(ipfsNs.size, bytes.size)
            val cid = encodeCid(cidBytes) ?: return null
            return EnsResult.Ok(
                name = name,
                protocol = "ipfs",
                uri = "ipfs://$cid",
                decoded = cid,
                trust = EnsTrust.UNCHECKED,
            )
        }

        // IPNS: 0xe5 0x01 + CID. Same CIDv0 / CIDv1 split as above. For
        // CIDv0-style IPNS the CID is a raw libp2p-key multihash; for
        // CIDv1 the codec is typically 0x72 (libp2p-key).
        val ipnsNs = byteArrayOf(0xe5.toByte(), 0x01)
        if (bytes.size > ipnsNs.size && bytes.startsWith(ipnsNs)) {
            val cidBytes = bytes.copyOfRange(ipnsNs.size, bytes.size)
            val cid = encodeCid(cidBytes) ?: return null
            return EnsResult.Ok(
                name = name,
                protocol = "ipns",
                uri = "ipns://$cid",
                decoded = cid,
                trust = EnsTrust.UNCHECKED,
            )
        }

        return null
    }

    /**
     * Encode raw CID bytes (everything after the EIP-1577 protoCode
     * varint) to the string form the IPFS gateway accepts. Returns `null`
     * if the layout isn't recognised as either CIDv0 or CIDv1.
     */
    private fun encodeCid(cid: ByteArray): String? {
        if (cid.isEmpty()) return null
        // CIDv0: first byte is the multihash algorithm code (e.g. 0x12
        // for sha2-256). Only sha2-256 + 32 bytes is defined as CIDv0,
        // but in practice we pass the whole multihash through unchanged.
        if (cid[0] == 0x12.toByte() && cid.size >= 2) {
            val mhLen = cid[1].toInt() and 0xff
            if (cid.size == 2 + mhLen) return Base58.encode(cid)
        }
        // CIDv1: starts with 0x01 <codec> <multihash>. The whole thing
        // — version + codec + multihash — is what gets Base32-encoded
        // with the 'b' multibase prefix.
        if (cid[0] == 0x01.toByte() && cid.size >= 3) {
            return "b" + Base32.encodeLower(cid)
        }
        return null
    }

    private fun mapRevert(name: String, revertData: String): EnsResult? {
        val lower = revertData.lowercase()
        val selector = if (lower.length >= 10) lower.substring(0, 10) else return null
        return when (selector) {
            // ResolverNotFound(bytes), ResolverNotContract(bytes,address)
            "0x77209fe8", "0x1e9535f2" -> EnsResult.NotFound(name, "NO_RESOLVER", EnsTrust.UNCHECKED)
            else -> null
        }
    }

    // ---- JSON-RPC ----

    private data class CallOutcome(val data: String?, val revertData: String?)

    private fun ethCall(
        rpc: String,
        to: String,
        callData: ByteArray,
        block: String = "latest",
        timeoutMs: Int = RPC_TIMEOUT_MS,
    ): CallOutcome {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_call")
            put(
                "params",
                org.json.JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("to", to)
                            put("data", "0x" + callData.toHex())
                        },
                    )
                    put(block)
                },
            )
        }.toString()

        val reply = http.request(
            method = "POST",
            url = rpc,
            headers = mapOf("content-type" to "application/json", "accept" to "application/json"),
            body = body,
            timeoutMs = timeoutMs,
            maxBytes = RPC_MAX_RESPONSE_BYTES,
            followRedirects = true,
        )
        if (reply.code !in 200..299) {
            throw RuntimeException("HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val json = JSONObject(reply.body)
        json.optJSONObject("error")?.let { err ->
            // Some providers pack the revert data inside error.data;
            // surface it so we can distinguish ResolverNotFound from
            // transport failures.
            val data = err.optString("data", "")
            if (data.startsWith("0x") && data.length >= 10) {
                return CallOutcome(data = null, revertData = data)
            }
            throw RuntimeException("RPC error: ${err.optString("message", "unknown")}")
        }
        val result = json.optString("result", "")
        return CallOutcome(data = result, revertData = null)
    }

    /** The server's head block number (`eth_blockNumber`). */
    private fun blockNumber(rpc: String): Long {
        val result = rpcRequest(rpc, "eth_blockNumber", org.json.JSONArray())
        val hex = (result as? String)?.removePrefix("0x")
            ?: throw RuntimeException("eth_blockNumber: no result")
        return hex.toLong(16)
    }

    /** The server's hash for block [tag] (`eth_getBlockByNumber`). */
    private fun blockHash(rpc: String, tag: String): String {
        val result = rpcRequest(rpc, "eth_getBlockByNumber", org.json.JSONArray().put(tag).put(false))
        val hash = (result as? JSONObject)?.optString("hash", "").orEmpty()
        if (!isHexBytes(hash) || hash.length != 66) throw RuntimeException("eth_getBlockByNumber($tag): no hash")
        return hash.lowercase()
    }

    private fun rpcRequest(rpc: String, method: String, params: org.json.JSONArray): Any? {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        val reply = http.request(
            method = "POST",
            url = rpc,
            headers = mapOf("content-type" to "application/json", "accept" to "application/json"),
            body = body,
            timeoutMs = QUORUM_TIMEOUT_MS,
            maxBytes = RPC_MAX_RESPONSE_BYTES,
            followRedirects = true,
        )
        if (reply.code !in 200..299) {
            throw RuntimeException("HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val json = JSONObject(reply.body)
        json.optJSONObject("error")?.let { err ->
            throw RuntimeException("RPC error: ${err.optString("message", "unknown")}")
        }
        return json.opt("result")
    }

    companion object {
        private const val TAG = "EnsResolver"
        private const val CACHE_TTL_MS = 15L * 60 * 1000
        private const val FAILED_ENDPOINT_COOLDOWN_MS = 10L * 60 * 1000
        /** ENSIP-10 DNS encoding's label limit (desktop's `ethers.dnsEncode(name, 255)`). */
        private const val MAX_DNS_LABEL_BYTES = 255

        // #96: one server's word is reused briefly, a disagreement for
        // a moment (iOS's and desktop's TTLs).
        private const val UNVERIFIED_TTL_MS = 60L * 1000
        private const val CONFLICT_TTL_MS = 10L * 1000

        // How long an anchor block is reused. Desktop and iOS default
        // to 30 s; longer here because settling a new one is the only
        // extra round trip the cross-check adds to a lookup, and an
        // anchor two minutes older (~18 blocks behind head, far inside
        // what public servers keep state for) delays nothing that the
        // 15-minute answer cache doesn't already.
        private const val ANCHOR_TTL_MS = 2L * 60 * 1000

        // Per-request bound on the quorum path (desktop's and iOS's
        // `quorum.timeoutMs`): a wave doesn't wait 15 s on one server
        // when others can answer.
        private const val QUORUM_TIMEOUT_MS = 5_000

        // A read's whole budget, CCIP-Read's gateway hops included.
        private const val LEG_TIMEOUT_MS = 20_000L

        // Once enough heads are in for a median, how much longer the
        // rest get: a slow server shouldn't cost every cold lookup its
        // timeout, but one a moment behind still gets its say.
        private const val HEAD_GRACE_MS = 50L

        private const val RPC_TIMEOUT_MS = 15_000
        private const val RPC_MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        // Per-gateway bounds for CCIP-Read fetches (same as the desktop
        // resolver's `ccip-fetch.js`).
        internal const val CCIP_TIMEOUT_MS = 15_000
        internal const val CCIP_MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_CCIP_ROUNDS = 10

        // bytes4(keccak256("OffchainLookup(address,string[],bytes,bytes4,bytes)"))
        private const val OFFCHAIN_LOOKUP_SELECTOR = "0x556f1830"

        internal fun isOffchainLookup(revertData: String): Boolean =
            revertData.length >= 10 &&
                revertData.substring(0, 10).equals(OFFCHAIN_LOOKUP_SELECTOR, ignoreCase = true)

        /**
         * Decode an `OffchainLookup` revert. `null` if any offset or
         * length points outside the data.
         */
        internal fun decodeOffchainLookup(revertData: String): OffchainLookup? {
            val bytes = runCatching { revertData.hexToBytes() }.getOrNull() ?: return null
            if (bytes.size < 4 + 5 * 32) return null
            val body = bytes.copyOfRange(4, bytes.size)
            val sender = "0x" + body.copyOfRange(12, 32).toHex()
            val urlsOffset = readUint256AsInt(body, 32) ?: return null
            val callData = decodeDynamicBytesAt(body, pointerSlot = 2) ?: return null
            val callback = body.copyOfRange(96, 100)
            val extraData = decodeDynamicBytesAt(body, pointerSlot = 4) ?: return null

            if (body.size < urlsOffset + 32) return null
            val count = readUint256AsInt(body, urlsOffset) ?: return null
            if (count < 0 || count > 64) return null
            val base = urlsOffset + 32
            val urls = ArrayList<String>(count)
            for (i in 0 until count) {
                if (body.size < base + (i + 1) * 32) return null
                val rel = readUint256AsInt(body, base + i * 32) ?: return null
                val str = decodeDynamicBytesAtOffset(body, base + rel) ?: return null
                urls.add(String(str, Charsets.UTF_8))
            }
            return OffchainLookup(sender, urls, callData, callback, extraData)
        }

        private const val UNIVERSAL_RESOLVER = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"

        // bytes4(keccak256("resolve(bytes,bytes)"))
        private val RESOLVE_SELECTOR = "9061b923".hexToBytes()

        // bytes4(keccak256("contenthash(bytes32)"))
        private val CONTENTHASH_SELECTOR = "bc1c58d1".hexToBytes()

        /** The server as a warning names it: host, and port if explicit. */
        private fun hostOf(rpc: String): String =
            runCatching { URL(rpc).authority }.getOrNull()?.takeIf { it.isNotEmpty() } ?: rpc

        val DEFAULT_RPC_ENDPOINTS: List<String> = EnsRpcConfig.PUBLIC_ENDPOINTS

        // ---- helpers used by both the instance and tests ----

        internal fun dnsEncode(name: String): ByteArray {
            if (name.isEmpty()) return byteArrayOf(0x00)
            val labels = name.split('.')
            var size = 1
            for (l in labels) size += 1 + l.toByteArray(Charsets.UTF_8).size
            val out = ByteArray(size)
            var pos = 0
            for (l in labels) {
                val bytes = l.toByteArray(Charsets.UTF_8)
                // ENS's DNS encoding (ENSIP-10) allows 255-byte labels —
                // desktop's `ethers.dnsEncode(name, 255)` — not DNS's 63:
                // a label of 16 emoji is already 64 UTF-8 bytes.
                require(bytes.size in 1..MAX_DNS_LABEL_BYTES) { "invalid DNS label: '$l'" }
                out[pos++] = bytes.size.toByte()
                bytes.copyInto(out, pos)
                pos += bytes.size
            }
            out[pos] = 0x00
            return out
        }

        /** ENSIP-1 namehash of an already [EnsNormalize]d name. */
        internal fun namehash(name: String): ByteArray {
            var node = ByteArray(32)
            if (name.isEmpty()) return node
            val labels = name.split('.')
            for (i in labels.indices.reversed()) {
                val labelHash = Keccak256.digest(labels[i])
                node = Keccak256.digest(node + labelHash)
            }
            return node
        }
    }
}

/**
 * [runCatching], minus the hole it leaves open around coroutines: a
 * cancelled coroutine unwinds through a `CancellationException`, which
 * `runCatching` catches like any other `Throwable` and hands back as a
 * `Result.failure` — so the cancelled coroutine keeps running and
 * reports a resolution *error* for a navigation that no longer exists.
 * Cancellation isn't a failure to report; it goes to the caller.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

// ---- hex / byte utilities (file-level, internal) ----

internal fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        hex[i * 2] = HEX_CHARS[v ushr 4]
        hex[i * 2 + 1] = HEX_CHARS[v and 0x0f]
    }
    return String(hex)
}

internal fun String.hexToBytes(): ByteArray {
    val s = if (startsWith("0x") || startsWith("0X")) substring(2) else this
    require(s.length % 2 == 0) { "odd-length hex: $this" }
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun writeUint256(v: Long, buf: ByteArray, off: Int) {
    for (i in 0..7) {
        buf[off + 31 - i] = (v ushr (8 * i)).toByte()
    }
}

private fun padLen32(n: Int): Int {
    val rem = n % 32
    return if (rem == 0) n else n + (32 - rem)
}

/** `abi.encode(bytes a, bytes b)` — two dynamic args, heads then tails. */
internal fun abiEncodeTwoBytes(a: ByteArray, b: ByteArray): ByteArray {
    val head = ByteArray(64)
    // Offsets relative to the start of the args: 0x40 and
    // 0x40 + 32 + padded(a).
    writeUint256(0x40L, head, 0)
    val aPaddedLen = padLen32(a.size)
    writeUint256((0x40 + 32 + aPaddedLen).toLong(), head, 32)
    val aBlock = ByteArray(32 + aPaddedLen).apply {
        writeUint256(a.size.toLong(), this, 0)
        a.copyInto(this, 32)
    }
    val bBlock = ByteArray(32 + padLen32(b.size)).apply {
        writeUint256(b.size.toLong(), this, 0)
        b.copyInto(this, 32)
    }
    return head + aBlock + bBlock
}

private fun isHexBytes(s: String): Boolean {
    if (!s.startsWith("0x") || s.length % 2 != 0) return false
    for (i in 2 until s.length) {
        val c = s[i]
        if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return false
    }
    return true
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

/**
 * Decode an ABI-encoded dynamic `bytes` whose pointer-slot lives at
 * `rawHex[pointerSlot * 32 : pointerSlot * 32 + 32]`. Returns null if
 * the layout doesn't look right.
 */
private fun decodeDynamicBytesAt(rawHex: String, pointerSlot: Int): ByteArray? {
    val body = if (rawHex.startsWith("0x") || rawHex.startsWith("0X")) rawHex.substring(2) else rawHex
    if (body.length % 2 != 0) return null
    return decodeDynamicBytesAt(body.hexToBytes(), pointerSlot)
}

private fun decodeDynamicBytesAt(bytes: ByteArray, pointerSlot: Int): ByteArray? {
    val pointerOffset = pointerSlot * 32
    if (bytes.size < pointerOffset + 32) return null
    val offset = readUint256AsInt(bytes, pointerOffset) ?: return null
    return decodeDynamicBytesAtOffset(bytes, offset)
}

/** Length-prefixed dynamic bytes / string whose length word sits at [offset]. */
private fun decodeDynamicBytesAtOffset(bytes: ByteArray, offset: Int): ByteArray? {
    if (offset < 0 || bytes.size < offset + 32) return null
    val len = readUint256AsInt(bytes, offset) ?: return null
    if (bytes.size < offset + 32 + len) return null
    return bytes.copyOfRange(offset + 32, offset + 32 + len)
}

// uint256 → Int, returning null if the value doesn't fit. ABI offsets
// and lengths in realistic ENS responses are well under Int.MAX_VALUE.
private fun readUint256AsInt(bytes: ByteArray, off: Int): Int? {
    for (i in 0 until 28) {
        if (bytes[off + i].toInt() != 0) return null
    }
    var v = 0L
    for (i in 28 until 32) {
        v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
    }
    return if (v < 0 || v > Int.MAX_VALUE) null else v.toInt()
}

/**
 * Bitcoin-style Base58 (no checksum). Used to render IPFS CIDv0 out of
 * multihash bytes, bit-for-bit matching what `ethers.encodeBase58` and
 * the Freedom desktop resolver emit.
 */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        // Count leading zero bytes — each becomes a leading '1' in Base58.
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        val encoded = CharArray(input.size * 2)
        val buf = input.copyOf()
        var outIdx = encoded.size

        var start = zeros
        while (start < buf.size) {
            encoded[--outIdx] = ALPHABET[divmod(buf, start, 256, 58)]
            if (buf[start].toInt() == 0) start++
        }
        while (outIdx < encoded.size && encoded[outIdx] == ALPHABET[0]) outIdx++
        repeat(zeros) { encoded[--outIdx] = ALPHABET[0] }
        return String(encoded, outIdx, encoded.size - outIdx)
    }

    // buf is treated as a big integer in base `base`; divide in place by
    // `divisor` and return the remainder. See Bitcoin Base58 reference.
    private fun divmod(buf: ByteArray, start: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in start until buf.size) {
            val num = (buf[i].toInt() and 0xff) + remainder * base
            buf[i] = (num / divisor).toByte()
            remainder = num % divisor
        }
        return remainder
    }
}

/**
 * RFC 4648 Base32 using the lowercase multibase-'b' alphabet and no
 * padding. Used to render CIDv1 bytes to their canonical `bafy…`
 * string form (see [multibase](https://github.com/multiformats/multibase)).
 */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encodeLower(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val outLen = (input.size * 8 + 4) / 5
        val out = CharArray(outLen)
        var bits = 0
        var bitCount = 0
        var idx = 0
        for (b in input) {
            bits = (bits shl 8) or (b.toInt() and 0xff)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                out[idx++] = ALPHABET[(bits ushr bitCount) and 0x1f]
            }
        }
        if (bitCount > 0) {
            out[idx++] = ALPHABET[(bits shl (5 - bitCount)) and 0x1f]
        }
        return String(out, 0, idx)
    }
}
