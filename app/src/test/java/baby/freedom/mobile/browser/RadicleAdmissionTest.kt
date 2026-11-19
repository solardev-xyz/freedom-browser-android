package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.RadicleAdmission.Admit
import baby.freedom.swarm.RadicleNode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop's `rad:` read limits on Android's Radicle calls (#533). */
class RadicleAdmissionTest {
    private val tabA = 1L
    private val tabB = 2L

    private fun granted(a: Admit): RadicleAdmission.Slot {
        assertTrue("expected a slot, got $a", a is Admit.Granted)
        return (a as Admit.Granted).slot
    }

    /** Fill every slot with [tab]'s reads; the slots, to release later. */
    private fun fill(adm: RadicleAdmission, tab: Long, n: Int = 2) =
        List(n) { granted(adm.admit(adm.requester(tab), write = false, waitMs = 1_000)) }

    /** Start a waiting [admit] on its own thread; its answer lands in the returned list. */
    private fun waitFor(
        adm: RadicleAdmission,
        requester: RadicleAdmission.Requester,
        write: Boolean = false,
        waitMs: Long = 5_000,
        order: MutableList<String>? = null,
        name: String = "",
    ): Pair<Thread, MutableList<Admit>> {
        val out = Collections.synchronizedList(mutableListOf<Admit>())
        val t = thread {
            val a = adm.admit(requester, write, waitMs)
            order?.add(name)
            out += a
            (a as? Admit.Granted)?.slot?.release()
        }
        return t to out
    }

    private fun awaitWaiting(adm: RadicleAdmission, n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (adm.stats().second != n) {
            check(System.nanoTime() < until) { "never saw $n waiting: ${adm.stats()}" }
            Thread.sleep(2)
        }
    }

    @Test
    fun `desktop's limits`() {
        val l = RadicleAdmission.Limits()
        assertEquals(2, l.inFlight)
        assertEquals(8, l.perRequester)
        assertEquals(32, l.queueMax)
        assertEquals(15_000L, l.waitMs)
        assertEquals(16 * 1024 * 1024, RadicleClient.MAX_ANSWER_BYTES)
        assertEquals(30_000L, RadicleClient.READ_TIMEOUT_MS)
    }

    @Test
    fun `the writes are exactly the provider's COB writes, all browser calls`() {
        assertTrue(RadicleNode.BROWSER_CALLS.containsAll(RadicleClient.WRITE_CALLS))
        assertEquals(setOf("createIssue", "commentIssue", "editIssueState", "commentPatch"), RadicleClient.WRITE_CALLS)
    }

    @Test
    fun `a tab with eight reads waiting is refused at once, another tab isn't`() {
        val adm = RadicleAdmission()
        val slots = fill(adm, tabA)
        val waiters = List(8) { waitFor(adm, adm.requester(tabA)) }
        awaitWaiting(adm, 8)
        val started = System.nanoTime()
        assertSame(Admit.Busy, adm.admit(adm.requester(tabA), write = false, waitMs = 5_000))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
        val other = waitFor(adm, adm.requester(tabB))
        awaitWaiting(adm, 9)
        slots.forEach { it.release() }
        (waiters + other).forEach { it.first.join(5_000) }
        assertTrue((waiters + other).all { it.second.single() is Admit.Granted })
        assertEquals(0 to 0, adm.stats())
    }

    @Test
    fun `no more than thirty-two wait in all`() {
        val adm = RadicleAdmission()
        val slots = fill(adm, tabA)
        val waiters = (10L until 42L).map { waitFor(adm, adm.requester(it)) }
        awaitWaiting(adm, 32)
        assertSame(Admit.Busy, adm.admit(adm.requester(99L), write = false, waitMs = 5_000))
        // A write the user allowed is not refused for a full queue.
        val write = waitFor(adm, adm.requester(99L), write = true)
        awaitWaiting(adm, 33)
        slots.forEach { it.release() }
        (waiters + write).forEach { it.first.join(5_000) }
        assertTrue(write.second.single() is Admit.Granted)
    }

    @Test
    fun `free slots go to the tabs in turn, not to whoever queued first`() {
        val adm = RadicleAdmission(RadicleAdmission.Limits(inFlight = 1))
        val slot = fill(adm, 0L, n = 1).single()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val waiters = mutableListOf<Thread>()
        for (i in 1..3) {
            waiters += waitFor(adm, adm.requester(tabA), order = order, name = "A$i").first
            awaitWaiting(adm, i)
        }
        waiters += waitFor(adm, adm.requester(tabB), order = order, name = "B1").first
        awaitWaiting(adm, 4)
        slot.release()
        waiters.forEach { it.join(5_000) }
        assertEquals(listOf("A1", "B1", "A2", "A3"), order.toList())
    }

    @Test
    fun `a read that gets no slot within its wait is refused as busy`() {
        val adm = RadicleAdmission(RadicleAdmission.Limits(waitMs = 50))
        val slots = fill(adm, tabA)
        val started = System.nanoTime()
        // Asked to wait 5 s, but a read waits no longer than the limit.
        assertSame(Admit.Busy, adm.admit(adm.requester(tabB), write = false, waitMs = 5_000))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        assertEquals(2 to 0, adm.stats())
        slots.forEach { it.release() }
    }

    @Test
    fun `a navigation's commit drops the old page's queued reads before they reach the node`() {
        val adm = RadicleAdmission()
        val slots = fill(adm, tabB)
        val old = waitFor(adm, adm.requester(tabA))
        val otherTab = waitFor(adm, adm.requester(tabB))
        awaitWaiting(adm, 2)
        adm.navigationRequested(tabA)
        // The page is still on screen until the commit: its read waits on.
        Thread.sleep(50)
        assertTrue(old.second.isEmpty())
        // The incoming document's reads, made before the commit reaches
        // the UI thread, are its own: the commit doesn't drop them.
        val fresh = waitFor(adm, adm.requester(tabA))
        awaitWaiting(adm, 3)
        adm.documentStarted(tabA)
        old.first.join(5_000)
        assertSame(Admit.Cancelled, old.second.single())
        assertEquals(2, adm.stats().second)
        slots.forEach { it.release() }
        fresh.first.join(5_000)
        otherTab.first.join(5_000)
        assertTrue(fresh.second.single() is Admit.Granted)
        assertTrue(otherTab.second.single() is Admit.Granted)
    }

    @Test
    fun `a navigation that never commits leaves the page's reads alone`() {
        // A download link, a 204, Stop: the page stays on screen.
        val adm = RadicleAdmission()
        val page = adm.requester(tabA)
        val running = granted(adm.admit(page, write = false, waitMs = 1_000))
        adm.navigationRequested(tabA)
        assertFalse(running.cancelled)
        val next = granted(adm.admit(page, write = false, waitMs = 1_000))
        // Going Home later drops what the page had from before the download.
        adm.documentStarted(tabA, blank = true)
        assertTrue(running.cancelled)
        assertTrue(next.cancelled)
        running.release()
        next.release()
    }

    @Test
    fun `a commit with no request seen drops the old document's reads`() {
        val adm = RadicleAdmission()
        val slots = fill(adm, tabB)
        val old = waitFor(adm, adm.requester(tabA))
        awaitWaiting(adm, 1)
        adm.documentStarted(tabA) // a service worker's answer, about:blank, a history entry
        old.first.join(5_000)
        assertSame(Admit.Cancelled, old.second.single())
        slots.forEach { it.release() }
    }

    @Test
    fun `a running read is told its document went away, and its slot is free once it returns`() {
        val adm = RadicleAdmission()
        val running = granted(adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        val write = granted(adm.admit(adm.requester(tabA), write = true, waitMs = 1_000))
        assertFalse(running.cancelled)
        adm.tabClosed(tabA)
        assertTrue(running.cancelled)
        // An allowed write isn't dropped with its page.
        assertFalse(write.cancelled)
        running.release()
        running.release() // idempotent
        write.release()
        assertEquals(0 to 0, adm.stats())
    }

    @Test
    fun `a later call from a document already gone is refused without a slot`() {
        val adm = RadicleAdmission()
        // serveApi reads repoInfo, then the endpoint: the second must not
        // run once the page has gone.
        val requester = adm.requester(tabA)
        granted(adm.admit(requester, write = false, waitMs = 1_000)).release()
        adm.documentStarted(tabA, gone = true)
        assertSame(Admit.Cancelled, adm.admit(requester, write = false, waitMs = 1_000))
        assertTrue(adm.admit(requester, write = true, waitMs = 1_000) is Admit.Granted)
    }

    @Test
    fun `a closed tab's later calls are refused`() {
        val adm = RadicleAdmission()
        val requester = adm.requester(tabA)
        granted(adm.admit(requester, write = false, waitMs = 1_000)).release()
        adm.tabClosed(tabA)
        assertSame(Admit.Cancelled, adm.admit(requester, write = false, waitMs = 1_000))
    }

    @Test
    fun `a closed tab's call captured after the close is refused too`() {
        // An interceptor call racing the close captures its requester late.
        val adm = RadicleAdmission()
        adm.tabClosed(tabA)
        assertSame(Admit.Cancelled, adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        adm.navigationRequested(tabA, "https://late.example/")
        assertSame(Admit.Cancelled, adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        assertEquals(0 to 0, adm.stats())
    }

    @Test
    fun `two navigations requested before either commits each keep their own reads`() {
        // B's commit reaches the UI thread only after C's request (R1-F1).
        val adm = RadicleAdmission()
        val page = granted(adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        adm.navigationRequested(tabA, "https://b.example/")
        adm.navigationRequested(tabA, "https://c.example/")
        adm.documentStarted(tabA, "https://b.example/")
        assertTrue(page.cancelled)
        val fromC = adm.requester(tabA)
        val c = granted(adm.admit(fromC, write = false, waitMs = 1_000))
        adm.documentStarted(tabA, "https://c.example/#top")
        assertFalse(c.cancelled)
        page.release()
        granted(adm.admit(fromC, write = false, waitMs = 1_000)).release()
        c.release()
        // The next commit with nothing requested is a document of its own.
        adm.documentStarted(tabA, "https://d.example/")
        assertSame(Admit.Cancelled, adm.admit(fromC, write = false, waitMs = 1_000))
    }

    @Test
    fun `redirected commits claim the requests in order`() {
        // Neither commit is on the URL that was requested.
        val adm = RadicleAdmission()
        adm.navigationRequested(tabA, "http://b.example/")
        adm.navigationRequested(tabA, "http://c.example/")
        adm.documentStarted(tabA, "https://b.example/")
        val c = granted(adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        adm.documentStarted(tabA, "https://c.example/")
        assertFalse(c.cancelled)
        c.release()
    }

    @Test
    fun `a commit no request was seen for doesn't drop a later request's reads`() {
        // A history entry or a service worker's answer commits after the
        // next navigation's request reached the interceptor.
        val adm = RadicleAdmission()
        val page = granted(adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        adm.navigationRequested(tabA, "https://c.example/")
        val c = granted(adm.admit(adm.requester(tabA), write = false, waitMs = 1_000))
        adm.documentStarted(tabA, "https://back.example/")
        assertTrue(page.cancelled)
        assertFalse(c.cancelled)
        adm.documentStarted(tabA, "https://other.example/")
        assertTrue(c.cancelled)
        page.release()
        c.release()
    }

    @Test
    fun `a commit on the URL of a superseded request drops it with the older`() {
        // B is abandoned for C; C commits.
        val adm = RadicleAdmission()
        adm.navigationRequested(tabA, "https://b.example/")
        val fromB = adm.requester(tabA)
        adm.navigationRequested(tabA, "https://c.example/")
        adm.documentStarted(tabA, "https://c.example/")
        assertSame(Admit.Cancelled, adm.admit(fromB, write = false, waitMs = 1_000))
        val c = adm.requester(tabA)
        // Nothing is left pending: the next request-less commit is its own.
        adm.documentStarted(tabA, "https://history.example/")
        assertSame(Admit.Cancelled, adm.admit(c, write = false, waitMs = 1_000))
    }

    @Test
    fun `a torn-down document is dropped even after its navigation's request`() {
        val adm = RadicleAdmission()
        adm.navigationRequested(tabA)
        val requester = adm.requester(tabA)
        adm.documentStarted(tabA, gone = true)
        assertSame(Admit.Cancelled, adm.admit(requester, write = false, waitMs = 1_000))
    }

    @Test
    fun `a new tab's blank page committing after the request doesn't take its place`() {
        // Seen on the emulator: a tab opened on an address asks for it,
        // then commits about:blank, then the address.
        val adm = RadicleAdmission()
        adm.navigationRequested(tabA)
        adm.documentStarted(tabA, blank = true)
        val incoming = adm.requester(tabA)
        adm.documentStarted(tabA)
        assertTrue(adm.admit(incoming, write = false, waitMs = 1_000) is Admit.Granted)
    }

    @Test
    fun `going Home to the blank page drops the page's reads`() {
        val adm = RadicleAdmission()
        val page = adm.requester(tabA)
        adm.documentStarted(tabA, blank = true)
        assertSame(Admit.Cancelled, adm.admit(page, write = false, waitMs = 1_000))
    }

    @Test
    fun `calls no tab made are never dropped`() {
        val adm = RadicleAdmission()
        val requester = adm.requester(null)
        assertEquals(RadicleAdmission.NO_TAB, requester.tab)
        val slot = granted(adm.admit(requester, write = false, waitMs = 1_000))
        adm.tabClosed(RadicleAdmission.NO_TAB)
        assertFalse(slot.cancelled)
        slot.release()
    }

    @Test
    fun `a waiting read is released as soon as a slot frees`() {
        val adm = RadicleAdmission(RadicleAdmission.Limits(inFlight = 1))
        val slot = fill(adm, tabA, n = 1).single()
        val got = CountDownLatch(1)
        val t = thread {
            val a = adm.admit(adm.requester(tabB), write = false, waitMs = 5_000)
            if (a is Admit.Granted) got.countDown()
            (a as? Admit.Granted)?.slot?.release()
        }
        awaitWaiting(adm, 1)
        assertFalse(got.await(50, TimeUnit.MILLISECONDS))
        slot.release()
        assertTrue(got.await(2, TimeUnit.SECONDS))
        t.join(1_000)
    }
}
