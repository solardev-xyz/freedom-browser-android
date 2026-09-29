package baby.freedom.mobile.wallet

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #244 R1-M2: a Play services Task a caller stopped waiting for (timed out,
 * cancelled) keeps running, so the next Block Store call mustn't be sent
 * until it has finished — or a late store could land over a delete made next.
 */
class TaskOrderTest {
    @Test
    fun `a call waits for an earlier one still running, then goes`() = runBlocking {
        val order = TaskOrder(timeoutMs = 5_000)
        val first = TaskCompletionSource<Unit>()
        order.submit { first.task }
        var started = false
        val second = async(Dispatchers.Default, CoroutineStart.DEFAULT) {
            order.submit<Unit> { started = true; TaskCompletionSource<Unit>().apply { setResult(Unit) }.task }
        }
        repeat(20) { yield() }
        Thread.sleep(100)
        assertFalse("sent while the earlier write still ran", started)
        first.setException(RuntimeException("failed late")) // however it ends
        second.await()
        assertTrue(started)
    }

    @Test
    fun `a call behind one that never ends is refused, not sent`() = runBlocking {
        val order = TaskOrder(timeoutMs = 50)
        order.submit { TaskCompletionSource<Unit>().task }
        var started = false
        try {
            order.submit<Unit> { started = true; TaskCompletionSource<Unit>().task }
            fail("sent behind a stuck call")
        } catch (e: BackupNoAnswerException) {
            assertTrue(e is BackupUnavailableException)
        }
        assertFalse(started)
    }

    @Test
    fun `finished calls hold nothing up`() = runBlocking {
        val order = TaskOrder(timeoutMs = 50)
        repeat(3) { i ->
            val t: Task<Int> = order.submit { TaskCompletionSource<Int>().apply { setResult(i) }.task }
            assertEquals(i, t.result)
        }
    }
}
