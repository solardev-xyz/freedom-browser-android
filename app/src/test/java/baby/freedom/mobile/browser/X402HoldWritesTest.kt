package baby.freedom.mobile.browser

import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** x402 holds reach the disk in order, and a failed write is tried again until it lands (#347 R1-M1). */
@OptIn(ExperimentalCoroutinesApi::class)
class X402HoldWritesTest {
    /** What's on "disk": the held origins. */
    private val disk = mutableSetOf<String>()
    private val tries = mutableListOf<Pair<String, Boolean>>()
    private var full = false
    /** Clears begun, the latest that landed, and whether one is being written. */
    private var clearsStarted = 0L
    private var clearLanded = 0L
    private var clearing = false

    private val writes = X402HoldWrites(
        write = { origin, held ->
            tries += origin to held
            if (full) {
                false
            } else {
                if (held) disk += origin else disk -= origin
                true
            }
        },
        era = { clearsStarted },
        cleared = { e ->
            when {
                clearLanded > e -> true
                clearing && clearsStarted > e -> null
                else -> false
            }
        },
    )

    @Test
    fun `holds and lifts are written in order`() = runTest {
        val job = launch { writes.run() }
        writes.send("a", held = true)
        writes.send("b", held = true)
        writes.send("a", held = false)
        runCurrent()
        assertEquals(setOf("b"), disk)
        writes.close()
        job.join()
    }

    @Test
    fun `a hold that failed to write is written once the disk takes it`() = runTest {
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        assertEquals(emptySet<String>(), disk)
        // Still failing: tried again, backing off.
        advanceTimeBy(X402HoldWrites.FIRST_RETRY_MS + 1)
        assertEquals(2, tries.size)
        // Space is freed: the next try lands, and a restart would still hold.
        full = false
        advanceTimeBy(X402HoldWrites.MAX_RETRY_MS + 1)
        assertEquals(setOf("a"), disk)
        val count = tries.size
        // Landed: not written again.
        advanceTimeBy(10 * X402HoldWrites.MAX_RETRY_MS)
        assertEquals(count, tries.size)
        writes.close()
        job.join()
    }

    @Test
    fun `retries back off up to the cap`() = runTest {
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        advanceTimeBy(30 * X402HoldWrites.MAX_RETRY_MS)
        // 1+2+4+…+32 s, then once a minute: far fewer than one try a second.
        assertEquals(true, tries.size in 30..40)
        writes.close()
        job.join()
    }

    @Test
    fun `a later lift replaces a hold still waiting to be written`() = runTest {
        disk += "a"
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        writes.send("a", held = false)
        full = false
        runCurrent()
        assertEquals(emptySet<String>(), disk)
        advanceTimeBy(10 * X402HoldWrites.MAX_RETRY_MS)
        // The stale hold never comes back.
        assertEquals(emptySet<String>(), disk)
        writes.close()
        job.join()
    }

    @Test
    fun `a hold waiting to be written is dropped once the store was cleared`() = runTest {
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        // Remove wallet clears the store.
        clearsStarted++
        clearLanded = clearsStarted
        full = false
        advanceTimeBy(10 * X402HoldWrites.MAX_RETRY_MS)
        assertEquals(emptySet<String>(), disk)
        assertEquals(listOf("a" to true), tries)
        writes.close()
        job.join()
    }

    @Test
    fun `holds sent before the writer runs are written when it starts`() = runTest {
        writes.send("a", held = true)
        val job = launch { writes.run() }
        runCurrent()
        assertEquals(setOf("a"), disk)
        writes.close()
        job.join()
    }

    @Test
    fun `R2-M1 a hold waits while a clear is written, and is kept if the clear fails`() = runTest {
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        // Remove wallet starts clearing; the disk frees up meanwhile.
        clearsStarted++
        clearing = true
        full = false
        advanceTimeBy(10 * X402HoldWrites.MAX_RETRY_MS)
        // Not written over a clear that may yet land, nor dropped for one that may not.
        assertEquals(emptySet<String>(), disk)
        assertEquals(listOf("a" to true), tries)
        // The clear fails: the allowances it would have removed are still on
        // disk, so the hold must be too.
        clearing = false
        advanceTimeBy(X402HoldWrites.MAX_RETRY_MS + 1)
        assertEquals(setOf("a"), disk)
        writes.close()
        job.join()
    }

    @Test
    fun `R2-M1 a hold waiting while a clear is written is dropped once the clear lands`() = runTest {
        val job = launch { writes.run() }
        full = true
        writes.send("a", held = true)
        runCurrent()
        clearsStarted++
        clearing = true
        full = false
        advanceTimeBy(5 * X402HoldWrites.MAX_RETRY_MS)
        clearing = false
        clearLanded = clearsStarted
        advanceTimeBy(5 * X402HoldWrites.MAX_RETRY_MS)
        assertEquals(emptySet<String>(), disk)
        assertEquals(listOf("a" to true), tries)
        writes.close()
        job.join()
    }

    /**
     * R2-F1: on a real single-thread dispatcher with scheduled timeouts (as
     * Dispatchers.Main), the retry deadline passes while the thread is busy,
     * and in that same busy task a hold is handed to the waiting writer. The
     * already-due timeout must not cancel the receive and lose that hold.
     */
    @Test
    fun `R2-F1 a hold sent as the retry deadline passes is not lost`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val lock = Any()
        val onDisk = mutableSetOf<String>()
        var diskFull = true
        val w = X402HoldWrites(write = { origin, held ->
            synchronized(lock) {
                if (diskFull) {
                    false
                } else {
                    if (held) onDisk += origin else onDisk -= origin
                    true
                }
            }
        })
        try {
            runBlocking {
                val job = launch(dispatcher) { w.run() }
                withContext(dispatcher) { w.send("a", held = true) }
                // Let "a" fail once, so the writer waits on the retry timeout.
                delay(200)
                withContext(dispatcher) {
                    // Busy past the deadline; then "b" is sent within the same task.
                    Thread.sleep(X402HoldWrites.FIRST_RETRY_MS + 300)
                    synchronized(lock) { diskFull = false }
                    w.send("b", held = true)
                }
                delay(X402HoldWrites.FIRST_RETRY_MS + 500)
                assertEquals(setOf("a", "b"), synchronized(lock) { onDisk.toSet() })
                w.close()
                job.join()
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `#380 a lift sent for one try isn't retried, quietly`() = runTest {
        var failures = 0
        val w = X402HoldWrites(
            write = { origin, held ->
                tries += origin to held
                !full
            },
            onFailed = { failures++ },
        )
        val job = launch { w.run() }
        full = true
        w.send("a", held = false, retry = false)
        w.send("b", held = false, retry = false)
        runCurrent()
        advanceTimeBy(10 * X402HoldWrites.MAX_RETRY_MS)
        assertEquals(listOf("a" to false, "b" to false), tries)
        assertEquals(0, failures)
        w.close()
        job.join()
    }

    @Test
    fun `#380 a one-try lift doesn't downgrade a retried lift of the same site still waiting`() = runTest {
        val job = launch { writes.run() }
        disk += "a"
        full = true
        writes.send("a", held = false)
        runCurrent()
        writes.send("a", held = false, retry = false)
        runCurrent()
        full = false
        advanceTimeBy(X402HoldWrites.MAX_RETRY_MS + 1)
        assertEquals(emptySet<String>(), disk)
        writes.close()
        job.join()
    }
}
