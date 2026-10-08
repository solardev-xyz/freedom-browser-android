package baby.freedom.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #480 R1-M1: a visit or icon asked for before a history delete never lands after it. */
class HistoryWriteGateTest {
    @Test
    fun `a write asked for before a delete and run after it is dropped`() = runTest {
        val gate = HistoryWriteGate()
        val ticket = gate.ticket()
        gate.revoke()
        gate.forget { }
        var wrote = false
        assertFalse(gate.write(ticket) { wrote = true })
        assertFalse(wrote)
    }

    @Test
    fun `a write asked for after the delete goes in`() = runTest {
        val gate = HistoryWriteGate()
        gate.revoke()
        gate.forget { }
        val ticket = gate.ticket()
        var wrote = false
        assertTrue(gate.write(ticket) { wrote = true })
        assertTrue(wrote)
    }

    @Test
    fun `a delete asked for while a write is running waits for it, then runs`() = runTest {
        val gate = HistoryWriteGate()
        val log = mutableListOf<String>()
        val inWrite = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ticket = gate.ticket()
        val write = launch {
            gate.write(ticket) {
                inWrite.complete(Unit)
                release.await()
                log += "write"
            }
        }
        inWrite.await()
        gate.revoke()
        val delete = launch { gate.forget { log += "delete" } }
        yield()
        // The delete can't start while the write holds the gate.
        assertEquals(emptyList<String>(), log)
        release.complete(Unit)
        write.join()
        delete.join()
        assertEquals(listOf("write", "delete"), log)
    }

    @Test
    fun `a write queued behind a running delete with an old ticket is dropped`() = runTest {
        val gate = HistoryWriteGate()
        val ticket = gate.ticket()
        val inDelete = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gate.revoke()
        val delete = launch {
            gate.forget {
                inDelete.complete(Unit)
                release.await()
            }
        }
        inDelete.await()
        var wrote = false
        val write = launch { gate.write(ticket) { wrote = true } }
        yield()
        release.complete(Unit)
        delete.join()
        write.join()
        assertFalse(wrote)
    }
}
