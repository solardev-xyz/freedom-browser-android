package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The media Range buffer around a Hard reload's (#262) fresh fetch: no
 * other fetch for the URL, overlapping it, can buffer or serve a stale body.
 */
class MediaBodyBufferTest {

    private val url = "http://127.0.0.1:1633/bzz/abc/clip.mp4"
    private val pool = Executors.newCachedThreadPool()

    @Test
    fun `a buffered body is served until a fresh load fetches it again`() {
        val buffer = MediaBodyBuffer<String>()
        val fetches = mutableListOf<Boolean>()
        assertEquals("v1", buffer.load(url, fresh = false) { fetches += it; "v1" })
        assertEquals("v1", buffer.load(url, fresh = false) { fetches += it; "x" })
        assertEquals("v2", buffer.load(url, fresh = true) { fetches += it; "v2" })
        assertEquals("v2", buffer.load(url, fresh = false) { fetches += it; "x" })
        assertEquals(listOf(false, true), fetches)
    }

    @Test
    fun `a request overlapping the fresh fetch waits for it instead of fetching`() {
        val buffer = MediaBodyBuffer<String>()
        buffer.load(url, fresh = false) { "stale" }
        val freshStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fetches = Collections.synchronizedList(mutableListOf<Boolean>())
        val fresh = pool.submit<String?> {
            buffer.load(url, fresh = true) {
                fetches += it
                freshStarted.countDown()
                release.await()
                "fresh"
            }
        }
        assertTrue(freshStarted.await(5, TimeUnit.SECONDS))
        // The document's next Range request, while the fresh fetch runs.
        val overlapping = pool.submit<String?> {
            buffer.load(url, fresh = false) { fetches += it; "stale-from-proxy" }
        }
        Thread.sleep(100)
        assertTrue(!overlapping.isDone)
        release.countDown()
        assertEquals("fresh", fresh.get(5, TimeUnit.SECONDS))
        assertEquals("fresh", overlapping.get(5, TimeUnit.SECONDS))
        assertEquals(listOf(true), fetches.toList())
        assertEquals("fresh", buffer.load(url, fresh = false) { "x" })
    }

    @Test
    fun `a fetch started before the fresh one can't buffer its body when it finishes last`() {
        val buffer = MediaBodyBuffer<String>()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        // Another tab's plain request, already at the gateway.
        val old = pool.submit<String?> {
            buffer.load(url, fresh = false) {
                oldStarted.countDown()
                releaseOld.await()
                "stale"
            }
        }
        assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
        assertEquals("fresh", buffer.load(url, fresh = true) { "fresh" })
        releaseOld.countDown()
        // Its own request gets what it fetched, but the buffer keeps the fresh body.
        assertEquals("stale", old.get(5, TimeUnit.SECONDS))
        assertEquals("fresh", buffer.load(url, fresh = false) { "x" })
    }

    @Test
    fun `a waiter whose fresh fetch failed fetches past the caches itself`() {
        val buffer = MediaBodyBuffer<String>()
        val freshStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fresh = pool.submit<String?> {
            buffer.load(url, fresh = true) {
                freshStarted.countDown()
                release.await()
                null
            }
        }
        assertTrue(freshStarted.await(5, TimeUnit.SECONDS))
        val noCache = AtomicInteger(-1)
        val waiter = pool.submit<String?> {
            buffer.load(url, fresh = false) { noCache.set(if (it) 1 else 0); "retried" }
        }
        Thread.sleep(100)
        release.countDown()
        assertNull(fresh.get(5, TimeUnit.SECONDS))
        assertEquals("retried", waiter.get(5, TimeUnit.SECONDS))
        assertEquals(1, noCache.get())
        assertEquals("retried", buffer.load(url, fresh = false) { "x" })
    }

    @Test
    fun `an older fresh fetch superseded by a newer one doesn't buffer`() {
        val buffer = MediaBodyBuffer<String>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = pool.submit<String?> {
            buffer.load(url, fresh = true) {
                firstStarted.countDown()
                releaseFirst.await()
                "first"
            }
        }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        assertEquals("second", buffer.load(url, fresh = true) { "second" })
        releaseFirst.countDown()
        assertEquals("first", first.get(5, TimeUnit.SECONDS))
        assertEquals("second", buffer.load(url, fresh = false) { "x" })
    }
}
