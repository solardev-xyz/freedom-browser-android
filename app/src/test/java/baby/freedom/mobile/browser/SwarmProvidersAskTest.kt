package baby.freedom.mobile.browser

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
 * sheet, a setup the user backs out of blocks the tab like a refusal, and
 * a sheet left unanswered is withdrawn before the page's own timer.
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
}
