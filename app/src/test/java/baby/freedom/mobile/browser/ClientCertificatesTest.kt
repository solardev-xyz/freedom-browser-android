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
        assertEquals(ClientCertPlan.SendNone, c.planFor(private = true, "mtls.example", 443, 2L))
        c.answered("mtls.example", 443, 2L, "alice", c.generation)
        assertEquals(ClientCertPlan.SendNone, c.planFor(private = true, "mtls.example", 443, 2L))
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(private = false, "mtls.example", 443, 2L))
    }

    @Test
    fun `a normal tab asks until the user answers, then the answer holds per host and port`() {
        val c = ClientCertChoices()
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
        c.answered("mtls.example", 443, 2L, "alice", c.generation)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "MTLS.example", 443, 2L))
        // Another port, another host: their own question.
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 8443, 2L))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "other.example", 443, 2L))
    }

    @Test
    fun `a refusal holds for the tab until the browser loads something in it`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        val asking = c.ticket()
        val queued = c.ticket()
        // Deny, Back, or "no certificates" before one is installed.
        c.answered("mtls.example", 443, 2L, null, c.generation)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, 2L, asking))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, 2L, queued))
        // The page keeps connecting (an iframe on a timer, a poll: a server
        // that requires a certificate asks on every new connection): no
        // chooser again (R4-F1)...
        repeat(3) { assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, 2L, c.ticket())) }
        // ...nor with a new host or port each time (wildcard DNS, R5-F1).
        for (n in 1..60) {
            assertEquals(ClientCertPlan.Refuse, c.planFor(false, "a$n.mtls.example", 8704, 2L, c.ticket()))
        }
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 8443, 2L, c.ticket()))
        // A load in another tab doesn't lift it here.
        c.loaded(3L)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, 2L, c.ticket()))
        // The tab's own reload asks again, so a certificate installed
        // since, or an accidental Deny, isn't a dead end...
        c.loaded(2L)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L, c.ticket()))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "a1.mtls.example", 8704, 2L, c.ticket()))
        // ...though a request from the old page, queued before the Deny,
        // still takes it.
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "mtls.example", 443, 2L, queued))
        // Picking one then holds for that server.
        c.answered("mtls.example", 443, 2L, "alice", c.generation)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "mtls.example", 443, 2L, c.ticket()))
    }

    @Test
    fun `a refusal is only its own tab's`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        c.loaded(3L)
        val otherTabsEarlier = c.ticket()
        c.answered("portal.example", 443, 2L, null, c.generation)
        // Tab B, open and loaded before the Deny, follows a link to the
        // same server: it asks (R5-M1). So does a request it already had
        // waiting, and a tab the browser never loaded anything in.
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "portal.example", 443, 3L, c.ticket()))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "portal.example", 443, 3L, otherTabsEarlier))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "portal.example", 443, 9L, c.ticket()))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "portal.example", 443, 2L, c.ticket()))
    }

    @Test
    fun `a Deny in a tab doesn't refuse a server that tab or any tab picked a certificate for`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        c.answered("portal.example", 8704, 3L, "alice", c.generation)
        c.answered("portal.example", 8704, 2L, "alice", c.generation)
        val queued = c.ticket()
        // An unrelated chooser the user rightly denies (a third-party
        // iframe): the portal they picked a certificate for keeps it
        // (R6-F1), even for a request queued behind that chooser.
        c.answered("tracker.example", 8705, 2L, null, c.generation)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "portal.example", 8704, 2L, c.ticket()))
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "portal.example", 8704, 2L, queued))
        // Anything without a pick is still refused, so the page can't
        // reopen the chooser (R5-F1).
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "tracker.example", 8705, 2L, c.ticket()))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "a7.tracker.example", 8705, 2L, c.ticket()))
    }

    @Test
    fun `a link the user taps after a Deny asks for its server, unless it is the one they refused`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        // No refusal holding: a tapped link changes nothing.
        assertEquals(false, c.followed(2L, "bank.example"))
        c.answered("tracker.example", 443, 2L, null, c.generation)
        // The user taps a link to a site they never refused (R6-M1): it asks.
        assertEquals(true, c.followed(2L, "Bank.example"))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "bank.example", 443, 2L, c.ticket()))
        // Every other server is still refused, the refused one too, even
        // through a tapped link to it.
        assertEquals(false, c.followed(2L, "tracker.example"))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "tracker.example", 443, 2L, c.ticket()))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "a1.tracker.example", 443, 2L, c.ticket()))
        // Denied there too: refused again, and a later tap doesn't lift it.
        c.answered("bank.example", 443, 2L, null, c.generation)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "bank.example", 443, 2L, c.ticket()))
        assertEquals(false, c.followed(2L, "bank.example"))
        // A pop-up the page opens inherits what the user refused, not
        // the links they followed.
        c.opened(11L, 2L)
        assertEquals(false, c.followed(11L, "tracker.example"))
        assertEquals(true, c.followed(11L, "shop.example"))
        // After a load, a new Deny starts afresh: an earlier tap to a
        // host doesn't exempt it.
        c.followed(2L, "news.example")
        c.loaded(2L)
        c.answered("tracker.example", 443, 2L, null, c.generation)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "news.example", 443, 2L, c.ticket()))
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "bank.example", 443, 2L, c.ticket()))
        assertEquals(true, c.followed(2L, "bank.example"))
    }

    @Test
    fun `a pop-up opened while its opener's refusal holds starts out refused`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        // Opened before any Deny: asks.
        c.opened(10L, 2L)
        c.answered("mtls.example", 443, 2L, null, c.generation)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 10L, c.ticket()))
        // Opened by the refused page: refused, any server, until its own load.
        c.opened(11L, 2L)
        assertEquals(ClientCertPlan.Refuse, c.planFor(false, "b.mtls.example", 443, 11L, c.ticket()))
        c.loaded(11L)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "b.mtls.example", 443, 11L, c.ticket()))
        // Opened after the opener was reloaded: asks.
        c.loaded(2L)
        c.opened(12L, 2L)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 12L, c.ticket()))
    }

    @Test
    fun `a closed tab's refusal is forgotten`() {
        val c = ClientCertChoices()
        c.loaded(2L)
        c.answered("mtls.example", 443, 2L, null, c.generation)
        c.tabClosed(2L)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L, c.ticket()))
    }

    @Test
    fun `a request queued behind a refused chooser takes the refusal, one after a reload opens its own`() = runBlocking {
        val c = ClientCertChoices()
        val lock = Mutex()
        val onScreen = MutableStateFlow<Long?>(2L)
        c.loaded(2L)
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
        // A later request from the same page (a new connection it opens):
        // still refused, no chooser (R4-F1).
        assertEquals(
            ClientCertPlan.Refuse,
            chooseInTurn(
                c, lock, "mtls.example", 443, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
                c.ticket(),
            ) { opened++; "alice" },
        )
        assertEquals(0, opened)
        // Nor for the next host the page tries (wildcard DNS, R5-F1).
        assertEquals(
            ClientCertPlan.Refuse,
            chooseInTurn(
                c, lock, "a2.mtls.example", 8704, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
                c.ticket(),
            ) { opened++; "alice" },
        )
        assertEquals(0, opened)
        // Another tab's request, even one queued behind that chooser, gets
        // its own once its tab is on screen (R5-M1). Denied there too.
        onScreen.value = 3L
        assertEquals(
            ClientCertPlan.Refuse,
            chooseInTurn(
                c, lock, "mtls.example", 443, 3L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
                queuedTicket,
            ) { opened++; null },
        )
        assertEquals(1, opened)
        onScreen.value = 2L
        // Once the user reloads, a request opens its own chooser.
        c.loaded(2L)
        val later = chooseInTurn(
            c, lock, "mtls.example", 443, 2L, onScreen, MutableStateFlow(false), MutableStateFlow(false),
            c.ticket(),
        ) { opened++; "alice" }
        assertEquals(ClientCertPlan.Send("alice"), later)
        assertEquals(2, opened)
    }

    @Test
    fun `clearing site data forgets every answer, including one given by a chooser opened before`() {
        val c = ClientCertChoices()
        c.answered("mtls.example", 443, 2L, "alice", c.generation)
        c.answered("other.example", 443, 2L, null, c.generation)
        val openedAt = c.generation
        c.clear()
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "other.example", 443, 2L))
        c.answered("mtls.example", 443, 2L, "alice", openedAt)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
    }

    @Test
    fun `an unreadable pick is forgotten, so the next request asks again`() {
        val c = ClientCertChoices()
        c.answered("mtls.example", 443, 2L, "gone", c.generation)
        c.forget("mtls.example", 443)
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
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
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
    }

    @Test
    fun `a pick for a tab still open is sent and remembered`() = runBlocking {
        val c = ClientCertChoices()
        val plan = chooseInTurn(
            c, Mutex(), "mtls.example", 443, 2L,
            MutableStateFlow(2L), MutableStateFlow(false), MutableStateFlow(false),
        ) { "alice" }
        assertEquals(ClientCertPlan.Send("alice"), plan)
        assertEquals(ClientCertPlan.Send("alice"), c.planFor(false, "mtls.example", 443, 2L))
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
        ClientCertificates.onBrowserLoad(2L)
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
        assertEquals(ClientCertPlan.Ask, c.planFor(false, "mtls.example", 443, 2L))
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
