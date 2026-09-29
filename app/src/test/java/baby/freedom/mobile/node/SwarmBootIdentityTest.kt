package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SwarmBootIdentityTest {
    private val boot = SwarmBootIdentity()
    private var restarts = 0

    private fun reload(want: String, running: Boolean = true) =
        boot.restartIfStale({ want }, { running }, { restarts++ })

    @Test
    fun `a node not booted yet is left alone`() {
        assertFalse(reload("0xabc"))
        assertEquals(0, restarts)
    }

    @Test
    fun `a node booted as the stored identity is left alone, case aside`() {
        assertEquals("id", boot.boot { "0xAbC" to "id" })
        assertFalse(reload("0xabc"))
        assertEquals(0, restarts)
    }

    @Test
    fun `a running node booted as something else restarts, a stopped one doesn't`() {
        boot.boot { "" to null }
        assertFalse(reload("0xabc", running = false))
        assertTrue(reload("0xabc"))
        assertEquals(1, restarts)
    }

    @Test
    fun `a second reload before the restarted launch reads the store doesn't restart again`() {
        boot.boot { "" to null }
        assertTrue(reload("0xabc"))
        // The new launch hasn't read the store yet (R1-F2).
        assertFalse(reload("0xabc"))
        assertEquals(1, restarts)
        // Once it has, only a further change restarts it.
        boot.boot { "0xabc" to null }
        assertFalse(reload("0xabc"))
        assertTrue(reload(""))
        assertEquals(2, restarts)
    }

    @Test
    fun `concurrent reloads for one change restart once`() {
        boot.boot { "" to null }
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        repeat(32) {
            pool.execute {
                go.await()
                boot.restartIfStale({ "0xabc" }, { true }, { count.incrementAndGet() })
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, count.get())
    }
}
