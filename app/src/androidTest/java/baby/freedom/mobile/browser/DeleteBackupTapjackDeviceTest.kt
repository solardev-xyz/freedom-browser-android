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
import baby.freedom.mobile.ui.FreedomTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #287 R2-M1: the "Delete Google backup?" / "Turn off Google backup?"
 * dialog deletes what may be the only copy of a wallet, so its confirm
 * button gets the same tap protection as Remove wallet's own
 * delete-the-backup tick. Taps are injected through the real input
 * pipeline at the button's place, on real clocks (a plain Activity, no
 * Compose test rule), like [ApprovalTapjackDeviceTest].
 *
 * - A tap as the dialog comes up — the second half of a double tap on
 *   "Delete backup", which put the dialog's own "Delete backup" under
 *   the finger — doesn't delete.
 * - A tap Android marks as having passed through another app's window
 *   doesn't delete either, and the dialog says why; a clean tap after
 *   that does.
 */
@RunWith(AndroidJUnit4::class)
class DeleteBackupTapjackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    private lateinit var confirm: Rect
    private var shownAt = 0L

    /**
     * Shows the dialog once to find where its confirm button is, then
     * afresh, running [block] timed from that one's setup with the
     * number of times it confirmed.
     */
    private fun showing(block: (AtomicInteger) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FreedomTheme { dialog({}) } } }
            confirm = findButton(LABEL)
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val confirmed = AtomicInteger()
            scenario.onActivity {
                it.setContent { FreedomTheme { dialog { confirmed.incrementAndGet() } } }
                shownAt = SystemClock.uptimeMillis()
            }
            block(confirmed)
        }
    }

    @androidx.compose.runtime.Composable
    private fun dialog(onConfirm: () -> Unit) =
        DeleteGoogleBackupDialog(turningOff = false, walletStays = false, onConfirm = onConfirm, onDismiss = {})

    private fun at(ms: Long) {
        val left = shownAt + ms - SystemClock.uptimeMillis()
        assertTrue("already ${-left} ms late", left >= 0)
        SystemClock.sleep(left)
    }

    private fun roots() = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)

    private fun findButton(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 5_000
        var settling = true
        while (SystemClock.uptimeMillis() < until) {
            val button = roots().firstNotNullOfOrNull { find(it, label) }?.parent
            if (button != null && settling) {
                settling = false
                SystemClock.sleep(1_000)
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
            if (roots().any { find(it, text) != null }) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label) return node
        return (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let { find(it, label) } }
    }

    private fun tapConfirm(flags: Int = 0) {
        val x = confirm.exactCenterX()
        val y = confirm.exactCenterY()
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

    private fun confirmedWithin(confirmed: AtomicInteger, ms: Long): Int {
        val until = SystemClock.uptimeMillis() + ms
        while (confirmed.get() == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        return confirmed.get()
    }

    @Test
    fun aTapAsTheDialogComesUpDoesNotDelete() = showing { confirmed ->
        // The dialog is on screen well before this, but not yet armed.
        at(250)
        tapConfirm()
        assertEquals(0, confirmedWithin(confirmed, 300))
    }

    @Test
    fun aTapThroughAnotherAppsWindowDoesNotDeleteACleanOneDoes() = showing { confirmed ->
        at(1_500)
        tapConfirm(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, confirmedWithin(confirmed, 600))
        assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
        // The notice makes the dialog taller, so its button has moved: the user taps it
        // where it is now.
        confirm = findButton(LABEL)
        tapConfirm()
        assertEquals(1, confirmedWithin(confirmed, 2_000))
    }

    private companion object {
        const val LABEL = "Delete backup"
    }
}
