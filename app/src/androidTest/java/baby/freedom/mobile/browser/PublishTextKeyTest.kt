package baby.freedom.mobile.browser

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #547: Ctrl+Enter in the Publish page's text field publishes, through
 * the field's real key path, and leaves no newline behind; Enter and
 * Shift+Enter still type one.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PublishTextKeyTest {
    @get:Rule val rule = createComposeRule()

    private var published = 0
    private var text = ""

    private fun show() {
        rule.setContent {
            FreedomTheme {
                Surface {
                    var value by remember { mutableStateOf("hello") }
                    text = value
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.testTag("field").onPublishTextKey { published++ },
                    )
                }
            }
        }
        rule.onNodeWithTag("field").performClick()
        rule.waitForIdle()
    }

    @Test
    fun ctrlEnterPublishesOnceAndTypesNothing() {
        show()
        rule.onNodeWithTag("field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
        }
        rule.waitForIdle()
        assertEquals(1, published)
        assertEquals("hello", text)
        rule.onNodeWithTag("field").performKeyInput {
            withKeyDown(Key.CtrlRight) { pressKey(Key.NumPadEnter) }
        }
        rule.waitForIdle()
        assertEquals(2, published)
        assertEquals("hello", text)
    }

    @Test
    fun enterAndShiftEnterStillTypeANewline() {
        show()
        rule.onNodeWithTag("field").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("hello\n", text)
        rule.onNodeWithTag("field").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }
        rule.waitForIdle()
        assertEquals(0, published)
        assertEquals(2, text.count { it == '\n' })
    }
}
