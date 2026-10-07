package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** The prompt's explicit choices and what each answers (#419). */
class SitePermissionPromptTest {

    @Test
    fun `a normal tab offers three choices, the standing grant never first`() {
        assertEquals(
            listOf(PermissionChoice.ALLOW_WHILE_VISITING, PermissionChoice.ALLOW_EVERY_VISIT, PermissionChoice.DONT_ALLOW),
            permissionChoices(private = false),
        )
    }

    @Test
    fun `a private tab has nothing to remember, so no every-visit choice`() {
        assertEquals(
            listOf(PermissionChoice.ALLOW_WHILE_VISITING, PermissionChoice.DONT_ALLOW),
            permissionChoices(private = true),
        )
    }

    @Test
    fun `only Allow every visit is a remembered allow`() {
        assertEquals(PromptAnswer.Allow(remember = false), permissionAnswer(PermissionChoice.ALLOW_WHILE_VISITING, private = false))
        assertEquals(PromptAnswer.Allow(remember = true), permissionAnswer(PermissionChoice.ALLOW_EVERY_VISIT, private = false))
        assertEquals(PromptAnswer.Block(remember = true), permissionAnswer(PermissionChoice.DONT_ALLOW, private = false))
    }

    @Test
    fun `nothing is remembered from a private tab`() {
        for (choice in PermissionChoice.entries) {
            val answer = permissionAnswer(choice, private = true)
            val remembered = when (answer) {
                is PromptAnswer.Allow -> answer.remember
                is PromptAnswer.Block -> answer.remember
                else -> error("unexpected $answer")
            }
            assertEquals(choice.name, false, remembered)
        }
    }
}
