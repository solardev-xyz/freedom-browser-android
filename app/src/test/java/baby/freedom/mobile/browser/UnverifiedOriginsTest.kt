package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnverifiedOriginsTest {

    @After
    fun tearDown() {
        UnverifiedOrigins.reset()
        UnverifiedOrigins.onSweep = null
        UnverifiedOrigins.clock = { System.nanoTime() / 1_000_000 }
    }

    private val a = "https://bafya.ipfs.freedom.baby"
    private val b = "https://vitalik-eth.ens.freedom.baby"

    @Test
    fun `switching away from the gateway wipes the origins it served`() {
        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.record("https://gw.example", b)
        UnverifiedOrigins.record("https://gw.example", a)
        // Still on that gateway: nothing to do.
        assertEquals(emptySet<String>(), UnverifiedOrigins.sweep("https://gw.example") { error("no wipe") })

        val wiped = mutableSetOf<String>()
        UnverifiedOrigins.sweep("") { wiped += it }
        assertEquals(setOf(a, b), wiped)
        assertTrue(UnverifiedOrigins.snapshot().isEmpty())
        // Each swept origin's next document clears its storage, once.
        assertEquals(setOf(a, b), UnverifiedOrigins.pendingClears())
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor("https://other.ipfs.freedom.baby"))
        assertEquals(setOf(b), UnverifiedOrigins.pendingClears())
        // Nothing left to wipe the next time round.
        assertEquals(emptySet<String>(), UnverifiedOrigins.sweep("") { error("no wipe") })
    }

    @Test
    fun `another external gateway wipes the previous one's origins too`() {
        UnverifiedOrigins.record("https://gw.example", a)
        val wiped = mutableSetOf<String>()
        UnverifiedOrigins.sweep("https://other.example") { wiped += it }
        assertEquals(setOf(a), wiped)
    }

    @Test
    fun `a request resolved before a switch can't record against the old gateway`() {
        UnverifiedOrigins.sweep("https://gw.example") {}
        val token = UnverifiedOrigins.record("https://gw.example", a)
        assertNotNull(token)
        assertTrue(UnverifiedOrigins.isCurrent(token!!))

        UnverifiedOrigins.sweep("") {}
        // In flight across the sweep: its response must not be served…
        assertFalse(UnverifiedOrigins.isCurrent(token))
        // …and a record after the sweep is refused, not left unswept.
        assertNull(UnverifiedOrigins.record("https://gw.example", b))
        assertTrue(UnverifiedOrigins.snapshot().isEmpty())
        // Same gateway chosen again: a new era, the old token stays stale.
        UnverifiedOrigins.sweep("https://gw.example") {}
        assertFalse(UnverifiedOrigins.isCurrent(token))
        assertNotNull(UnverifiedOrigins.record("https://gw.example", b))
    }

    @Test
    fun `the switch itself is applied under the sweep, before records resume`() {
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", a)
        val order = mutableListOf<String>()
        UnverifiedOrigins.sweep("", apply = { order += "apply" }) { order += "wipe" }
        assertEquals(listOf("apply", "wipe"), order)
    }

    @Test
    fun `a sweep tells the tab host, which holds origins until the stale tab commits`() {
        var now = 0L
        UnverifiedOrigins.clock = { now }
        val tab = Any()
        UnverifiedOrigins.onSweep = { swept -> UnverifiedOrigins.hold(tab, swept) }
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.sweep("") {}

        // The one-shot clear, then again while the stale tab's document lives.
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(b))
        // The stale document is gone: the next document clears once more,
        // for whatever it wrote before going, and then that's it.
        UnverifiedOrigins.release(tab)
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(a))
        UnverifiedOrigins.release(tab)
        assertFalse(UnverifiedOrigins.takeClearFor(a))

        // A tab whose reload never commits doesn't hold the origin forever;
        // its hold ends like a release.
        UnverifiedOrigins.hold(tab, setOf(a))
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        now += 10_000
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(a))
    }

    @Test
    fun `a frame's writes after another tab took the one-shot clear are cleared at its release`() {
        // Tab A shows the origin, tab B embeds it in a frame; both held.
        val tabA = Any()
        val tabB = Any()
        UnverifiedOrigins.onSweep = { swept ->
            UnverifiedOrigins.hold(tabA, swept)
            UnverifiedOrigins.hold(tabB, swept)
        }
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.sweep("") {}

        // A's reload takes the one-shot clear, then commits.
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        UnverifiedOrigins.release(tabA)
        // B's stale frame writes storage now, then B's main frame commits:
        // its new frame on the origin must still get the cleanup page.
        UnverifiedOrigins.release(tabB)
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(a))
    }

    @Test
    fun `a tab that released is served another tab's hold only once (R5-F2)`() {
        val tabA = Any()
        val tabB = Any()
        UnverifiedOrigins.onSweep = { swept ->
            UnverifiedOrigins.hold(tabA, swept)
            UnverifiedOrigins.hold(tabB, swept)
        }
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.sweep("") {}

        // A's reload: the one-shot clear, and again while A holds it.
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabA))
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabA))
        UnverifiedOrigins.release(tabA)
        // Released: once more for B's hold (plus the re-queued clear),
        // then A's cleanup page's reload is served normally.
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabA))
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabA))
        assertFalse(UnverifiedOrigins.takeClearFor(a, tabA))
        assertFalse(UnverifiedOrigins.takeClearFor(a, tabA))
        // The holder itself is served it every time; a worker once.
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabB))
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabB))
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertFalse(UnverifiedOrigins.takeClearFor(a))
        // B commits: its stale frame's writes get cleared on the next visit.
        UnverifiedOrigins.release(tabB)
        assertTrue(UnverifiedOrigins.takeClearFor(a, tabA))
        assertFalse(UnverifiedOrigins.takeClearFor(a, tabA))
    }

    @Test
    fun `a worker fetch is placed by when it started, not when it was answered (R5-F3)`() {
        UnverifiedOrigins.sweep("https://gw.example") {}
        UnverifiedOrigins.record("https://gw.example", a)
        val tab = TabDocuments()
        // The outgoing document's frame fetch starts, then the user
        // navigates and the new document commits before the fetch is in.
        val started = DocumentClock.next()
        tab.mainFrameAnswered("https://example.org/")
        tab.committed("https://example.org/", null)
        UnverifiedOrigins.noteWorkerDocument(a, started)
        // An older fetch finishing later doesn't move the tick back.
        val later = DocumentClock.next()
        UnverifiedOrigins.noteWorkerDocument(a, later)
        UnverifiedOrigins.noteWorkerDocument(a, started)
        val anyTab = UnverifiedOrigins.takeWorkerDocuments(setOf(a))
        assertEquals(later, anyTab.getValue(a))
        assertTrue(tab.mayHoldWorkerFetchAt(later))

        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.noteWorkerDocument(a, started)
        assertFalse(tab.mayHoldWorkerFetchAt(UnverifiedOrigins.takeWorkerDocuments(setOf(a)).getValue(a)))
    }

    @Test
    fun `frame documents a service worker fetched count for every tab not navigated since`() {
        UnverifiedOrigins.sweep("https://gw.example") {}
        // An origin the external gateway never served isn't kept at all.
        UnverifiedOrigins.noteWorkerDocument(b, DocumentClock.next())
        assertTrue(UnverifiedOrigins.workerDocumentOrigins().isEmpty())

        val stayed = TabDocuments()
        val navigated = TabDocuments()
        UnverifiedOrigins.record("https://gw.example", a)
        UnverifiedOrigins.noteWorkerDocument(a, DocumentClock.next())
        // This tab has moved to a new document since the worker's fetch.
        navigated.mainFrameAnswered("https://example.org/")
        navigated.committed("https://example.org/", null)

        val anyTab = UnverifiedOrigins.takeWorkerDocuments(setOf(a, b))
        assertEquals(setOf(a), anyTab.keys)
        assertTrue(stayed.mayHoldWorkerFetchAt(anyTab.getValue(a)))
        assertFalse(navigated.mayHoldWorkerFetchAt(anyTab.getValue(a)))
        // A tab whose own requests never showed the origin is still reloaded.
        assertEquals(
            setOf(a),
            sweptOrigins(setOf(a), emptySet(), "https://example.org/", anyTab.keys),
        )
        // Handed over once, and nothing is left behind.
        assertTrue(UnverifiedOrigins.takeWorkerDocuments(setOf(a)).isEmpty())
        assertTrue(UnverifiedOrigins.workerDocumentOrigins().isEmpty())
    }

    @Test
    fun `a tab's frames count alongside its committed page`() {
        val frame = "https://bafyframe.ipfs.freedom.baby"
        assertEquals(
            setOf(frame),
            sweptOrigins(setOf(frame, b), setOf(frame), "https://example.org/"),
        )
        assertEquals(setOf(b), sweptOrigins(setOf(b), emptySet(), "$b/page"))
    }

    @Test
    fun `a second sweep before the tab commits adds to its hold`() {
        val tab = Any()
        var sweeps = 0
        UnverifiedOrigins.onSweep = { swept -> sweeps++; UnverifiedOrigins.hold(tab, swept) }
        // External A serves a, external B serves b, then embedded: the
        // tab never commits in between.
        UnverifiedOrigins.sweep("https://a.example") {}
        UnverifiedOrigins.record("https://a.example", a)
        UnverifiedOrigins.sweep("https://b.example") {}
        UnverifiedOrigins.record("https://b.example", b)
        UnverifiedOrigins.sweep("") {}
        assertEquals(2, sweeps)
        // Take the one-shot clears (another tab's reload, say).
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        assertTrue(UnverifiedOrigins.takeClearFor(b))
        // Both are still held, and both get the cleanup page again at release.
        UnverifiedOrigins.release(tab)
        assertEquals(setOf(a, b), UnverifiedOrigins.pendingClears())
    }

    @Test
    fun `external gateway responses are marked Vary star so Cache Storage refuses them`() {
        assertEquals(mapOf("Vary" to "*"), varyAll(null))
        assertEquals(
            mapOf("Content-Type" to "text/html", "Vary" to "*"),
            varyAll(mapOf("Content-Type" to "text/html", "vary" to "Accept-Encoding")),
        )
    }

    @Test
    fun `service worker scripts are recognised by their request header`() {
        assertTrue(isServiceWorkerScript(mapOf("Service-Worker" to "script")))
        assertTrue(isServiceWorkerScript(mapOf("service-worker" to " Script ")))
        assertFalse(isServiceWorkerScript(mapOf("Accept" to "*/*")))
        assertFalse(isServiceWorkerScript(null))
    }
}
