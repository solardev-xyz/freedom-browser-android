package baby.freedom.mobile.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.data.AppDatabase
import baby.freedom.mobile.data.BrowsingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Bookmarks list's non-drag reorder (#264): the moves TalkBack gets
 * as custom actions on each row, and the row menu's Edit.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()
    private val repo = BrowsingRepository(db)

    @After
    fun close() = db.close()

    private fun titles() = runBlocking { db.bookmarks().all().first().map { it.title } }

    private fun awaitTitles(expected: List<String>) = runBlocking {
        withTimeout(5_000) { db.bookmarks().all().first { list -> list.map { it.title } == expected } }
    }

    /** The accessibility actions on [title]'s row, by label. */
    private fun actions(title: String) = rule.onNode(hasText(title) and hasClickAction())
        .fetchSemanticsNode().config[SemanticsActions.CustomActions].associateBy { it.label }

    @Test
    fun talkBackMovesReorderAndPersist() {
        runBlocking {
            for (t in listOf("C", "B", "A")) repo.bookmark("https://$t.example/", t).await()
        }
        assertEquals(listOf("A", "B", "C"), titles())
        rule.setContent { MaterialTheme { BookmarksScreen(repo, onDismiss = {}, onOpen = {}) } }
        rule.waitForIdle()

        // First row: only down-moves.
        assertEquals(setOf("Move down", "Move to bottom"), actions("A").keys)
        rule.runOnUiThread { actions("A").getValue("Move down").action() }
        awaitTitles(listOf("B", "A", "C"))
        rule.waitForIdle()
        assertEquals(setOf("Move up", "Move down"), actions("A").keys)

        rule.runOnUiThread { actions("C").getValue("Move to top").action() }
        awaitTitles(listOf("C", "B", "A"))
        rule.waitForIdle()
        assertEquals(setOf("Move up", "Move to top"), actions("A").keys)
    }

    @Test
    fun rowMenuEditRenamesAndReaddresses() {
        runBlocking { repo.bookmark("https://a.example/", "A").await() }
        rule.setContent { MaterialTheme { BookmarksScreen(repo, onDismiss = {}, onOpen = {}) } }
        rule.onNode(hasContentDescription("Bookmark options")).performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNode(hasSetTextAction() and hasText("A")).performTextReplacement("Renamed")
        rule.onNode(hasSetTextAction() and hasText("https://a.example/")).performTextReplacement("vitalik.eth")
        rule.onNodeWithText("Save").performClick()
        runBlocking {
            withTimeout(5_000) {
                db.bookmarks().all().first { list ->
                    list.singleOrNull()?.let { it.title == "Renamed" && it.url == "vitalik.eth" } == true
                }
            }
        }
        rule.onNodeWithText("Edit bookmark").assertDoesNotExist()
    }
}
