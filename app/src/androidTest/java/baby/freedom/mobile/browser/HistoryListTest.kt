package baby.freedom.mobile.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId

/**
 * The History page's day headers (#263) are headings for TalkBack, and
 * a header still reads whole at 200% font scale; an entry's long-press
 * and TalkBack actions open it in a new or a private tab (#321); a row
 * names the site and keeps the full address for its long-press (#418).
 */
@RunWith(AndroidJUnit4::class)
class HistoryListTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 9, 30)

    private fun entry(id: Long, date: LocalDate) = HistoryEntry(
        id = id,
        url = "https://example.com/$id",
        title = "Page $id",
        visitedAt = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
    )

    /** Every open-in-new-tab request, as (url, private). */
    private val opened = mutableListOf<Pair<String, Boolean>>()

    private fun show(fontScale: Float, fromPrivate: Boolean = false) {
        val days = historyDays(
            listOf(entry(1, today), entry(2, today.minusDays(1)), entry(3, today.minusDays(3))),
            today,
            zone,
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    HistoryList(
                        days = days,
                        timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT),
                        fromPrivate = fromPrivate,
                        onOpen = { error("opened in the current tab: $it") },
                        onOpenInNewTab = { url, private -> opened += url to private },
                        onRemove = {},
                    )
                }
            }
        }
    }

    @Test
    fun dayHeadersAreHeadings() {
        show(fontScale = 1f)
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        rule.onAllNodes(heading).assertCountEquals(3)
        rule.onNodeWithText("Today").assert(heading)
        rule.onNodeWithText("Yesterday").assert(heading)
        rule.onAllNodesWithText("Page", substring = true).assertCountEquals(3)
    }

    @Test
    fun datedHeaderIsNotCutAtDoubleFontScale() {
        show(fontScale = 2f)
        val label = historyDayLabel(today.minusDays(3), today, java.util.Locale.getDefault())
        val node = rule.onNodeWithText(label).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val layout = results.single()
        val text = layout.layoutInput.text.text
        assertEquals("header text", label, text)
        // The header wraps rather than cuts, so overflow alone proves
        // nothing: check every character is laid out, no line was
        // ellipsised, and each line break falls between words.
        assertTrue("header height overflow: $label", !layout.didOverflowHeight)
        assertEquals("last laid-out char", text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertTrue("line $line ellipsised: $label", !layout.isLineEllipsized(line))
        }
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue(
                "line $line of \"$label\" breaks mid-word at $end",
                text[end - 1].isWhitespace() || text[end].isWhitespace() || !text[end - 1].isLetterOrDigit(),
            )
        }
        // And the header's own box is tall enough for every line.
        val box = node.boundsInRoot
        assertTrue(
            "header box ${box.height} shorter than its text ${layout.size.height}",
            box.height >= layout.size.height,
        )
    }

    /** The accessibility actions on [title]'s row, by label. */
    private fun actions(title: String) = rule.onNode(hasText(title) and hasClickAction())
        .fetchSemanticsNode().config[SemanticsActions.CustomActions].associateBy { it.label }

    @Test
    fun longPressOffersNewAndPrivateTab() {
        show(fontScale = 1f)
        rule.onNodeWithText("Open in new tab").assertDoesNotExist()
        rule.onNode(hasText("Page 1") and hasClickAction()).performTouchInput { longClick(center) }
        rule.onNodeWithText("Open in private tab").assertIsDisplayed()
        rule.onNodeWithText("Open in new tab").performClick()
        rule.onNodeWithText("Open in private tab").assertDoesNotExist()
        rule.onNode(hasText("Page 2") and hasClickAction()).performTouchInput { longClick(center) }
        rule.onNodeWithText("Open in private tab").performClick()
        assertEquals(listOf("https://example.com/1" to false, "https://example.com/2" to true), opened)
    }

    @Test
    fun rowsNameTheSiteAndLongPressShowsTheFullAddress() {
        show(fontScale = 1f)
        // Flat rows (#418): the host, not the whole address.
        rule.onAllNodesWithText("example.com", substring = true).assertCountEquals(3)
        rule.onNodeWithText("https://example.com/1").assertDoesNotExist()
        // TalkBack still reads the full address on the row.
        rule.onNode(hasText("Page 1") and hasContentDescription("https://example.com/1")).assertExists()
        rule.onNode(hasText("Page 1") and hasClickAction()).performTouchInput { longClick(center) }
        rule.onNodeWithText("https://example.com/1").assertIsDisplayed()
    }

    @Test
    fun talkBackActions() {
        show(fontScale = 1f)
        assertEquals(setOf("Open in new tab", "Open in private tab"), actions("Page 3").keys)
        rule.runOnUiThread { actions("Page 3").getValue("Open in private tab").action() }
        rule.runOnUiThread { actions("Page 3").getValue("Open in new tab").action() }
        assertEquals(listOf("https://example.com/3" to true, "https://example.com/3" to false), opened)
    }

    @Test
    fun fromAPrivateTabNewTabIsPrivate() {
        show(fontScale = 1f, fromPrivate = true)
        assertEquals(setOf("Open in new tab"), actions("Page 1").keys)
        rule.onNode(hasText("Page 1") and hasClickAction()).performTouchInput { longClick(center) }
        rule.onNodeWithText("Open in private tab").assertDoesNotExist()
        rule.onNodeWithText("Open in new tab").performClick()
        assertEquals(listOf("https://example.com/1" to true), opened)
    }
}
