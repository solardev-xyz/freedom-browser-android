package baby.freedom.mobile.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #600: what Allow does on a download or upload card is on screen when
 * the card comes up, however long the link's address is (up to the
 * 4,095 characters browser_download shows); the address scrolls below it.
 */
@RunWith(AndroidJUnit4::class)
class AgentApprovalCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        AgentApprovals.withdrawAll()
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(action: AgentAction) {
        val approval = AgentApproval(
            id = AgentApprovals.nextId(),
            sessionId = "chat",
            callId = "call",
            tabId = 1,
            site = "https://example.com",
            document = 1,
            action = action,
            intent = "Get the report",
            byUser = true,
        )
        scope.launch { AgentApprovals.ask(approval) }
        rule.setContent { MaterialTheme { AgentApprovalHost() } }
        rule.waitUntil(5_000) { AgentApprovals.shown.value != null }
    }

    @Test
    fun a_long_download_link_does_not_push_the_rule_off_the_card() {
        val href = "https://example.com/files/" + "a".repeat(BrowserToolset.MAX_LINK_CHARS - 40) + "/report.zip"
        show(AgentAction.Download(PageTarget("link", "a", "Download report", effect = "file_download", target = href)))
        rule.onNodeWithText(text(R.string.agent_approve_download_saves)).assertIsDisplayed()
    }

    @Test
    fun the_upload_card_says_what_allow_opens() {
        show(AgentAction.Upload(PageTarget("button", "input", "Choose photo", effect = "file_upload")))
        rule.onNodeWithText(text(R.string.agent_approve_upload_picker)).assertIsDisplayed()
    }
}
