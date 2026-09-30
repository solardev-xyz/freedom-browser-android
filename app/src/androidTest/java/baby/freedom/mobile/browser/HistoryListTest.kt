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
 * a header still reads whole at 200% font scale.
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

    private fun show(fontScale: Float) {
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
                        onOpen = {},
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
}
