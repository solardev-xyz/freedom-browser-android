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
import org.junit.Before
import org.junit.Test

/** #541: how the Swarm node page keeps the ACT public key read. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActPublicKeyPollTest {
    private val main = StandardTestDispatcher()
    private val source = ActPublicKey.Source("https://bee.example", external = true, node = "https://bee.example")
    private val oldKey = "02" + "ab".repeat(32)
    private val newKey = "03" + "cd".repeat(32)

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
    fun `a key read is read again on return, so a node replaced behind the same URL shows its own`() = runTest(main) {
        val lifecycle = lifecycle()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        var answer: String? = oldKey
        var fetches = 0
        var shown: ActPublicKey.Read? = null
        val job = launch {
            ActPublicKey.keepRead(lifecycle, source, { fetches++; answer }) { shown = it }
        }
        runCurrent()
        assertEquals(1, fetches)
        assertEquals(oldKey, shown?.key)
        advanceTimeBy(10 * ActPublicKey.RETRY_MAX_MS)
        assertEquals("not asked again while on screen once read", 1, fetches)

        // In the background, the node behind the URL is replaced.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        answer = newKey
        runCurrent()
        advanceTimeBy(10 * ActPublicKey.RETRY_MAX_MS)
        assertEquals("nothing read in the background", 1, fetches)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals("read once on return", 2, fetches)
        assertEquals(newKey, shown?.key)
        assertEquals(source, shown?.source)
        advanceTimeBy(10 * ActPublicKey.RETRY_MAX_MS)
        assertEquals(2, fetches)
        job.cancel()
    }

    @Test
    fun `a re-read with no answer keeps the key and asks again, backing off`() = runTest(main) {
        val lifecycle = lifecycle()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        var answer: String? = oldKey
        var fetches = 0
        var shown: ActPublicKey.Read? = null
        val job = launch {
            ActPublicKey.keepRead(lifecycle, source, { fetches++; answer }) { shown = it }
        }
        runCurrent()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()
        answer = null
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals(2, fetches)
        assertEquals("a failed re-read doesn't blank the key", oldKey, shown?.key)

        advanceTimeBy(ActPublicKey.RETRY_MS + 1)
        assertEquals(3, fetches)
        answer = newKey
        advanceTimeBy(2 * ActPublicKey.RETRY_MS + 1)
        assertEquals(4, fetches)
        assertEquals(newKey, shown?.key)
        advanceTimeBy(10 * ActPublicKey.RETRY_MAX_MS)
        assertEquals("stops once answered", 4, fetches)
        job.cancel()
    }
}
