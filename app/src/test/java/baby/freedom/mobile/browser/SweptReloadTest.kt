package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.SweptReload.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweptReloadTest {

    @After
    fun tearDown() {
        UnverifiedOrigins.reset()
        UnverifiedOrigins.onSweep = null
    }

    private val origin = "https://bafya.ipfs.freedom.baby"
    private val page = "https://shop.example/checkout/result"

    /**
     * A tab as the tab host drives it: its navigations, a fake clock for
     * [SweptReload]'s deadlines, and its commit (`onPageStarted`), which
     * releases its hold.
     */
    private inner class Tab {
        val navigations = mutableListOf<Step>()
        private var now = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val swept = SweptReload(
            navigate = { navigations += it },
            schedule = { delay, action -> timers += (now + delay) to action },
        )

        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = timers.filter { it.first <= now }
                if (due.isEmpty()) return
                timers.removeAll(due)
                due.forEach { it.second() }
            }
        }

        fun commit() {
            swept.committed()
            UnverifiedOrigins.release(this)
        }
    }

    /** The external gateway served [origin] into [tab]'s frame; now switched away. */
    private fun sweepInto(tab: Tab) {
        UnverifiedOrigins.onSweep = { origins ->
            UnverifiedOrigins.hold(tab, origins, private = false)
            tab.swept.swept(page)
        }
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", origin)
        UnverifiedOrigins.sweep("") {}
        // The sweep's one-shot clear, taken by whoever asks first.
        assertTrue(UnverifiedOrigins.takeClearFor(origin))
    }

    @Test
    fun `a swept GET page is reloaded, and nothing else happens once it commits`() {
        val tab = Tab()
        sweepInto(tab)
        assertEquals(listOf(Step.RELOAD), tab.navigations)

        tab.commit()
        assertNull(tab.swept.step)
        // Released at the commit: the stale document's writes get one more clear.
        assertEquals(setOf(origin), UnverifiedOrigins.pendingClears())
        // Deadlines scheduled for the reload are spent.
        tab.advance(10 * SweptReload.OVERDUE_MS)
        assertEquals(listOf(Step.RELOAD), tab.navigations)
    }

    @Test
    fun `a swept POST result falls back to a GET, and clearing waits for its commit (R6-F1)`() {
        val tab = Tab()
        sweepInto(tab)
        // WebView asks to resend the POST; the host answers "don't resend".
        assertTrue(tab.swept.refused())
        assertEquals(listOf(Step.RELOAD, Step.GET), tab.navigations)
        assertEquals(page, tab.swept.address)

        // However long the GET takes, the stale frame's origin stays held:
        // not handed to the one-shot clear while it may still write there.
        tab.advance(SweptReload.OVERDUE_MS - 1)
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears())
        assertTrue(UnverifiedOrigins.isHeld(tab))
        assertTrue(UnverifiedOrigins.takeClearFor(origin, tab))

        tab.commit()
        assertFalse(UnverifiedOrigins.isHeld(tab))
        assertEquals(setOf(origin), UnverifiedOrigins.pendingClears())
        tab.advance(10 * SweptReload.OVERDUE_MS)
        assertEquals(listOf(Step.RELOAD, Step.GET), tab.navigations)
    }

    @Test
    fun `a reload that never commits is forced along before anything is cleared`() {
        val tab = Tab()
        sweepInto(tab)
        // No resubmission prompt, no commit: the reload was dropped some
        // other way. The deadline moves on to a GET, then to about:blank.
        tab.advance(SweptReload.OVERDUE_MS)
        assertEquals(listOf(Step.RELOAD, Step.GET), tab.navigations)
        // The GET started but never committed (a download, a 204).
        tab.advance(SweptReload.OVERDUE_MS)
        assertEquals(listOf(Step.RELOAD, Step.GET, Step.BLANK), tab.navigations)
        tab.advance(SweptReload.OVERDUE_MS)
        assertEquals(Step.BLANK, tab.navigations.last())
        // Held all along.
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears())
        assertTrue(UnverifiedOrigins.isHeld(tab))

        tab.commit()
        assertEquals(setOf(origin), UnverifiedOrigins.pendingClears())
        val count = tab.navigations.size
        tab.advance(10 * SweptReload.OVERDUE_MS)
        assertEquals(count, tab.navigations.size)
    }

    @Test
    fun `a stale document off http goes straight to about blank`() {
        val navigations = mutableListOf<Step>()
        val swept = SweptReload(navigate = { navigations += it }, schedule = { _, _ -> })
        swept.swept("data:text/html,x")
        swept.refused()
        assertEquals(listOf(Step.RELOAD, Step.BLANK), navigations)
    }

    @Test
    fun `a resubmission prompt outside a sweep's reload is not the sweep's`() {
        val navigations = mutableListOf<Step>()
        val swept = SweptReload(navigate = { navigations += it }, schedule = { _, _ -> })
        // The user's own reload of a POST page: left to WebView's default.
        assertFalse(swept.refused())
        swept.swept(page)
        assertTrue(swept.refused())
        // The GET can't prompt; a later prompt (the user's) doesn't skip it.
        assertFalse(swept.refused())
        assertEquals(listOf(Step.RELOAD, Step.GET), navigations)
    }

    @Test
    fun `a prompt for the user's own navigation after the sweep's reload isn't the sweep's`() {
        val navigations = mutableListOf<Step>()
        val timers = mutableListOf<() -> Unit>()
        lateinit var swept: SweptReload
        // The WebView's load overrides report every load, the sweep's own too.
        swept = SweptReload(
            navigate = { navigations += it; swept.navigationStarted() },
            schedule = { _, a -> timers += a },
        )
        swept.swept(page)
        // Before the reload commits, the user goes Back to a POST entry.
        swept.navigationStarted()
        // Its resubmission prompt doesn't send them to the stale address.
        assertFalse(swept.refused())
        assertEquals(listOf(Step.RELOAD), navigations)
        // If that Back never commits either, the deadline still moves on.
        timers.last()()
        assertEquals(listOf(Step.RELOAD, Step.GET), navigations)
    }

    @Test
    fun `the sweep's own reload, reported as a load, can still be refused`() {
        val navigations = mutableListOf<Step>()
        lateinit var swept: SweptReload
        swept = SweptReload(
            navigate = { navigations += it; swept.navigationStarted() },
            schedule = { _, _ -> },
        )
        swept.swept(page)
        assertTrue(swept.refused())
        assertEquals(listOf(Step.RELOAD, Step.GET), navigations)
    }

    @Test
    fun `a deadline from an earlier sweep doesn't act on a later one`() {
        val navigations = mutableListOf<Step>()
        val timers = mutableListOf<() -> Unit>()
        val swept = SweptReload(navigate = { navigations += it }, schedule = { _, a -> timers += a })
        swept.swept(page)
        swept.committed()
        swept.swept(page)
        timers.first()()
        assertEquals(listOf(Step.RELOAD, Step.RELOAD), navigations)
        timers.last()()
        assertEquals(listOf(Step.RELOAD, Step.RELOAD, Step.GET), navigations)
    }
}
