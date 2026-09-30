package baby.freedom.mobile.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.ledger.LedgerKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #240 (security audit #229): the wallet's approval sheet against
 * tapjacking. Taps are injected through the real input pipeline at the
 * button's place on screen, on real clocks (the sheet runs in a plain
 * Activity with no Compose test rule, whose virtual clock would drive
 * the sheet's arming instead of time passing), so what's measured is
 * what a user's finger — or a page's "tap fast here" game — gets.
 *
 * - A tap Android marks as having passed through another app's window
 *   (`FLAG_WINDOW_IS_OBSCURED`, an overlay steering the tap) is
 *   ignored and the sheet says why, and a clean tap right after still
 *   approves.
 * - Sign and Send arm only after the sheet has been on screen *and*
 *   left alone for [PromptTapGuard.SPEND_PROTECTION_MS]: a tap just
 *   past the old half second doesn't sign, and a stream of taps (the
 *   game) never does — only a tap after the user stops. That holds for
 *   taps on the scrim above the sheet too, the page's part of the screen.
 *
 * The signing account is a Ledger's, so Approve answers the sheet
 * straight away (the Ledger confirms afterwards) with no screen lock in
 * the way.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalTapjackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private val site = "https://game.example"

    private val ledgerAccount = WalletAccount(
        0, "Account 1", "0x1111111111111111111111111111111111111111",
        LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Ledger Nano X"),
    )

    /** Where the sheet's action button is on screen, once the sheet has slid in. */
    private lateinit var approve: Rect

    /** When the sheet was set up (uptime ms): its first frame is no earlier. */
    private var shownAt = 0L

    /** Sleeps until [ms] after the sheet was set up. */
    private fun at(ms: Long) {
        val left = shownAt + ms - SystemClock.uptimeMillis()
        assertTrue("already ${-left} ms late", left >= 0)
        SystemClock.sleep(left)
    }

    /**
     * Shows [ask]'s sheet once to find where its button settles (a sheet
     * that's never been tapped, so nothing there is timed), then shows it
     * afresh and runs [block] against that one, timed from its setup.
     */
    private fun showing(ask: EthAsk, block: (EthereumPromptRequest) -> Unit) {
        val label = ethApprovalCopy(ask).approve
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val request = EthereumPromptRequest(ask, setUpWallet = {})
            scenario.onActivity { it.setContent { FreedomTheme { EthereumApprovalSheet(request) } } }
            approve = findButton(label)
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val request = EthereumPromptRequest(ask, setUpWallet = {})
            scenario.onActivity {
                it.setContent { FreedomTheme { EthereumApprovalSheet(request) } }
                shownAt = SystemClock.uptimeMillis()
            }
            block(request)
        }
    }

    /** The action button's bounds once the sheet has finished sliding in. */
    private fun findButton(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 5_000
        var settling = true
        while (SystemClock.uptimeMillis() < until) {
            // The sheet is a window of its own: look in every one.
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            // Compose reports the label as a child of the button's own node.
            val button = roots.firstNotNullOfOrNull { find(it, label) }?.parent
            if (button != null && settling) {
                // Seen: give the slide-in time to finish (it may not have started yet), then read.
                settling = false
                SystemClock.sleep(1_500)
                continue
            }
            if (button != null) {
                val r = Rect()
                button.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("no \"$label\" button on screen")
    }

    private fun onScreen(text: String): Boolean {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            if (roots.any { find(it, text) != null }) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label) return node
        return (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let { find(it, label) } }
    }

    /** A tap on the action button's centre through the input pipeline, carrying [flags]. */
    private fun tapApprove(flags: Int = 0) = tapAt(approve.exactCenterX(), approve.exactCenterY(), flags)

    /** A tap on the scrim above the sheet: the page's part of the screen. */
    private fun tapScrim() = tapAt(approve.exactCenterX(), 150f)

    private fun tapAt(x: Float, y: Float, flags: Int = 0) {
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, x, y, flags)
        inject(down, down + 40, MotionEvent.ACTION_UP, x, y, flags)
        instrumentation.waitForIdleSync()
    }

    private fun inject(down: Long, at: Long, action: Int, x: Float, y: Float, flags: Int) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(
            down, at, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        try {
            assertTrue(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun answered(request: EthereumPromptRequest, withinMs: Long = 400): EthAnswer? {
        val until = SystemClock.uptimeMillis() + withinMs
        while (!request.answer.isCompleted && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        return if (request.answer.isCompleted) runBlocking { request.answer.await() } else null
    }

    private fun signAsk() = EthAsk.SignMessage(site, ledgerAccount, "Log in to game.example", "0x00")

    @Test
    fun aTapThroughAnotherAppsWindowDoesNotApprove() = showing(
        EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM),
    ) { request ->
        at(2_000)
        tapApprove(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(null, answered(request, withinMs = 800))
        // The sheet says why, in itself: a toast would be the system's window over the
        // button, and the user's retry would be refused as obscured in turn.
        assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
        // The same tap, not obscured, straight after, is the user's own.
        tapApprove()
        assertTrue(answered(request, withinMs = 2_000) is EthAnswer.Approved)
    }

    @Test
    fun aSignTapJustPastHalfASecondDoesNotSign() = showing(signAsk()) { request ->
        // Past the old half second, well short of the new one.
        at(750)
        tapApprove()
        assertEquals(null, answered(request, withinMs = 300))
    }

    @Test
    fun aStreamOfTapsNeverSignsOnlyATapAfterThePause() = showing(signAsk()) { request ->
        // A "tap fast here" game lined up with Sign: four taps a second for three seconds.
        at(300)
        val until = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < until) {
            tapApprove()
            assertFalse("a tap in the stream signed", request.answer.isCompleted)
            SystemClock.sleep(250)
        }
        assertEquals(null, answered(request, withinMs = 300))
        // The user stops, reads, and taps.
        SystemClock.sleep(PromptTapGuard.PROTECTION_MS * 3)
        tapApprove()
        assertTrue(answered(request, withinMs = 2_000) is EthAnswer.Approved)
    }

    @Test
    fun aStreamOfTapsOnTheScrimAlsoHoldsSignBack() = showing(signAsk()) { request ->
        // The game plays in the page's half of the screen, over the sheet's scrim, which
        // refuses to dismiss the sheet before it arms (#287 R1-F1). It runs from when the
        // sheet is up for longer than Sign's protection period, so a period the scrim
        // taps didn't restart would have armed mid-stream.
        assertTrue(onScreen(ethApprovalCopy(signAsk()).approve))
        val until = SystemClock.uptimeMillis() + PromptTapGuard.SPEND_PROTECTION_MS * 3 / 2
        var last = 0L
        while (SystemClock.uptimeMillis() < until) {
            tapScrim()
            last = SystemClock.uptimeMillis()
            assertFalse("a scrim tap answered the sheet", request.answer.isCompleted)
            SystemClock.sleep(250)
        }
        // Then its target moves onto Sign, in the stream's rhythm: short of a second after
        // the last scrim tap.
        SystemClock.sleep((last + 600 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        tapApprove()
        assertEquals(null, answered(request, withinMs = 300))
        // The user stops, reads, and taps.
        SystemClock.sleep(PromptTapGuard.SPEND_PROTECTION_MS * 2)
        tapApprove()
        assertTrue(answered(request, withinMs = 2_000) is EthAnswer.Approved)
    }

    /**
     * TalkBack's double-tap: no touch reaches the app, only an
     * `ACTION_CLICK` on the button's accessibility node (#279, the #160
     * lesson). Returns whether the node took the action.
     */
    private fun accessibilityClick(label: String): Boolean {
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        val button = roots.firstNotNullOfOrNull { find(it, label) }?.parent
            ?: throw AssertionError("no \"$label\" button on screen")
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test
    fun aTalkBackClickBeforeTheSheetArmsDoesNotSign() = showing(signAsk()) { request ->
        val label = ethApprovalCopy(signAsk()).approve
        assertTrue(onScreen(label))
        // Well inside Sign's protection period: the button is still disabled
        // to accessibility too, so the click is refused rather than queued.
        accessibilityClick(label)
        assertEquals(null, answered(request, withinMs = 300))
        // Armed, the same double-tap signs.
        SystemClock.sleep(PromptTapGuard.SPEND_PROTECTION_MS * 2)
        assertTrue(accessibilityClick(label))
        assertTrue(answered(request, withinMs = 2_000) is EthAnswer.Approved)
    }

    @Test
    fun aSignSheetSignsOnceItHasBeenUpAndLeftAlone() = showing(signAsk()) { request ->
        at(1_600)
        tapApprove()
        assertTrue(answered(request, withinMs = 2_000) is EthAnswer.Approved)
    }
}
