package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    private var era = 0L

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
        era = { era },
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
        era++
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
}
