package baby.freedom.mobile.wallet

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #244 R1-M2: a Play services Task a caller stopped waiting for (timed out,
 * cancelled) keeps running, so the next Block Store call mustn't be sent
 * until it has finished — or a late store could land over a delete made next.
 */
class TaskOrderTest {
    @Test
    fun `a call waits for an earlier one still running, then goes`() = runBlocking {
        val order = TaskOrder(abandonMs = 5_000)
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
    fun `a call whose deadline passes behind a running one is never sent`() = runBlocking {
        val order = TaskOrder(abandonMs = 60_000)
        order.submit { TaskCompletionSource<Unit>().task }
        var started = false
        val sent = withTimeoutOrNull(50) { order.submit<Unit> { started = true; TaskCompletionSource<Unit>().task } }
        assertNull(sent)
        assertFalse(started)
    }

    @Test
    fun `a call that never ends stops holding later ones back once abandoned`() = runBlocking {
        // #244 R2-M1: otherwise one lost Task refuses every Block Store call until the process dies.
        var clock = 0L
        val order = TaskOrder(abandonMs = 120_000, now = { clock })
        order.submit { TaskCompletionSource<Unit>().task }
        clock = 60_000
        var started = false
        assertNull(withTimeoutOrNull(50) { order.submit<Unit> { started = true; TaskCompletionSource<Unit>().task } })
        assertFalse("sent while the earlier call may still land", started)
        clock = 120_000
        order.submit<Unit> { started = true; TaskCompletionSource<Unit>().apply { setResult(Unit) }.task }
        assertTrue(started)
    }

    @Test
    fun `finished calls hold nothing up`() = runBlocking {
        val order = TaskOrder(abandonMs = 50)
        repeat(3) { i ->
            val t: Task<Int> = order.submit { TaskCompletionSource<Int>().apply { setResult(i) }.task }
            assertEquals(i, t.result)
        }
    }
}
