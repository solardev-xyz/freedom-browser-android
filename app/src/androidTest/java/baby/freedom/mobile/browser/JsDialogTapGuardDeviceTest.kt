package baby.freedom.mobile.browser

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page's JS dialog ignores its buttons for its first moments on
 * screen (#252 R4-M1): a dialog queued behind another prompt comes up
 * the instant that one is answered, and the second tap of a double-tap
 * must not land on its OK/Leave.
 */
@RunWith(AndroidJUnit4::class)
class JsDialogTapGuardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun showing(kind: JsDialogKind, block: (AlertDialog, MutableList<Boolean>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val answers = mutableListOf<Boolean>()
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                val request = JsDialogRequest(
                    kind = kind, url = "https://example.com", message = "hi",
                    defaultValue = null, secure = false,
                    answer = { confirmed, _ -> answers += confirmed },
                )
                dialog = showJsDialog(activity, request)!!
            }
            try {
                block(dialog, answers)
            } finally {
                instrumentation.runOnMainSync { dialog.dismiss() }
            }
        }
    }

    private fun click(dialog: AlertDialog, which: Int) =
        instrumentation.runOnMainSync { dialog.getButton(which).performClick() }

    @Test
    fun leaveIgnoresATapTheMomentItAppears() = showing(JsDialogKind.BEFORE_UNLOAD) { dialog, answers ->
        instrumentation.runOnMainSync {
            assertFalse(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
            assertFalse(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled)
            // Even a click that reaches a disabled button's listener.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        }
        assertEquals(emptyList<Boolean>(), answers)
        instrumentation.runOnMainSync { assertTrue(dialog.isShowing) }
    }

    @Test
    fun okAnswersOnceArmed() = showing(JsDialogKind.CONFIRM) { dialog, answers ->
        val deadline = SystemClock.uptimeMillis() + 5_000
        var enabled = false
        while (!enabled && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                enabled = dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled
            }
            if (!enabled) SystemClock.sleep(50)
        }
        assertTrue("OK never armed", enabled)
        click(dialog, DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf(true), answers)
        instrumentation.runOnMainSync { assertFalse(dialog.isShowing) }
    }

    @Test
    fun cancelAnswersOnceArmed() = showing(JsDialogKind.CONFIRM) { dialog, answers ->
        SystemClock.sleep(PromptTapGuard.PROTECTION_MS + 500)
        click(dialog, DialogInterface.BUTTON_NEGATIVE)
        assertEquals(listOf(false), answers)
    }
}
