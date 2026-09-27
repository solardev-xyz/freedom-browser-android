package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnverifiedOriginsTest {

    @After
    fun tearDown() = UnverifiedOrigins.reset()

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
    fun `service worker scripts are recognised by their request header`() {
        assertTrue(isServiceWorkerScript(mapOf("Service-Worker" to "script")))
        assertTrue(isServiceWorkerScript(mapOf("service-worker" to " Script ")))
        assertFalse(isServiceWorkerScript(mapOf("Accept" to "*/*")))
        assertFalse(isServiceWorkerScript(null))
    }
}
