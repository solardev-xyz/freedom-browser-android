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
    fun `navigating drops the tab's queued reads before they reach the node`() {
        val adm = RadicleAdmission()
        val slots = fill(adm, tabB)
        val old = waitFor(adm, adm.requester(tabA))
        val otherTab = waitFor(adm, adm.requester(tabB))
        awaitWaiting(adm, 2)
        adm.navigationRequested(tabA)
        old.first.join(5_000)
        assertSame(Admit.Cancelled, old.second.single())
        assertEquals(1, adm.stats().second)
        // The incoming document's reads are its own, and the commit that
        // follows the request doesn't drop them.
        val fresh = waitFor(adm, adm.requester(tabA))
        awaitWaiting(adm, 2)
        adm.documentStarted(tabA)
        slots.forEach { it.release() }
        fresh.first.join(5_000)
        otherTab.first.join(5_000)
        assertTrue(fresh.second.single() is Admit.Granted)
        assertTrue(otherTab.second.single() is Admit.Granted)
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
    fun `a torn-down document is dropped even after its navigation's request`() {
        val adm = RadicleAdmission()
        adm.navigationRequested(tabA)
        val requester = adm.requester(tabA)
        adm.documentStarted(tabA, gone = true)
        assertSame(Admit.Cancelled, adm.admit(requester, write = false, waitMs = 1_000))
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
