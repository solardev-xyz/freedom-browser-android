package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.WalletAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The can't-send sheet (#423, W23) on `window.ethereum`'s per-tab gate
 * (R1-F1): closing it refuses nothing, so the page's next connect/sign/send
 * still gets its sheet — only a repeat can't-send sheet is held back until
 * the user navigates the tab, so a page retrying in a loop can't keep
 * putting it up.
 */
class EthereumCantSendAskTest {
    private val site = "https://dapp.example"
    private val tabs = mutableListOf<BrowserState>()
    private val account = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val cantSend = EthAsk.CantSend(site, BuiltInChains.GNOSIS, account, "Not enough xDAI", "Not enough xDAI", "xDAI")
    private val sign = EthAsk.SignMessage(site, account, "hello", "0x68656c6c6f")

    @After
    fun tearDown() {
        tabs.forEach { EthereumProviders.onTabClosed(it.id) }
    }

    private fun tab() = BrowserState(9_700L + tabs.size).also { tabs += it }

    /** Asks [ask] on [tab]'s current document, answering [answer] once a sheet is up (null: expect none). */
    private fun ask(tab: BrowserState, ask: EthAsk, answer: EthAnswer?): Pair<EthAnswer, Boolean> = runBlocking {
        val doc = EthereumProviders.currentDocument(tab.id)
        var shown = false
        val result = async { EthereumProviders.askOnDocument(tab, doc, ask) }
        if (answer != null) {
            while (tab.ethereumPrompt == null && !result.isCompleted) yield()
            tab.ethereumPrompt?.let { shown = true; it.respond(answer) }
        }
        result.await() to shown
    }

    @Test
    fun `closing the can't-send sheet leaves the tab's other asks open`() {
        val tab = tab()
        assertEquals(EthAnswer.Closed to true, ask(tab, cantSend, EthAnswer.Closed))
        // The page's next request that needs a sheet still gets one.
        assertEquals(EthAnswer.Approved() to true, ask(tab, sign, EthAnswer.Approved()))
        // A second can't-send isn't put back up (the page still gets its error from the provider).
        val (again, shownAgain) = ask(tab, cantSend, null)
        assertEquals(EthAnswer.Unseen, again)
        assertEquals(false, shownAgain)
        assertNull(tab.ethereumPrompt)
        // Once the user navigates the tab, it may show again.
        EthereumProviders.allowPrompts(tab.id)
        assertEquals(EthAnswer.Closed to true, ask(tab, cantSend, EthAnswer.Closed))
    }

    @Test
    fun `can't-send asks queued behind the closed one aren't shown in turn`() = runBlocking {
        val tab = tab()
        val doc = EthereumProviders.currentDocument(tab.id)
        // A page firing five sends at once, none awaited: all wait on the tab's sheet.
        val asks = List(5) { async { EthereumProviders.askOnDocument(tab, doc, cantSend) } }
        var shown = 0
        while (asks.any { !it.isCompleted }) {
            tab.ethereumPrompt?.let { shown++; it.respond(EthAnswer.Closed) }
            yield()
        }
        assertEquals(1, shown)
        assertEquals(listOf(EthAnswer.Closed) + List(4) { EthAnswer.Unseen }, asks.map { it.await() })
        assertNull(tab.ethereumPrompt)
    }

    @Test
    fun `rejecting a real sheet still pauses the tab`() {
        val tab = tab()
        assertEquals(EthAnswer.Rejected to true, ask(tab, sign, EthAnswer.Rejected))
        assertEquals(EthAnswer.Paused to false, ask(tab, sign, null))
    }
}
