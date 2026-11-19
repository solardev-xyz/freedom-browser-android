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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #547: Ctrl+Enter in the Publish page's text field publishes, through
 * the field's real key path, and leaves no newline behind; Enter and
 * Shift+Enter still type one. On the real Publish page, Ctrl+Enter opens
 * the same confirmation the Publish text button does, and nothing with
 * the field empty. No stamp is reachable here, so that confirmation's
 * Publish stays off: nothing is uploaded.
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

    private fun showPublishPage() {
        rule.setContent {
            FreedomTheme {
                PublishScreen(
                    nodeInfo = NodeInfo(status = NodeStatus.Running, lightMode = true),
                    onOpenStamps = {},
                    onOpenSetup = {},
                    onOpenUrl = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Text").performClick()
        rule.waitForIdle()
    }

    private val field get() = rule.onNode(hasSetTextAction())

    /** The confirmation's title, "Publish <name>?", is up. */
    private fun confirmShown(): Boolean =
        rule.onAllNodes(hasText("Publish ", substring = true)).fetchSemanticsNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.endsWith("?") }
        }

    private fun ctrlEnter() {
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
    }

    @Test
    fun ctrlEnterOnThePublishPageOpensTheConfirmation() {
        showPublishPage()
        field.performClick()
        // Empty: the button would do nothing, and so does the key.
        ctrlEnter()
        Thread.sleep(500)
        rule.waitForIdle()
        assertFalse(confirmShown())

        field.performTextInput("hello swarm")
        ctrlEnter()
        rule.waitUntil(5_000) { confirmShown() }
    }
}
