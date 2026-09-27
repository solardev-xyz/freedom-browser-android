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
        UnverifiedOrigins.release(tab)
        assertFalse(UnverifiedOrigins.takeClearFor(a))

        // A tab whose reload never commits doesn't hold the origin forever.
        UnverifiedOrigins.hold(tab, setOf(a))
        assertTrue(UnverifiedOrigins.takeClearFor(a))
        now += 10_000
        assertFalse(UnverifiedOrigins.takeClearFor(a))
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
