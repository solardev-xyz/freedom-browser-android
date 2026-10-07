package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.WalletAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A no-sheet chain switch's turn on `window.ethereum`'s per-tab line (#440,
 * #446): its "switched to" notice holds the tab's next no-sheet switch
 * until it's down (R1-F1), but a sheet the page asks for — the sign or send
 * that usually follows a switch — takes the turn from it at once (R4-F1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EthereumSwitchTurnTest {
    private val site = "https://dapp.example"
    private val tabs = mutableListOf<BrowserState>()
    private val account = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val sign = EthAsk.SignMessage(site, account, "hello", "0x68656c6c6f")
    private val toEthereum = EthereumProvider.ChainSwitched(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        tabs.forEach { EthereumProviders.onTabClosed(it.id) }
        Dispatchers.resetMain()
    }

    private fun tab() = BrowserState(9_800L + tabs.size).also {
        tabs += it
        EthereumProviders.onDocumentStarted(it, site)
    }

    private suspend fun settle() = repeat(50) { yield() }

    /** The prompt [tab] puts up next, answered [answer]. */
    private suspend fun answerNext(tab: BrowserState, answer: EthAnswer): EthereumPromptRequest = withTimeout(5_000) {
        while (tab.ethereumPrompt == null) yield()
        tab.ethereumPrompt!!.also { it.respond(answer) }
    }

    /** A no-sheet switch's turn on [tab], taken and made; its notice, as the browser gets it. */
    private suspend fun CoroutineScope.switched(tab: BrowserState): Pair<Deferred<EthAnswer>, EthereumProviders.SwitchNotice> {
        val notice = async { EthereumProviders.chainSwitches.first() }
        settle()
        val turn = EthAsk.SwitchNotice(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)
        val answer = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), turn) }
        assertSame(turn, answerNext(tab, EthAnswer.Approved()).ask)
        turn.switched.complete(toEthereum)
        return answer to withTimeout(5_000) { notice.await() }
    }

    @Test
    fun `a sheet asked right after a switch comes up at once and takes the notice down (#446 R4-F1)`() = runBlocking<Unit> {
        val tab = tab()
        val (turn, notice) = switched(tab)
        assertTrue(turn.await() is EthAnswer.Approved)
        notice.shown()
        settle()
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        // No waiting out the notice: the sign sheet is up straight away.
        settle()
        assertSame(sign, tab.ethereumPrompt?.ask)
        assertTrue(notice.closed.isCompleted)
        assertFalse(notice.closed.await())
        tab.ethereumPrompt!!.respond(EthAnswer.Approved())
        assertTrue(signed.await() is EthAnswer.Approved)
    }

    @Test
    fun `the next no-sheet switch still waits for the notice to be down (#446 R1-F1)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.shown()
        settle()
        val next = EthAsk.SwitchNotice(site, BuiltInChains.ETHEREUM, BuiltInChains.BASE)
        val answer = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), next) }
        settle()
        assertNull(tab.ethereumPrompt)
        assertFalse(notice.closed.isCompleted)
        notice.close(undo = false)
        assertSame(next, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(answer.await() is EthAnswer.Approved)
        next.switched.complete(null)
    }

    @Test
    fun `a sheet asked while the switch is still being made gets the turn, with no notice in its way (#446 R4-F1)`() = runBlocking<Unit> {
        val tab = tab()
        val notices = mutableListOf<EthereumProviders.SwitchNotice>()
        val watch = async { EthereumProviders.chainSwitches.collect { notices += it } }
        settle()
        val turn = EthAsk.SwitchNotice(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)
        async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), turn) }
        answerNext(tab, EthAnswer.Approved())
        settle()
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        settle()
        turn.switched.complete(toEthereum)
        assertSame(sign, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(signed.await() is EthAnswer.Approved)
        assertTrue(notices.isEmpty())
        watch.cancel()
    }

    @Test
    fun `a switch made just before its page moved on is still announced, and its Undo pauses nothing (#446 R4-M2)`() = runBlocking<Unit> {
        val tab = tab()
        val notice = async { EthereumProviders.chainSwitches.first() }
        settle()
        val turn = EthAsk.SwitchNotice(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)
        async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), turn) }
        answerNext(tab, EthAnswer.Approved())
        settle()
        // The page navigates between the switch being written and its notice.
        EthereumProviders.onDocumentStarted(tab, site)
        turn.switched.complete(toEthereum)
        val shown = withTimeout(5_000) { notice.await() }
        assertEquals(toEthereum, shown.switch)
        shown.shown()
        shown.close(undo = true)
        settle()
        // The new document's asks aren't paused by an Undo of the old one's switch.
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        assertSame(sign, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(signed.await() is EthAnswer.Approved)
    }

    @Test
    fun `Undo still pauses the page that switched (#440)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.shown()
        notice.close(undo = true)
        settle()
        assertEquals(EthAnswer.Paused, EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign))
    }
}
