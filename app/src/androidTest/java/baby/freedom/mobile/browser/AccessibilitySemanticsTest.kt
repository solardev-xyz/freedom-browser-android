package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What TalkBack is handed for the controls #279 labelled: one node per
 * control, with its name, role and state — asserted on the merged tree,
 * the one a screen reader walks.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun theTabsButtonIsNamedAndItsDigitIsSilent() {
        rule.setContent { MaterialTheme { TabsCountButton(count = 3, onClick = {}) } }
        // The drawn "3" is no longer part of what the button says.
        rule.onNode(hasContentDescription("Tabs, 3 open") and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    @Test
    fun aSwitchRowIsOneSwitchWithItsTitleAndState() {
        rule.setContent {
            var on by remember { mutableStateOf(false) }
            MaterialTheme {
                PageRow(
                    title = "Block ads",
                    subtitle = "EasyList",
                    style = PageRowStyle.Inset,
                    onClick = { on = !on },
                    checked = on,
                    trailing = { Switch(checked = on, onCheckedChange = null) },
                )
            }
        }
        val row = rule.onNode(isToggleable())
        row.assert(hasText("Block ads") and hasText("EasyList") and hasRole(Role.Switch)).assertIsOff()
        // Only the row: the switch isn't a second, unlabelled control.
        assertEquals(1, rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        row.performClick().assertIsOn()
    }

    @Test
    fun aStatusSwitchRowIsNamedForWhatItSwitches() {
        rule.setContent {
            var on by remember { mutableStateOf(true) }
            MaterialTheme {
                Row(Modifier.switchRow(checked = on, onCheckedChange = { on = it }, label = "Swarm node")) {
                    Text("Running")
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        }
        rule.onNode(isToggleable())
            .assert(hasContentDescription("Swarm node") and hasText("Running") and hasRole(Role.Switch))
            .assertIsOn()
            .performClick()
            .assertIsOff()
    }

    @Test
    fun sectionTitlesAreHeadings() {
        rule.setContent {
            MaterialTheme {
                FullScreenScaffold(title = "Settings", onDismiss = {}) {
                    SectionCard(title = "Privacy") { Text("body") }
                }
            }
        }
        rule.onNodeWithText("Settings").assert(isHeading())
        rule.onNodeWithText("Privacy").assert(isHeading())
        rule.onNodeWithText("body").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theTabSwitcherSaysWhichTabIsCurrentAndOffersClose() {
        val tabs = TabsState(homepage = "https://home.example/")
        tabs.tabs[0].apply { url = "https://a.example/"; title = "Alpha" }
        tabs.newTab().apply { url = "https://b.example/"; title = "Beta" }
        rule.setContent { MaterialTheme { TabSwitcherScreen(tabs = tabs, onDismiss = {}, onNewTab = {}) } }
        val beta = rule.onNode(hasText("Beta") and hasClickAction())
        beta.assert(isSelected())
        rule.onNode(hasText("Alpha") and hasClickAction()).assert(isSelected().not())
        val close = beta.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Close tab" }
        rule.runOnUiThread { assertTrue(close.action()) }
        rule.waitForIdle()
        assertEquals(listOf("Alpha"), tabs.tabs.map { it.title })
    }

    @Test
    fun aTabCardOffersCloseOtherTabsAndReportsTheBulkClose() {
        val tabs = TabsState(homepage = "https://home.example/")
        tabs.tabs[0].apply { url = "https://a.example/"; title = "Alpha" }
        tabs.newTab().apply { url = "https://b.example/"; title = "Beta" }
        tabs.newTab().apply { url = "https://c.example/"; title = "Gamma" }
        var closed: TabsState.BulkClose? = null
        rule.setContent {
            MaterialTheme {
                TabSwitcherScreen(tabs = tabs, onDismiss = {}, onNewTab = {}, onTabsClosed = { closed = it })
            }
        }
        val others = rule.onNode(hasText("Beta") and hasClickAction()).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Close other tabs" }
        rule.runOnUiThread { assertTrue(others.action()) }
        rule.waitForIdle()
        assertEquals(listOf("Beta"), tabs.tabs.map { it.title })
        assertEquals(2, closed?.count)
        // The one tab left has no others to close.
        val left = rule.onNode(hasText("Beta") and hasClickAction()).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }
        assertTrue("Close other tabs" !in left)
        rule.runOnUiThread { assertTrue(tabs.reopenClosed(closed!!.undo!!)) }
        rule.waitForIdle()
        assertEquals(listOf("Alpha", "Beta", "Gamma"), tabs.tabs.map { it.title })
    }

    @Test
    fun aLongPressLetGoInPlaceOpensTheTabMenu() {
        val tabs = TabsState(homepage = "https://home.example/")
        tabs.tabs[0].apply { url = "https://a.example/"; title = "Alpha" }
        tabs.newTab().apply { url = "https://b.example/"; title = "Beta" }
        rule.setContent { MaterialTheme { TabSwitcherScreen(tabs = tabs, onDismiss = {}, onNewTab = {}) } }
        rule.onNode(hasText("Alpha") and hasClickAction()).performTouchInput { longClick() }
        rule.onNodeWithText("Close other tabs").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Alpha"), tabs.tabs.map { it.title })
    }

    @Test
    fun theNodesMenuRowSaysWhereItGoesWithNoCount() {
        var note by mutableStateOf<String?>(null)
        rule.setContent { MaterialTheme { NodesMenuItem(note) {} } }
        fun texts() = rule.onNode(hasClickAction(), useUnmergedTree = false).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        // Its label is what TalkBack reads; no peer count (#416).
        assertEquals(listOf("Nodes & networks"), texts())
        // A failed node adds its line, read after the label.
        note = "Swarm has a problem"
        rule.waitForIdle()
        assertEquals(listOf("Nodes & networks", "Swarm has a problem"), texts())
    }

    @Test
    fun theCopyLabelKeepsItsWidthAndSaysOnlyTheShownWord() {
        var copied by mutableStateOf(false)
        rule.setContent {
            val base = LocalDensity.current
            // A large font, where "Copied" is noticeably wider than "Copy".
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                MaterialTheme {
                    OutlinedButton(onClick = {}) { CopyLabel(copied, Modifier.testTag("copy")) }
                }
            }
        }
        val before = rule.onNodeWithTag("copy", useUnmergedTree = true).fetchSemanticsNode().size
        assertEquals(listOf("Copy"), rule.onNode(hasClickAction()).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
        copied = true
        rule.waitForIdle()
        val after = rule.onNodeWithTag("copy", useUnmergedTree = true).fetchSemanticsNode().size
        assertEquals(before, after)
        assertEquals(listOf("Copied"), rule.onNode(hasClickAction()).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
    }
}

