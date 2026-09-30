package baby.freedom.mobile.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.ui.FreedomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #287 R3-M1: a standing-grant switch on an approval sheet — the
 * wallet's "Always approve … on this contract" and Swarm's "always allow"
 * — is guarded like the sheet's confirm button. Once the sheet has armed,
 * a tap Android marks as having passed through another app's window
 * (what an overlay steering the tap onto the switch looks like on
 * Android 11, where other apps' overlays can't be hidden) doesn't turn
 * the switch on, and the sheet says why; a clean tap does. Taps are
 * injected through the real input pipeline on real clocks, like
 * [ApprovalTapjackDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class StandingGrantTapjackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    private val rule = AutoApproveRule(
        "https://game.example", "0x2222222222222222222222222222222222222222", "0xa9059cbb", 100,
    )

    @Composable
    private fun ethereumSwitch() {
        val tap = rememberArmedTapGuard(Unit)
        var on by remember { mutableStateOf(false) }
        Column(Modifier.padding(16.dp)) {
            AutoApproveSwitch(rule, "Gnosis", on, tap, enabled = true) { on = it }
            ObscuredTapNotice(tap)
        }
    }

    private fun showing(content: @Composable () -> Unit, label: String) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FreedomTheme { content() } } }
            val switch = findToggle(label)
            // Well past arming (SwarmPrompt's publish ask arms at the ordinary delay; 2 s covers both).
            SystemClock.sleep(2_000)
            tap(switch, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            SystemClock.sleep(500)
            assertFalse("an obscured tap turned the switch on", checked(label))
            assertTrue(onScreen(OBSCURED_TAP_MESSAGE))
            // The notice grows a bottom sheet upwards: find the switch again where it is now.
            tap(findToggle(label))
            assertTrue("a clean tap didn't turn the switch on", waitChecked(label))
        }
    }

    @Test
    fun anObscuredTapDoesNotTurnOnAlwaysApprove() = showing({ ethereumSwitch() }, autoApproveSwitchLabel(rule))

    @Test
    fun anObscuredTapDoesNotTurnOnSwarmAlwaysAllow() {
        val ask = SwarmAsk.Publish("https://game.example", SwarmAsk.Publish.Kind.Data, 10, "text/plain", null, emptyList())
        val label = swarmPromptCopy(ask).always!!
        showing({ SwarmPromptSheet(SwarmPromptRequest(ask)) }, label)
    }

    private fun roots() = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)

    /** The toggleable row that [label] is in (merged semantics: the text's node or its parent). */
    private fun toggle(label: String): AccessibilityNodeInfo? {
        val text = roots().firstNotNullOfOrNull { find(it, label) } ?: return null
        return if (text.isCheckable) text else text.parent?.takeIf { it.isCheckable }
    }

    private fun findToggle(label: String): Rect {
        val until = SystemClock.uptimeMillis() + 5_000
        var settling = true
        while (SystemClock.uptimeMillis() < until) {
            val node = toggle(label)
            if (node != null && settling) {
                settling = false
                SystemClock.sleep(1_000)
                continue
            }
            if (node != null) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("no \"$label\" switch on screen")
    }

    private fun checked(label: String): Boolean = toggle(label)?.isChecked == true

    private fun waitChecked(label: String): Boolean {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            if (checked(label)) return true
            SystemClock.sleep(50)
        }
        return false
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

    private fun tap(r: Rect, flags: Int = 0) {
        val x = r.exactCenterX()
        val y = r.exactCenterY()
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
}
