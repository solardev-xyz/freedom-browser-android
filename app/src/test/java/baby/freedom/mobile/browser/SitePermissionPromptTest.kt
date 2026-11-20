package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt's explicit choices and what each answers (#419). */
class SitePermissionPromptTest {

    @Test
    fun `a normal tab offers three choices, the standing grant never first`() {
        assertEquals(
            listOf(PermissionChoice.ALLOW_THIS_SESSION, PermissionChoice.ALLOW_EVERY_VISIT, PermissionChoice.DONT_ALLOW),
            permissionChoices(private = false, agentHeld = false),
        )
    }

    @Test
    fun `a private tab has nothing to remember, so no every-visit choice`() {
        assertEquals(
            listOf(PermissionChoice.ALLOW_THIS_SESSION, PermissionChoice.DONT_ALLOW),
            permissionChoices(private = true, agentHeld = false),
        )
    }

    @Test
    fun `only Allow every visit is a remembered allow`() {
        assertEquals(PromptAnswer.Allow(remember = false), permissionAnswer(PermissionChoice.ALLOW_THIS_SESSION, private = false, agentHeld = false))
        assertEquals(PromptAnswer.Allow(remember = true), permissionAnswer(PermissionChoice.ALLOW_EVERY_VISIT, private = false, agentHeld = false))
        assertEquals(PromptAnswer.Block(remember = true), permissionAnswer(PermissionChoice.DONT_ALLOW, private = false, agentHeld = false))
    }

    @Test
    fun `nothing is remembered from a private tab`() {
        for (choice in PermissionChoice.entries) {
            val answer = permissionAnswer(choice, private = true, agentHeld = false)
            val remembered = when (answer) {
                is PromptAnswer.Allow -> answer.remember
                is PromptAnswer.Block -> answer.remember
                else -> error("unexpected $answer")
            }
            assertEquals(choice.name, false, remembered)
        }
    }

    @Test
    fun `a tab Freedom Agent holds offers Allow this time and Don't allow, and remembers neither (#605)`() {
        assertEquals(
            listOf(PermissionChoice.ALLOW_THIS_SESSION, PermissionChoice.DONT_ALLOW),
            permissionChoices(private = false, agentHeld = true),
        )
        assertEquals(PromptAnswer.Allow(remember = false), permissionAnswer(PermissionChoice.ALLOW_THIS_SESSION, private = false, agentHeld = true))
        assertEquals(PromptAnswer.Allow(remember = false), permissionAnswer(PermissionChoice.ALLOW_EVERY_VISIT, private = false, agentHeld = true))
        assertEquals(PromptAnswer.Block(remember = false), permissionAnswer(PermissionChoice.DONT_ALLOW, private = false, agentHeld = true))
        assertEquals(
            baby.freedom.mobile.R.string.library_permission_prompt_allow_once,
            permissionChoiceLabel(PermissionChoice.ALLOW_THIS_SESSION, private = false, agentHeld = true),
        )
        assertEquals(
            baby.freedom.mobile.R.string.library_permission_prompt_allow_session,
            permissionChoiceLabel(PermissionChoice.ALLOW_THIS_SESSION, private = false, agentHeld = false),
        )
    }

    @Test
    fun `a held tab's Allow goes into no tier, its block does (#605)`() {
        assertFalse(heldAnswerRecords(PromptAnswer.Allow(remember = false), agentHeld = true))
        assertTrue(heldAnswerRecords(PromptAnswer.Block(remember = false), agentHeld = true))
        assertTrue(heldAnswerRecords(PromptAnswer.Allow(remember = false), agentHeld = false))
        assertTrue(heldAnswerRecords(PromptAnswer.Allow(remember = true), agentHeld = false))
    }

    @Test
    fun `a held tab's note says a location or MIDI grant stays with the page until reload (#605)`() {
        // Each camera, microphone or app-link request reaches the broker, so it asks again.
        assertEquals(baby.freedom.mobile.R.string.library_permission_prompt_agent_held, agentHeldPromptHint(listOf(SitePermission.CAMERA)))
        assertEquals(
            baby.freedom.mobile.R.string.library_permission_prompt_agent_held,
            agentHeldPromptHint(listOf(SitePermission.CAMERA, SitePermission.MICROPHONE)),
        )
        assertEquals(baby.freedom.mobile.R.string.library_permission_prompt_agent_held, agentHeldPromptHint(listOf(ExternalScheme("tel"))))
        // WebView keeps these for the document's life: no "asks again".
        assertEquals(baby.freedom.mobile.R.string.library_permission_prompt_agent_held_until_reload, agentHeldPromptHint(listOf(SitePermission.LOCATION)))
        assertEquals(baby.freedom.mobile.R.string.library_permission_prompt_agent_held_until_reload, agentHeldPromptHint(listOf(SitePermission.MIDI)))
        assertEquals(
            baby.freedom.mobile.R.string.library_permission_prompt_agent_held_until_reload,
            agentHeldPromptHint(listOf(SitePermission.CAMERA, SitePermission.MIDI)),
        )
    }
}
