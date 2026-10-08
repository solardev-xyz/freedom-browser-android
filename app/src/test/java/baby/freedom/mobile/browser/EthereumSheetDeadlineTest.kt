package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.WalletAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `window.ethereum` sheets wait at most their request's deadline (#463):
 * a background tab asking in a loop can't pile up waiting asks and a chain
 * of stale sheets, and running out pauses nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EthereumSheetDeadlineTest {
    private val site = "https://dapp.example"
    private val tabs = mutableListOf<BrowserState>()
    private val account = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val sign = EthAsk.SignMessage(site, account, "hello", "0x68656c6c6f")

    @After
    fun tearDown() {
        tabs.forEach { EthereumProviders.onTabClosed(it.id) }
    }

    private fun tab() = BrowserState(9_900L + tabs.size).also {
        tabs += it
        EthereumProviders.onDocumentStarted(it, site)
    }

    private fun doc(tab: BrowserState) = EthereumProviders.currentDocument(tab.id)

    @Test
    fun `a sheet nobody answers is withdrawn at its deadline, and the tab isn't paused`() = runTest {
        val tab = tab()
        val asked = async { EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 10_000) }
        runCurrent()
        assertSame(sign, tab.ethereumPrompt?.ask)
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(asked.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(EthAnswer.Rejected, asked.await())
        assertNull(tab.ethereumPrompt)
        // Nothing was turned down: the page's next ask still gets its sheet.
        val next = async { EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 10_000) }
        runCurrent()
        assertSame(sign, tab.ethereumPrompt?.ask)
        tab.ethereumPrompt!!.respond(EthAnswer.Approved())
        assertTrue(next.await() is EthAnswer.Approved)
    }

    @Test
    fun `the deadline counts the wait for the tab's line`() = runTest {
        val tab = tab()
        // One sheet up (an x402 payment's, no deadline), the page's asks queued behind it.
        val first = async { EthereumProviders.askOnDocument(tab, doc(tab), sign) }
        runCurrent()
        val firstSheet = tab.ethereumPrompt!!
        val queued = List(20) { async { EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 5_000) } }
        runCurrent()
        assertTrue(queued.none { it.isCompleted })
        advanceTimeBy(5_001)
        runCurrent()
        // All given up while still in line: none ever comes up after the first.
        assertEquals(List(20) { EthAnswer.Rejected }, queued.map { it.await() })
        assertSame(firstSheet, tab.ethereumPrompt)
        firstSheet.respond(EthAnswer.Approved())
        assertTrue(first.await() is EthAnswer.Approved)
        runCurrent()
        assertNull(tab.ethereumPrompt)
    }

    @Test
    fun `an answer before the deadline stands, and an ask already past it gets no sheet`() = runTest {
        val tab = tab()
        val asked = async { EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 10_000) }
        runCurrent()
        advanceTimeBy(9_000)
        tab.ethereumPrompt!!.respond(EthAnswer.Approved())
        assertTrue(asked.await() is EthAnswer.Approved)
        assertEquals(EthAnswer.Rejected, EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 0))
        assertNull(tab.ethereumPrompt)
    }

    @Test
    fun `a no-sheet switch's turn gives up at its deadline and frees the line`() = runTest {
        val tab = tab()
        val turn = EthAsk.SwitchNotice(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)
        val switched = async { EthereumProviders.askOnDocument(tab, doc(tab), turn, waitMs = 5_000) }
        runCurrent()
        assertSame(turn, tab.ethereumPrompt?.ask)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(EthAnswer.Rejected, switched.await())
        assertNull(tab.ethereumPrompt)
        // The line is free, and the tab not paused.
        val next = async { EthereumProviders.askOnDocument(tab, doc(tab), sign, waitMs = 5_000) }
        runCurrent()
        assertSame(sign, tab.ethereumPrompt?.ask)
        tab.ethereumPrompt!!.respond(EthAnswer.Approved())
        assertTrue(next.await() is EthAnswer.Approved)
    }
}
