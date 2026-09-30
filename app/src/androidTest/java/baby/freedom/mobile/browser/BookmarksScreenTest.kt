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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
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
 * as custom actions on each row, and the row menu's Edit; and its
 * Open in new / private tab (#321), from the menu, a long-press and
 * TalkBack.
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
        rule.setContent { MaterialTheme { BookmarksScreen(repo, private = false, onDismiss = {}, onOpen = {}, onOpenInNewTab = { _, _ -> }) } }
        rule.waitForIdle()

        // First row: only down-moves, between the row menu's opens and Edit, and Remove (#279, #321).
        assertEquals(setOf(OPEN_NEW, OPEN_PRIVATE, "Edit", "Move down", "Move to bottom", "Remove"), actions("A").keys)
        rule.runOnUiThread { actions("A").getValue("Move down").action() }
        awaitTitles(listOf("B", "A", "C"))
        rule.waitForIdle()
        assertEquals(setOf(OPEN_NEW, OPEN_PRIVATE, "Edit", "Move up", "Move down", "Remove"), actions("A").keys)

        rule.runOnUiThread { actions("C").getValue("Move to top").action() }
        awaitTitles(listOf("C", "B", "A"))
        rule.waitForIdle()
        assertEquals(setOf(OPEN_NEW, OPEN_PRIVATE, "Edit", "Move up", "Move to top", "Remove"), actions("A").keys)
    }

    @Test
    fun talkBackRemoveActionRemovesTheRow() {
        runBlocking { for (t in listOf("B", "A")) repo.bookmark("https://$t.example/", t).await() }
        rule.setContent { MaterialTheme { BookmarksScreen(repo, private = false, onDismiss = {}, onOpen = {}, onOpenInNewTab = { _, _ -> }) } }
        rule.waitForIdle()
        rule.runOnUiThread { actions("A").getValue("Remove").action() }
        awaitTitles(listOf("B"))
    }

    @Test
    fun rowMenuEditRenamesAndReaddresses() {
        runBlocking { repo.bookmark("https://a.example/", "A").await() }
        rule.setContent { MaterialTheme { BookmarksScreen(repo, private = false, onDismiss = {}, onOpen = {}, onOpenInNewTab = { _, _ -> }) } }
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

    /** Every open-in-new-tab request, as (url, private). */
    private val opened = mutableListOf<Pair<String, Boolean>>()

    private fun showOpening(private: Boolean) {
        rule.setContent {
            MaterialTheme {
                BookmarksScreen(
                    repo,
                    private = private,
                    onDismiss = {},
                    onOpen = { error("opened in the current tab: $it") },
                    onOpenInNewTab = { url, inPrivate -> opened += url to inPrivate },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun rowMenuOpensInANewOrAPrivateTab() {
        runBlocking { repo.bookmark("https://a.example/", "A").await() }
        showOpening(private = false)
        rule.onNode(hasContentDescription("Bookmark options")).performClick()
        rule.onNodeWithText(OPEN_NEW).performClick()
        rule.onNodeWithText(OPEN_NEW).assertDoesNotExist()
        rule.onNode(hasContentDescription("Bookmark options")).performClick()
        rule.onNodeWithText(OPEN_PRIVATE).performClick()
        assertEquals(listOf("https://a.example/" to false, "https://a.example/" to true), opened)
    }

    @Test
    fun fromAPrivateTabNewTabIsPrivateAndOffersNoSeparatePrivateItem() {
        runBlocking { repo.bookmark("https://a.example/", "A").await() }
        showOpening(private = true)
        assertEquals(setOf(OPEN_NEW, "Edit", "Remove"), actions("A").keys)
        rule.onNode(hasContentDescription("Bookmark options")).performClick()
        rule.onNodeWithText(OPEN_PRIVATE).assertDoesNotExist()
        rule.onNodeWithText(OPEN_NEW).performClick()
        assertEquals(listOf("https://a.example/" to true), opened)
    }

    @Test
    fun talkBackOpenActions() {
        runBlocking { repo.bookmark("https://a.example/", "A").await() }
        showOpening(private = false)
        rule.runOnUiThread { actions("A").getValue(OPEN_PRIVATE).action() }
        rule.runOnUiThread { actions("A").getValue(OPEN_NEW).action() }
        assertEquals(listOf("https://a.example/" to true, "https://a.example/" to false), opened)
    }

    @Test
    fun longPressWithoutDragOpensTheRowMenu() {
        runBlocking { for (t in listOf("B", "A")) repo.bookmark("https://$t.example/", t).await() }
        showOpening(private = false)
        rule.onNodeWithText(OPEN_NEW).assertDoesNotExist()
        rule.onNode(hasText("A") and hasClickAction()).performTouchInput { longClick(center) }
        rule.onNodeWithText(OPEN_NEW).performClick()
        assertEquals(listOf("https://A.example/" to false), opened)
        // The order is untouched.
        assertEquals(listOf("A", "B"), titles())
    }

    @Test
    fun longPressAndDragReordersWithoutOpeningTheMenu() {
        runBlocking { for (t in listOf("B", "A")) repo.bookmark("https://$t.example/", t).await() }
        showOpening(private = false)
        val row = rule.onNode(hasText("A") and hasClickAction())
        val height = row.fetchSemanticsNode().size.height.toFloat()
        row.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, height * 0.2f)) }
            up()
        }
        awaitTitles(listOf("B", "A"))
        rule.waitForIdle()
        rule.onNodeWithText(OPEN_NEW).assertDoesNotExist()
        assertEquals(emptyList<Pair<String, Boolean>>(), opened)
    }

    private companion object {
        const val OPEN_NEW = "Open in new tab"
        const val OPEN_PRIVATE = "Open in private tab"
    }
}
