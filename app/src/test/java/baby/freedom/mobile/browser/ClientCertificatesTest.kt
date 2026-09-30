package baby.freedom.mobile.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
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
    }

    @Test
    fun `a refusal answers the requests already waiting, and the next one asks again`() {
        val c = ClientCertChoices()
        val asking = c.ticket()
        val queued = c.ticket()
        // Deny, Back, or "no certificates" before one is installed.
        c.answered("mtls.example", 443, null, c.generation)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, asking))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, queued))
        // A reload (or another tab) after the answer: asked again, so a
        // certificate installed since, or an accidental Deny, isn't a dead end.
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, c.ticket()))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
        // …and picking one then holds.
        c.answered("mtls.example", 443, "alice", c.generation)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "mtls.example", 443, queued))
    }

    @Test
    fun `a request queued behind a refused chooser takes the refusal, a later one opens its own`() = runBlocking {
        val c = ClientCertChoices()
        val lock = Mutex()
        val onScreen = MutableStateFlow<Long?>(2L)
        val queuedTicket = c.ticket()
        val first = chooseInTurn(
            c, lock, "mtls.example", 443, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
            c.ticket(),
        ) { null }
        assertEquals(ClientCertPlan.Refuse, first)
        var opened = 0
        val queued = chooseInTurn(
            c, lock, "mtls.example", 443, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
            queuedTicket,
        ) { opened++; "alice" }
        assertEquals(ClientCertPlan.Refuse, queued)
        assertEquals(0, opened)
        val later = chooseInTurn(
            c, lock, "mtls.example", 443, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
            c.ticket(),
        ) { opened++; "alice" }
        assertEquals(ClientCertPlan.Send("alice"), later)
        assertEquals(1, opened)
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

    @Test
    fun `a pick answered after a clear isn't kept in WebView's table`() {
        // No private tab open: a pick made since the last clear stays in
        // WebView's table (normal tabs reuse it).
        val before = ClientCertificates.generation
        assertFalse(ClientCertificates.emptiesTableAfterProceed(before))
        // The chooser (or the key read) was still going when site data
        // was cleared: its request is answered, then the table emptied.
        ClientCertificates.clear()
        assertTrue(ClientCertificates.emptiesTableAfterProceed(before))
        assertFalse(ClientCertificates.emptiesTableAfterProceed(ClientCertificates.generation))
    }

    @Test
    fun `a refusal kept by WebView is emptied on the browser's next load, not before`() {
        ClientCertificates.clear()
        assertFalse(ClientCertificates.emptiesTableOnLoad)
        // A Deny, a "no certificates" or a private tab: Chromium files the
        // empty answer, and a server that only requests one never asks again
        // on its own. It stays until the user loads something...
        ClientCertificates.refused()
        assertTrue(ClientCertificates.emptiesTableOnLoad)
        // ...which empties the table once.
        ClientCertificates.onBrowserLoad()
        assertFalse(ClientCertificates.emptiesTableOnLoad)
        // Clearing site data empties it too.
        ClientCertificates.refused()
        ClientCertificates.clear()
        assertFalse(ClientCertificates.emptiesTableOnLoad)
    }

    @Test
    fun `a chooser that never answers doesn't hold the lock once its tab closes`() = runBlocking {
        val c = ClientCertChoices()
        val lock = Mutex()
        val withdrawn = MutableStateFlow(false)
        val first = async {
            chooseInTurn(
                c, lock, "mtls.example", 443, 2L,
                MutableStateFlow(2L), MutableStateFlow(false), withdrawn,
            ) { awaitCancellation() }
        }
        yield()
        assertTrue(lock.isLocked)
        withdrawn.value = true
        assertEquals(ClientCertPlan.SendNone, withTimeout(2_000) { first.await() })
        assertFalse(lock.isLocked)
        // Another tab's request gets its own chooser.
        val next = withTimeout(2_000) {
            chooseInTurn(
                c, lock, "other.example", 443, 3L,
                MutableStateFlow(3L), MutableStateFlow(false), MutableStateFlow(false),
            ) { "bob" }
        }
        assertEquals(ClientCertPlan.Send("bob"), next)
    }

    @Test
    fun `a chooser gone without a callback gives up once the browser is back in front`() = runBlocking {
        val resumes = MutableStateFlow(0)
        val picked = CompletableDeferred<String?>()
        val lock = Mutex()
        val c = ClientCertChoices()
        val plan = async {
            chooseInTurn(
                c, lock, "mtls.example", 443, 2L,
                MutableStateFlow(2L), MutableStateFlow(false), MutableStateFlow(false),
            ) { awaitChooserAnswer(picked, resumes, launchedAt = 0, graceMs = 50) }
        }
        yield()
        // Still covered by the chooser: keeps waiting.
        kotlinx.coroutines.delay(200)
        assertFalse(plan.isCompleted)
        // KeyChainActivity killed; our screen resumes and no callback comes.
        resumes.value = 1
        assertEquals(ClientCertPlan.SendNone, withTimeout(2_000) { plan.await() })
        assertFalse(lock.isLocked)
        // Nothing remembered: the next request asks again.
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443))
    }

    @Test
    fun `an answer arriving just after the browser resumes still counts`() = runBlocking {
        val resumes = MutableStateFlow(0)
        val picked = CompletableDeferred<String?>()
        val answer = async { awaitChooserAnswer(picked, resumes, launchedAt = 0, graceMs = 1_000) }
        yield()
        resumes.value = 1
        yield()
        picked.complete("alice")
        assertEquals("alice", withTimeout(2_000) { answer.await() })
    }
}
