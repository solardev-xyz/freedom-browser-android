package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CallWaiterTest {
    @Test
    fun `an answer lets go of the call before the waiter wakes`() {
        // R2-F2: a waiter that woke before the release could start its next
        // probe while this one still counted as in the engine.
        val released = AtomicBoolean(false)
        val sawReleased = AtomicBoolean(false)
        lateinit var waiter: CallWaiter
        waiter = CallWaiter {
            // The waiter must not have been woken yet.
            assertFalse(waiter.await(0))
            released.set(true)
        }
        val t = Thread {
            if (waiter.await(5_000)) sawReleased.set(released.get())
        }.apply { start() }
        Thread.sleep(50)
        waiter.deliver("{}")
        t.join(5_000)
        assertTrue(sawReleased.get())
        assertEquals("{}", waiter.answer)
    }

    @Test
    fun `an answer after the waiter gave up still lets go of the call`() {
        val released = AtomicBoolean(false)
        val waiter = CallWaiter { released.set(true) }
        assertFalse(waiter.await(10))
        waiter.deliver(null)
        assertTrue(released.get())
    }
}
