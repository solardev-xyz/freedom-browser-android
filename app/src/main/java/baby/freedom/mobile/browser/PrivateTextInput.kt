package baby.freedom.mobile.browser

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * The IME options a text field of a tab asks for: a private tab's (#86)
 * add [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING], so the keyboard
 * doesn't learn what is typed there — otherwise Gboard & co. would add
 * an unusual URL or search term to their dictionary and suggest it later
 * in other apps, outliving the private tab. Chrome sets the same flag in
 * incognito.
 */
internal fun tabImeOptions(imeOptions: Int, private: Boolean): Int =
    if (private) imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else imeOptions

/**
 * Wraps a tab's text fields (address bar, find bar) so the input sessions
 * they open carry [tabImeOptions]. Always composed, whatever [private]
 * is, so moving between a private and a normal tab doesn't change the
 * tree shape and reset the field's state; [private] is read when the
 * field starts its input session.
 *
 * [onCommitText], if given, hears the text the keyboard commits, just
 * before the field applies it (the Import page tells a paste over the very
 * same text from a mere selection change by it — [PastedPhrases]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TabTextInput(
    private: Boolean,
    onCommitText: ((CharSequence?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isPrivate by rememberUpdatedState(private)
    val commitText by rememberUpdatedState(onCommitText)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val tagged = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    request.createInputConnection(outAttributes).let { connection ->
                        outAttributes.imeOptions = tabImeOptions(outAttributes.imeOptions, isPrivate)
                        if (commitText == null) {
                            connection
                        } else {
                            object : InputConnectionWrapper(connection, false) {
                                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                                    commitText?.invoke(text)
                                    return super.commitText(text, newCursorPosition)
                                }

                                @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                                override fun commitText(
                                    text: CharSequence,
                                    newCursorPosition: Int,
                                    textAttribute: TextAttribute?,
                                ): Boolean {
                                    commitText?.invoke(text)
                                    return super.commitText(text, newCursorPosition, textAttribute)
                                }
                            }
                        }
                    }
            }
            nextHandler.startInputMethod(tagged)
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
