package baby.freedom.mobile.browser

import android.accessibilityservice.AccessibilityService
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
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.ui.FreedomTheme
import baby.freedom.mobile.wallet.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * When Freedom last read the clipboard, as the system records it
     * (`READ_CLIPBOARD`, the app op Android's "pasted from your clipboard"
     * notice is shown for): a typed phrase must never cause one. The
     * record's absolute times only, so two looks with no read between
     * them agree.
     */
    private fun lastClipboardRead(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys appops --package $pkg")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
        // "READ_CLIPBOARD (allow):\n  null=[\n    Access: [top-s] 2026-09-30 02:46:10.958 (-2s21ms)\n  ]"
        val section = text.substringAfter("READ_CLIPBOARD (", "").substringBefore("]\n", "")
        return Regex("""Access: \[[^]]*]\s*(\S+ \S+)""").findAll(section).joinToString { it.groupValues[1] }
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
     * Freedom losing window focus to a window of its own (a dialog): the
     * clipboard can still be read then, so what's on it decides.
     */
    private fun loseFocusToOwnWindow(block: () -> Unit) {
        val dialog = rule.runOnIdle { android.app.Dialog(rule.activity).apply { setTitle("focus"); show() } }
        rule.waitUntil(5_000) { rule.runOnIdle { !rule.activity.hasWindowFocus() } }
        rule.waitForIdle()
        try {
            block()
        } finally {
            rule.runOnIdle { dialog.dismiss() }
        }
    }

    /**
     * Freedom losing window focus to another app, Home here: the clipboard
     * can't be read by Freedom then (Android checks the focused window's
     * uid), so anything cleared was cleared unread. [block] gets to read it
     * as the shell may, with `READ_CLIPBOARD_IN_BACKGROUND`, and is told
     * what Freedom itself reads (nothing, proving it couldn't look).
     */
    private fun goHome(block: (ownRead: String?) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        rule.waitUntil(10_000) { rule.runOnIdle { !rule.activity.hasWindowFocus() } }
        rule.waitForIdle()
        // Freedom's own view, without focus: nothing, whatever is there.
        val ownRead = clipText()
        val shell = instrumentation.uiAutomation
        shell.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            block(ownRead)
        } finally {
            shell.dropShellPermissionIdentity()
        }
    }

    @Test
    fun pastedPhraseIsClearedWhenFreedomLosesFocusToItsOwnWindow() {
        setClip(twelve)
        paste()
        loseFocusToOwnWindow {
            // The page is still up, the clipboard readable: the paste is there, cleared.
            assertTrue(shown)
            assertEquals(null, clipText())
        }
    }

    @Test
    fun pastedPhraseIsClearedUnreadWhenFreedomGoesToTheBackground() {
        setClip(twelve)
        paste()
        goHome { ownRead ->
            assertTrue(shown)
            assertEquals(null, ownRead)
            // Cleared without Freedom being able to look: the clipIsPaste path.
            assertEquals(null, clipText())
        }
    }

    @Test
    fun somethingCopiedSinceThePasteIsLeftAloneWhenFreedomGoesToTheBackground() {
        setClip(twelve)
        paste()
        setClip("https://example.com")
        goHome { ownRead ->
            // Unreadable to Freedom (so the check above really ran blind)…
            assertEquals(null, ownRead)
            // …and not cleared: a change was seen since the paste.
            assertEquals("https://example.com", clipText())
        }
    }

    @Test
    fun somethingCopiedSinceThePasteIsLeftAloneWhenFreedomLosesFocus() {
        setClip(twelve)
        paste()
        setClip("https://example.com")
        loseFocusToOwnWindow { assertEquals("https://example.com", clipText()) }
        // …and on Back once focus is back, read and still left alone.
        pressBack()
        assertEquals("https://example.com", clipText())
    }

    private fun selectAll() {
        rule.onNode(field).performTextInputSelection(TextRange(0, twelve.length))
        rule.waitForIdle()
    }

    /**
     * The phrase already in the field, and forgotten (put in once, then a
     * focus loss that looked at the clipboard and found something else),
     * copied again and pasted over itself: the text doesn't change, the
     * paste still counts.
     */
    private fun pasteOverItself() {
        setClip("https://example.com")
        rule.onNode(field).performTextInput(twelve)
        rule.waitForIdle()
        loseFocusToOwnWindow { assertEquals("https://example.com", clipText()) }
        rule.waitUntil(5_000) { rule.runOnIdle { rule.activity.hasWindowFocus() } }
        setClip(twelve)
        selectAll()
        paste()
    }

    @Test
    fun aPhrasePastedOverItselfIsClearedOnBack() {
        pasteOverItself()
        pressBack()
        assertFalse(shown)
        assertEquals(null, clipText())
    }

    @Test
    fun aPhrasePastedOverItselfIsClearedUnreadWhenFreedomGoesToTheBackground() {
        pasteOverItself()
        goHome { ownRead ->
            assertEquals(null, ownRead)
            assertEquals(null, clipText())
        }
    }

    @Test
    fun aPastedPhraseCutBackOutIsClearedUnreadWhenFreedomGoesToTheBackground() {
        setClip(twelve)
        paste()
        selectAll()
        rule.onNode(field).performSemanticsAction(SemanticsActions.CutText)
        rule.waitForIdle()
        // The Cut put it back on the clipboard.
        assertEquals(twelve, clipText())
        goHome { ownRead ->
            assertEquals(null, ownRead)
            assertEquals(null, clipText())
        }
    }

    @Test
    fun aPastedPhraseCutBackOutIsClearedOnBack() {
        setClip(twelve)
        paste()
        selectAll()
        rule.onNode(field).performSemanticsAction(SemanticsActions.CutText)
        rule.waitForIdle()
        assertEquals(twelve, clipText())
        pressBack()
        assertEquals(null, clipText())
    }

    @Test
    fun wordsTypedAWordAtATimeLeaveTheClipboardAloneWhenFreedomLosesFocus() {
        // A swiped word goes in whole, but it's no phrase-sized paste.
        setClip(twelve)
        rule.onNode(field).performTextInput("abandon")
        rule.waitForIdle()
        goHome { assertEquals(twelve, clipText()) }
    }

    @Test
    fun aPhraseTypedAWordAtATimeNeverReadsTheClipboard() {
        // Swipe typing, or a keyboard suggestion taken, puts a whole word in
        // at once: no paste, so leaving the page doesn't read the clipboard
        // (and Android shows no "pasted from your clipboard" notice).
        setClip(twelve)
        for (word in twelve.split(" ")) {
            rule.onNode(field).performTextInput("$word ")
            rule.waitForIdle()
        }
        val reads = lastClipboardRead()
        pressBack()
        assertFalse(shown)
        assertEquals(reads, lastClipboardRead())
        assertEquals(twelve, clipText())
        // The watch works: that read of the test's own was seen.
        assertNotEquals(reads, lastClipboardRead())
    }

    @Test
    fun aTypedPhraseLeavesTheClipboardAlone() {
        // The same words on the clipboard, but the user typed them: nothing
        // was pasted, so the clipboard isn't read or touched.
        setClip(twelve)
        rule.onNode(field).performTextInput("a")
        rule.waitForIdle()
        val reads = lastClipboardRead()
        pressBack()
        assertEquals(reads, lastClipboardRead())
        assertNotNull(clipText())
    }
}
