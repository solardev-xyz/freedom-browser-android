package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #241 (audit #229): a recovery phrase pasted into the Import page is
 * taken off the clipboard however the page is left — Back, an import
 * whose authentication was cancelled or that failed, the page going
 * away — not only after a successful import. Against the real clipboard
 * service, from the test Activity's focused window.
 */
@RunWith(AndroidJUnit4::class)
class ImportPhraseClipboardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val twelve = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    private var shown by mutableStateOf(true)
    private val imports = mutableListOf<Mnemonic>()

    private val clipboard get() = rule.activity.getSystemService(ClipboardManager::class.java)

    private fun clipText(): String? = rule.runOnIdle {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    private fun setClip(text: String) = rule.runOnIdle {
        clipboard.setPrimaryClip(ClipData.newPlainText("Notes", text))
    }

    /** The page, with an import that never completes: like a cancelled or failed authentication. */
    @Before
    fun show() {
        rule.setContent {
            FreedomTheme {
                if (shown) {
                    ImportPhrasePage(
                        busy = false,
                        error = null,
                        deviceSecure = true,
                        onImport = { mnemonic, _ -> imports += mnemonic },
                        onBack = { shown = false },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private val field = hasSetTextAction()

    /** A real paste, from the clipboard, through the field's own Paste action. */
    private fun paste() {
        rule.onNode(field).performSemanticsAction(SemanticsActions.PasteText)
        rule.waitForIdle()
        val inField = rule.onNode(field).fetchSemanticsNode().config
            .getOrElseNullable(SemanticsProperties.EditableText) { null }?.text
        assertEquals(twelve, inField)
    }

    private fun pressBack() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun pastedPhraseIsClearedOnBack() {
        setClip(twelve)
        paste()
        pressBack()
        assertFalse(shown)
        assertEquals(null, clipText())
    }

    @Test
    fun pastedPhraseIsClearedWhenTheImportIsCancelled() {
        setClip(twelve)
        paste()
        rule.onNode(hasText("Import wallet") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, imports.size)
        // The import never completed (authentication cancelled), the page is still up.
        assertTrue(shown)
        assertEquals(null, clipText())
    }

    @Test
    fun pastedPhraseIsClearedWhenThePageGoesAway() {
        setClip(twelve)
        paste()
        // Closed from outside (the wallet page dismissed, the vault changing under it).
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertEquals(null, clipText())
    }

    @Test
    fun somethingCopiedSinceThePasteIsLeftAlone() {
        setClip(twelve)
        paste()
        setClip("https://example.com")
        pressBack()
        assertEquals("https://example.com", clipText())
    }

    /**
     * Freedom losing window focus with the page up, as on Home or another
     * app coming to the front: a dialog window of its own takes focus here.
     */
    private fun loseFocus(block: () -> Unit) {
        val dialog = rule.runOnIdle { android.app.Dialog(rule.activity).apply { setTitle("focus"); show() } }
        rule.waitUntil(5_000) { rule.runOnIdle { !rule.activity.hasWindowFocus() } }
        rule.waitForIdle()
        try {
            block()
        } finally {
            rule.runOnIdle { dialog.dismiss() }
        }
    }

    @Test
    fun pastedPhraseIsClearedWhenFreedomLosesFocus() {
        setClip(twelve)
        paste()
        loseFocus {
            // The page is still up, nothing else happened: cleared unread.
            assertTrue(shown)
            assertEquals(null, clipText())
        }
    }

    @Test
    fun somethingCopiedSinceThePasteIsLeftAloneWhenFreedomLosesFocus() {
        setClip(twelve)
        paste()
        setClip("https://example.com")
        loseFocus { assertEquals("https://example.com", clipText()) }
        // …and on Back once focus is back, read and still left alone.
        pressBack()
        assertEquals("https://example.com", clipText())
    }

    @Test
    fun wordsTypedAWordAtATimeLeaveTheClipboardAloneWhenFreedomLosesFocus() {
        // A swiped word goes in whole, but it's no phrase-sized paste.
        setClip(twelve)
        rule.onNode(field).performTextInput("abandon")
        rule.waitForIdle()
        loseFocus { assertEquals(twelve, clipText()) }
    }

    @Test
    fun aTypedPhraseLeavesTheClipboardAlone() {
        // The same words on the clipboard, but the user typed them: nothing
        // was pasted, so the clipboard isn't read or touched.
        setClip(twelve)
        rule.onNode(field).performTextInput("a")
        rule.waitForIdle()
        pressBack()
        assertNotNull(clipText())
    }
}
