package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.data.UrlSuggestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #170: tapping a suggestion while the address bar has focus picks it.
 *
 * Built from the same pieces as BrowserScreen — [PageWithSuggestions]
 * with the real [endEditOnPress] on the page layer, the real
 * [SuggestionsList] shown only while a text field is focused — so a
 * panel that the page layer's press-to-dismiss can tear down before the
 * click lands fails here.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionTapTest {

    @get:Rule
    val rule = createComposeRule()

    private val template = SearchEngines.DEFAULT.template
    private val history = UrlSuggestion(
        url = "https://example.com/visited",
        title = "Visited page",
        source = UrlSuggestion.Source.HISTORY,
    )

    private fun show(query: String, picks: MutableList<String>) {
        val focus = FocusRequester()
        rule.setContent {
            MaterialTheme {
                var focused by remember { mutableStateOf(false) }
                val dismiss = Modifier.endEditOnPress(
                    LocalFocusManager.current,
                    LocalSoftwareKeyboardController.current,
                )
                Column(Modifier.fillMaxSize()) {
                    PageWithSuggestions(
                        dismissKeyboardOnTap = dismiss,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        suggestions = if (!focused) null else {
                            {
                                SuggestionsList(
                                    query = query,
                                    actions = addressActions(query, template),
                                    suggestions = listOf(history),
                                    onPick = { picks += it },
                                    bottomContentPadding = 0.dp,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                    ) {}
                    BasicTextField(
                        value = query,
                        onValueChange = {},
                        modifier = Modifier
                            .testTag("address")
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.onNodeWithTag("address").assertIsFocused()
    }

    @Test
    fun tappingHistorySuggestionSubmitsItsUrl() {
        val picks = mutableListOf<String>()
        show("visited", picks)
        rule.onNodeWithText("Visited page").performClick()
        rule.runOnIdle { assertEquals(listOf("https://example.com/visited"), picks) }
    }

    @Test
    fun tappingSearchRowSubmitsTheEngineSearch() {
        val picks = mutableListOf<String>()
        show("swarm storage", picks)
        rule.onNodeWithText("Search with DuckDuckGo").performClick()
        rule.runOnIdle {
            assertEquals(listOf(UrlParser.toUrl("swarm storage", template)), picks)
        }
    }

    @Test
    fun tappingGoRowSubmitsTheTypedAddress() {
        val picks = mutableListOf<String>()
        show("vitalik.eth", picks)
        rule.onNodeWithText("Open ENS name").performClick()
        rule.runOnIdle { assertEquals(listOf("vitalik.eth"), picks) }
    }

    @Test
    fun tappingEmptyPanelSpaceEndsTheEdit() {
        val picks = mutableListOf<String>()
        show("visited", picks)
        // Top of the panel — above every row (the list is bottom-anchored).
        rule.onNodeWithTag("address").assertIsFocused()
        rule.onNodeWithText("Visited page").assertExists()
        rule.onRoot().performTouchInput { click(topCenter + Offset(0f, 20f)) }
        rule.runOnIdle { assertEquals(emptyList<String>(), picks) }
        rule.onNodeWithText("Visited page").assertDoesNotExist()
    }
}
