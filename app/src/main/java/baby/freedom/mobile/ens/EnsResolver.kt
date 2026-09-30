package baby.freedom.mobile.ens

import android.util.Log
import baby.freedom.mobile.browser.PublicSuffixList
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * A proof comes first. The Myotis light client, when it's ready, is
 * asked before anything else (#101, below); then, or when it has no
 * answer, [EnsColibri] (#100) asks corpus.core's Colibri
 * prover for a proof of the Universal Resolver's (or the NameNFT
 * registry's) answer and checks it on this device against Ethereum's
 * sync committee — the chain's own consensus, the tier above servers
 * agreeing ([EnsTrust.Source.COLIBRI], [resolveByColibri]). Only an
 * answer is taken from it, or a proven "no resolver"; when it can't
 * give one — prover or network down, not in this build, switched off in
 * Settings, a revert that proves nothing — the lookup goes on to the
 * RPC servers below, as before. Both proven sources earn the same
 * *Proven name* tier ([EnsTrust.proven]).
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
 * With the Myotis light client on and ready ([EnsLightClient], #101),
 * an Ethereum name is resolved through it first: the same call, run on
 * this device against state proven to the chain's sync committee, CCIP-
 * Read callbacks included. Its answer is proven on its own
 * ([EnsTrust.Source.MYOTIS]); neither Colibri nor any RPC server is asked. When it isn't ready,
 * can't answer within [LIGHT_CLIENT_DEADLINE_MS], or gives anything but
 * a record or a known "no resolver" revert, the lookup falls back to the
 * Colibri and the RPC servers exactly as without it. After a miss that may be the light
 * client's own (unavailable, an engine error, out of time with the
 * engine — not a name's CCIP-Read gateway — using most of it), and only
 * if a name-independent probe call then fails too (a name's own resolver
 * can fail the engine's call for that name alone), it's skipped for
 * [LIGHT_CLIENT_BACKOFF_MS], so one struggling light client doesn't add
 * its whole deadline to every lookup. `busy` (every engine slot taken)
 * is back-pressure, never a miss of the light client's: that lookup
 * goes to RPC and the next one tries again. A started engine call can't
 * be cancelled, so one a lookup gave up on keeps its slot until the
 * engine returns; those are charged to the name's site ([siteOf]),
 * which may hold at most [LIGHT_CLIENT_CALLS_PER_SITE] engine calls at
 * once — one page's slow names can't take every slot — and a site whose
 * call outlived its lookup, or that held its full share when the engine
 * said `busy`, shares [LIGHT_CLIENT_SLOW_CALLS] slots with
 * every other such site for a while, so several registrations can't
 * either; nor can a wave of fresh ones, which with the slow sites share
 * [LIGHT_CLIENT_FRESH_CALLS] slots until they've answered in time for a
 * while. With no RPC server to fall back to, none of these caps applies. The
 * probe has an engine slot of its own, and while a probe that outlived
 * its wait is still in the engine — whatever the generation — the
 * light client stays skipped. Its readiness is
 * read per lookup and is not part of [Settings]: a flapping light client
 * never throws away the RPC epoch (cache, anchor, failed endpoints).
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
    private val lightClient: EnsLightClient? = null,
    /** [LIGHT_CLIENT_DEADLINE_MS]; shorter in tests. */
    private val lightClientDeadlineMs: Long = LIGHT_CLIENT_DEADLINE_MS,
    /** [LIGHT_CLIENT_BACKOFF_MS]; shorter in tests. */
    private val lightClientBackoffMs: Long = LIGHT_CLIENT_BACKOFF_MS,
    /** The proven tier (#100); `null` leaves every lookup to the quorum. */
    private val colibri: EnsColibri? = null,
    /** How long a lookup waits for a proof ([COLIBRI_WAIT_MS]); shorter in tests. */
    private val colibriWaitMs: Long = COLIBRI_WAIT_MS,
    /**
     * How long a proven lookup's CCIP gateways have to answer, from when
     * the first is asked ([LEG_TIMEOUT_MS], the quorum's own read
     * budget); shorter in tests.
     */
    private val colibriGatewayMs: Long = LEG_TIMEOUT_MS,
    /** [LIGHT_CLIENT_ESTABLISHED_MS]; shorter in tests. */
    private val lightClientEstablishedMs: Long = LIGHT_CLIENT_ESTABLISHED_MS,
    /** [LIGHT_CLIENT_NAME_MISS_MS]; shorter in tests. */
    private val lightClientNameMissMs: Long = LIGHT_CLIENT_NAME_MISS_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * What the user configured (#102): the RPC endpoints to ask, in
     * order, whether to follow CCIP-Read, and whether to ask the Colibri
     * verifier first (#100). Read afresh for every lookup, so a change
     * in Settings applies to the next one without a restart; a change
     * also drops the cache (see [Epoch]).
     */
    data class Settings(
        val endpoints: List<String>,
        val ccipRead: Boolean = true,
        val colibri: Boolean = true,
    )

    constructor(settings: suspend () -> Settings) : this(settings, EnsHttp.Default)

    constructor(settings: suspend () -> Settings, lightClient: EnsLightClient?) :
        this(settings, EnsHttp.Default, TezosDomainsResolver(), lightClient)

    constructor(rpcEndpoints: List<String> = DEFAULT_RPC_ENDPOINTS) :
        this({ Settings(rpcEndpoints) }, EnsHttp.Default)

    internal constructor(
        rpcEndpoints: List<String>,
        http: EnsHttp,
        tezos: TezosDomainsResolver = TezosDomainsResolver(),
    ) : this({ Settings(rpcEndpoints) }, http, tezos)

    /**
     * A cached answer. [verified]: a quorum's or the light client's, not
     * one server's word (nor a disagreement) — with the light client
     * ready, only a verified answer is reused, so an answer cached before
     * it came up doesn't keep it from verifying the next visit.
     */
    private data class Cached(val result: EnsResult, val expiresAt: Long, val verified: Boolean)

    /**
     * The light client's last miss that says it can't serve right now
     * ([LightClientMiss.backOff]): when, and in which of its
     * [EnsLightClient.readyGeneration]s. It's skipped until
     * [lightClientBackoffMs] has passed, it answers again, or it comes
     * back as a new generation (a restart, an off/on) — a miss from one
     * stretch of readiness says nothing about the next.
     */
    private class LightClientBackoff(val generation: Long, val at: Long)

    @Volatile
    private var lightClientMiss: LightClientBackoff? = null

    /**
     * Skipped for [lightClientBackoffMs] after a failed probe — and past
     * that for as long as a probe that outlived its wait is still in the
     * engine ([lightClientProbeInEngine]), in any generation: an engine
     * that hasn't finished a one-slot read yet would only cost the next
     * lookup its whole deadline. The engine's probe slot is per process,
     * not per stretch of readiness, so a peer flap or a trip to the
     * background (a new [EnsLightClient.readyGeneration], same engine)
     * doesn't end it; `:myotis` exiting does (it releases every call).
     */
    private fun lightClientBackingOff(generation: Long, now: Long): Boolean {
        if (synchronized(this) { lightClientProbeInEngine?.outlivedWait == true }) return true
        val miss = lightClientMiss?.takeIf { it.generation == generation } ?: return false
        return now - miss.at in 0 until lightClientBackoffMs
    }

    /**
     * Names the light client recently couldn't serve (#101), and when:
     * the engine errored, reverted oddly, gave nothing decodable, ran out
     * of time, or the name's CCIP-Read failed on it — anything but a
     * momentary refusal (`busy`, a closed read gate or an unreachable
     * `:myotis`, readiness moving, the site's share taken). For
     * [lightClientNameMissMs] (10 min, memory only, on [clock]) a lookup
     * of such a name goes straight to Colibri/RPC instead
     * of paying the miss time again on every revisit and document
     * re-check — the probe usually finds the engine healthy after such a
     * miss, so nothing else would spare it. Not while there's no RPC
     * server to go to instead.
     *
     * A miss that sent a probe ([NameMiss.awaitingProbe]) only stays
     * remembered if that probe gets a real answer in the same stretch of
     * readiness: then the miss was the name's. If it fails, times out,
     * is inconclusive (`busy`) or readiness moves meanwhile, the miss may
     * have been the engine's, and the generation-scoped back-off (or the
     * new generation) decides instead — so a restart of Myotis gets the
     * name asked again straight away rather than 10 min later. While the
     * probe runs the name counts as missed, so its revisits don't wait
     * for the probe.
     */
    private class NameMiss(val at: Long, val awaitingProbe: Long?)

    /**
     * Keyed by the record read ([Record.cacheKey]): the bare name for a
     * page's `contenthash`, `addr:<coin>:<name>` for a send's address
     * (#277) — one record's miss doesn't skip the light client for another.
     */
    private val lightClientNameMisses = ConcurrentHashMap<String, NameMiss>()

    private fun lightClientMissedName(name: String, now: Long): Boolean {
        val miss = lightClientNameMisses[name] ?: return false
        // A miss stamped in the future (the clock since set back) is stale.
        if (now - miss.at in 0 until lightClientNameMissMs) return true
        lightClientNameMisses.remove(name, miss)
        return false
    }

    /** [awaitingProbe]: the generation whose probe decides whether this miss was the name's. */
    private fun rememberLightClientMiss(name: String, awaitingProbe: Long?) {
        val now = clock()
        lightClientNameMisses.entries.removeIf { now - it.value.at !in 0 until lightClientNameMissMs }
        if (lightClientNameMisses.size >= LIGHT_CLIENT_NAME_MISSES_MAX) {
            lightClientNameMisses.entries.minByOrNull { it.value.at }?.let { lightClientNameMisses.remove(it.key, it.value) }
        }
        lightClientNameMisses[name] = NameMiss(now, awaitingProbe)
    }

    /**
     * [generation]'s probe has decided: keep the misses it was judging
     * as the names' own ([namesOwn]), or forget them.
     */
    private fun settleLightClientMisses(generation: Long, namesOwn: Boolean) {
        for ((name, miss) in lightClientNameMisses) {
            if (miss.awaitingProbe != generation) continue
            if (namesOwn) {
                lightClientNameMisses.replace(name, miss, NameMiss(miss.at, awaitingProbe = null))
            } else {
                lightClientNameMisses.remove(name, miss)
            }
        }
    }

    /**
     * A [probeLightClient] still deciding whether a miss in [generation]
     * was the light client's own; `true` once it's found it healthy.
     */
    private class LightClientProbe(val generation: Long, val healthy: Deferred<Boolean>)

    @Volatile
    private var lightClientProbe: LightClientProbe? = null

    /**
     * The probe call the engine still holds (it has one probe slot for
     * the whole `:myotis` process, whatever the generation) — set when a
     * probe is sent, cleared when the engine lets go of it (`released`,
     * completing [released]), which for a probe that timed out is up to
     * the engine's ~90 s budget later. [outlivedWait]: its own wait is
     * over and the engine still has it. Guarded by `this`.
     */
    private class ProbeInEngine {
        var outlivedWait = false
        val released = CompletableDeferred<Unit>()
    }

    private var lightClientProbeInEngine: ProbeInEngine? = null

    /**
     * Light-client calls each site ([siteOf]) still has in the engine —
     * running ones, and ones a lookup has given up on that the engine
     * hasn't let go of yet (a started call can't be cancelled; see
     * [EnsLightClient.ethCall]). At [LIGHT_CLIENT_CALLS_PER_SITE] a
     * site's further lookups go straight to RPC: a page whose own slow
     * names pin engine slots fills only its own share, not every slot the
     * user's other names need. Guarded by itself.
     */
    private val lightClientHeld = HashMap<String, Int>()

    /**
     * Sites whose light-client call outlived the lookup that made it (it
     * was still in the engine when the lookup gave up), or that held a
     * full per-site share when the engine said `busy`
     * ([markCrowdingSites]), and when that last happened; guarded by
     * [lightClientHeld]. The per-site cap
     * bounds one registration, not one page: a page can load slow names
     * from several registrations, 2 slots each, and fill every lookup
     * slot. So for [LIGHT_CLIENT_SLOW_SITE_MS] after, every such site
     * shares [LIGHT_CLIENT_SLOW_CALLS] engine slots with all the others:
     * a page gets one wave's worth of slots per fresh registration, not
     * a standing claim on the engine. Oldest first; at most
     * [LIGHT_CLIENT_SLOW_SITES_MAX] entries.
     */
    private val lightClientSlowSites = LinkedHashMap<String, Long>()

    /**
     * When each site's light-client call first answered in time; guarded
     * by [lightClientHeld]. A site is *established* once that was at
     * least [lightClientEstablishedMs] ago (the user has come back to it,
     * or stayed); every other site — fresh, or answered only just now —
     * shares [LIGHT_CLIENT_FRESH_CALLS] engine slots with the others and
     * with the slow sites. So a page's wave of names from fresh
     * registrations (each one paid for, each looking like any other site
     * until it has outlived a lookup) takes that many slots, not the
     * whole engine, and a registration made to answer fast once and then
     * go slow doesn't escape it either. The rest stay for names the user
     * has been resolving all along. Oldest first; at most
     * [LIGHT_CLIENT_SLOW_SITES_MAX] entries.
     */
    private val lightClientAnswered = LinkedHashMap<String, Long>()

    /** Whether [site] is established ([lightClientAnswered]). Holds the lock. */
    private fun establishedSiteLocked(site: String, now: Long): Boolean {
        val at = lightClientAnswered[site] ?: return false
        return now - at >= lightClientEstablishedMs
    }

    /** Note that one of [site]'s light-client calls answered in time ([lightClientAnswered]). */
    private fun markAnsweredSite(site: String) {
        synchronized(lightClientHeld) {
            val at = lightClientAnswered.remove(site)
            val now = System.currentTimeMillis()
            // A first answer stamped in the future (the clock since set
            // back) would never come of age: start it again.
            lightClientAnswered[site] = if (at != null && at <= now) at else now
            while (lightClientAnswered.size > LIGHT_CLIENT_SLOW_SITES_MAX) {
                lightClientAnswered.remove(lightClientAnswered.keys.first())
            }
        }
    }

    /** Whether [site] is in [lightClientSlowSites] still; drops stale entries. Holds the lock. */
    private fun slowSiteLocked(site: String, now: Long): Boolean {
        val at = lightClientSlowSites[site] ?: return false
        if (now - at in 0 until LIGHT_CLIENT_SLOW_SITE_MS) return true
        lightClientSlowSites.remove(site)
        return false
    }

    /** Mark [site] slow ([lightClientSlowSites]). */
    private fun markSlowSite(site: String) {
        synchronized(lightClientHeld) {
            lightClientSlowSites.remove(site)
            lightClientSlowSites[site] = System.currentTimeMillis()
            while (lightClientSlowSites.size > LIGHT_CLIENT_SLOW_SITES_MAX) {
                lightClientSlowSites.remove(lightClientSlowSites.keys.first())
            }
        }
    }

    /**
     * Mark every site holding a full [LIGHT_CLIENT_CALLS_PER_SITE] share
     * of [lightClientHeld] slow ([lightClientSlowSites]) — called when the
     * engine says `busy`. A call that outlives its wait isn't the only way
     * to pin the engine: established registrations whose resolvers answer
     * slowly but in time, a fresh subname after each, can hold every slot
     * without one ever doing so. Holding a full share while the engine is
     * full is what that looks like, whatever each call's own duration.
     *
     * Trade-off: this can't tell who filled the engine, so a site the user
     * is really using that happens to hold its full share when some other
     * page fills the engine is marked slow too. For
     * [LIGHT_CLIENT_SLOW_SITE_MS] its lookups then share the slow sites'
     * [LIGHT_CLIENT_SLOW_CALLS] slots, and one that finds them taken is
     * resolved by the RPC quorum instead of the light client: a weaker
     * check, never a failed lookup.
     */
    private fun markCrowdingSites() {
        synchronized(lightClientHeld) {
            lightClientHeld.filterValues { it >= LIGHT_CLIENT_CALLS_PER_SITE }.keys.forEach(::markSlowSite)
        }
    }

    /**
     * Take one of [site]'s [lightClientHeld] slots; the returned release
     * gives it back (once). `null` when the site has none left, or it's
     * a slow site ([lightClientSlowSites]) and the slow sites' shared
     * slots are all taken, or it isn't established
     * ([lightClientAnswered]) and the not-established and slow sites'
     * [LIGHT_CLIENT_FRESH_CALLS] are all taken. [capped] `false` (no RPC server to fall back
     * to: refusing would only turn a lookup the engine may still serve
     * into `NO_RPC_ENDPOINTS`) takes the slot regardless — the engine's
     * own `busy` is the only limit then.
     */
    private fun holdLightClientSlot(site: String, capped: Boolean): (() -> Unit)? {
        synchronized(lightClientHeld) {
            val held = lightClientHeld[site] ?: 0
            if (capped) {
                if (held >= LIGHT_CLIENT_CALLS_PER_SITE) return null
                val now = System.currentTimeMillis()
                val slow = slowSiteLocked(site, now)
                if (slow) {
                    val slowHeld = lightClientHeld.entries.sumOf { (s, n) -> if (slowSiteLocked(s, now)) n else 0 }
                    if (slowHeld >= LIGHT_CLIENT_SLOW_CALLS) return null
                }
                if (slow || !establishedSiteLocked(site, now)) {
                    val freshHeld = lightClientHeld.entries.sumOf { (s, n) ->
                        if (slowSiteLocked(s, now) || !establishedSiteLocked(s, now)) n else 0
                    }
                    if (freshHeld >= LIGHT_CLIENT_FRESH_CALLS) return null
                }
            }
            lightClientHeld[site] = held + 1
        }
        val once = AtomicBoolean(false)
        return {
            if (once.compareAndSet(false, true)) {
                synchronized(lightClientHeld) {
                    val left = (lightClientHeld[site] ?: 1) - 1
                    if (left <= 0) lightClientHeld.remove(site) else lightClientHeld[site] = left
                }
            }
        }
    }

    /** Whether [site] is a slow site now ([lightClientSlowSites]); for tests. */
    internal fun lightClientSlowSite(site: String): Boolean =
        synchronized(lightClientHeld) { slowSiteLocked(site, System.currentTimeMillis()) }

    /** [lightClientHeld] for [site] now; for tests. */
    internal fun lightClientCallsHeld(site: String): Int = synchronized(lightClientHeld) { lightClientHeld[site] ?: 0 }

    /**
     * After a Colibri call can't reach its provers / servers
     * ([EnsColibri.Failure.unreachable], or an unexpected error) or
     * outlasts a lookup's wait, the verifier is skipped for
     * [COLIBRI_BACKOFF_MS], doubling with each further such call up to
     * [COLIBRI_BACKOFF_MAX_MS]; any proof that comes in (a background
     * call included) resets it. Each call counts at most once — one that
     * misses the wait and then times out in the background is one
     * failure — and one name's proof not checking out doesn't count at
     * all: it says nothing about the other names.
     */
    internal inner class ColibriBackoff {
        private var failures = 0
        private var until = 0L

        /** How much longer to skip the verifier; `null` when it may be asked. */
        @Synchronized
        fun remainingMs(): Long? {
            val left = until - clock()
            // A clock set back mustn't stretch the back-off forever.
            return left.takeIf { it > 0 && it <= COLIBRI_BACKOFF_MAX_MS }
        }

        @Synchronized
        fun failed() {
            failures = (failures + 1).coerceAtMost(16)
            val span = (COLIBRI_BACKOFF_MS shl (failures - 1)).coerceAtMost(COLIBRI_BACKOFF_MAX_MS)
            until = clock() + span
        }

        @Synchronized
        fun succeeded() {
            failures = 0
            until = 0
        }
    }

    internal val colibriBackoff = ColibriBackoff()

    /**
     * How long a lookup of [name] under [settings] may wait on the
     * proven tier before it asks the RPC servers: [colibriWaitMs] when
     * the verifier would be asked now, 0 when the lookup would skip it
     * (a `.tez` name, which never goes to the verifier; switched off;
     * not in this build; or backing off). A caller holding a document
     * for a bounded re-check adds this to its own deadline, so a normal
     * proof doesn't miss it just for taking longer than an RPC read.
     */
    internal fun colibriWaitFor(settings: Settings, name: String): Long {
        if (!settings.colibri || colibri?.available != true || colibriBackoff.remainingMs() != null) return 0
        val normalized = try {
            EnsNormalize.fastNormalize(name.trim())
        } catch (e: EnsNormalize.InvalidNameException) {
            return 0
        }
        return if (NameSystem.forName(normalized) == NameSystem.TEZOS) 0 else colibriWaitMs
    }

    /**
     * How long a lookup of [name] under [settings] may spend on the
     * light client (#101) before it gets to the Colibri/RPC tiers:
     * [lightClientDeadlineMs], plus [LIGHT_CLIENT_PROBE_TIMEOUT_MS] while
     * a probe of the current readiness is still judging an earlier miss
     * (the lookup waits for it first, see [resolve]); 0 when the lookup
     * would skip it (no light client, not ready, backing off or having
     * recently missed this name with RPC servers to fall back on, or a
     * `.tez` name). The re-check's
     * counterpart of [colibriWaitFor]: an ordinary answer from the light
     * client takes seconds, and a deadline that doesn't allow for it
     * serves the earlier answer and opens the caller's failure window
     * on a network that is working fine.
     */
    internal fun lightClientWaitFor(settings: Settings, name: String): Long {
        val generation = lightClient?.readyGeneration() ?: return 0
        val normalized = try {
            EnsNormalize.fastNormalize(name.trim())
        } catch (e: EnsNormalize.InvalidNameException) {
            return 0
        }
        if (NameSystem.forName(normalized) == NameSystem.TEZOS) return 0
        if (settings.endpoints.isEmpty()) return lightClientDeadlineMs
        if (lightClientMissedName(normalized, clock())) return 0
        val probing = lightClientProbe?.takeIf { it.generation == generation && it.healthy.isActive } != null
        if (!probing && lightClientBackingOff(generation, System.currentTimeMillis())) return 0
        return lightClientDeadlineMs + if (probing) LIGHT_CLIENT_PROBE_TIMEOUT_MS else 0
    }

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
        val result = resolve(rawName, Record.Contenthash, fresh = false)
        // Not every cancellation arrives as a `CancellationException`:
        // tearing down the RPC in flight can surface as an ordinary
        // `IOException`, which the retry loop maps to a PROVIDER_ERROR
        // like any other transport failure. One check on the way out
        // covers every return path above.
        coroutineContext.ensureActive()
        return result
    }

    /**
     * The address [rawName] names for a send on chain [chainId] (#277):
     * its `addr` record for that chain's coin type (ENSIP-9 on Ethereum,
     * ENSIP-11 elsewhere), read through the same tiers as a page's
     * contenthash — light client, Colibri, the quorum, one server —
     * and labelled with the same [EnsTrust]. ENS names (`.eth`, `.box`,
     * and DNS names such as `gregskril.com`, through the Universal
     * Resolver) work on any chain; WNS (`.wei`) and GNS (`.gwei`)
     * registries hold only the chain-agnostic `addr(bytes32)`, so off
     * Ethereum they're refused rather than answered with a mainnet
     * address that may not be the owner's there ([EnsAddressResult.NoAddress]
     * with `CHAIN_UNSUPPORTED`, desktop's stance). Tezos Domains names
     * aren't Ethereum names and are refused too.
     *
     * [fresh]: skip the cache and ask again — the re-check just before
     * signing, so a record changed since the review isn't paid to the
     * old address. The fresh answer is cached as any other.
     *
     * Cancellation-honest, as [resolveContenthash].
     */
    suspend fun resolveAddress(rawName: String, chainId: Long, fresh: Boolean = false): EnsAddressResult {
        // ENSIP-11 coin types exist only for chain ids below 2^31: a
        // larger one has no record to ask for — nothing to send to, not
        // a failure to try again (#277).
        if (chainId !in 1 until 0x80000000L) {
            val normalized = runCatching { EnsNormalize.fastNormalize(rawName.trim()) }.getOrDefault(rawName.trim())
            return EnsAddressResult.NoAddress(normalized, "CHAIN_ID_UNSUPPORTED", trust = null)
        }
        val coinType = if (chainId == 1L) ETH_COIN_TYPE else 0x80000000L + chainId
        val result = resolve(rawName, Record.Address(coinType), fresh)
        coroutineContext.ensureActive()
        return EnsAddressResult.of(result)
    }

    /**
     * Which record a lookup reads: a page's `contenthash`, or the
     * `addr` for [Address.coinType] a send pays (#277). Everything but
     * the call data and its decoding is shared.
     */
    private sealed class Record {
        abstract fun cacheKey(name: String): String

        data object Contenthash : Record() {
            override fun cacheKey(name: String) = name
        }

        data class Address(val coinType: Long) : Record() {
            override fun cacheKey(name: String) = "addr:$coinType:$name"
        }
    }

    /** An answer, and whether a quorum of servers stands behind it. */
    private class Verdict(val result: EnsResult, val verified: Boolean)

    private suspend fun resolve(rawName: String, record: Record, fresh: Boolean): EnsResult {
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
        if (record is Record.Address) {
            if (system == NameSystem.TEZOS) {
                return EnsResult.Error(normalized, "UNSUPPORTED_SYSTEM", "Tezos Domains names don't name Ethereum accounts")
            }
            // A NameNFT registry has one chain-agnostic address; see [resolveAddress].
            if (system.contractAddress != null && record.coinType != ETH_COIN_TYPE) {
                return EnsResult.Error(
                    normalized,
                    "CHAIN_UNSUPPORTED",
                    "${system.label} names hold an Ethereum address only, not one for this network",
                )
            }
        }
        // `.tez` isn't Ethereum: its own resolver, quorum and TTL cache.
        if (system == NameSystem.TEZOS) return tezos.resolve(normalized)

        val config = settings()
        // Answers from endpoints the user has since dropped (or got
        // with CCIP-Read on) must not outlive the change by the cache
        // TTL: a new configuration starts a new, empty epoch.
        val epoch = epochFor(config)
        val cache = epoch.cache
        // Read afresh per lookup (#101), outside the settings: see the class KDoc.
        val generation = lightClient?.readyGeneration()
        if (config.endpoints.isEmpty() && generation == null) {
            return EnsResult.Error(normalized, "NO_RPC_ENDPOINTS", "no RPC endpoints configured")
        }

        // The light client first (#101) — backing off after a miss, unless
        // it's the only source there is.
        // A name it recently missed skips it — and, like
        // [lightClientWaitFor] allowing it nothing, doesn't wait for a
        // probe either: a re-check of it has only the RPC/Colibri share.
        val missedName = generation != null && config.endpoints.isNotEmpty() &&
            lightClientMissedName(record.cacheKey(normalized), clock())
        // A probe still judging an earlier miss is waited for (it's
        // bounded by [LIGHT_CLIENT_PROBE_TIMEOUT_MS]), so a struggling light
        // client costs this lookup no more than that before it's skipped.
        if (generation != null && config.endpoints.isNotEmpty() && !missedName) {
            lightClientProbe?.takeIf { it.generation == generation }?.let { probe ->
                withTimeoutOrNull(LIGHT_CLIENT_PROBE_TIMEOUT_MS) { runCatchingCancellable { probe.healthy.await() } }
            }
        }
        val askLightClient = generation != null && !missedName &&
            (config.endpoints.isEmpty() || !lightClientBackingOff(generation, System.currentTimeMillis()))

        val cacheKey = record.cacheKey(normalized)
        if (!fresh) {
            cache[cacheKey]?.let {
                // One server's word (or a disagreement) doesn't stand in the
                // way of the light client verifying it.
                if (System.currentTimeMillis() < it.expiresAt && (it.verified || !askLightClient)) return it.result
            }
        }

        val contract = system.contractAddress
        val target = contract ?: UNIVERSAL_RESOLVER
        val callData = if (contract != null) {
            recordCallData(record, namehash(normalized))
        } else {
            try {
                buildResolveCallData(normalized, record)
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

        // A proof needs no second opinion.
        if (missedName) {
            Log.i(TAG, "[$normalized] light client: missed this name recently; asking RPC")
        } else if (generation != null && !askLightClient) {
            Log.i(TAG, "[$normalized] light client: backing off after a recent miss; asking RPC")
        }
        if (askLightClient) {
            resolveByLightClient(
                generation!!, normalized, target, callData, contract, record, config.ccipRead,
                capped = config.endpoints.isNotEmpty(),
            )
                ?.let { verdict ->
                    val ttl = ttlFor(verdict)
                    if (ttl > 0) cache[cacheKey] = Cached(verdict.result, System.currentTimeMillis() + ttl, verdict.verified)
                    Log.i(TAG, "[$normalized] → ${verdict.result}")
                    return verdict.result
                }
            if (config.endpoints.isEmpty()) {
                return EnsResult.Error(normalized, "NO_RPC_ENDPOINTS", "no RPC endpoints configured")
            }
        }

        // Proven if the Colibri verifier can (#100); else cross-checked
        // across servers whenever there are enough of them to (#96); one
        // server's word otherwise, labelled as such.
        val quorumPossible = EnsQuorum.canCrossCheck(config.endpoints)
        val verdict =
            (if (config.colibri) resolveByColibri(config, normalized, target, callData, contract, record) else null)
                ?: (if (quorumPossible) resolveByQuorum(epoch, normalized, target, callData, contract, record) else null)
                ?: resolveSingleSource(epoch, normalized, target, callData, contract, record)
        val ttl = ttlFor(verdict)
        if (ttl > 0) {
            val verified = verdict.verified && verdict.result !is EnsResult.Conflict
            cache[cacheKey] = Cached(verdict.result, System.currentTimeMillis() + ttl, verified)
        }
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

    // ---- Light client (#101) ----

    /**
     * The light client couldn't give a usable answer: fall back to RPC.
     * [backOff]: the miss may be the light client's, not the name's (it
     * was unavailable, erroring, or out of time) — skip it for a
     * while if a name-independent probe fails too ([probeLightClient]).
     * [momentary]: a refusal that says nothing about this name (`busy`,
     * a closed read gate, readiness moving, the site's share taken) — not
     * remembered in [lightClientNameMisses].
     */
    private class LightClientMiss(
        reason: String,
        val backOff: Boolean = false,
        val momentary: Boolean = false,
    ) : Exception(reason)

    /** The verified head moved between a call and its CCIP-Read callback: run the read again. */
    private class HeadMoved : Exception("head moved during CCIP-Read")

    /**
     * One light-client read's budget, and where it went: into the engine
     * (the light client's own time) or into a name's CCIP-Read gateway
     * (the name's). Running out of time backs the light client off only
     * when the engine spent at least as much of it as the gateways did —
     * one name's stalled gateway is that name's miss, not the light
     * client's. [abandon]ed once the lookup stops waiting, after which
     * the read has no time left: it stops before its next engine call or
     * gateway URL instead of fetching on for an answer nobody reads.
     */
    private class LightClientBudget(private val deadline: Long) {
        @Volatile
        private var abandoned = false
        private var gaveUp = false
        private val onGiveUp = mutableListOf<() -> Unit>()
        private var engineMs = 0L
        private var gatewayMs = 0L
        private var engineSince = -1L
        private var gatewaySince = -1L

        /** [gaveUp]: the lookup stopped waiting for an answer (its deadline), rather than the read finishing. */
        fun abandon(gaveUp: Boolean) {
            val run = synchronized(this) {
                abandoned = true
                if (gaveUp) this.gaveUp = true
                onGiveUp.toList().also { onGiveUp.clear() }
            }
            if (gaveUp) run.forEach { it() }
        }

        /**
         * Run [action] if the lookup gives up on the read ([abandon] with
         * `gaveUp`) — at once if it already has.
         */
        fun onGiveUp(action: () -> Unit) {
            val now = synchronized(this) {
                if (!abandoned) onGiveUp += action
                gaveUp
            }
            if (now) action()
        }

        /** What's left, 0 once spent or [abandon]ed. */
        fun left(now: Long = System.currentTimeMillis()): Long =
            if (abandoned) 0 else (deadline - now).coerceAtLeast(0)

        fun <T> engine(block: () -> T): T = timed(gateway = false, block)

        fun <T> gateway(block: () -> T): T = timed(gateway = true, block)

        private fun <T> timed(gateway: Boolean, block: () -> T): T {
            val start = System.currentTimeMillis()
            synchronized(this) { if (gateway) gatewaySince = start else engineSince = start }
            try {
                return block()
            } finally {
                val took = System.currentTimeMillis() - start
                synchronized(this) {
                    if (gateway) {
                        gatewayMs += took
                        gatewaySince = -1
                    } else {
                        engineMs += took
                        engineSince = -1
                    }
                }
            }
        }

        /** Whether the time spent so far (a call still running included) was mostly the engine's. */
        fun engineOwnsTheTime(now: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
            val engine = engineMs + if (engineSince >= 0) now - engineSince else 0
            val gateway = gatewayMs + if (gatewaySince >= 0) now - gatewaySince else 0
            engine >= gateway
        }
    }

    /**
     * Resolve through [lightClient], within [lightClientDeadlineMs] in
     * all — CCIP-Read's gateway hops included, bounded from out here so a
     * slow gateway or a stuck engine can't hold the lookup past it. The
     * abandoned read stops at its next engine call or gateway URL, and
     * none of its blocking calls is given more than the budget had left
     * (see [LightClientBudget]). Out of time backs the light client off
     * only when the engine, not a gateway, used most of it. `null` when
     * it has no usable answer, for the RPC servers to give one.
     */
    private suspend fun resolveByLightClient(
        generation: Long,
        name: String,
        target: String,
        callData: ByteArray,
        contract: String?,
        record: Record,
        ccipRead: Boolean,
        capped: Boolean,
    ): Verdict? {
        val client = lightClient ?: return null
        val startedAt = System.currentTimeMillis()
        val budget = LightClientBudget(startedAt + lightClientDeadlineMs)
        val read = io.async { lightClientRead(client, generation, name, target, callData, contract, record, ccipRead, budget, capped) }
        var engineOwnsTheTime = true
        var gaveUp = false
        val outcome = try {
            withTimeoutOrNull(lightClientDeadlineMs) { runCatchingCancellable { read.await() } }
                .also {
                    if (it == null) {
                        engineOwnsTheTime = budget.engineOwnsTheTime()
                        gaveUp = true
                    }
                }
        } finally {
            budget.abandon(gaveUp)
            read.cancel()
        }
        val took = System.currentTimeMillis() - startedAt
        val failure = outcome?.exceptionOrNull()
        val miss = when {
            outcome == null -> "no answer within ${lightClientDeadlineMs}ms"
            failure != null -> failure.message ?: "failed"
            else -> null
        }
        if (miss != null) {
            // Out of time on the engine's account, or an engine error:
            // maybe the light client's miss. A name's slow gateway isn't.
            // Whether it is decides a probe no name has a say in.
            val suspect = (outcome == null && engineOwnsTheTime) || (failure as? LightClientMiss)?.backOff == true
            Log.i(
                TAG,
                "[$name] light client: $miss after ${took}ms; falling back to RPC" +
                    if (suspect) " (probing it before skipping it)" else "",
            )
            // Not the name's either when it says nothing about it, or when
            // readiness moved meanwhile (the app went to the background,
            // `:myotis` died): the new stretch of readiness asks afresh.
            val momentary = (failure as? LightClientMiss)?.momentary == true ||
                client.readyGeneration() != generation
            // Remembered before the probe starts, so the probe's verdict
            // always finds it (see [settleLightClientMisses]).
            // Keyed by the record too: a Send lookup's miss of a name's
            // `addr` says nothing of its `contenthash` a page needs (#277).
            if (!momentary) rememberLightClientMiss(record.cacheKey(name), awaitingProbe = if (suspect) generation else null)
            if (suspect) probeLightClient(client, generation)
            return null
        }
        lightClientMiss = null
        Log.i(TAG, "[$name] light client answered in ${took}ms")
        return outcome!!.getOrThrow()
    }

    /**
     * Whether a miss in [generation] was the light client's own, or
     * only the name's: the engine and the RPC path both run whatever the
     * name's resolver contract does, so a resolver that reverts oddly,
     * reads state no snap peer serves, or burns the budget in the EVM
     * fails the engine's call for that name alone. Backing off on that
     * would let any page that loads one subresource from such a name
     * turn the light client off for every other name, again every
     * [lightClientBackoffMs]. So the engine is asked one call no name
     * has any say in ([PROBE_CALL_DATA] on [ENS_REGISTRY]: one account,
     * one slot, on an engine slot lookups can't take) and only backed off
     * if that fails too — unavailable, erroring, or unanswered within
     * [LIGHT_CLIENT_PROBE_TIMEOUT_MS] — or an earlier probe, of any
     * generation, is still in the engine past its own wait
     * ([lightClientProbeInEngine]), which fails the new one without
     * asking; one still inside its wait is waited for (the probe slot is
     * the engine's, per process), within this probe's own timeout. Otherwise a busy probe (the
     * engine's own admission) proves nothing either way and backs nothing off.
     * One probe per generation at a time; the lookup that missed doesn't
     * wait for it, later ones do ([resolve]).
     */
    private fun probeLightClient(client: EnsLightClient, generation: Long) {
        synchronized(this) {
            val running = lightClientProbe
            if (running != null && running.generation == generation && running.healthy.isActive) return
            val timeoutMs = minOf(LIGHT_CLIENT_PROBE_TIMEOUT_MS, lightClientDeadlineMs)
            val healthy = io.async {
                // An earlier probe that timed out and is still in the
                // engine — this generation's or an earlier one's: the
                // engine's probe slot is per process, and a readiness flip
                // (a peer flap, a trip to the background) doesn't free it.
                // The engine hasn't finished a one-slot read in all that
                // time, the plainest sign it's stuck — and asking again
                // would only be answered `busy` by the slot that probe
                // still holds, which proves nothing. So it fails this probe
                // outright, and the back-off holds for as long as the
                // engine keeps the old probe. One still inside its own
                // wait (an earlier generation's, just before a flip) is
                // waited for first, within this probe's timeout.
                val startedAt = System.currentTimeMillis()
                val mine = ProbeInEngine()
                var stuck = false
                while (true) {
                    val earlier = synchronized(this@EnsResolver) {
                        lightClientProbeInEngine.also { if (it == null) lightClientProbeInEngine = mine }
                    } ?: break
                    val left = timeoutMs - (System.currentTimeMillis() - startedAt)
                    if (earlier.outlivedWait || left <= 0 ||
                        withTimeoutOrNull(left) { earlier.released.await() } == null
                    ) {
                        stuck = true
                        break
                    }
                }
                val release = {
                    synchronized(this@EnsResolver) {
                        if (lightClientProbeInEngine === mine) lightClientProbeInEngine = null
                    }
                    mine.released.complete(Unit)
                    Unit
                }
                val answer = if (stuck) {
                    EnsLightClient.Call.Unavailable("an earlier probe is still in the engine")
                } else {
                    val left = (timeoutMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1)
                    try {
                        client.ethCall(ENS_REGISTRY, PROBE_CALL_DATA, left, probe = true, released = release)
                            .also { synchronized(this@EnsResolver) { mine.outlivedWait = true } }
                    } catch (e: CancellationException) {
                        release()
                        throw e
                    } catch (e: Throwable) {
                        release()
                        EnsLightClient.Call.Unavailable(e.message ?: "failed")
                    }
                }
                // A new stretch of readiness isn't this probe's to judge.
                if (client.readyGeneration() != generation) {
                    settleLightClientMisses(generation, namesOwn = false)
                    return@async true
                }
                // Only a real answer shows the engine could serve: the
                // misses it was judging were the names' own.
                settleLightClientMisses(generation, namesOwn = answer !is EnsLightClient.Call.Unavailable)
                // Busy with no probe of ours in the engine (the engine's own
                // admission, or a probe from an earlier stretch of
                // readiness) says nothing about health.
                val ok = answer !is EnsLightClient.Call.Unavailable || answer.notReady || answer.busy
                if (!ok) {
                    lightClientMiss = LightClientBackoff(generation, System.currentTimeMillis())
                    Log.i(TAG, "light client: probe failed too (${(answer as EnsLightClient.Call.Unavailable).reason}); skipping it for ${lightClientBackoffMs}ms")
                } else {
                    Log.i(TAG, "light client: probe answered; the miss was the name's, not skipping it")
                }
                ok
            }
            lightClientProbe = LightClientProbe(generation, healthy)
        }
    }

    /** [resolveByLightClient]'s read, blocking; throws [LightClientMiss] for a fallback. */
    private suspend fun lightClientRead(
        client: EnsLightClient,
        generation: Long,
        name: String,
        target: String,
        callData: ByteArray,
        contract: String?,
        record: Record,
        ccipRead: Boolean,
        budget: LightClientBudget,
        capped: Boolean,
    ): Verdict {
        val site = siteOf(name)
        // Each call waits only for what's left of the lookup's budget, so
        // a read abandoned at the deadline doesn't hold a thread past it.
        fun call(to: String, data: ByteArray, pin: Long?): Pair<CallOutcome, Long?> {
            if (client.readyGeneration() != generation) throw LightClientMiss("light client no longer ready", momentary = true)
            val left = budget.left()
            if (left <= 0) throw LightClientMiss("out of time", backOff = budget.engineOwnsTheTime())
            // The name's own site pays for its slow calls, not the light client.
            val slot = holdLightClientSlot(site, capped)
                ?: throw LightClientMiss("$site has its share of light-client calls in the engine", momentary = true)
            // A call that outlives the wait for it — still in the engine when
            // its own timeout came, or when the lookup gave up — marks its
            // site slow ([lightClientSlowSites]).
            val inEngine = AtomicBoolean(true)
            budget.onGiveUp { if (inEngine.get()) markSlowSite(site) }
            val release = { if (inEngine.compareAndSet(true, false)) slot() }
            val answer = budget.engine { client.ethCall(to, "0x" + data.toHex(), left, released = release) }
            if (answer is EnsLightClient.Call.Unavailable && answer.timedOut && inEngine.get()) markSlowSite(site)
            // Every engine slot taken: whoever holds a full share of them
            // right now is crowding the rest out, however quickly each of
            // its calls answers, and joins the slow sites. This call's own
            // slot is let go of first, so a site asking for its second is
            // never the one marked for it.
            if (answer is EnsLightClient.Call.Unavailable && answer.busy && capped) {
                release()
                markCrowdingSites()
            }
            // An answer from a light client that has since stopped or
            // restarted isn't one this lookup's epoch vouches for.
            if (client.readyGeneration() != generation) throw LightClientMiss("light client availability changed", momentary = true)
            val (outcome, at) = when (answer) {
                is EnsLightClient.Call.Ok -> {
                    markAnsweredSite(site)
                    CallOutcome(data = answer.resultHex, revertData = null) to answer.block
                }
                is EnsLightClient.Call.Revert -> {
                    markAnsweredSite(site)
                    CallOutcome(data = null, revertData = answer.dataHex) to answer.block
                }
                // A closed read gate: readiness moved before this lookup
                // heard (the app just went to the background, say) — fall
                // back this once, but don't skip the light client for it.
                is EnsLightClient.Call.Unavailable -> throw LightClientMiss(
                    answer.reason,
                    backOff = when {
                        answer.notReady -> false
                        // Every slot taken is back-pressure (other names'
                        // calls, abandoned ones included), not a light
                        // client that can't serve: RPC this once, and the
                        // next lookup tries it again.
                        answer.busy -> false
                        // The call waited out what was left of the budget:
                        // the same out-of-time verdict as the deadline's own,
                        // so a slow gateway earlier in the lookup doesn't
                        // count against the light client here either.
                        answer.timedOut -> budget.engineOwnsTheTime()
                        else -> true
                    },
                    momentary = answer.notReady || answer.busy || answer.unreachable,
                )
            }
            // A CCIP-Read callback checks the gateway's answer against the
            // state that deferred to it, as the RPC path does by pinning the
            // block. The engine only runs at its verified head (a block
            // number still means head state), so the callback can't be
            // pinned: check it ran at the same block instead.
            if (pin != null && at != null && at != pin) throw HeadMoved()
            return outcome to (at ?: pin)
        }

        var attempt = 0
        while (true) {
            attempt++
            val (first, block) = call(target, callData, pin = null)
            var outcome = first
            // CCIP-Read is a Universal Resolver affair; a NameNFT registry is
            // called directly and never defers offchain.
            val offchain = outcome.revertData
            if (contract == null && offchain != null && isOffchainLookup(offchain)) {
                // Settings-driven, and what every RPC server would say too.
                if (!ccipRead) return Verdict(ccipDisabled(name), verified = false)
                // The gateway's answer is checked by the callback, which runs
                // on the light client like the first call, at the same block.
                outcome = try {
                    followOffchainLookup(
                        offchain,
                        fetch = { sender, urls, data -> budget.gateway { ccipFetch(sender, urls, data, budget::left) } },
                    ) { to, data -> call(to, data, pin = block).first }
                } catch (e: HeadMoved) {
                    if (attempt < LIGHT_CLIENT_CCIP_ATTEMPTS) continue
                    throw LightClientMiss("head kept moving during CCIP-Read", momentary = true)
                } catch (e: LightClientMiss) {
                    throw e
                } catch (e: Exception) {
                    throw LightClientMiss("CCIP-Read: ${e.message}")
                }
            }
            return lightClientVerdict(name, outcome, contract, record, block)
        }
    }

    /** The light client's final [outcome] as a [Verdict], or [LightClientMiss] for anything but an answer. */
    private fun lightClientVerdict(name: String, outcome: CallOutcome, contract: String?, record: Record, block: Long?): Verdict {
        val trust = EnsTrust(
            verified = true,
            agreed = listOf(LIGHT_CLIENT_SOURCE),
            block = block,
            source = EnsTrust.Source.MYOTIS,
        )
        outcome.revertData?.let { revert ->
            // Only a revert that says "no such name" is taken as an
            // answer; anything else is left for the RPC servers to report.
            val mapped = (if (contract == null) mapRevert(name, revert, record) else null)
                ?: throw LightClientMiss("revert ${revert.take(10)}")
            return Verdict(mapped.withTrust(trust), verified = true)
        }
        if (outcome.data.isNullOrEmpty()) throw LightClientMiss("empty result")
        val decoded = decode(name, outcome, contract, record)
        if (decoded is EnsResult.Error) throw LightClientMiss("${decoded.reason}: ${decoded.error}")
        return Verdict(decoded.withTrust(trust), verified = true)
    }

    // ---- Colibri (#100) ----

    /**
     * The proven tier: the record read through [EnsColibri], labelled
     * [EnsTrust.Source.COLIBRI]. `null` — go on to the RPC servers —
     * whenever it can't *prove* an answer:
     *
     * - no verifier (not in this build), a prover / network / proof
     *   failure, or no answer within [COLIBRI_WAIT_MS]. A call still
     *   running then carries on in the background (up to
     *   [COLIBRI_BACKGROUND_MS]) rather than being cut off: on a first
     *   lookup that is the verifier bootstrapping its sync committee,
     *   which the next lookup then needn't repeat. An unreachable
     *   prover / server or a missed wait (counted once per call) starts
     *   a back-off ([colibriBackoff]) during which lookups skip the
     *   verifier outright, so unreachable provers don't cost every new
     *   name the full wait, nor pile up background calls; a proof that
     *   does come in ends it. A proof failure specific to this call
     *   (the verifier rejecting it, a result that isn't return data)
     *   falls through for this name only;
     * - a revert other than the Universal Resolver's own "no resolver"
     *   errors (a proven `ResolverNotFound` is a proven negative): no
     *   data at all is ambiguous (a degraded prover hop can look like
     *   that), and any other error proves the resolver failed, not that
     *   the record is absent — desktop's and iOS's classification. A
     *   NameNFT registry (`.wei`, `.gwei`) has no error vocabulary, so
     *   any of its reverts falls through the same way;
     * - an `OffchainLookup` whose callback can't be proven. The callback
     *   goes through the verifier too, so the gateway's answer is only
     *   taken once the resolver contract has accepted it under proof.
     *   With CCIP-Read off the lookup ends here, as it would on the
     *   servers — and so does a gateway that fails or runs out the
     *   [COLIBRI_CCIP_BUDGET_MS] budget (`CCIP_GATEWAY_FAILED`, not
     *   cached): the quorum would fetch the very same gateway again,
     *   doubling the wait and showing it the name twice. The gateway
     *   fetches themselves must be done within [LEG_TIMEOUT_MS] of the
     *   first one's start — the quorum read's own budget, not shortened
     *   by the proof before it — so a black-holed gateway fails the name
     *   no later than it would without Colibri, and a pass that has given
     *   up asks no further gateway.
     */
    private suspend fun resolveByColibri(
        config: Settings,
        name: String,
        target: String,
        callData: ByteArray,
        contract: String?,
        record: Record,
    ): Verdict? {
        val colibri = colibri ?: return null
        colibriBackoff.remainingMs()?.let {
            Log.i(TAG, "[$name] colibri: backing off for ${it}ms after a failure, asking the RPC servers")
            return null
        }
        val started = System.currentTimeMillis()
        val provers = LinkedHashSet<String>()
        suspend fun prove(to: String, data: ByteArray, waitMs: Long): EnsColibri.Outcome? {
            // One call is one failure at most, whichever side (a missed
            // wait here, or the background call's own end) sees it first.
            val counted = AtomicBoolean(false)
            fun failed() {
                if (counted.compareAndSet(false, true)) colibriBackoff.failed()
            }
            val call = io.async {
                try {
                    withTimeoutOrNull(COLIBRI_BACKGROUND_MS) { colibri.ethCall(to, data, config.endpoints) }
                        .also { if (it != null) colibriBackoff.succeeded() else failed() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EnsColibri.Failure) {
                    // This call's own proof failing isn't the provers
                    // being unreachable: other names may still prove.
                    if (e.unreachable) failed()
                    throw e
                } catch (e: Throwable) {
                    failed()
                    throw e
                }
            }
            val proven = try {
                withTimeoutOrNull(waitMs) { call.await() }
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "[$name] colibri: ${e.message}")
                return null
            }
            if (proven == null) {
                // The call runs on in the background and clears this if
                // it does come in (a first lookup's bootstrap).
                failed()
                Log.i(TAG, "[$name] colibri: no proof within ${waitMs}ms, asking the RPC servers")
                return null
            }
            provers += proven.provers
            return proven.outcome
        }
        val first = prove(target, callData, colibriWaitMs) ?: return null
        var offchain = false
        val trust = {
            EnsTrust(
                verified = true,
                agreed = provers.ifEmpty { setOf(hostOf(EnsColibri.PROVERS.first())) }.toList(),
                source = EnsTrust.Source.COLIBRI,
                offchain = offchain,
            )
        }
        var outcome = when (first) {
            is EnsColibri.Outcome.Returned -> CallOutcome(data = first.data, revertData = null)
            is EnsColibri.Outcome.Reverted -> CallOutcome(data = null, revertData = first.data)
        }
        val revert = outcome.revertData
        if (revert != null && contract == null && isOffchainLookup(revert)) {
            if (!config.ccipRead) return Verdict(ccipDisabled(name), verified = true)
            // Whether the pass is (or ended) at the gateway rather than
            // at a proof: a gateway failure is the name's answer, since
            // the quorum would only fetch the same gateway again.
            val atGateway = AtomicBoolean(false)
            val followed = io.async {
                runCatchingCancellable {
                    // The gateways get what the quorum's read would have
                    // (its [LEG_TIMEOUT_MS]), counted from when they're
                    // first asked rather than from this lookup's start,
                    // so the proof that got here doesn't eat into it; a
                    // dead gateway still fails no later than it would
                    // there, and a proven callback still gets its wait.
                    followOffchainLookup(revert, atGateway, gatewayMs = colibriGatewayMs) { to, data ->
                        when (val o = prove(to, data, colibriWaitMs)) {
                            is EnsColibri.Outcome.Returned -> CallOutcome(data = o.data, revertData = null)
                            is EnsColibri.Outcome.Reverted -> CallOutcome(data = null, revertData = o.data)
                            null -> throw IllegalStateException("no proof for the CCIP-Read callback")
                        }
                    }
                }
            }
            val result = try {
                withTimeoutOrNull(COLIBRI_CCIP_BUDGET_MS) { followed.await() }
            } catch (e: CancellationException) {
                followed.cancel()
                throw e
            }
            if (result == null) followed.cancel()
            outcome = result?.getOrNull() ?: run {
                val why = result?.exceptionOrNull()?.message ?: "timed out"
                if (atGateway.get()) {
                    // The gateway failed (or used up the budget): end the
                    // lookup here, as the quorum would after asking it again.
                    Log.w(TAG, "[$name] colibri: CCIP gateway failed ($why)")
                    return Verdict(
                        EnsResult.Error(name = name, reason = "CCIP_GATEWAY_FAILED", error = why, retryable = true),
                        verified = false,
                    )
                }
                Log.i(TAG, "[$name] colibri: CCIP-Read failed ($why), asking the RPC servers")
                return null
            }
            offchain = true
        }
        val verdict = when (val r = outcome.revertData) {
            null -> {
                if (outcome.data.isNullOrEmpty() || outcome.data == "0x") return null
                decode(name, outcome, contract, record).withTrust(trust())
            }
            else -> (if (contract == null) mapRevert(name, r, record) else null)?.withTrust(trust()) ?: run {
                Log.i(TAG, "[$name] colibri: proven revert ${r.take(10)} proves no record; asking the RPC servers")
                return null
            }
        }
        Log.i(TAG, "[$name] colibri proved it in ${System.currentTimeMillis() - started}ms via ${provers.joinToString()}")
        return Verdict(verdict, verified = true)
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
        /**
         * The seats: one server per provider that reported a head, in
         * the configured order ([EnsQuorum.waveOrder]).
         */
        val order: List<String>,
        /**
         * Every server that reported a head, in the configured order —
         * [order] plus each seat's same-provider twins, which stand in
         * for a seat whose record read fails ([collectLegs]).
         */
        val reported: List<String>,
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
        // Every endpoint is probed, but each provider's head is counted
        // once ([EnsQuorum.waveOrder]): its first server, in the user's
        // order, that reported one. A second endpoint of the same operator
        // would otherwise count its head, and its hash, twice — and a
        // provider whose first endpoint is down (a wrong key) keeps its
        // vote through its twin.
        val pool = epoch.settings.endpoints
        val probes = pool.associateWith { rpc -> io.async { blockNumber(rpc) } }
        // Every server, not just a wave's worth: the more heads, the
        // harder the median is to move. Waits for all of them — or,
        // once enough are in for a median, a moment longer for the rest
        // rather than for the slowest server's timeout.
        val heads = gather<Long>(
            tasks = probes.mapValues { (_, probe) -> suspend { probe.await() } },
            timeoutMs = QUORUM_TIMEOUT_MS.toLong(),
            graceMs = HEAD_GRACE_MS,
            graceFrom = { got ->
                EnsQuorum.waveOrder(pool, got.filterValues { it != null }.keys).size >= EnsQuorum.MIN_PROVIDERS
            },
        ).filterValues { it != null }.mapValues { it.value!! }
        // The user's order, not arrival order (#102), one server per
        // provider: see [EnsQuorum.waveOrder].
        val order = EnsQuorum.waveOrder(pool, heads.keys)
        val number = EnsQuorum.anchorNumber(order.map { heads.getValue(it) })
        if (number == null) {
            Log.w(TAG, "anchor infeasible: ${order.size} of ${EnsQuorum.voters(pool).size} providers reported a head")
            return null
        }
        val tag = "0x" + number.toString(16)
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
        val reported = pool.filter { it in heads }
        return AnchorRound(number, order, reported, hashes, vote, startedAt).also { epoch.anchor = it }
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
        record: Record,
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
        collectLegs(calls, round, hash, legs, outcomes, target, callData, contract, ccipRead)
        var vote = EnsQuorum.waveVote(legs)
        val rest = round.order.drop(EnsQuorum.K)
        if (EnsQuorum.worthWidening(vote, asked = first.size) && rest.isNotEmpty()) {
            Log.i(TAG, "[$name] widening the wave to ${rest.map(::hostOf)} after ${scrub(vote, round.reported)}")
            val more = rest.associateWith { startCall(it, target, callData, contract, round.tag, ccipRead) }
            collectLegs(more, round, hash, legs, outcomes, target, callData, contract, ccipRead)
            vote = EnsQuorum.waveVote(legs)
        }
        Log.i(TAG, "[$name] block #${round.number}: ${scrub(vote, round.reported)}")
        return when (vote) {
            is EnsQuorum.WaveVote.Agreed -> Verdict(
                decode(name, outcomes.getValue(vote.agreed.first()), contract, record).withTrust(
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
                decode(name, outcomes.getValue(vote.hosts.first()), contract, record).withTrust(
                    EnsTrust(verified = false, agreed = vote.hosts.map(::hostOf), block = round.number),
                ),
                verified = false,
            )
            is EnsQuorum.WaveVote.Conflict -> {
                val groups = vote.byKey.values.map { rpcs ->
                    EnsResult.Conflict.Group(
                        describe(decode(name, outcomes.getValue(rpcs.first()), contract, record)),
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
                followOffchainLookup(call.revertData) { to, data -> ethCall(rpc, to, data, block, QUORUM_TIMEOUT_MS) }
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
     *
     * A seat whose read fails hands its provider's vote to that
     * provider's next server that reported a head
     * ([AnchorRound.reported]) — a keyed endpoint that answers heads but
     * is rate-limited on `eth_call` doesn't take its public twin's vote
     * down with it. The stand-in replaces the seat in [legs], so the
     * provider still counts once. Not after a CCIP-Read failure: that
     * was the name's gateway, which the twin would ask too. The stand-in
     * starts the moment its seat fails — not once the slowest seat is
     * in — with its read and its block hash asked side by side, so each
     * stand-in adds at most one read's time (its own [LEG_TIMEOUT_MS])
     * after the server it replaces, alongside the others. They chain: a
     * provider with more servers that reported a head can go through
     * one stand-in after another, one read's time each, until one
     * answers or none is left.
     */
    private suspend fun collectLegs(
        calls: Map<String, Deferred<CallOutcome>>,
        round: AnchorRound,
        hash: String,
        legs: LinkedHashMap<String, EnsQuorum.Leg>,
        outcomes: MutableMap<String, CallOutcome>,
        target: String,
        callData: ByteArray,
        contract: String?,
        ccipRead: Boolean,
    ): Unit = coroutineScope {
        val tried = HashSet(legs.keys)
        val arrivals = Channel<Pair<String, EnsQuorum.Leg?>>(Channel.UNLIMITED)
        val running = HashMap<String, Job>()
        // Everything this call started, to cancel once the wave is
        // decided: the reads, and a stand-in's own block-hash request
        // (a seat's is [AnchorRound.hashes], shared with later lookups).
        val started = ArrayList<Deferred<*>>(calls.values)

        fun seat(rpc: String, call: Deferred<CallOutcome>, theirs: Deferred<String>) {
            tried += rpc
            running[rpc] = launch {
                val leg = try {
                    withTimeoutOrNull(LEG_TIMEOUT_MS) { judge(rpc, call, theirs, round, hash, outcomes) }
                } catch (t: Throwable) {
                    // Ours cancelled: unwind. The read's own failure: no vote.
                    ensureActive()
                    if (t !is CancellationException) Log.w(TAG, "${hostOf(rpc)}: ${t.message}")
                    null
                }
                arrivals.send(rpc to leg)
            }
        }

        try {
            for ((rpc, call) in calls) {
                seat(rpc, call, round.hashes[rpc] ?: io.async { blockHash(rpc, round.tag) }.also { started += it })
            }
            while (running.isNotEmpty()) {
                val (rpc, leg) = arrivals.receive()
                running.remove(rpc)
                legs[rpc] = leg ?: EnsQuorum.Leg.Failed()
                if (EnsQuorum.waveDecided(legs)) return@coroutineScope
                if (leg is EnsQuorum.Leg.Answer || (leg is EnsQuorum.Leg.Failed && leg.ccip)) continue
                // Stood in for at once, not after the slowest seat: the
                // twin's read and its block hash go out together.
                val twin = EnsQuorum.standIn(round.reported, rpc, tried) ?: continue
                legs.remove(rpc)
                Log.i(TAG, "${hostOf(rpc)}: no read; its provider votes through ${hostOf(twin)}")
                val call = startCall(twin, target, callData, contract, round.tag, ccipRead)
                val theirs = round.hashes[twin] ?: io.async { blockHash(twin, round.tag) }
                started += call
                started += theirs
                seat(twin, call, theirs)
            }
        } finally {
            running.values.forEach { it.cancel() }
            started.forEach { if (it.isActive) it.cancel() }
        }
    }

    /** One server's read as its vote — see [collectLegs]. */
    private suspend fun judge(
        rpc: String,
        call: Deferred<CallOutcome>,
        theirHash: Deferred<String>,
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
        val theirs = theirHash.await()
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
        is EnsResult.NotFound -> if (result.reason == "NO_ADDRESS") "no address" else "no content (${result.reason})"
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
        record: Record,
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
                        followOffchainLookup(call.revertData!!) { to, data -> ethCall(rpc, to, data) }
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
                val mapped = if (contract == null) mapRevert(normalized, call.revertData, record) else null
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
            return Verdict(decode(normalized, call, contract, record).withTrust(trustOf(rpc)), verified = false)
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
    private fun decode(name: String, call: CallOutcome, contract: String?, record: Record): EnsResult {
        call.revertData?.let { revert ->
            if (contract == null && isOffchainLookup(revert)) return ccipDisabled(name)
            return (if (contract == null) mapRevert(name, revert, record) else null)
                ?: EnsResult.Error(name = name, reason = "RESOLUTION_ERROR", error = "revert: $revert")
        }
        val raw = call.data.orEmpty()
        if (record is Record.Address) return decodeAddressResponse(name, raw, viaUniversalResolver = contract == null, record)
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

    private fun buildResolveCallData(normalizedName: String, record: Record): ByteArray {
        val dnsName = dnsEncode(normalizedName)
        val node = namehash(normalizedName)
        return RESOLVE_SELECTOR + abiEncodeTwoBytes(dnsName, recordCallData(record, node))
    }

    /**
     * The resolver call reading [record] for [node]: `contenthash(node)`;
     * for an address, `addr(node)` for Ethereum's coin type (ENSIP-1/9,
     * the one every resolver — and a NameNFT registry — has) and
     * `addr(node, coinType)` for any other chain (ENSIP-11).
     */
    private fun recordCallData(record: Record, node: ByteArray): ByteArray = when (record) {
        Record.Contenthash -> CONTENTHASH_SELECTOR + node
        is Record.Address -> if (record.coinType == ETH_COIN_TYPE) {
            ADDR_SELECTOR + node
        } else {
            ByteArray(32).also { writeUint256(record.coinType, it, 0) }.let { MULTICOIN_ADDR_SELECTOR + node + it }
        }
    }

    /**
     * An address record's answer, as an [EnsResult.Ok] whose [EnsResult.Ok.uri]
     * (and `decoded`) is the lowercase `0x` address, protocol `addr` —
     * internal to this class; [resolveAddress] hands out an
     * [EnsAddressResult]. `addr(bytes32)` returns an ABI `address`;
     * `addr(bytes32,uint256)` ABI `bytes`, 20 of them for an EVM chain.
     * Through the Universal Resolver that return is wrapped in its
     * `(bytes, address)` tuple. No record — empty bytes, or the zero
     * address — is `NotFound` with `NO_ADDRESS`: nothing may be sent there.
     */
    private fun decodeAddressResponse(name: String, rawHex: String, viaUniversalResolver: Boolean, record: Record.Address): EnsResult {
        val inner = if (viaUniversalResolver) {
            decodeDynamicBytesAt(rawHex, pointerSlot = 0)
                ?: return EnsResult.Error(name, "RESOLUTION_ERROR", "malformed UR outer response")
        } else {
            runCatching { rawHex.hexToBytes() }.getOrNull()
                ?: return EnsResult.Error(name, "RESOLUTION_ERROR", "malformed addr response")
        }
        if (inner.isEmpty()) return EnsResult.NotFound(name, "NO_ADDRESS", EnsTrust.UNCHECKED)
        val address: ByteArray = if (record.coinType == ETH_COIN_TYPE) {
            if (inner.size != 32 || (0 until 12).any { inner[it] != 0.toByte() }) {
                return EnsResult.Error(name, "RESOLUTION_ERROR", "addr record isn't an ABI address")
            }
            inner.copyOfRange(12, 32)
        } else {
            val bytes = decodeDynamicBytesAt(inner, pointerSlot = 0)
                ?: return EnsResult.Error(name, "RESOLUTION_ERROR", "addr record isn't ABI bytes")
            if (bytes.isEmpty()) return EnsResult.NotFound(name, "NO_ADDRESS", EnsTrust.UNCHECKED)
            if (bytes.size != 20) {
                return EnsResult.Error(name, "RESOLUTION_ERROR", "addr record is ${bytes.size} bytes, not an EVM address")
            }
            bytes
        }
        if (address.all { it == 0.toByte() }) return EnsResult.NotFound(name, "NO_ADDRESS", EnsTrust.UNCHECKED)
        val hex = "0x" + address.toHex()
        return EnsResult.Ok(name, protocol = ADDRESS_PROTOCOL, uri = hex, decoded = hex, trust = EnsTrust.UNCHECKED)
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
     * Resolver, malformed revert data, or too many rounds. The callback
     * goes through [ethCall]: the same server (at the same block) as the
     * call that deferred, or the light client.
     */
    private suspend fun followOffchainLookup(
        rpc: String,
        firstRevert: String,
        block: String = "latest",
        timeoutMs: Int = RPC_TIMEOUT_MS,
    ): CallOutcome =
        // At the same block as the call that deferred: the callback
        // checks the gateway's answer against that state.
        followOffchainLookup(firstRevert) { to, data -> ethCall(rpc, to, data, block, timeoutMs) }

    /**
     * [followOffchainLookup] with each callback made by [call] — one
     * server's `eth_call`, or a proven one (#100), so the gateway's
     * answer is only ever accepted through the same check that asked
     * for it. Runs on an IO thread: [ccipFetch] blocks. [atGateway], if
     * given, is `true` while a gateway is being asked and stays `true`
     * when the gateways failed, so a caller can tell a gateway failure
     * (or a budget spent waiting on one) from a failed callback.
     * [gatewayMs], if given, is how long every gateway fetch of the pass
     * has in all, counted on the monotonic clock from the first one's
     * start; a fetch still running then fails the pass as if the
     * gateways were down, and is told to ask no further gateway.
     * [fetch], if given, replaces the gateway fetch outright — the light
     * client's (#101), which bounds it by its own budget — and then
     * [gatewayMs] doesn't apply.
     */
    private suspend fun followOffchainLookup(
        firstRevert: String,
        atGateway: AtomicBoolean? = null,
        gatewayMs: Long? = null,
        fetch: ((sender: String, urls: List<String>, callData: ByteArray) -> ByteArray?)? = null,
        call: suspend (to: String, data: ByteArray) -> CallOutcome,
    ): CallOutcome {
        var revert = firstRevert
        // Monotonic, so a wall-clock step (NTP) can neither fail a
        // working gateway at once nor lift the bound.
        var gatewayDeadlineNs: Long? = null
        repeat(MAX_CCIP_ROUNDS) {
            val lookup = decodeOffchainLookup(revert)
                ?: throw IllegalStateException("malformed OffchainLookup revert")
            // Only follow lookups issued by the contract we called. A
            // resolver can't redirect us into calling back some other
            // contract with gateway-supplied bytes.
            if (!lookup.sender.equals(UNIVERSAL_RESOLVER, ignoreCase = true)) {
                throw IllegalStateException("OffchainLookup sender is not the Universal Resolver")
            }
            atGateway?.set(true)
            val fetched = if (fetch != null) {
                fetch(lookup.sender, lookup.urls, lookup.callData)
            } else if (gatewayMs == null) {
                ccipFetch(lookup.sender, lookup.urls, lookup.callData)
            } else {
                val deadlineNs = gatewayDeadlineNs
                    ?: (System.nanoTime() + gatewayMs * 1_000_000).also { gatewayDeadlineNs = it }
                // [ccipFetch] blocks; wait on it from here so the deadline
                // holds even while a read is stalled inside it. Cancelling
                // the job can't interrupt that read, so [stop] is what
                // keeps the loop from sending the name to the next gateway
                // once the pass has already given up on it.
                val stop = AtomicBoolean(false)
                val job = io.async { ccipFetch(lookup.sender, lookup.urls, lookup.callData) { stop.get() } }
                val left = ((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                // Boxed, so a fetch that ended with no answer (null) isn't
                // taken for one that ran out of time.
                val done = try {
                    withTimeoutOrNull(left) { Result.success(job.await()) }
                } finally {
                    if (job.isActive) {
                        stop.set(true)
                        job.cancel()
                    }
                }
                (done ?: throw IllegalStateException("CCIP gateways didn't answer within the lookup's budget"))
                    .getOrThrow()
            }
            val response = fetched ?: throw IllegalStateException("CCIP gateways unavailable or returned invalid data")
            atGateway?.set(false)
            val callbackData = lookup.callback + abiEncodeTwoBytes(response, lookup.extraData)
            val outcome = call(lookup.sender, callbackData)
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
     * skipped like any other non-https string. Once [stopped] says so
     * (the caller gave up waiting), no further gateway is asked. [left],
     * when given, is
     * the caller's remaining budget: each gateway gets no more than it,
     * and none is tried once it's spent.
     */
    internal fun ccipFetch(
        sender: String,
        urls: List<String>,
        callData: ByteArray,
        left: (() -> Long)? = null,
        stopped: () -> Boolean = { false },
    ): ByteArray? {
        val senderLower = sender.lowercase()
        val dataHex = "0x" + callData.toHex()
        for (template in urls) {
            if (stopped()) return null
            val remaining = left?.invoke()
            if (remaining != null && remaining <= 0) return null
            // Never 0: that's "no timeout" to HttpURLConnection.
            val timeoutMs = remaining?.coerceIn(1, CCIP_TIMEOUT_MS.toLong())?.toInt() ?: CCIP_TIMEOUT_MS
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
                    timeoutMs = timeoutMs,
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

    private fun mapRevert(name: String, revertData: String, record: Record): EnsResult? {
        val lower = revertData.lowercase()
        val selector = if (lower.length >= 10) lower.substring(0, 10) else return null
        return when (selector) {
            // ResolverNotFound(bytes), ResolverNotContract(bytes,address)
            "0x77209fe8", "0x1e9535f2" -> EnsResult.NotFound(name, "NO_RESOLVER", EnsTrust.UNCHECKED)
            // UnsupportedResolverProfile(bytes4): the name's resolver has no
            // multicoin `addr` (#277) — no address for this chain, from
            // the Universal Resolver itself, not a failure.
            "0x7b1c461b" -> if (record is Record.Address) EnsResult.NotFound(name, "NO_ADDRESS", EnsTrust.UNCHECKED) else null
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

        // #100: how long a lookup waits for a proof before asking the
        // RPC servers. A warm verifier answers in about a second; the
        // first call after install (or a library upgrade) bootstraps the
        // sync committee, and a prover that's down or black-holed would
        // otherwise hold every name up by its connect timeouts. The call
        // itself may run on for [COLIBRI_BACKGROUND_MS], to finish that
        // bootstrap for the next lookup.
        private const val COLIBRI_WAIT_MS = 6_000L
        private const val COLIBRI_BACKGROUND_MS = 60_000L

        // How long the verifier is skipped after a failure or a missed
        // wait, doubling per further failure (#100, R2-F2).
        internal const val COLIBRI_BACKOFF_MS = 30_000L
        internal const val COLIBRI_BACKOFF_MAX_MS = 5 * 60_000L

        // A proven CCIP-Read pass, gateway hops and proven callbacks
        // included (iOS's `provenCCIPBudget`).
        private const val COLIBRI_CCIP_BUDGET_MS = 30_000L

        // Once enough heads are in for a median, how much longer the
        // rest get: a slow server shouldn't cost every cold lookup its
        // timeout, but one a moment behind still gets its say.
        private const val HEAD_GRACE_MS = 50L

        private const val RPC_TIMEOUT_MS = 15_000

        /**
         * The light client's whole budget for one lookup (#101), CCIP-
         * Read hops included, before the RPC servers are asked instead.
         */
        internal const val LIGHT_CLIENT_DEADLINE_MS = 20_000L

        /**
         * How long the light client is skipped after it missed for its own
         * reasons (unavailable, erroring, out of time in the engine) — lookups go straight to
         * RPC meanwhile instead of each paying its delay first.
         */
        internal const val LIGHT_CLIENT_BACKOFF_MS = 60_000L

        /**
         * How long [probeLightClient]'s name-independent call gets before
         * the light client counts as struggling (capped at the lookup
         * deadline). A proof of one registry slot is the least any call costs.
         */
        internal const val LIGHT_CLIENT_PROBE_TIMEOUT_MS = 5_000L

        /** The ENS registry: [probeLightClient]'s target, a contract that is always there. */
        internal const val ENS_REGISTRY = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e"

        /** `owner(bytes32(0))` — the root node's owner: one storage slot, no name involved. */
        internal const val PROBE_CALL_DATA = "0x02571be3" + "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * Light-client calls one site ([siteOf]) may have in the engine at
         * once, abandoned ones included ([lightClientHeld]) — of the
         * engine's eight, one of which only the probe can use.
         */
        internal const val LIGHT_CLIENT_CALLS_PER_SITE = 2

        /**
         * Whose resolver a light-client call's cost is charged to: the
         * name's registration (`evil.eth` for `a1.b.evil.eth`) — subnames
         * are free to mint, a new registration isn't, and every name
         * under one registration answers to the same owner. Under a
         * subname registrar ([ENS_SUBNAME_REGISTRARS]: `alice.base.eth` is
         * a Basename, registered by alice, not base.eth's owner) it's one
         * label deeper; a DNS name's is its registrable domain by the
         * [PublicSuffixList] (`shop.example.co.uk` → `example.co.uk`).
         */
        internal fun siteOf(name: String): String {
            val labels = name.split('.')
            ENS_SUBNAME_REGISTRARS.firstOrNull { name.endsWith(".$it") }?.let { registrar ->
                return labels.takeLast(registrar.count { it == '.' } + 2).joinToString(".")
            }
            return runCatching { PublicSuffixList.registrableDomain(name) }.getOrNull()
                ?: labels.takeLast(2).joinToString(".")
        }

        /**
         * Names whose subnames are each a separate registration, sold or
         * given to unrelated owners: Basenames, Uniswap's `uni.eth`,
         * Linea names. (Coinbase's `cb.id` isn't here: `.id` isn't a
         * suffix the browser resolves, so no such name gets this far.)
         */
        internal val ENS_SUBNAME_REGISTRARS = listOf("base.eth", "uni.eth", "linea.eth")

        /**
         * How long a site stays slow ([lightClientSlowSites]) after one of
         * its light-client calls outlived the lookup that made it.
         */
        internal const val LIGHT_CLIENT_SLOW_SITE_MS = 10 * 60_000L

        /**
         * How long a name the light client couldn't serve is left to
         * Colibri/RPC ([lightClientNameMisses]).
         */
        internal const val LIGHT_CLIENT_NAME_MISS_MS = 10 * 60_000L

        /** Names [lightClientNameMisses] remembers at most; the oldest goes first. */
        private const val LIGHT_CLIENT_NAME_MISSES_MAX = 512

        /**
         * Engine calls every slow site together may hold: of the seven
         * lookup slots, the rest stay free for names that answer in time.
         */
        internal const val LIGHT_CLIENT_SLOW_CALLS = 2

        /**
         * Engine calls every site that isn't established yet
         * ([lightClientAnswered]) — fresh ones and slow ones together —
         * may hold: of the seven lookup slots, three stay for the names
         * the user has been resolving all along.
         */
        internal const val LIGHT_CLIENT_FRESH_CALLS = 4

        /** How long after its first in-time answer a site is established ([lightClientAnswered]). */
        internal const val LIGHT_CLIENT_ESTABLISHED_MS = 10 * 60_000L

        /** Most slow sites remembered; the oldest go first. */
        private const val LIGHT_CLIENT_SLOW_SITES_MAX = 256

        /** Tries at a CCIP-Read name whose callback lands on a newer head than its first call. */
        private const val LIGHT_CLIENT_CCIP_ATTEMPTS = 3

        /** How [EnsTrust.agreed] names the light client. */
        const val LIGHT_CLIENT_SOURCE = "Myotis light client (on this device)"
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

        // bytes4(keccak256("addr(bytes32)")), ENSIP-1
        private val ADDR_SELECTOR = "3b3b57de".hexToBytes()

        // bytes4(keccak256("addr(bytes32,uint256)")), ENSIP-9
        private val MULTICOIN_ADDR_SELECTOR = "f1cb7e06".hexToBytes()

        /** SLIP-44 coin type 60: Ethereum mainnet's `addr` (ENSIP-9). */
        internal const val ETH_COIN_TYPE = 60L

        /** [EnsResult.Ok.protocol] of an address record's answer (internal; see [decodeAddressResponse]). */
        internal const val ADDRESS_PROTOCOL = "addr"

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
