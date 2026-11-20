package baby.freedom.mobile.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #595: while a new chat's first send is in flight the mode picker takes
 * no pick, so the mode shown is the one the chat is created under; a menu
 * already open closes rather than picking.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalModePickerTest {
    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun a_send_in_flight_takes_no_pick() {
        var enabled by mutableStateOf(true)
        val picks = mutableListOf<ApprovalMode>()
        val shown = ApprovalMode.AllowWebsiteInteractions
        rule.setContent { MaterialTheme { ApprovalModePicker(shown, enabled) { picks += it } } }
        val label = text(R.string.agent_mode_label, text(R.string.agent_mode_allow))
        val every = text(R.string.agent_mode_every)

        // Open while idle, then a send starts: the menu closes.
        rule.onNodeWithContentDescription(label).assertIsEnabled().performClick()
        rule.onNodeWithText(every).assertExists()
        enabled = false
        rule.waitForIdle()
        rule.onNodeWithText(every).assertDoesNotExist()
        rule.onNodeWithContentDescription(label).assertIsNotEnabled().performClick()
        rule.onNodeWithText(every).assertDoesNotExist()
        assertEquals(emptyList<ApprovalMode>(), picks)

        // Once it's back, a pick goes through.
        enabled = true
        rule.onNodeWithContentDescription(label).assertIsEnabled().performClick()
        rule.onNodeWithText(every).performClick()
        assertEquals(listOf(ApprovalMode.EveryInteraction), picks)
    }
}
