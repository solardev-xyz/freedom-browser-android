package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BottomUiChannels]: which reply channels a detector request reaches,
 * across the orderings of readies, first paints and reports that the
 * platform allows (#69). Channels are plain strings named after their
 * document; "a dead channel drops the message" is the WebView's job.
 */
class BottomUiChannelsTest {

    /** Kotlin's side of one tab, driven the way [BrowserWebView] drives it. */
    private class Tab {
        val slot = BottomChromeSlot(newToken = generateSequence(1) { it + 1 }.map { "t$it" }.iterator()::next)
        val channels = BottomUiChannels<String>()

        /** (channel, token) for every start/probe request sent. */
        val sent = mutableListOf<Pair<String, String>>()

        private fun post(targets: List<String>) {
            val t = slot.token ?: return
            targets.forEach { sent += it to t }
        }

        fun pageStarted() {
            slot.startDocument()
            channels.startDocument()
        }

        fun ready(channel: String) = post(channels.onReady(channel, slot.installed))

        fun firstPaint() {
            slot.install() ?: return
            post(channels.targets)
        }

        fun probe() {
            if (slot.installed) post(channels.targets)
        }

        fun report(channel: String) = channels.onReport(channel)

        fun take() = sent.toList().also { sent.clear() }
    }

    @Test
    fun `ready before first paint waits, first paint starts it`() {
        val tab = Tab()
        tab.pageStarted()
        tab.ready("A")
        assertEquals(emptyList<Pair<String, String>>(), tab.take())
        tab.firstPaint()
        assertEquals(listOf("A" to "t1"), tab.take())
    }

    @Test
    fun `a painted document's late ready starts it`() {
        val tab = Tab()
        tab.pageStarted()
        tab.firstPaint()
        assertEquals(emptyList<Pair<String, String>>(), tab.take())
        tab.ready("A")
        assertEquals(listOf("A" to "t1"), tab.take())
    }

    @Test
    fun `the next document's early ready is not started with the painted document's token`() {
        // R1-F1 (a): B's ready arrives while A is installed and proved,
        // before onPageStarted(B). B must stay dormant until its own paint.
        val tab = Tab()
        tab.pageStarted()
        tab.ready("A")
        tab.firstPaint()
        tab.report("A")
        tab.take()

        tab.ready("B")
        assertEquals(emptyList<Pair<String, String>>(), tab.take())
        // A's own probes still reach only A.
        tab.probe()
        assertEquals(listOf("A" to "t1"), tab.take())

        tab.pageStarted()
        tab.firstPaint()
        assertEquals(listOf("B" to "t2"), tab.take())
    }

    @Test
    fun `an out-of-order ready from a skipped document can't take the channel`() {
        // R1-F1 (b): A -> B -> C, with B's ready delivered after C's. C's
        // start must still reach C, and its reports prove C's channel.
        val tab = Tab()
        tab.pageStarted()
        tab.ready("A")
        tab.firstPaint()
        tab.report("A")
        tab.pageStarted() // B
        tab.pageStarted() // C
        tab.ready("C")
        tab.ready("B")
        tab.take()

        tab.firstPaint()
        val sent = tab.take()
        assertEquals(setOf("C" to "t3", "B" to "t3"), sent.toSet())
        tab.report("C")
        assertEquals("C", tab.channels.proved)
        tab.probe()
        assertEquals(listOf("C" to "t3"), tab.take())
    }

    @Test
    fun `a new document forgets the proved channel but keeps waiting candidates`() {
        val tab = Tab()
        tab.pageStarted()
        tab.ready("A")
        tab.firstPaint()
        tab.report("A")
        tab.pageStarted()
        assertNull(tab.channels.proved)
        // A's channel was claimed by A's report: never a target again.
        assertEquals(emptyList<String>(), tab.channels.targets)
    }

    @Test
    fun `candidates are bounded, oldest dropped`() {
        val channels = BottomUiChannels<String>(maxCandidates = 2)
        channels.onReady("a", installed = false)
        channels.onReady("b", installed = false)
        channels.onReady("c", installed = false)
        assertEquals(listOf("b", "c"), channels.targets)
        // A repeat doesn't duplicate.
        channels.onReady("b", installed = false)
        assertEquals(listOf("c", "b"), channels.targets)
    }
}
