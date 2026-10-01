package baby.freedom.mobile.wallet.ledger

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A Ledger over USB (#319), with a fake pipe standing in for Android's
 * USB host API: an APDU goes out as HID packets and its answer comes
 * back; an unplug mid-answer fails it at once with words that say so,
 * and the interface is let go; a device that says nothing times out;
 * a cancel ends the wait.
 */
class LedgerUsbLinkTest {
    /** A plugged-in Ledger that answers each APDU with [answer]'s packets, unless told otherwise. */
    private class FakePipe(val answer: (ByteArray) -> ByteArray?) : LedgerUsbLink.Pipe {
        val written = ArrayList<ByteArray>()
        private val pending = ConcurrentLinkedQueue<ByteArray>()
        private var reader = LedgerHidFraming.Reader()

        @Volatile var plugged = true
        val releases = AtomicInteger()
        val reads = AtomicInteger()

        /** Unplugged once this many reads have been made (after the drain). */
        @Volatile var unplugAfterReads = Int.MAX_VALUE

        override fun write(buf: ByteArray, timeoutMs: Int): Int {
            if (!plugged) return -1
            written += buf.copyOf()
            reader.add(buf.copyOf())?.let { apdu ->
                reader = LedgerHidFraming.Reader()
                answer(apdu)?.let { pending.addAll(LedgerHidFraming.packets(it)) }
            }
            return buf.size
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (timeoutMs > 1 && reads.incrementAndGet() > unplugAfterReads) plugged = false
            if (!plugged) return -1
            val p = pending.poll() ?: run {
                Thread.sleep(timeoutMs.toLong().coerceAtMost(20))
                return -1
            }
            p.copyInto(buf)
            return p.size
        }

        override fun attached() = plugged
        override fun release() {
            releases.incrementAndGet()
        }
    }

    private val apdu = byteArrayOf(0xe0.toByte(), 0x02, 0x00, 0x00, 0x05) + ByteArray(5) { it.toByte() }

    @Test
    fun `an APDU goes out as HID packets and its answer comes back`() = runBlocking {
        val long = ByteArray(200) { (it * 3).toByte() } + byteArrayOf(0x90.toByte(), 0x00)
        val pipe = FakePipe { long }
        val link = LedgerUsbLink(pipe)
        val big = ByteArray(300) { it.toByte() }
        assertArrayEquals(long, link.exchange(big, 5_000))
        assertEquals(LedgerHidFraming.packets(big).size, pipe.written.size)
        assertTrue(pipe.written.all { it.size == 64 })
        link.close()
        link.close()
        assertEquals("released once", 1, pipe.releases.get())
    }

    @Test
    fun `unplugged mid-answer, the operation fails at once as unplugged and the interface is let go`() = runBlocking {
        val pipe = FakePipe { null } // Waiting on the user's confirmation: nothing comes back yet.
        pipe.unplugAfterReads = 3
        val link = LedgerUsbLink(pipe)
        val started = System.nanoTime()
        try {
            link.exchange(apdu, 90_000)
            fail("answered")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
            assertTrue(e.ownWords)
            assertTrue(e.message, e.message.contains("unplugged"))
        } finally {
            link.close() // As Ledger.session's finally does.
        }
        assertTrue("failed within a second, not at the timeout", System.nanoTime() - started < 1_000_000_000L)
        assertEquals(1, pipe.releases.get())
        // Closed, it refuses at once.
        try {
            link.exchange(apdu, 1_000)
            fail("answered after close")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
    }

    @Test
    fun `plugged back in, a new link works`() = runBlocking {
        val first = FakePipe { null }.also { it.plugged = false }
        val link = LedgerUsbLink(first)
        try {
            link.exchange(apdu, 1_000)
            fail("answered while unplugged")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
        }
        link.close()
        val answer = byteArrayOf(0x01, 0x90.toByte(), 0x00)
        val again = LedgerUsbLink(FakePipe { answer })
        assertArrayEquals(answer, again.exchange(apdu, 1_000))
        again.close()
    }

    @Test
    fun `a Ledger that says nothing times out`() = runBlocking {
        val link = LedgerUsbLink(FakePipe { null })
        val started = System.nanoTime()
        try {
            link.exchange(apdu, 300)
            fail("answered")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.TIMEOUT, e.kind)
        }
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        link.close()
    }

    @Test
    fun `a packet that isn't part of the answer fails it`() = runBlocking {
        val pipe = FakePipe { null }
        val bad = LedgerUsbLink(object : LedgerUsbLink.Pipe by pipe {
            var sent = false
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                if (timeoutMs <= 1 || sent) return pipe.read(buf, timeoutMs)
                sent = true
                // The second packet of some answer, with no first.
                LedgerHidFraming.packets(ByteArray(100)).last().copyInto(buf)
                return 64
            }
        })
        try {
            bad.exchange(apdu, 5_000)
            fail("answered")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.DISCONNECTED, e.kind)
            assertTrue(e.cause is LedgerHidFraming.BadPacket)
        }
        bad.close()
    }

    @Test
    fun `a cancel ends the wait within a read slice`() = runBlocking {
        val link = LedgerUsbLink(FakePipe { null })
        val work = async(Dispatchers.Default) { link.exchange(apdu, 90_000) }
        delay(100)
        val started = System.nanoTime()
        work.cancel()
        try {
            work.await()
            fail("answered")
        } catch (_: CancellationException) {
        }
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
        link.close()
    }

    @Test
    fun `unplugged while Android asks for access, the wait ends at once`() = runBlocking {
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val plugged = java.util.concurrent.atomic.AtomicBoolean(true)
        val waiting = async(Dispatchers.Default) {
            val started = System.nanoTime()
            LedgerUsbLink.awaitAnswer(answered, 60_000, 50) { plugged.get() } to (System.nanoTime() - started) / 1_000_000
        }
        delay(200)
        plugged.set(false)
        val (got, ms) = waiting.await()
        assertEquals(false, got)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `an answer to Android's prompt ends the wait, and none runs out at the deadline`() = runBlocking {
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waiting = async(Dispatchers.Default) { LedgerUsbLink.awaitAnswer(answered, 60_000, 50) { true } }
        delay(120)
        answered.complete(Unit)
        assertEquals(true, waiting.await())
        val started = System.nanoTime()
        assertEquals(false, LedgerUsbLink.awaitAnswer(kotlinx.coroutines.CompletableDeferred(), 300, 50) { true })
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms in 250..1_500)
    }

    @Test
    fun `every USB access request has its own broadcast, so answering one prompt never ends another's wait`() {
        // R4-F1: two prompts up at once (two Ledgers tried together) mustn't share a pending intent.
        val a = LedgerUsbLink.permissionAction("baby.freedom.mobile", 1)
        val b = LedgerUsbLink.permissionAction("baby.freedom.mobile", 2)
        assertTrue(a != b)
        assertTrue(a.startsWith("baby.freedom.mobile.") && b.startsWith("baby.freedom.mobile."))
    }

    private class Opened : LedgerLink {
        val closes = AtomicInteger()
        override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray = error("unused")
        override fun close() { closes.incrementAndGet() }
    }

    @Test
    fun `a route let go while its Ledger is opening closes the link it opened`() = runBlocking {
        // R6-M1: a plain withContext would drop the opened link, leaving its connection open.
        val link = Opened()
        val opening = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            LedgerUsbLink.keptOrClosed(Dispatchers.IO) {
                opening.complete(Unit)
                release.await()
                link
            }
        }
        opening.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, link.closes.get())
    }

    @Test
    fun `an opened link is handed back as is when nothing cancels it`() = runBlocking {
        val link = Opened()
        val got = LedgerUsbLink.keptOrClosed(Dispatchers.IO) { link }
        assertTrue(got === link)
        assertEquals(0, link.closes.get())
    }
}
