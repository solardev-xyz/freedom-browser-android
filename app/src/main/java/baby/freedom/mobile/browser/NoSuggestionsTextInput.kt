package baby.freedom.mobile.browser

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard options for a field that takes a URL — the address bar and the
 * URL-type settings (custom search template, external gateway endpoints),
 * as Chrome and Safari type their address fields (#172): the URI keyboard
 * (`/` and `.` keys), no auto-correct, no auto-capitalisation.
 *
 * `autoCorrectEnabled = false` alone isn't enough: with the default
 * [KeyboardType.Text] Gboard ignores it and keeps "fixing" words. The
 * URI variation plus [NoSuggestionsTextInput] is what it honours, so
 * always wrap the field in that.
 */
internal fun urlKeyboardOptions(imeAction: ImeAction = ImeAction.Default): KeyboardOptions =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
        imeAction = imeAction,
    )

/**
 * The input type a no-correction field's input session asks for:
 * [inputType] plus [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS], minus any
 * auto-correct / auto-complete flag. [KeyboardOptions] has no way to ask
 * for NO_SUGGESTIONS, hence [NoSuggestionsTextInput].
 *
 * Gboard ignores `autoCorrectEnabled = false` on a plain Text field
 * (#172): the find bar, with exactly that, still turned "teh" into
 * "the" on space. NO_SUGGESTIONS is what it honours there. On the URI
 * field it also drops the strip while a URL is typed; for plain words
 * Gboard (15.x) still shows next-word predictions in the strip, but
 * nothing is replaced unless the user taps one.
 */
internal fun noSuggestionsInputType(inputType: Int): Int =
    (inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) and
        InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv() and
        InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE.inv()

/**
 * Wraps a text field that must not be auto-corrected — the URL fields
 * (see [urlKeyboardOptions]) and the find bar — so its input session
 * carries [noSuggestionsInputType]. Composes with an outer
 * [TabTextInput]: this runs first and hands the tagged request on.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NoSuggestionsTextInput(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val tagged = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    request.createInputConnection(outAttributes).also {
                        outAttributes.inputType = noSuggestionsInputType(outAttributes.inputType)
                    }
            }
            nextHandler.startInputMethod(tagged)
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
