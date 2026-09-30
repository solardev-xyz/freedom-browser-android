package baby.freedom.mobile.node

import android.os.IBinder
import baby.freedom.mobile.ens.EnsLightClient
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * This process's line to the Myotis light client in the `:myotis`
 * process: name resolution's [EnsLightClient] over
 * [IMyotisService.ethCall] (#101), and the chain-data router's reads over
 * [IMyotisService.read] ([read], #329).
 *
 * Every `MainActivity` instance owns its own binding and reports it here
 * under its own key — the service when it connects ([connected]), every
 * state it publishes ([onState]), and the end of the binding
 * ([disconnected]). There can be more than one at a time (an App Link
 * opens a second instance in the linking app's task), and one ending
 * leaves the others' in place ([MyotisBindings]). Ready means a binding
 * that is running with Ethereum mainnet
 * [baby.freedom.swarm.MyotisChainStatus.ready] — the same gate the node
 * screen's "serving" state uses. With Ethereum switched off and only
 * Gnosis running (#274) that is never, so the resolver goes straight to
 * its next tier, as with the light client off. Each time that flips (or a different
 * binding takes over) there's a new [readyGeneration]. The router's reads
 * go by each chain's own readiness instead ([isReady]).
 *
 * In the `:node` process the Swarm node's binding reports here the same
 * way ([MyotisReadBinding]), so its Gnosis reads reach the light client too.
 */
object MyotisLink : EnsLightClient {
    private val bindings = MyotisBindings<IMyotisService> { info ->
        info.status == MyotisStatus.Running && info.chain(MyotisNetwork.Mainnet)?.ready == true
    }

    fun connected(owner: Any, service: IMyotisService) = bindings.connected(owner, service)

    fun onState(owner: Any, info: MyotisInfo) = bindings.onState(owner, info)

    fun disconnected(owner: Any) = bindings.disconnected(owner)

    override fun readyGeneration(): Long? = bindings.generation

    /**
     * Whether chain [chainId] is [baby.freedom.swarm.MyotisChainStatus.ready]
     * in the state the binding reads go to last published: synced, a
     * state peer at the head, not parked on a stale anchor, not
     * recovering, not paused in the background.
     */
    fun isReady(chainId: Long): Boolean = chainId in bindings.readyChains

    /**
     * One of the chain-data router's reads ([IMyotisService.read]): the
     * service's `MyotisReads` reply, or an `unavailable` one when there's
     * no binding or `:myotis` dies before answering. Cancellable at once:
     * the service still answers a read it started, into nothing.
     */
    suspend fun read(chainId: Long, method: String, paramsJson: String, page: Boolean = false): String {
        val service = bindings.service ?: return UNAVAILABLE_JSON
        val binder = service.asBinder()
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            lateinit var death: IBinder.DeathRecipient
            fun finish(json: String) {
                if (!done.compareAndSet(false, true)) return
                runCatching { binder.unlinkToDeath(death, 0) }
                if (cont.isActive) cont.resume(json)
            }
            death = IBinder.DeathRecipient { finish(UNREACHABLE_JSON) }
            cont.invokeOnCancellation { runCatching { binder.unlinkToDeath(death, 0) } }
            try {
                binder.linkToDeath(death, 0)
                service.read(chainId, method, paramsJson, page, object : IMyotisCallResult.Stub() {
                    override fun onResult(json: String?) = finish(json ?: UNAVAILABLE_JSON)
                })
            } catch (e: Exception) {
                // DeadObjectException (`:myotis` exited) and friends.
                finish(UNREACHABLE_JSON)
            }
        }
    }

    private const val UNAVAILABLE_JSON = """{"status":"unavailable","reason":"light client not connected","notReady":true}"""
    private const val UNREACHABLE_JSON = """{"status":"unavailable","reason":"light client unreachable"}"""

    override fun ethCall(
        to: String,
        data: String,
        timeoutMs: Long,
        probe: Boolean,
        released: (() -> Unit)?,
    ): EnsLightClient.Call {
        val release = releaseOnce(released)
        val service = bindings.service ?: run {
            release()
            return EnsLightClient.Call.Unavailable("light client not connected", notReady = true)
        }
        // `:myotis` exiting ends every call it held, answered or not.
        val binder = service.asBinder()
        val death = IBinder.DeathRecipient { release() }
        val waiter = CallWaiter {
            runCatching { binder.unlinkToDeath(death, 0) }
            release()
        }
        val result = object : IMyotisCallResult.Stub() {
            override fun onResult(json: String?) = waiter.deliver(json)
        }
        return try {
            runCatching { binder.linkToDeath(death, 0) }.onFailure { release() }
            service.ethCall(MyotisNetwork.Mainnet.chainId, to, data, probe, result)
            if (waiter.await(timeoutMs)) {
                EnsLightClient.parse(waiter.answer)
            } else {
                EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms", timedOut = true)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EnsLightClient.Call.Unavailable("interrupted", unreachable = true)
        } catch (e: Exception) {
            // DeadObjectException (`:myotis` exited) and friends: the call
            // never reached the engine, or the engine is gone with it.
            runCatching { binder.unlinkToDeath(death, 0) }
            release()
            EnsLightClient.Call.Unavailable("light client unreachable: ${e.javaClass.simpleName}", unreachable = true)
        }
    }

    private fun releaseOnce(released: (() -> Unit)?): () -> Unit {
        if (released == null) return {}
        val once = AtomicBoolean(false)
        return { if (once.compareAndSet(false, true)) released() }
    }
}

/**
 * One [MyotisLink.ethCall]'s wait for the engine's answer. [deliver]
 * runs [letGo] — the engine has let go of the call — *before* it wakes
 * the waiter, so whatever the caller does next (another probe, say)
 * never finds this call still counted as in the engine. [letGo] runs
 * also for an answer that comes after the waiter gave up.
 */
internal class CallWaiter(private val letGo: () -> Unit) {
    private val answerRef = AtomicReference<String?>()
    private val done = CountDownLatch(1)

    val answer: String? get() = answerRef.get()

    fun deliver(json: String?) {
        answerRef.set(json)
        letGo()
        done.countDown()
    }

    fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)
}

/**
 * [MyotisLink]'s bookkeeping, one entry per binding owner (a
 * `MainActivity` instance), so one owner's unbind never clears another's
 * still-live binding. The binding reads go to is the most recently
 * connected one that is [ready], else the most recently connected one;
 * [generation] is a fresh id each time readiness turns on or that
 * binding changes, `null` while it isn't ready.
 *
 * Owners are held weakly: an owner that is gone without reporting
 * [disconnected] drops out on its own (values never point back at them).
 */
internal class MyotisBindings<S : Any>(private val ready: (MyotisInfo) -> Boolean) {
    private class Binding<S>(val service: S, val seq: Long) {
        var info: MyotisInfo? = null
    }

    private val lock = Any()
    private val byOwner = WeakHashMap<Any, Binding<S>>()
    private var seq = 0L
    private var counter = 0L
    private var current: Binding<S>? = null

    /** The service reads go to, whether or not it's ready. */
    @Volatile
    var service: S? = null
        private set

    @Volatile
    var generation: Long? = null
        private set

    /** The chains ready in the state [service]'s binding last published. */
    @Volatile
    var readyChains: Set<Long> = emptySet()
        private set

    fun connected(owner: Any, service: S) = synchronized(lock) {
        byOwner[owner] = Binding(service, ++seq)
        refresh()
    }

    fun onState(owner: Any, info: MyotisInfo) = synchronized(lock) {
        byOwner[owner]?.info = info
        refresh()
    }

    fun disconnected(owner: Any) = synchronized(lock) {
        byOwner.remove(owner)
        refresh()
    }

    private fun isReady(b: Binding<S>) = b.info?.let(ready) == true

    private fun refresh() {
        val all = byOwner.values.sortedByDescending { it.seq }
        val pick = all.firstOrNull(::isReady) ?: all.firstOrNull()
        val readyNow = pick != null && isReady(pick)
        generation = when {
            !readyNow -> null
            pick !== current || generation == null -> ++counter
            else -> generation
        }
        current = pick
        service = pick?.service
        readyChains = pick?.info?.takeIf { it.status == MyotisStatus.Running }?.chains
            ?.filter { it.ready }?.mapTo(HashSet()) { it.chainId }.orEmpty()
    }
}
