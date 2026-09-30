package baby.freedom.mobile.browser

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateTextInputTest {
    private val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN

    @Test
    fun privateTabFieldsAskTheKeyboardNotToLearn() {
        val options = tabImeOptions(base, private = true)
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        // The action and every flag the field already set survive.
        assertEquals(base, options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv())
    }

    @Test
    fun normalTabFieldsAreLeftAlone() {
        assertEquals(base, tabImeOptions(base, private = false))
    }

    @Test
    fun `a commit is heard before it is applied, and taken back if refused`() {
        val heard = mutableListOf<CharSequence?>()
        // Applied: heard once, before the field saw it.
        assertTrue(reportingCommit("abandon ability", { heard += it }) { heard += "<applied>"; true })
        assertEquals(listOf<CharSequence?>("abandon ability", "<applied>"), heard)
        // Refused (an inactive connection): taken back with a null.
        heard.clear()
        assertFalse(reportingCommit("abandon ability", { heard += it }) { false })
        assertEquals(listOf<CharSequence?>("abandon ability", null), heard)
        // Nothing listening: the commit's own answer passes through.
        assertTrue(reportingCommit("a", null) { true })
    }

    @Test
    fun `a refused commit leaves no paste for the next edit (R4-F2)`() {
        val phrase = List(12) { "abandon" }.joinToString(" ")
        val all = TextFieldValue(phrase, TextRange(0, phrase.length))
        val end = TextFieldValue(phrase, TextRange(phrase.length))
        val pastes = PastedPhrases()
        reportingCommit(phrase, pastes::committing) { false }
        // A deselect at the end of the identical selection: no paste.
        pastes.edit(all, end)
        assertTrue(pastes.words.isEmpty())
        assertFalse(pastes.clipIsPaste)
    }
}
