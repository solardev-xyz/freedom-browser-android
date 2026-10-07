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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
    fun `a sheet asked right after a switch comes up at once and leaves the notice up (#446 R4-F1, R5-M2)`() = runBlocking<Unit> {
        val tab = tab()
        val (turn, notice) = switched(tab)
        assertTrue(turn.await() is EthAnswer.Approved)
        notice.received()
        notice.shown()
        settle()
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        // No waiting out the notice: the sign sheet is up straight away.
        settle()
        assertSame(sign, tab.ethereumPrompt?.ask)
        // The notice, and its Undo, stay up.
        assertFalse(notice.closed.isCompleted)
        tab.ethereumPrompt!!.respond(EthAnswer.Approved())
        assertTrue(signed.await() is EthAnswer.Approved)
        notice.close(undo = true)
        settle()
        assertEquals(EthAnswer.Paused, EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign))
    }

    @Test
    fun `the next no-sheet switch still waits for the notice to be down (#446 R1-F1)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.received()
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
    fun `a sheet asked while the switch is still being made gets the turn, and the switch is still named (#446 R4-F1, R5-M2)`() = runBlocking<Unit> {
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
        assertEquals(listOf(toEthereum), notices.map { it.switch })
        assertFalse(notices.single().closed.isCompleted)
        notices.single().close(undo = false)
        watch.cancel()
    }

    @Test
    fun `a switch queued behind a notice a sheet ends is announced, ahead of the sheet (#446 R5-M2)`() = runBlocking<Unit> {
        val tab = tab()
        val notices = mutableListOf<EthereumProviders.SwitchNotice>()
        val watch = async { EthereumProviders.chainSwitches.collect { it.received(); notices += it } }
        settle()
        val first = EthAsk.SwitchNotice(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM)
        async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), first) }
        answerNext(tab, EthAnswer.Approved())
        first.switched.complete(toEthereum)
        settle()
        notices.single().shown()
        // Base waits behind the Ethereum notice; then the page asks to sign.
        val toBase = EthAsk.SwitchNotice(site, BuiltInChains.ETHEREUM, BuiltInChains.BASE)
        async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), toBase) }
        settle()
        assertNull(tab.ethereumPrompt)
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        // The sign ends the Ethereum notice's hold; Base had the line first.
        assertSame(toBase, answerNext(tab, EthAnswer.Approved()).ask)
        toBase.switched.complete(EthereumProvider.ChainSwitched(site, BuiltInChains.ETHEREUM, BuiltInChains.BASE))
        settle()
        assertSame(sign, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(signed.await() is EthAnswer.Approved)
        // The user's last notice names Base.
        assertEquals(listOf(BuiltInChains.ETHEREUM, BuiltInChains.BASE), notices.map { it.switch.to })
        notices.forEach { it.close(undo = false) }
        watch.cancel()
    }

    @Test
    fun `a notice already up stays up when its page starts a new document (#446 R5-M1)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.received()
        settle()
        // The page sets location.href right after its switch.
        EthereumProviders.onDocumentStarted(tab, site)
        settle()
        assertFalse(notice.closed.isCompleted)
        notice.shown()
        settle()
        assertFalse(notice.closed.isCompleted)
        notice.close(undo = true)
        settle()
        // The new document isn't paused by the old one's Undo.
        val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
        assertSame(sign, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(signed.await() is EthAnswer.Approved)
    }

    @Test
    fun `closing its tab closes the notice (#446 R2-M2)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.received()
        notice.shown()
        settle()
        EthereumProviders.onTabClosed(tab.id)
        settle()
        assertTrue(notice.closed.isCompleted)
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
        shown.received()
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
        notice.received()
        notice.shown()
        notice.close(undo = true)
        settle()
        assertEquals(EthAnswer.Paused, EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign))
    }

    @Test
    fun `a notice the browser has waits for its tab with no limit, and one it never took is dropped (#446 R5-M3)`() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        runTest(main) {
            val tab = tab()
            val (_, notice) = switched(tab)
            notice.received()
            // The user is on another tab for ten minutes: the notice still waits to be shown.
            advanceTimeBy(600_000)
            assertFalse(notice.closed.isCompleted)
            notice.shown()
            // Once on screen, its hold runs out.
            advanceTimeBy(151_000)
            assertTrue(notice.closed.isCompleted)

            // A notice nobody took (the screen was being rebuilt) doesn't hold the tab for ever.
            val (_, lost) = switched(tab)
            advanceTimeBy(151_000)
            assertTrue(lost.closed.isCompleted)
        }
    }

    @Test
    fun `a new document's first switch doesn't wait behind the old page's notice (#446 R6-M3)`() = runBlocking<Unit> {
        val tab = tab()
        val (_, notice) = switched(tab)
        notice.received()
        notice.shown()
        settle()
        // The user types another site into the tab; it's connected and switches at once.
        EthereumProviders.onDocumentStarted(tab, site)
        val next = EthAsk.SwitchNotice(site, BuiltInChains.ETHEREUM, BuiltInChains.BASE)
        val answer = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), next) }
        assertSame(next, answerNext(tab, EthAnswer.Approved()).ask)
        assertTrue(answer.await() is EthAnswer.Approved)
        // The old notice is still up: the switch it names stays.
        assertFalse(notice.closed.isCompleted)
        next.switched.complete(null)
        notice.close(undo = false)
    }

    @Test
    fun `a notice a sheet covered isn't timed out behind it (#446 R6-M1)`() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        runTest(main) {
            val tab = tab()
            val (_, notice) = switched(tab)
            notice.received()
            notice.shown()
            // The page asks to sign; the user reads the sheet for five minutes.
            val signed = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign) }
            advanceTimeBy(300_000)
            assertSame(sign, tab.ethereumPrompt?.ask)
            assertFalse(notice.closed.isCompleted)
            tab.ethereumPrompt!!.respond(EthAnswer.Approved())
            assertTrue(signed.await() is EthAnswer.Approved)
            // Back up after the sheet, its Undo still pauses the tab.
            notice.close(undo = true)
            advanceTimeBy(1_000)
            assertEquals(EthAnswer.Paused, EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign))
        }
    }
    @Test
    fun `a notice covered by anything else isn't timed out behind it, and holds nothing meanwhile (#446 R1-M1)`() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        runTest(main) {
            val tab = tab()
            val (_, notice) = switched(tab)
            notice.received()
            notice.shown()
            // Settings, or a Swarm sheet, covers the page; the browser takes the notice down.
            notice.covered()
            advanceTimeBy(300_000)
            assertFalse(notice.closed.isCompleted)
            // The page's next no-sheet switch isn't held behind a notice nobody can see.
            val next = EthAsk.SwitchNotice(site, BuiltInChains.ETHEREUM, BuiltInChains.BASE)
            val answer = async { EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), next) }
            assertSame(next, answerNext(tab, EthAnswer.Approved()).ask)
            assertTrue(answer.await() is EthAnswer.Approved)
            next.switched.complete(null)
            // Back up once the page is clear, its Undo still pauses the tab.
            notice.close(undo = true)
            advanceTimeBy(1_000)
            assertEquals(EthAnswer.Paused, EthereumProviders.askOnDocument(tab, EthereumProviders.currentDocument(tab.id), sign))
        }
    }

    @Test
    fun `while shown and uncovered, the notice is still bounded by its hold (#446 R2-M1)`() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        runTest(main) {
            val tab = tab()
            val (_, notice) = switched(tab)
            notice.received()
            notice.shown()
            advanceTimeBy(151_000)
            assertTrue(notice.closed.isCompleted)
        }
    }
}
