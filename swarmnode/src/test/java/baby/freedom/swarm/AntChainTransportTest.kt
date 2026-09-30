package baby.freedom.swarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AntChainTransportTest {
    /** A reader whose reads block until cancelled, as the bridge's do while the router walks. */
    private class BlockingReader {
        @Volatile var cancelled = CountDownLatch(1)
        val entered = Semaphore(0)
        val reads = AtomicInteger(0)
        val cancels = AtomicInteger(0)
        fun read(body: String): String {
            reads.incrementAndGet()
            val gate = cancelled
            entered.release()
            return if (gate.await(30, TimeUnit.SECONDS)) CANCELLED else """{"result":"0x1"}"""
        }
        fun cancel() {
            cancels.incrementAndGet()
            val gate = cancelled
            cancelled = CountDownLatch(1)
            gate.countDown()
        }
    }

    @After
    fun uninstall() {
        AntChainTransport.install({ """{"result":"0x0"}""" })
    }

    private fun serve(body: String) = String(AntChainTransport.serve(body.toByteArray()), Charsets.UTF_8)

    @Test
    fun `a stop ends the reads in flight and refuses those that come in meanwhile, then serves again`() {
        val reader = BlockingReader()
        AntChainTransport.install(reader::read, reader::cancel)
        val inFlight = AtomicReference<String>()
        val t = Thread { inFlight.set(serve("""{"id":1}""")) }.apply { start() }
        assertTrue(reader.entered.tryAcquire(5, TimeUnit.SECONDS))

        val during = AtomicReference<String>()
        val readsBefore = AtomicInteger()
        val took = measure {
            AntChainTransport.whileStopping {
                // A read that comes in after the cancel (ant's background
                // task, a gateway handler finishing) never reaches the reader.
                readsBefore.set(reader.reads.get())
                during.set(serve("""{"id":2}"""))
            }
        }
        t.join(5_000)
        assertEquals(CANCELLED, inFlight.get())
        assertTrue(during.get(), "the Swarm node is stopping" in during.get())
        assertEquals(readsBefore.get(), reader.reads.get())
        assertTrue("took $took ms", took < 1_000)

        // After the stop, reads are served as usual.
        AntChainTransport.install({ """{"result":"0x2"}""" })
        assertEquals("""{"result":"0x2"}""", serve("""{"id":3}"""))
    }

    @Test
    fun `a read that got past the check just before the stop began is cancelled once it reaches the reader`() {
        // The reader's first cancel lands before the read is inside it
        // (the race R2-M1 names): the stop keeps cancelling until it's gone.
        val reader = BlockingReader()
        val atReader = CountDownLatch(1)
        val letIn = CountDownLatch(1)
        AntChainTransport.install({ body ->
            atReader.countDown()
            letIn.await(5, TimeUnit.SECONDS)
            reader.read(body)
        }, reader::cancel)
        val answer = AtomicReference<String>()
        val t = Thread { answer.set(serve("""{"id":1}""")) }.apply { start() }
        assertTrue(atReader.await(5, TimeUnit.SECONDS))
        val stopper = Thread {
            AntChainTransport.whileStopping { }
        }.apply { start() }
        // Let the read into the blocking part only after the stop's first cancel.
        Thread.sleep(50)
        assertTrue(reader.cancels.get() >= 1)
        letIn.countDown()
        stopper.join(AntChainTransport.DRAIN_MS)
        t.join(5_000)
        assertEquals(CANCELLED, answer.get())
        assertTrue(!stopper.isAlive)
    }

    @Test
    fun `a reader that ignores the cancel holds a stop for the drain limit only`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        AntChainTransport.install({ entered.countDown(); release.await(30, TimeUnit.SECONDS); """{"result":"0x1"}""" })
        val t = Thread { serve("""{"id":1}""") }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val took = measure { AntChainTransport.whileStopping { } }
        assertTrue("took $took ms", took in AntChainTransport.DRAIN_MS - 100..AntChainTransport.DRAIN_MS + 1_000)
        release.countDown()
        t.join(5_000)
    }

    private fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val CANCELLED = """{"error":{"code":-32002,"message":"Chain request failed: the Swarm node is stopping"}}"""
    }
}
