package baby.freedom.mobile.browser

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #400 item 11: a full-screen page's header reads ← title … actions,
 * mirrored in RTL, and the arrow is a 48 dp "Back" that calls the
 * page's own way out.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenScaffoldTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    private fun show(direction: LayoutDirection, exit: PageExit = PageExit.Back) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                FullScreenScaffold(
                    title = "History",
                    onDismiss = { backs++ },
                    exit = exit,
                    trailing = { TextButton(onClick = {}) { Text("Action") } },
                ) {}
            }
        }
    }

    @Test
    fun ltrReadsArrowTitleThenActions() {
        show(LayoutDirection.Ltr)
        val arrow = rule.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText("History").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText("Action").getUnclippedBoundsInRoot()
        assertTrue(arrow.right <= title.left)
        assertTrue(title.right <= action.left)
    }

    @Test
    fun rtlMirrorsTheHeader() {
        show(LayoutDirection.Rtl)
        val arrow = rule.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText("History").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText("Action").getUnclippedBoundsInRoot()
        assertTrue(arrow.left >= title.right)
        assertTrue(title.left >= action.right)
    }

    @Test
    fun arrowIsAFullTargetAndGoesBack() {
        show(LayoutDirection.Ltr)
        val button = rule.onNodeWithContentDescription("Back")
        // Drawn at 40 dp; Material extends its touch target to 48 dp.
        val touch = button.fetchSemanticsNode().touchBoundsInRoot
        with(rule.density) {
            assertTrue(touch.width >= 48.dp.toPx() - 1f)
            assertTrue(touch.height >= 48.dp.toPx() - 1f)
        }
        button.performClick()
        assertEquals(1, backs)
    }

    @Test
    fun aFullScreenDialogClosesWithAnX() {
        show(LayoutDirection.Ltr, PageExit.Close)
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, backs)
        assertEquals(0, rule.onAllNodesWithContentDescriptionCount("Back"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(label: String) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
            .fetchSemanticsNodes().size
}
