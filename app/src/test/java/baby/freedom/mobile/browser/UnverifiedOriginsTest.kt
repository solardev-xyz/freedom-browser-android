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
    fun `an origin only a private tab was served is swept but never written to disk`() {
        val tab = Any()
        UnverifiedOrigins.onSweep = { swept -> UnverifiedOrigins.hold(tab, swept) }
        UnverifiedOrigins.record("https://gw.example", a, private = true)
        UnverifiedOrigins.record("https://gw.example", b)
        // Known to this process, so a switch still sweeps it…
        assertEquals(setOf(a, b), UnverifiedOrigins.snapshot())
        // …but the CID a private tab opened isn't in the persisted list (#86).
        assertEquals(setOf(b) to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())

        val wiped = mutableSetOf<String>()
        UnverifiedOrigins.sweep("") { wiped += it }
        assertEquals(setOf(a, b), wiped)
        assertEquals(setOf(a, b), UnverifiedOrigins.pendingClears())
        assertEquals(emptySet<String>() to setOf(b), UnverifiedOrigins.persistedSnapshot())
        // Its cleanup taken, then the held tab released: queued again,
        // still in memory only.
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        UnverifiedOrigins.release(tab)
        assertTrue(a in UnverifiedOrigins.pendingClears())
        assertFalse(a in UnverifiedOrigins.persistedSnapshot().second)
    }

    @Test
    fun `a normal tab served a private tab's origin makes it a persisted one`() {
        UnverifiedOrigins.record("https://gw.example", a, private = true)
        UnverifiedOrigins.record("https://gw.example", a, private = true)
        assertEquals(emptySet<String>() to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())
        UnverifiedOrigins.record("https://gw.example", a)
        assertEquals(setOf(a) to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())
        // A private tab served it again afterwards doesn't take it back.
        UnverifiedOrigins.record("https://gw.example", a, private = true)
        assertEquals(setOf(a) to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())
    }

    @Test
    fun `a private tab served an origin a normal tab's earlier gateway left pending keeps that cleanup on disk`() {
        UnverifiedOrigins.record("https://gw-a.example", a)
        UnverifiedOrigins.sweep("https://gw-b.example") {}
        assertEquals(emptySet<String>() to setOf(a), UnverifiedOrigins.persistedSnapshot())
        // A private tab opens the same CID through the new gateway.
        UnverifiedOrigins.record("https://gw-b.example", a, private = true)
        // Gateway A's storage in the normal profile still gets its cleanup
        // after a restart; the origin is a persisted one again too.
        assertEquals(setOf(a) to setOf(a), UnverifiedOrigins.persistedSnapshot())
    }

    @Test
    fun `a private tab served an origin a normal tab still holds keeps its release persisted`() {
        val tab = Any()
        UnverifiedOrigins.onSweep = { swept -> UnverifiedOrigins.hold(tab, swept) }
        UnverifiedOrigins.record("https://gw-a.example", a)
        UnverifiedOrigins.sweep("https://gw-b.example") {}
        assertTrue(UnverifiedOrigins.takeClearFor(a, tab))
        UnverifiedOrigins.record("https://gw-b.example", a, private = true)
        UnverifiedOrigins.release(tab)
        assertTrue(a in UnverifiedOrigins.persistedSnapshot().second)
    }

    @Test
    fun `a private tab that took its own cleanup leaves the normal profile's pending on disk`() {
        UnverifiedOrigins.privateSessionStarted()
        UnverifiedOrigins.record("https://gw-1.example", a)
        UnverifiedOrigins.sweep("https://gw-2.example") {}
        assertEquals(emptySet<String>() to setOf(a), UnverifiedOrigins.persistedSnapshot())
        // A private tab's document request takes the private profile's
        // cleanup, then is served: the normal profile's is untouched.
        assertTrue(UnverifiedOrigins.takeClearFor(a, Any(), private = true))
        UnverifiedOrigins.record("https://gw-2.example", a, private = true)
        assertEquals(setOf(a) to setOf(a), UnverifiedOrigins.persistedSnapshot())
        UnverifiedOrigins.sweep("https://gw-3.example") {}
        assertEquals(emptySet<String>() to setOf(a), UnverifiedOrigins.persistedSnapshot())
    }

    @Test
    fun `a normal tab taking the cleanup leaves the private profile's (#351)`() {
        // A private tab opens the CID through external gateway A, which
        // writes to the private profile's storage; the tab navigates away
        // but the private session stays live.
        val normalTab = Any()
        val privateTab = Any()
        UnverifiedOrigins.privateSessionStarted()
        UnverifiedOrigins.sweep("https://gw-a.example") {}
        UnverifiedOrigins.record("https://gw-a.example", a, private = true)
        // Back to the embedded node.
        UnverifiedOrigins.sweep("") {}
        assertEquals(setOf(a), UnverifiedOrigins.pendingClears())
        assertEquals(setOf(a), UnverifiedOrigins.pendingClears(private = true))
        // A normal tab opens it first: its cleanup page clears the
        // default profile only…
        assertTrue(UnverifiedOrigins.takeClearFor(a, normalTab))
        assertFalse(UnverifiedOrigins.takeClearFor(a, normalTab))
        // …so the private tab still gets its own, once.
        assertTrue(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        assertFalse(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        // A private profile's service worker (no tab) is the same profile.
        assertFalse(UnverifiedOrigins.takeClearFor(a, null, private = true))
    }

    @Test
    fun `a private tab taking the cleanup leaves the normal profile's (#360)`() {
        val normalTab = Any()
        val privateTab = Any()
        UnverifiedOrigins.privateSessionStarted()
        // A normal tab's page from external gateway A writes to the
        // default profile's storage.
        UnverifiedOrigins.sweep("https://gw-a.example") {}
        UnverifiedOrigins.record("https://gw-a.example", a)
        UnverifiedOrigins.sweep("") {}
        // A private tab opens it first: its cleanup page clears the
        // private profile only…
        assertTrue(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        assertFalse(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        // …so the normal tab still gets the default profile's, from disk.
        assertEquals(emptySet<String>() to setOf(a), UnverifiedOrigins.persistedSnapshot())
        assertTrue(UnverifiedOrigins.takeClearFor(a, normalTab))
        assertFalse(UnverifiedOrigins.takeClearFor(a, normalTab))
        assertEquals(emptySet<String>() to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())
    }

    @Test
    fun `a sweep with no private session live queues nothing for the private profile`() {
        UnverifiedOrigins.record("https://gw-a.example", a)
        UnverifiedOrigins.sweep("") {}
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears(private = true))
        // A private tab opened afterwards starts on an empty profile.
        UnverifiedOrigins.privateSessionStarted()
        assertFalse(UnverifiedOrigins.takeClearFor(a, Any(), private = true))
        // The default profile's is still pending.
        assertTrue(UnverifiedOrigins.takeClearFor(a, Any()))
    }

    @Test
    fun `the private profile's cleanups and holds go when its session ends, never to disk`() {
        val privateTab = Any()
        UnverifiedOrigins.privateSessionStarted()
        UnverifiedOrigins.onSweep = { swept -> UnverifiedOrigins.hold(privateTab, swept, private = true) }
        UnverifiedOrigins.sweep("https://gw-a.example") {}
        UnverifiedOrigins.record("https://gw-a.example", a, private = true)
        UnverifiedOrigins.record("https://gw-a.example", b, private = true)
        UnverifiedOrigins.sweep("") {}
        assertEquals(setOf(a, b), UnverifiedOrigins.pendingClears(private = true))
        // Nothing a private tab was served is on disk, cleanups included.
        assertEquals(emptySet<String>() to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())

        UnverifiedOrigins.privateSessionEnded()
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears(private = true))
        assertFalse(UnverifiedOrigins.isHeld(privateTab))
        // Its tab released afterwards (closed with the session) queues
        // nothing for a profile that is gone.
        UnverifiedOrigins.release(privateTab)
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears(private = true))
        // A new session starts on an empty profile: nothing to clear there.
        UnverifiedOrigins.privateSessionStarted()
        assertFalse(UnverifiedOrigins.takeClearFor(a, Any(), private = true))
        assertEquals(emptySet<String>() to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())
    }

    @Test
    fun `a tab's hold serves and re-queues the cleanup in its own profile only`() {
        val normalTab = Any()
        val privateTab = Any()
        val otherPrivate = Any()
        val otherNormal = Any()
        UnverifiedOrigins.privateSessionStarted()
        UnverifiedOrigins.onSweep = { swept -> UnverifiedOrigins.hold(privateTab, swept, private = true) }
        UnverifiedOrigins.sweep("https://gw-a.example") {}
        UnverifiedOrigins.record("https://gw-a.example", a, private = true)
        UnverifiedOrigins.sweep("") {}
        // Both profiles' one-shot cleanups are taken.
        assertTrue(UnverifiedOrigins.takeClearFor(a, otherNormal))
        assertTrue(UnverifiedOrigins.takeClearFor(a, otherPrivate, private = true))
        // The private tab's stale document writes to the private profile:
        // its hold serves the cleanup page there…
        assertTrue(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        assertTrue(UnverifiedOrigins.takeClearFor(a, privateTab, private = true))
        assertTrue(UnverifiedOrigins.takeClearFor(a, otherPrivate, private = true))
        assertFalse(UnverifiedOrigins.takeClearFor(a, otherPrivate, private = true))
        assertTrue(UnverifiedOrigins.takeClearFor(a, null, private = true))
        assertFalse(UnverifiedOrigins.takeClearFor(a, null, private = true))
        // …and never in the default profile, which it can't write to.
        assertFalse(UnverifiedOrigins.takeClearFor(a, otherNormal))
        assertFalse(UnverifiedOrigins.takeClearFor(a, null))
        // Released: the next private document clears once more; the
        // default profile has nothing queued, in memory or on disk.
        UnverifiedOrigins.release(privateTab)
        assertEquals(setOf(a), UnverifiedOrigins.pendingClears(private = true))
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears())
        assertEquals(emptySet<String>() to emptySet<String>(), UnverifiedOrigins.persistedSnapshot())

        // A normal tab's hold is the default profile's, the other way round.
        UnverifiedOrigins.hold(normalTab, setOf(b))
        assertTrue(UnverifiedOrigins.takeClearFor(b, otherNormal))
        assertFalse(UnverifiedOrigins.takeClearFor(b, otherPrivate, private = true))
        UnverifiedOrigins.release(normalTab)
        assertEquals(setOf(b), UnverifiedOrigins.pendingClears())
        assertEquals(setOf(a), UnverifiedOrigins.pendingClears(private = true))
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

        // A hold has no timeout: however long the stale document takes
        // to go, its origin isn't handed back to the one-shot clear while
        // it may still write there (R6-F1) — the tab host makes sure it
        // goes ([SweptReload]).
        UnverifiedOrigins.hold(tab, setOf(a))
        assertTrue(UnverifiedOrigins.takeClearFor(a, tab))
        assertTrue(UnverifiedOrigins.takeClearFor(a, tab))
        assertEquals(emptySet<String>(), UnverifiedOrigins.pendingClears())
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
