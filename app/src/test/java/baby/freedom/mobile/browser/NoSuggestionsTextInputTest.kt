package baby.freedom.mobile.browser

import android.text.InputType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoSuggestionsTextInputTest {
    private val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

    @Test
    fun urlFieldsAskForNoSuggestionsAndKeepTheUriVariation() {
        val type = noSuggestionsInputType(uri)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(uri, type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS.inv())
    }

    @Test
    fun urlFieldsDropAnyAutoCorrectOrAutoComplete() {
        val type = noSuggestionsInputType(
            uri or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
        )
        assertEquals(0, type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(0, type and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
    }

    @Test
    fun urlKeyboardIsUriWithoutCorrectionOrCapitals() {
        val options = urlKeyboardOptions(ImeAction.Go)
        assertEquals(KeyboardType.Uri, options.keyboardType)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertFalse(options.autoCorrectEnabled!!)
        assertEquals(ImeAction.Go, options.imeAction)
    }
}
