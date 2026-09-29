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
 * A JavaScript dialog a tab's page has opened and is blocked on (#246).
 * Every tab's `alert`/`confirm`/`prompt`/`beforeunload` comes through
 * one of these, held on [BrowserState.jsDialog] rather than shown by
 * WebView: [BrowserScreen] shows it ([showJsDialog]) when it's the
 * tab's turn among the page's prompts ([modalPromptTurn]), so it never
 * stacks on a dApp approval sheet or the long-press menu, and never
 * pops over another tab.
 *
 * Answered exactly once, by whichever of [confirm]/[cancel] comes
 * first; later calls do nothing. [answer] hands it to the page
 * ([answerJsResult]): true for OK/Leave, with a prompt's text; for a
 * tab's `beforeunload` it's also how the tab hears that Stay ended its
 * navigation without a commit (#180, R2-F2). [onSettled] lets the tab
 * forget the request. Main thread only.
 */
internal class JsDialogRequest(
    val kind: JsDialogKind,
    val url: String?,
    val message: String?,
    val defaultValue: String?,
    /** The dialog window is `FLAG_SECURE`: the tab is private (#86). */
    val secure: Boolean,
    private val answer: (confirmed: Boolean, text: String?) -> Unit,
    private val onSettled: (JsDialogRequest) -> Unit = {},
) {
    var answered = false
        private set

    /** OK / Leave; [text] is a prompt's answer. */
    fun confirm(text: String? = null) {
        if (answered) return
        answered = true
        answer(true, text)
        onSettled(this)
    }

    /**
     * Cancel / Stay / Back — and what the page gets when the dialog
     * can't be shown to the user: its tab left the screen or closed.
     * A page's pending dialog blocks the renderer every tab shares, so
     * a tab out of view can't be left waiting on one (see
     * [BrowserScreen]): `alert` returns, `confirm` is false, `prompt`
     * is null and `beforeunload` stays, as in Chrome for a background
     * tab.
     */
    fun cancel() {
        if (answered) return
        answered = true
        answer(false, null)
        onSettled(this)
    }
}

/** Answers WebView's [result] for a dialog: OK (with a prompt's [text]) or Cancel. */
internal fun answerJsResult(result: JsResult, confirmed: Boolean, text: String?) {
    when {
        !confirmed -> result.cancel()
        result is JsPromptResult -> result.confirm(text.orEmpty())
        else -> result.confirm()
    }
}

/**
 * Shows [request] in a dialog of our own — `FLAG_SECURE` for a private
 * tab (#86): WebView's default dialogs are windows of their own,
 * outside the Activity window [PrivateScreenGuard] secures, so the
 * page's text and origin would show in screenshots, casts and — on API
 * 30–32, which lack `setRecentsScreenshotEnabled` — the Recents
 * snapshot. Same buttons and results as WebView's: OK/Cancel, the
 * prompt's text field (learning off, as in the tab's other fields), and
 * back/outside tap = Cancel. Returns the dialog so the caller can take
 * it down (which cancels the request) when it loses its turn, or null
 * — with the request cancelled — when there's no Activity to show it in.
 */
internal fun showJsDialog(context: Context, request: JsDialogRequest): AlertDialog? {
    val kind = request.kind
    val activity = context.findHostActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        request.cancel()
        return null
    }
    val input = if (kind == JsDialogKind.PROMPT) {
        EditText(activity).apply {
            setText(request.defaultValue.orEmpty())
            setSingleLine()
            // Typing replaces the page's default, as in desktop browsers.
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = tabImeOptions(EditorInfo.IME_ACTION_DONE, private = request.secure)
        }
    } else null
    val builder = AlertDialog.Builder(activity)
        .setTitle(jsDialogTitle(kind, request.url))
        .setOnDismissListener { request.cancel() }
    if (kind == JsDialogKind.BEFORE_UNLOAD) {
        builder.setMessage("Changes you made may not be saved.")
    } else if (!request.message.isNullOrEmpty()) {
        builder.setMessage(request.message)
    }
    if (input != null) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        builder.setView(FrameLayout(activity).apply {
            setPadding(pad, 0, pad, 0)
            addView(input)
        })
    }
    builder.setPositiveButton(if (kind == JsDialogKind.BEFORE_UNLOAD) "Leave" else "OK") { _, _ ->
        request.confirm(input?.text?.toString())
    }
    if (kind != JsDialogKind.ALERT) {
        builder.setNegativeButton(if (kind == JsDialogKind.BEFORE_UNLOAD) "Stay" else "Cancel") { _, _ ->
            request.cancel()
        }
    }
    val dialog = builder.create()
    dialog.window?.apply {
        // Before show(): the window's first frame must already be secure.
        if (request.secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (input != null) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    dialog.show()
    return dialog
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
