package baby.freedom.mobile.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.widget.EditText
import android.widget.FrameLayout
import java.net.URI

/** The JavaScript dialogs a page can open: `alert`, `confirm`, `prompt`, `beforeunload`. */
internal enum class JsDialogKind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

/**
 * The dialog title naming who's asking, as WebView's own dialog has it:
 * `The page at "https://example.com" says:` for a page with a host, a
 * neutral title for one without (`data:`, `about:blank`, a malformed URL).
 */
internal fun jsDialogTitle(kind: JsDialogKind, url: String?): String {
    if (kind == JsDialogKind.BEFORE_UNLOAD) return "Leave this page?"
    val origin = runCatching { URI(url.orEmpty()) }.getOrNull()?.let { uri ->
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != null && !host.isNullOrEmpty()) {
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } else null
    }
    return if (origin != null) "The page at \"$origin\" says:" else "This page says:"
}

/**
 * Shows a private tab's (#86) JavaScript dialog in a `FLAG_SECURE`
 * window. WebView's default dialogs are windows of their own, outside
 * the Activity window [PrivateScreenGuard] secures, so the page's text
 * and origin would show in screenshots, casts and — on API 30–32, which
 * lack `setRecentsScreenshotEnabled` — the Recents snapshot. Same
 * buttons and results as WebView's: OK/Cancel, the prompt's text field
 * (learning off, as in the tab's other fields), and back/outside tap =
 * Cancel. Always handles the dialog (returns true), so the default one
 * never appears for a private tab: with no Activity to show it in, the
 * page's call is cancelled.
 */
internal fun showPrivateJsDialog(
    context: Context,
    kind: JsDialogKind,
    url: String?,
    message: String?,
    defaultValue: String?,
    result: JsResult,
): Boolean {
    val activity = context.findHostActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        result.cancel()
        return true
    }
    var answered = false
    val input = if (kind == JsDialogKind.PROMPT) {
        EditText(activity).apply {
            setText(defaultValue.orEmpty())
            setSingleLine()
            // Typing replaces the page's default, as in desktop browsers.
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = tabImeOptions(EditorInfo.IME_ACTION_DONE, private = true)
        }
    } else null
    val builder = AlertDialog.Builder(activity)
        .setTitle(jsDialogTitle(kind, url))
        .setOnDismissListener { if (!answered) result.cancel() }
    if (kind == JsDialogKind.BEFORE_UNLOAD) {
        builder.setMessage("Changes you made may not be saved.")
    } else if (!message.isNullOrEmpty()) {
        builder.setMessage(message)
    }
    if (input != null) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        builder.setView(FrameLayout(activity).apply {
            setPadding(pad, 0, pad, 0)
            addView(input)
        })
    }
    builder.setPositiveButton(if (kind == JsDialogKind.BEFORE_UNLOAD) "Leave" else "OK") { _, _ ->
        answered = true
        if (input != null && result is JsPromptResult) result.confirm(input.text.toString())
        else result.confirm()
    }
    if (kind != JsDialogKind.ALERT) {
        builder.setNegativeButton(if (kind == JsDialogKind.BEFORE_UNLOAD) "Stay" else "Cancel") { _, _ ->
            answered = true
            result.cancel()
        }
    }
    val dialog = builder.create()
    dialog.window?.apply {
        // Before show(): the window's first frame must already be secure.
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (input != null) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    dialog.show()
    return true
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
