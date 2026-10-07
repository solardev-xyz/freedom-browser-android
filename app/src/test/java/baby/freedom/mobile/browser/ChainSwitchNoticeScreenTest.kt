package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser screen's side of a dApp's "switched to" notice (#440, #446):
 * what covers it, and which notices a new one replaces.
 */
class ChainSwitchNoticeScreenTest {
    private data class Notice(val tab: Long, val shown: Boolean)

    private fun replaced(notices: List<Notice>, tab: Long) =
        switchNoticesReplaced(notices, tab, { it.tab }, { it.shown })

    @Test
    fun `a new notice keeps another tab's that hasn't been shown yet (#446 R6-M2)`() {
        val waiting = Notice(tab = 2, shown = false)
        assertEquals(emptyList<Notice>(), replaced(listOf(waiting), tab = 1))
    }

    @Test
    fun `a new notice replaces its own tab's, shown or not, and any already seen (#446 R6-M2)`() {
        val ownWaiting = Notice(tab = 1, shown = false)
        val ownSeen = Notice(tab = 1, shown = true)
        val otherSeen = Notice(tab = 2, shown = true)
        val otherWaiting = Notice(tab = 3, shown = false)
        assertEquals(listOf(ownWaiting), replaced(listOf(ownWaiting, otherWaiting), tab = 1))
        assertEquals(listOf(ownSeen, otherSeen), replaced(listOf(ownSeen, otherSeen, otherWaiting), tab = 1))
        assertEquals(listOf(otherSeen, otherWaiting), replaced(listOf(otherSeen, otherWaiting), tab = 3))
    }

    @Test
    fun `only a clear page shows the notice, any sheet, prompt or panel covers it (#446 R1-M1)`() {
        // Every turn but the page's own covers it, whatever else is true.
        for (turn in PromptTurn.entries - PromptTurn.None) {
            assertFalse("$turn", switchNoticeUncovered(true, turn, switchTurn = false, pageSheetUp = false))
        }
        // A no-sheet switch's own turn is answered at once: it doesn't cover the page.
        assertTrue(switchNoticeUncovered(true, PromptTurn.Ethereum, switchTurn = true, pageSheetUp = false))
        // ...but a panel or the Site permissions sheet over that still does.
        assertFalse(switchNoticeUncovered(false, PromptTurn.Ethereum, switchTurn = true, pageSheetUp = false))
        assertFalse(switchNoticeUncovered(true, PromptTurn.Ethereum, switchTurn = true, pageSheetUp = true))
        // A switch turn flag on someone else's turn doesn't uncover anything.
        assertFalse(switchNoticeUncovered(true, PromptTurn.Swarm, switchTurn = true, pageSheetUp = false))
        // The cases the finding names, spelled out.
        assertTrue(switchNoticeUncovered(true, PromptTurn.None, false, false))
        assertFalse(switchNoticeUncovered(true, PromptTurn.Swarm, false, false))
        assertFalse(switchNoticeUncovered(true, PromptTurn.Radicle, false, false))
        assertFalse(switchNoticeUncovered(true, PromptTurn.SitePermission, false, false))
        assertFalse(switchNoticeUncovered(true, PromptTurn.Ethereum, false, false))
        assertFalse(switchNoticeUncovered(false, PromptTurn.None, false, false)) // Settings, the Wallet, the tab switcher
        assertFalse(switchNoticeUncovered(true, PromptTurn.None, false, true)) // Site permissions sheet
    }

    @Test
    fun `a window of its own over the page covers the notice too (#446 R2-M1)`() {
        // Fullscreen video, Android's permission dialog (whose turn reads as None), a Ledger
        // conversation, a remote-signing sheet, the app backgrounded: each covers it.
        assertFalse(switchNoticeUncovered(true, PromptTurn.None, false, false, windowOver = true))
        assertFalse(switchNoticeUncovered(true, PromptTurn.Ethereum, switchTurn = true, pageSheetUp = false, windowOver = true))
        assertTrue(switchNoticeUncovered(true, PromptTurn.None, false, false, windowOver = false))
    }
}
