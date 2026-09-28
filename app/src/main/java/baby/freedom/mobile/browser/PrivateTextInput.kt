package baby.freedom.mobile.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TabTextInput(private: Boolean, content: @Composable () -> Unit) {
    val isPrivate by rememberUpdatedState(private)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val tagged = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions = tabImeOptions(outAttributes.imeOptions, isPrivate)
                    }
            }
            nextHandler.startInputMethod(tagged)
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
