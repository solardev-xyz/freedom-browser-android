package baby.freedom.mobile.browser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCertificatesTest {

    @Test
    fun `a private tab sends none and is never asked, whatever a normal tab picked`() {
        val c = ClientCertChoices()
        assertEquals(ClientCertPlan.SendNone, c.planFor(private = true, "mtls.example", 443))
        c.answered("mtls.example", 443, "alice", c.generation)
        assertEquals(ClientCertPlan.SendNone, c.planFor(private = true, "mtls.example", 443))
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(private = false, "mtls.example", 443))
    }

    @Test
    fun `a normal tab asks until the user answers, then the answer holds per host and port`() {
        val c = ClientCertChoices()
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
        c.answered("mtls.example", 443, "alice", c.generation)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "MTLS.example", 443))
        // Another port, another host: their own question.
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 8443))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "other.example", 443))
        // Dismissing the chooser is an answer too: send none, don't ask again.
        c.answered("other.example", 443, null, c.generation)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "other.example", 443))
    }

    @Test
    fun `clearing site data forgets every answer, including one given by a chooser opened before`() {
        val c = ClientCertChoices()
        c.answered("mtls.example", 443, "alice", c.generation)
        c.answered("other.example", 443, null, c.generation)
        val openedAt = c.generation
        c.clear()
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "other.example", 443))
        c.answered("mtls.example", 443, "alice", openedAt)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
    }

    @Test
    fun `an unreadable pick is forgotten, so the next request asks again`() {
        val c = ClientCertChoices()
        c.answered("mtls.example", 443, "gone", c.generation)
        c.forget("mtls.example", 443)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
    }

    @Test
    fun `the chooser waits for its own tab on screen with no Android dialog up`() = runBlocking {
        val onScreen = MutableStateFlow<Long?>(1L)
        val dialog = MutableStateFlow(false)
        val withdrawn = MutableStateFlow(false)
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitChooserTurn(onScreen, dialog, 2L, withdrawn)
        }
        repeat(3) { yield() }
        assertFalse("must not open over another tab", wait.isCompleted)
        onScreen.value = null
        repeat(3) { yield() }
        assertFalse("must not open over a panel or another app", wait.isCompleted)
        dialog.value = true
        onScreen.value = 2L
        repeat(3) { yield() }
        assertFalse("must not open over Android's permission dialog", wait.isCompleted)
        dialog.value = false
        assertTrue(withTimeout(1_000) { wait.await() })
    }

    @Test
    fun `a closed tab's request stops waiting`() = runBlocking {
        val withdrawn = MutableStateFlow(false)
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitChooserTurn(MutableStateFlow(1L), MutableStateFlow(false), 2L, withdrawn)
        }
        withdrawn.value = true
        assertFalse(withTimeout(1_000) { wait.await() })
        // Withdrawn beats on screen.
        assertFalse(awaitChooserTurn(MutableStateFlow(2L), MutableStateFlow(false), 2L, MutableStateFlow(true)))
    }

    @Test
    fun `a tab closed while its chooser is up sends none and its pick isn't remembered`() = runBlocking {
        val c = ClientCertChoices()
        val withdrawn = MutableStateFlow(false)
        val plan = chooseInTurn(
            c, Mutex(), "mtls.example", 443, 2L,
            MutableStateFlow(2L), MutableStateFlow(false), withdrawn,
        ) {
            // The user closes the tab from the switcher, then picks.
            withdrawn.value = true
            "alice"
        }
        assertEquals(ClientCertPlan.SendNone, plan)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
    }

    @Test
    fun `a pick for a tab still open is sent and remembered`() = runBlocking {
        val c = ClientCertChoices()
        val plan = chooseInTurn(
            c, Mutex(), "mtls.example", 443, 2L,
            MutableStateFlow(2L), MutableStateFlow(false), MutableStateFlow(false),
        ) { "alice" }
        assertEquals(ClientCertPlan.Send("alice"), plan)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "mtls.example", 443))
    }

    @Test
    fun `a closed private tab stops counting as open`() {
        ClientCertificates.onPrivateTab(41L)
        ClientCertificates.onPrivateTab(42L)
        assertTrue(ClientCertificates.privateTabOpen)
        // What the host's real destroy does for every tab.
        for (id in listOf(41L, 42L)) ClientCertificates.onTabClosed(id)
        assertFalse(ClientCertificates.privateTabOpen)
    }
}
