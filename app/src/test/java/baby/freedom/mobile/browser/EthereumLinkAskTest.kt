package baby.freedom.mobile.browser

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A payment link's ask (#317) on `window.ethereum`'s per-tab gate: a Send
 * page the user saw and left pauses the tab; one closed before it ever
 * showed (R1-M3) doesn't.
 */
class EthereumLinkAskTest {
    private val site = "https://shop.example"
    private val tabs = mutableListOf<BrowserState>()
    private val prefill = SendPrefill(site, "100:native", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", null)

    @After
    fun tearDown() {
        tabs.forEach { EthereumProviders.onTabClosed(it.id) }
    }

    private fun tab() = BrowserState(9_500L + tabs.size).also { tabs += it }

    /** Asks on [tab]'s current document, answering once the ask is up as [link] would when closed. */
    private fun ask(tab: BrowserState, link: ((EthereumPromptRequest) -> LinkSend)?): EthAnswer = runBlocking {
        val doc = EthereumProviders.currentDocument(tab.id)
        val result = async { EthereumProviders.askOnDocument(tab, doc, EthAsk.SendLink(site, prefill)) }
        if (link != null) {
            while (tab.ethereumPrompt == null && !result.isCompleted) yield()
            tab.ethereumPrompt?.let { link(it).closed() }
        }
        result.await()
    }

    @Test
    fun `a Send page closed before it showed leaves the tab's asks open`() {
        val tab = tab()
        assertEquals(EthAnswer.Unseen, ask(tab) { LinkSend(prefill, it) })
        // The next link opens: it's put up, and answered here as seen and left.
        assertEquals(EthAnswer.Rejected, ask(tab) { LinkSend(prefill, it).apply { shown = true } })
        // Now the tab is paused.
        assertEquals(EthAnswer.Paused, ask(tab, null))
        EthereumProviders.allowPrompts(tab.id)
        assertEquals(EthAnswer.Approved(), ask(tab) { LinkSend(prefill, it).apply { shown = true; started = true } })
    }
}
