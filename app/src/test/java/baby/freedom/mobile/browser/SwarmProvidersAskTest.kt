package baby.freedom.mobile.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwarmProviders.askOnTab] (#120): wallet setup only follows an approved
 * sheet, a setup the user backs out of blocks the tab like a refusal (and
 * the tab's other asks wait it out), and a sheet left unanswered is
 * withdrawn before the page's own timer.
 */
class SwarmProvidersAskTest {
    private val site = "https://app.example"
    private val tabs = mutableListOf<BrowserState>()
    private val setUps = mutableListOf<String>()
    private var setUpAnswer = false
    private var approvals = 0

    init {
        SwarmProviders.setUpWallet = { reason ->
            setUps += reason
            setUpAnswer
        }
    }

    @After
    fun tearDown() {
        tabs.forEach { SwarmProviders.onTabClosed(it.id) }
        SwarmProviders.setUpWallet = { false }
    }

    private fun tab() = BrowserState(9_000L + tabs.size).also { tabs += it }

    private fun sign(needsWallet: Boolean) =
        SwarmAsk.Sign(site, "swarm_getSigningIdentity", SwarmProvider.AutoApprove.Signing, true, null, "Signing identity", null, needsWallet)

    /** Asks on [tab], answering the sheet with [answer] once it's up (null: leave it up). */
    private fun ask(tab: BrowserState, ask: SwarmAsk, answer: SwarmProvider.Answer?, waitMs: Long = SwarmProviders.SHEET_WAIT_MS) = runBlocking {
        val result = async { SwarmProviders.askOnTab(tab, 0, ask, waitMs) { approvals++ } }
        if (answer != null) {
            while (tab.swarmPrompt == null && !result.isCompleted) yield()
            tab.swarmPrompt?.respond(answer)
        }
        result.await()
    }

    @Test
    fun `a refused sheet never opens wallet setup, and blocks the tab`() {
        val tab = tab()
        assertFalse(ask(tab, sign(needsWallet = true), SwarmProvider.Answer.REJECTED).allowed)
        assertTrue(setUps.isEmpty())
        assertEquals(0, approvals)
        // Blocked: the next ask is refused without a sheet.
        assertFalse(ask(tab, sign(needsWallet = true), null).allowed)
        assertNull(tab.swarmPrompt)
        assertTrue(setUps.isEmpty())
    }

    @Test
    fun `backing out of wallet setup blocks the tab, so a page can't loop the wallet page`() {
        val tab = tab()
        assertFalse(ask(tab, sign(needsWallet = true), SwarmProvider.Answer(true)).allowed)
        assertEquals(listOf(swarmWalletReason(site)), setUps)
        assertEquals("the page stops its timer once the sheet is approved", 1, approvals)
        assertFalse(ask(tab, sign(needsWallet = true), null).allowed)
        assertEquals(1, setUps.size)
        // The user navigating the tab lets it ask again.
        SwarmProviders.allowPrompts(tab.id)
        setUpAnswer = true
        assertTrue(ask(tab, sign(needsWallet = true), SwarmProvider.Answer(true)).allowed)
        assertEquals(2, setUps.size)
    }

    @Test
    fun `other asks wait out wallet setup, and a backed-out setup refuses them without a sheet`() = runBlocking {
        val tab = tab()
        val setUp = CompletableDeferred<Boolean>()
        SwarmProviders.setUpWallet = { reason ->
            setUps += reason
            setUp.await()
        }
        val first = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = true)) { approvals++ } }
        while (tab.swarmPrompt == null) yield()
        val firstSheet = tab.swarmPrompt!!
        val second = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = true)) { approvals++ } }
        repeat(10) { yield() }
        assertTrue("only one sheet at a time", tab.swarmPrompt === firstSheet)
        firstSheet.respond(SwarmProvider.Answer(true))
        while (setUps.isEmpty()) yield()
        // The wallet page is open: the second sheet stays parked behind it.
        repeat(50) { yield() }
        assertNull(tab.swarmPrompt)
        assertFalse(second.isCompleted)
        setUp.complete(false)
        assertFalse(first.await().allowed)
        assertFalse(second.await().allowed)
        assertNull("the blocked tab puts up no second sheet", tab.swarmPrompt)
        assertEquals(1, setUps.size)
        assertEquals(1, approvals)
    }

    @Test
    fun `after a completed wallet setup the waiting ask gets its own sheet`() = runBlocking {
        val tab = tab()
        val setUp = CompletableDeferred<Boolean>()
        SwarmProviders.setUpWallet = { reason ->
            setUps += reason
            setUp.await()
        }
        val first = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = true)) { approvals++ } }
        while (tab.swarmPrompt == null) yield()
        val firstSheet = tab.swarmPrompt!!
        val second = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = false)) { approvals++ } }
        firstSheet.respond(SwarmProvider.Answer(true))
        while (setUps.isEmpty()) yield()
        repeat(50) { yield() }
        assertNull(tab.swarmPrompt)
        setUp.complete(true)
        assertTrue(first.await().allowed)
        while (tab.swarmPrompt == null) yield()
        tab.swarmPrompt!!.respond(SwarmProvider.Answer(true))
        assertTrue(second.await().allowed)
        assertEquals(2, approvals)
    }

    @Test
    fun `a sheet queued behind wallet setup shows the ask as it stands once it's up`() = runBlocking {
        val tab = tab()
        var walletExists = false
        val setUp = CompletableDeferred<Boolean>()
        SwarmProviders.setUpWallet = { reason ->
            setUps += reason
            setUp.await().also { walletExists = it }
        }
        // Stands in for SwarmProvider.current: worked out when the sheet goes up, not when it was asked.
        val current: suspend (SwarmAsk) -> SwarmAsk? = { ask -> (ask as SwarmAsk.Sign).copy(grant = !walletExists, needsWallet = !walletExists) }
        val first = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = true), current = current) }
        while (tab.swarmPrompt == null) yield()
        val firstSheet = tab.swarmPrompt!!
        // Built while there was no wallet: first grant, needs a wallet.
        val second = async { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = true), current = current) }
        firstSheet.respond(SwarmProvider.Answer(true))
        while (setUps.isEmpty()) yield()
        setUp.complete(true)
        assertTrue(first.await().allowed)
        while (tab.swarmPrompt == null) yield()
        val shown = tab.swarmPrompt!!.ask as SwarmAsk.Sign
        assertFalse("the wallet is there now", shown.needsWallet)
        assertFalse("and feed access was granted with it", shown.grant)
        tab.swarmPrompt!!.respond(SwarmProvider.Answer(true))
        assertTrue(second.await().allowed)
        assertEquals("no second wallet setup", 1, setUps.size)
    }

    @Test
    fun `an ask with nothing left to show is refused without a sheet or a block`() {
        val tab = tab()
        val result = runBlocking { SwarmProviders.askOnTab(tab, 0, sign(needsWallet = false), current = { null }) }
        assertFalse(result.allowed)
        assertTrue("told apart from a refusal", result.ownerGone)
        assertNull(tab.swarmPrompt)
        assertTrue(ask(tab, sign(needsWallet = false), SwarmProvider.Answer(true)).allowed)
    }

    @Test
    fun `a sheet that doesn't need a wallet never opens wallet setup`() {
        val tab = tab()
        assertTrue(ask(tab, sign(needsWallet = false), SwarmProvider.Answer(true)).allowed)
        assertTrue(ask(tab, SwarmAsk.Connect(site), SwarmProvider.Answer(true)).allowed)
        assertTrue(setUps.isEmpty())
        assertEquals(2, approvals)
    }

    @Test
    fun `an unanswered sheet is withdrawn at its deadline without blocking the tab`() {
        val tab = tab()
        assertFalse(ask(tab, SwarmAsk.Connect(site), null, waitMs = 50).allowed)
        assertNull(tab.swarmPrompt)
        assertEquals(0, approvals)
        assertTrue("not a refusal: the tab may still ask", ask(tab, SwarmAsk.Connect(site), SwarmProvider.Answer(true)).allowed)
        // No time left at all: refused without a sheet.
        assertFalse(ask(tab, SwarmAsk.Connect(site), null, waitMs = 0).allowed)
    }

    @Test
    fun `the sheet waits well short of the page's five-minute timer`() {
        assertTrue(SwarmProviders.SHEET_WAIT_MS <= 300_000 - 20_000)
    }

    @Test
    fun `only the user's own answer reaches the manifest decision (#226 R1-F2)`() {
        val consent = SwarmManifests.Consent(site, "App", "", emptyList(), emptyList(), false, false, false, false)
        val manifest = SwarmAsk.Manifest(site, consent)
        val tab = tab()
        var heard: SwarmProvider.Answer? = null
        // A stale document: refused without a sheet, and nobody answered.
        val stale = runBlocking { SwarmProviders.askOnTab(tab, 1, manifest, answered = { heard = it }) }
        assertFalse(stale.allowed)
        assertNull(heard)
        assertNull(SwarmProviders.manifestOutcome(manifest, stale, heard))
        // A sheet left to time out: no answer either.
        val timedOut = runBlocking { SwarmProviders.askOnTab(tab, 0, manifest, waitMs = 50, answered = { heard = it }) }
        assertFalse(timedOut.allowed)
        assertNull(heard)
        assertNull(SwarmProviders.manifestOutcome(manifest, timedOut, heard))
        // The user's own Don't allow is a decision.
        val refused = runBlocking {
            val r = async { SwarmProviders.askOnTab(tab, 0, manifest, answered = { heard = it }) }
            while (tab.swarmPrompt == null && !r.isCompleted) yield()
            tab.swarmPrompt?.respond(SwarmProvider.Answer.REJECTED)
            r.await()
        }
        assertEquals(SwarmManifests.Outcome.Deny, SwarmProviders.manifestOutcome(manifest, refused, heard))
        assertEquals(
            SwarmManifests.Outcome.AllowAll,
            SwarmProviders.manifestOutcome(manifest, SwarmProvider.Answer(true, always = true), SwarmProvider.Answer(true, always = true)),
        )
        // Approved by the user but the tab moved on meanwhile: nothing is decided.
        assertNull(SwarmProviders.manifestOutcome(manifest, SwarmProvider.Answer.REJECTED, SwarmProvider.Answer(true, always = true)))
    }
}
