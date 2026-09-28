package baby.freedom.mobile.node

import baby.freedom.mobile.ens.EnsLightClient
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Name resolution's line to the Myotis light client in the `:myotis`
 * process (#101): [EnsLightClient] over [IMyotisService.ethCall].
 *
 * Every `MainActivity` instance owns its own binding and reports it here
 * under its own key — the service when it connects ([connected]), every
 * state it publishes ([onState]), and the end of the binding
 * ([disconnected]). There can be more than one at a time (an App Link
 * opens a second instance in the linking app's task), and one ending
 * leaves the others' in place ([MyotisBindings]). Ready means a binding
 * that is running with Ethereum mainnet
 * [baby.freedom.swarm.MyotisChainStatus.ready] — the same gate the node
 * screen's "serving" state uses. Each time that flips (or a different
 * binding takes over) there's a new [readyGeneration].
 */
object MyotisLink : EnsLightClient {
    private val bindings = MyotisBindings<IMyotisService> { info ->
        info.status == MyotisStatus.Running && info.chain(MyotisNetwork.Mainnet)?.ready == true
    }

    fun connected(owner: Any, service: IMyotisService) = bindings.connected(owner, service)

    fun onState(owner: Any, info: MyotisInfo) = bindings.onState(owner, info)

    fun disconnected(owner: Any) = bindings.disconnected(owner)

    override fun readyGeneration(): Long? = bindings.generation

    override fun ethCall(to: String, data: String, timeoutMs: Long): EnsLightClient.Call {
        val service = bindings.service ?: return EnsLightClient.Call.Unavailable("light client not connected", notReady = true)
        val answer = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val result = object : IMyotisCallResult.Stub() {
            override fun onResult(json: String?) {
                answer.set(json)
                done.countDown()
            }
        }
        return try {
            service.ethCall(MyotisNetwork.Mainnet.chainId, to, data, result)
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                EnsLightClient.parse(answer.get())
            } else {
                EnsLightClient.Call.Unavailable("no answer within ${timeoutMs}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EnsLightClient.Call.Unavailable("interrupted")
        } catch (e: Exception) {
            // DeadObjectException (`:myotis` exited) and friends.
            EnsLightClient.Call.Unavailable("light client unreachable: ${e.javaClass.simpleName}")
        }
    }
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
    }
}
