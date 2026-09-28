package baby.freedom.mobile.node

import baby.freedom.mobile.ens.EnsLightClient
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Name resolution's line to the Myotis light client in the `:myotis`
 * process (#101): [EnsLightClient] over [IMyotisService.ethCall].
 *
 * `MainActivity` owns the binding and reports it here — the service when
 * it connects ([connected]), every state it publishes ([onState]), and
 * the end of the binding ([disconnected]). Ready means bound, running,
 * and Ethereum mainnet [baby.freedom.swarm.MyotisChainStatus.ready] —
 * the same gate the node screen's "serving" state uses. Each time that
 * flips (or the binding is replaced) there's a new [readyGeneration].
 */
object MyotisLink : EnsLightClient {
    private val lock = Any()

    @Volatile
    private var service: IMyotisService? = null
    private var info: MyotisInfo? = null
    private var counter = 0L

    @Volatile
    private var generation: Long? = null

    fun connected(service: IMyotisService) = synchronized(lock) {
        this.service = service
        info = null
        refresh(rebound = true)
    }

    fun onState(info: MyotisInfo) = synchronized(lock) {
        this.info = info
        refresh(rebound = false)
    }

    fun disconnected() = synchronized(lock) {
        service = null
        info = null
        refresh(rebound = true)
    }

    private fun refresh(rebound: Boolean) {
        val ready = service != null && info?.status == MyotisStatus.Running &&
            info?.chain(MyotisNetwork.Mainnet)?.ready == true
        generation = when {
            !ready -> null
            rebound || generation == null -> ++counter
            else -> generation
        }
    }

    override fun readyGeneration(): Long? = generation

    override fun ethCall(to: String, data: String, timeoutMs: Long): EnsLightClient.Call {
        val service = service ?: return EnsLightClient.Call.Unavailable("light client not connected")
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
