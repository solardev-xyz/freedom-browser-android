package baby.freedom.mobile.browser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadCancellationTest {

    private class Closeable : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test
    fun `cancel before the job is registered stops it from starting`() {
        val c = DownloadCancellation()
        c.cancel(7)
        assertFalse(c.register(7, Job()))
    }

    @Test
    fun `a registered job is cancelled, and a later one for another id isn't`() {
        val c = DownloadCancellation()
        val job = Job()
        assertTrue(c.register(1, job))
        c.cancel(1)
        assertTrue(job.isCancelled)
        assertTrue(c.register(2, Job()))
    }

    @Test
    fun `forget drops an early-cancel mark`() {
        val c = DownloadCancellation()
        c.cancel(3)
        c.forget(3)
        assertTrue(c.register(3, Job()))
    }

    @Test
    fun `cancel closes what the job is blocked on`() {
        val c = DownloadCancellation()
        val job = Job()
        c.register(4, job)
        val conn = Closeable()
        c.track(4, job, conn)
        c.cancel(4)
        assertEquals(1, conn.closes)
    }

    @Test
    fun `something tracked after the cancel is closed at once`() {
        // The connect / header wait returned only after Cancel: the body
        // that turns up then must not stay open until GC.
        val c = DownloadCancellation()
        val job = Job()
        c.register(5, job)
        c.cancel(5)
        val body = Closeable()
        c.track(5, job, body)
        assertEquals(1, body.closes)
    }

    @Test
    fun `release forgets without closing`() {
        val c = DownloadCancellation()
        val job = Job()
        c.register(6, job)
        val body = Closeable()
        c.track(6, job, body)
        c.release(6)
        c.cancel(6)
        assertEquals(0, body.closes)
    }

    @Test
    fun `a completed job unregisters itself`() {
        val c = DownloadCancellation()
        val job = Job()
        c.register(8, job)
        job.complete()
        // Its id is no longer running: a cancel now is only a mark.
        c.cancel(8)
        assertFalse(c.register(8, Job()))
    }

    @Test
    fun `cancel unblocks a read waiting on a silent server`() = runBlocking {
        // A server that accepts and never answers — the "slow to send
        // headers" case. The blocked read must end on cancel, not at a
        // read timeout.
        ServerSocket(0).use { server ->
            val c = DownloadCancellation()
            val started = CountDownLatch(1)
            val ended = CountDownLatch(1)
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            val job = GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO, start = CoroutineStart.LAZY) {
                val socket = Socket("127.0.0.1", server.localPort)
                c.track(9, coroutineContext[Job], socket)
                started.countDown()
                runCatching { socket.getInputStream().read() }
                ended.countDown()
            }
            assertTrue(c.register(9, job))
            job.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            c.cancel(9)
            assertTrue("read still blocked after cancel", ended.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a stop is a cancel unless it's asked as a pause`() {
        val c = DownloadCancellation()
        val a = Job()
        val b = Job()
        c.register(10, a)
        c.register(11, b)
        c.cancel(10)
        c.cancel(11, DownloadStop.PAUSE)
        assertEquals(DownloadStop.CANCEL, c.stopOf(10))
        assertEquals(DownloadStop.PAUSE, c.stopOf(11))
        assertTrue(b.isCancelled)
    }

    @Test
    fun `a cancel on top of a pause wins, and a pause doesn't undo a cancel`() = runBlocking {
        // Jobs that, like a real download winding down in its catch
        // block, are still cancelling when the second stop lands.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun winding() = @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) GlobalScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
        }
        val c = DownloadCancellation()
        c.register(12, winding())
        c.cancel(12, DownloadStop.PAUSE)
        c.cancel(12)
        assertEquals(DownloadStop.CANCEL, c.stopOf(12))
        c.register(13, winding())
        c.cancel(13)
        c.cancel(13, DownloadStop.PAUSE)
        assertEquals(DownloadStop.CANCEL, c.stopOf(13))
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `an early pause is reported as a pause to the refused job`() {
        val c = DownloadCancellation()
        c.cancel(14, DownloadStop.PAUSE)
        assertFalse(c.register(14, Job()))
        assertEquals(DownloadStop.PAUSE, c.stopOf(14))
    }

    @Test
    fun `a resumed run isn't stopped by a mark aimed at its last run`() {
        // A second Pause tap landed after the download had paused: once
        // forgotten (as resume does), it can't stop the resumed run.
        val c = DownloadCancellation()
        val first = Job()
        c.register(15, first)
        c.cancel(15, DownloadStop.PAUSE)
        first.complete()
        c.cancel(15, DownloadStop.PAUSE)
        c.forget(15)
        assertTrue(c.register(15, Job()))
        assertEquals(null, c.stopOf(15))
    }
}
