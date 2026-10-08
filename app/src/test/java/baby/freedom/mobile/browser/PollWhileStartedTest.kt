package baby.freedom.mobile.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #472: the node pages' gateway/RPC polls stop while the app is in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class PollWhileStartedTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun lifecycle(): LifecycleRegistry {
        val owner = object : LifecycleOwner {
            lateinit var registry: LifecycleRegistry
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry = LifecycleRegistry.createUnsafe(owner)
        return owner.registry
    }

    @Test
    fun `polls only while started, and again at once on return`() = runTest(main) {
        val lifecycle = lifecycle()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        var polls = 0
        val job = launch { lifecycle.pollWhileStarted(PERIOD) { polls++ } }

        advanceTimeBy(10 * PERIOD)
        assertEquals("nothing before the screen is started", 0, polls)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals("reads at once when started", 1, polls)
        advanceTimeBy(2 * PERIOD + PERIOD / 2)
        assertEquals("then once per period", 3, polls)

        // Home: the composition (and this coroutine) live on, the polls don't.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()
        advanceTimeBy(100 * PERIOD)
        assertEquals("no reads in the background", 3, polls)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals("reads at once on return", 4, polls)
        advanceTimeBy(PERIOD)
        runCurrent()
        assertEquals(5, polls)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCurrent()
        assertTrue("ends with the lifecycle", job.isCompleted)
    }

    @Test
    fun `a read still running when the app stops is cancelled`() = runTest(main) {
        val lifecycle = lifecycle()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        var started = 0
        var finished = 0
        val job = launch {
            lifecycle.pollWhileStarted(PERIOD) {
                started++
                kotlinx.coroutines.delay(PERIOD / 2)
                finished++
            }
        }
        runCurrent()
        assertEquals(1, started)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        advanceTimeBy(100 * PERIOD)
        assertEquals(1, started)
        assertEquals(0, finished)
        job.cancel()
    }

    @Test
    fun `a backing-off poll reads only while started, keeps its pace, and stops when done`() = runTest(main) {
        val lifecycle = lifecycle()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val at = mutableListOf<Long>()
        var due = true
        val job = launch {
            lifecycle.pollWhileStartedBackingOff(PERIOD, 4 * PERIOD) {
                at += testScheduler.currentTime
                due
            }
        }
        runCurrent()
        advanceTimeBy(3 * PERIOD + 1)
        assertEquals("waits double each time", listOf(0L, PERIOD, 3 * PERIOD), at)

        // Home with a send still pending: no chain reads in the background.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()
        advanceTimeBy(100 * PERIOD)
        assertEquals(3, at.size)

        // Back: reads at once, then carries on at the backed-off pace, capped.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        val back = testScheduler.currentTime
        assertEquals(back, at.last())
        advanceTimeBy(4 * PERIOD + 1)
        assertEquals("the wait so far is kept", back + 4 * PERIOD, at.last())
        advanceTimeBy(4 * PERIOD + 1)
        assertEquals("capped at the max", back + 8 * PERIOD, at.last())

        // Settled: no more reads while up, one on the next return.
        due = false
        advanceTimeBy(4 * PERIOD + 1)
        val n = at.size
        advanceTimeBy(100 * PERIOD)
        assertEquals(n, at.size)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals(n + 1, at.size)
        job.cancel()
    }

    private companion object {
        const val PERIOD = 5_000L
    }
}
