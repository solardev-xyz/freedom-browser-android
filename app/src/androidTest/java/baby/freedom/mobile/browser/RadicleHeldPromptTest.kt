package baby.freedom.mobile.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #591: a write in a tab Freedom Agent holds is prompted with no "always
 * allow" switch, and its answer carries none, so the prompt can't turn the
 * site's rule on or off; the user's own tab still has the switch.
 */
@RunWith(AndroidJUnit4::class)
class RadicleHeldPromptTest {
    @get:Rule val rule = createComposeRule()

    private fun write(held: Boolean) = RadicleAsk.Write(
        "https://forge.example",
        "radicle_commentIssue",
        "rad:z3gqcJUoA1n9HaHKufZs5FCSGazv5",
        target = "abcdef",
        body = "hello",
        ruleOn = !held,
        agentHeld = held,
    )

    private fun answerOf(held: Boolean): RadicleAnswer {
        val request = RadiclePromptRequest(write(held))
        // The tap guard waits on real uptime: an auto-advancing test clock
        // would spin its delay loop and never go idle.
        rule.mainClock.autoAdvance = false
        rule.setContent { FreedomTheme { RadiclePromptDialog(request) } }
        rule.mainClock.advanceTimeByFrame()
        if (held) {
            rule.onNodeWithTag("radicle-always-allow").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("radicle-always-allow").assertIsDisplayed()
        }
        // Past the tap guard, then Cancel.
        Thread.sleep(PromptTapGuard.PROTECTION_MS + 100)
        rule.mainClock.advanceTimeBy(PromptTapGuard.PROTECTION_MS + 100)
        rule.onNodeWithText("Cancel").performClick()
        return runBlocking { request.answer.await() }
    }

    @Test
    fun heldTabsWritePromptHasNoSwitch() {
        assertEquals(RadicleAnswer(false, null), answerOf(held = true))
    }

    @Test
    fun usersOwnTabKeepsTheSwitch() {
        assertEquals(RadicleAnswer(false, true), answerOf(held = false))
    }
}
