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

    @Test
    fun `a commit dropped by a close inside a batch leaves no paste (R5-F1)`() {
        val phrase = List(12) { "abandon" }.joinToString(" ")
        val all = TextFieldValue(phrase, TextRange(0, phrase.length))
        val end = TextFieldValue(phrase, TextRange(phrase.length))
        val pastes = PastedPhrases()
        val reports = CommitReports(pastes::committing)
        reports.beginBatch()
        // Recorded in the batch, answered true, never applied: the connection closes first.
        assertTrue(reports.commit(phrase) { true })
        reports.closed()
        pastes.edit(all, end)
        assertTrue(pastes.words.isEmpty())
        assertFalse(pastes.clipIsPaste)
    }

    @Test
    fun `a batch that ends keeps its commit, and a later close takes nothing back`() {
        val heard = mutableListOf<CharSequence?>()
        val reports = CommitReports { heard += it }
        reports.beginBatch()
        reports.beginBatch()
        assertTrue(reports.commit("abandon ability") { true })
        reports.endBatch()
        reports.endBatch()
        reports.closed()
        assertEquals(listOf<CharSequence?>("abandon ability"), heard)
        // Nested, closed before the outer end: taken back.
        heard.clear()
        reports.beginBatch()
        reports.beginBatch()
        reports.commit("abandon ability") { true }
        reports.endBatch()
        reports.closed()
        assertEquals(listOf<CharSequence?>("abandon ability", null), heard)
        // Outside any batch: applied at once, a close takes nothing back.
        heard.clear()
        reports.commit("abandon ability") { true }
        reports.closed()
        assertEquals(listOf<CharSequence?>("abandon ability"), heard)
        // Refused inside a batch: taken back at once, and only once.
        heard.clear()
        reports.beginBatch()
        assertFalse(reports.commit("abandon ability") { false })
        reports.closed()
        assertEquals(listOf<CharSequence?>("abandon ability", null), heard)
    }
}
