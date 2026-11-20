package baby.freedom.mobile.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #578: the composer's microphone is one node whatever its state, so a
 * TalkBack user's focus stays on it when a tap starts, finishes or ends
 * dictation instead of dropping and making them explore (every label on
 * the way read aloud into the open microphone) to find Stop.
 */
@RunWith(AndroidJUnit4::class)
class MicButtonNodeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun the_microphone_stays_the_same_node_through_a_run() {
        var status by mutableStateOf<DictationStatus>(DictationStatus.Idle)
        rule.setContent { MaterialTheme { MicButton(RecognizerKind.OnDevice, status) {} } }

        val idle = rule.onNodeWithContentDescription(text(R.string.agent_dictate_on_device)).assertIsEnabled()
            .fetchSemanticsNode().id
        status = DictationStatus.Listening(RecognizerKind.OnDevice, hearing = true)
        val listening = rule.onNodeWithContentDescription(text(R.string.agent_dictate_stop)).assertIsEnabled()
            .fetchSemanticsNode().id
        status = DictationStatus.Listening(RecognizerKind.OnDevice, hearing = true, finishing = true)
        val finishing = rule.onNodeWithContentDescription(text(R.string.agent_dictation_finishing)).assertIsNotEnabled()
            .fetchSemanticsNode().id
        status = DictationStatus.Done
        val done = rule.onNodeWithContentDescription(text(R.string.agent_dictate_on_device)).assertIsEnabled()
            .fetchSemanticsNode().id

        assertEquals(idle, listening)
        assertEquals(idle, finishing)
        assertEquals(idle, done)
    }
}
