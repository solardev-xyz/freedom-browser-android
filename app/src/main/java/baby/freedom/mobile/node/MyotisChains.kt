package baby.freedom.mobile.node

import android.util.Log
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.swarm.MyotisNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which chains of the Myotis light client run right now (#274): the node
 * page's Ethereum and Gnosis switches. `MainActivity` binds [MyotisService]
 * while at least one is on and relays the set to it
 * ([IMyotisService.setNetworks]).
 *
 * Process-wide, so every `MainActivity` instance (an App Link opens a
 * second one) binds for — and relays — the same chains. Not persisted:
 * each launch of the app process starts from the chains set to start at
 * launch ([NodeSettings.myotisStartOnLaunch]), as desktop's Nodes panel
 * and Settings → Startup do.
 */
object MyotisChains {
    private val chains = RunningChains()
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The chains that should run, in [MyotisNetwork] order; null until the launch choice is read. */
    val running: StateFlow<Set<MyotisNetwork>?> get() = chains.running

    /** Read the chains that start at launch, once per process. */
    fun init(settings: NodeSettings) {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            val atLaunch = try {
                settings.myotisStartOnLaunch.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable settings file starts nothing: the switches still work.
                Log.w("MyotisChains", "couldn't read the light client's start-at-launch chains", e)
                emptySet()
            }
            chains.launchWith(atLaunch)
        }
    }

    /** A chain's switch on the node page. */
    fun set(network: MyotisNetwork, run: Boolean) = chains.set(network, run)
}

/** [MyotisChains]' state, apart from the process-wide wiring. */
internal class RunningChains {
    private val _running = MutableStateFlow<Set<MyotisNetwork>?>(null)
    val running: StateFlow<Set<MyotisNetwork>?> = _running.asStateFlow()

    /** The launch choice, unless a switch was already flipped before it was read. */
    fun launchWith(chains: Set<MyotisNetwork>) {
        _running.compareAndSet(null, ordered(chains))
    }

    fun set(network: MyotisNetwork, run: Boolean) {
        _running.update { now ->
            val current = now.orEmpty()
            ordered(if (run) current + network else current - network)
        }
    }

    private fun ordered(chains: Set<MyotisNetwork>): Set<MyotisNetwork> =
        MyotisNetwork.entries.filterTo(LinkedHashSet()) { it in chains }
}
