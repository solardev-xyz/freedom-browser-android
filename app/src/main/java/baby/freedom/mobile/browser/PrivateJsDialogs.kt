package baby.freedom.mobile.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.os.SystemClock
import android.text.InputType
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.net.URI

/** The JavaScript dialogs a page can open: `alert`, `confirm`, `prompt`, `beforeunload`. */
internal enum class JsDialogKind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

/**
 * The dialog title naming who's asking, as WebView's own dialog has it:
 * `The page at "https://example.com" says:` for a page with a host, a
 * neutral title for one without (`data:`, `about:blank`, a malformed URL).
 */
internal fun jsDialogTitle(kind: JsDialogKind, url: String?): String {
    if (kind == JsDialogKind.BEFORE_UNLOAD) return Strings.get(R.string.browser_js_leave_page_title)
    val origin = runCatching { URI(url.orEmpty()) }.getOrNull()?.let { uri ->
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != null && !host.isNullOrEmpty()) {
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } else null
    }
    return if (origin != null) Strings.get(R.string.browser_js_page_at_says, origin) else Strings.get(R.string.browser_js_page_says)
}

/**
 * A tab's guard against a page that won't stop opening JavaScript
 * dialogs (#466). Every dialog is app-modal, so a page looping
 * `alert()` would otherwise keep the user out of the menu and the tab
 * switcher for good — and a restored tab starts the loop again after a
 * relaunch. As in Chrome:
 *
 * - A dialog that comes within [REPEAT_WINDOW_MS] of the tab's last
 *   one being answered offers "Don't let this page show more dialogs"
 *   ([Admit.Show.offerBlock]); ticking it [block]s the tab.
 * - A flood — [FLOOD_COUNT] dialogs asked for within [FLOOD_WINDOW_MS],
 *   which no user answering them could keep up with (each one's buttons
 *   wait [PromptTapGuard.PROTECTION_MS]) — blocks it without asking: a
 *   page out of view whose dialogs are answered for it, say.
 *
 * Blocked, every dialog the tab's pages ask for is answered at once,
 * unseen ([Admit.Refuse]), until a navigation the user started
 * themselves commits ([committed] `byUser`): their address, Reload or
 * Back / Forward, or a link they tapped on a page — as Chrome lifts it
 * on any navigation with a user gesture, so the site a tapped link leads
 * to gets its `confirm()` asked (R1-F1). At the commit, not the tap:
 * until then the page that looped is still the one on screen, and
 * lifting the block at the tap would hand it its loop back. Which commit
 * is the user's is the tab's WebView's to say, per navigation
 * ([PageWebView.commitIsUsersOwn]): one that never commits leaves
 * nothing armed for the page's own next one (R1-M1). A navigation the
 * page starts without a tap doesn't lift it. Main thread only.
 */
internal class JsDialogGate(private val clock: () -> Long = SystemClock::uptimeMillis) {
    sealed interface Admit {
        /** Answered unseen: `alert` returns, `confirm` false, `prompt` null, `beforeunload` leaves. */
        data object Refuse : Admit

        /** Queued for the user, with the option to block the page's further dialogs when [offerBlock]. */
        data class Show(val offerBlock: Boolean) : Admit
    }

    var blocked = false
        private set
    private var lastSettledAt: Long? = null
    private val asked = ArrayDeque<Long>()

    /** The page asks for a dialog. */
    fun admit(): Admit {
        if (blocked) return Admit.Refuse
        val now = clock()
        asked.addLast(now)
        while (asked.isNotEmpty() && now - asked.first() >= FLOOD_WINDOW_MS) asked.removeFirst()
        if (asked.size >= FLOOD_COUNT) {
            block()
            return Admit.Refuse
        }
        val last = lastSettledAt
        return Admit.Show(offerBlock = last != null && now - last in 0 until REPEAT_WINDOW_MS)
    }

    /** A dialog the gate let through was answered (by the user or not). */
    fun settled() {
        lastSettledAt = clock()
    }

    /** No more dialogs from this tab's pages until [allow]. */
    fun block() {
        blocked = true
        asked.clear()
    }

    /**
     * A main-frame document committed in the tab, [byUser]: from a
     * navigation the user started themselves. Theirs lifts the block, and
     * the page they reach starts afresh.
     */
    fun committed(byUser: Boolean) {
        if (!byUser) return
        blocked = false
        lastSettledAt = null
        asked.clear()
    }

    companion object {
        const val REPEAT_WINDOW_MS = 5_000L
        const val FLOOD_COUNT = 10
        const val FLOOD_WINDOW_MS = 2_000L
    }
}

/**
 * Whether a main-frame document committing at [url] is the navigation
 * [chain] follows, hop by hop, when that one is the user's own
 * ([usersOwn]): what lifts a [JsDialogGate] block (#466). A navigation
 * that ended without a commit has ended its [chain] too, so it leaves
 * nothing for a later commit to take (R1-M1).
 */
internal fun usersOwnCommit(usersOwn: Boolean, chain: UserNamedChain, url: String?): Boolean =
    usersOwn && url != null && chain.asker()?.let { sameRequestUrl(it, url) } == true

/**
 * A JavaScript dialog a tab's page has opened and is blocked on (#246).
 * Every tab's `alert`/`confirm`/`prompt`/`beforeunload` comes through
 * one of these, held on [BrowserState.jsDialog] rather than shown by
 * WebView: [BrowserScreen] shows it ([showJsDialog]) when it's the
 * tab's turn among the page's prompts ([modalPromptTurn]), so it never
 * stacks on a dApp approval sheet or the long-press menu, and never
 * pops over another tab.
 *
 * Answered exactly once, by whichever of [confirm]/[cancel]/[withdraw]
 * comes first; later calls do nothing. [answer] hands it to the page
 * ([answerJsResult]): true for OK/Leave, with a prompt's text; for a
 * tab's `beforeunload` it's also how the tab hears that Stay ended its
 * navigation without a commit (#180, R2-F2). [onSettled] lets the tab
 * forget the request. Main thread only.
 *
 * Taking the dialog's window down is not itself an answer; whatever
 * takes it down answers it with [withdraw] — its tab closing, the user
 * switching away from it, or the WebView host going away (the Activity
 * finishing or being relaunched alike: every WebView goes with the
 * host, so a dialog isn't carried over to the next one). A dialog is
 * never shown again for a page that went away under it.
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
    /** Offer "Don't let this page show more dialogs" ([JsDialogGate], #466). */
    val offerBlock: Boolean = false,
    private val onBlock: () -> Unit = {},
) {
    /**
     * The user ticked "Don't let this page show more dialogs": called
     * before their answer, so the page's next dialog is already refused.
     */
    fun blockMore() {
        if (!answered && offerBlock) onBlock()
    }

    var answered = false
        private set

    /** Whether the user has been shown it ([showJsDialog]). */
    var seen = false
        internal set

    /** OK / Leave; [text] is a prompt's answer. */
    fun confirm(text: String? = null) {
        if (answered) return
        answered = true
        answer(true, text)
        onSettled(this)
    }

    /** Cancel / Stay / Back: the user's no. */
    fun cancel() {
        if (answered) return
        answered = true
        answer(false, null)
        onSettled(this)
    }

    /**
     * Answers it without the user, when it can't be left waiting for
     * them: its page isn't on screen (a background tab, a tab under a
     * full-screen panel), its tab is closing, or its WebView is going
     * away. A page's pending dialog blocks the renderer every tab
     * shares, so a page out of view can't be left waiting on one (see
     * [BrowserScreen]). As in Chrome for a hidden tab: `alert` returns,
     * `confirm` is false and `prompt` is null. A `beforeunload` the
     * user never saw lets the navigation go ahead (Leave), as Chrome
     * does for a background tab: a page out of view can't hold a
     * navigation hostage — least of all the app's own reload that takes
     * a document an unverified gateway served off its tab (#125,
     * [SweptReload]), which Stay would refuse over and over. One the
     * user was shown and left unanswered keeps the page (Stay): their
     * edits aren't thrown away on a guess.
     */
    fun withdraw() {
        if (kind == JsDialogKind.BEFORE_UNLOAD && !seen) confirm() else cancel()
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
 * back/outside tap = Cancel. Like the app's other prompts, its buttons
 * (and an outside tap) ignore taps for the first
 * [PromptTapGuard.PROTECTION_MS] it's on screen, counted from its first
 * drawn frame, showing disabled meanwhile: a dialog queued behind
 * another prompt comes up the instant that one is answered, so the
 * second tap of a double-tap on that prompt's button would otherwise
 * land on this one's OK/Leave. Back, a deliberate key, stays live.
 * Returns the dialog so the caller can take
 * it down when it loses its turn — which by itself leaves the request
 * unanswered; whatever took the turn [JsDialogRequest.withdraw]s it — or
 * null, with the request withdrawn, when there's no Activity to show it
 * in.
 */
internal fun showJsDialog(context: Context, request: JsDialogRequest): AlertDialog? {
    val kind = request.kind
    val activity = context.findHostActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        request.withdraw()
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
    // The page's way out of an endless loop of dialogs (#466).
    val blockBox = if (request.offerBlock) {
        CheckBox(activity).apply {
            setText(R.string.browser_js_block_more)
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
        }
    } else null
    fun answerBlock() {
        if (blockBox?.isChecked == true) request.blockMore()
    }
    val builder = AlertDialog.Builder(activity)
        .setTitle(jsDialogTitle(kind, request.url))
        // Back / outside tap only: dismiss() taking the window down when
        // the dialog loses its turn doesn't answer the page.
        .setOnCancelListener {
            answerBlock()
            request.cancel()
        }
    if (kind == JsDialogKind.BEFORE_UNLOAD) {
        builder.setMessage(activity.getString(R.string.browser_js_leave_page_message))
    } else if (!request.message.isNullOrEmpty()) {
        builder.setMessage(request.message)
    }
    if (input != null || blockBox != null) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        builder.setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            input?.let(::addView)
            blockBox?.let(::addView)
        })
    }
    val guard = PromptTapGuard(SystemClock::uptimeMillis)
    builder.setPositiveButton(if (kind == JsDialogKind.BEFORE_UNLOAD) R.string.browser_js_leave else R.string.common_ok, null)
    if (kind != JsDialogKind.ALERT) {
        builder.setNegativeButton(if (kind == JsDialogKind.BEFORE_UNLOAD) R.string.browser_js_stay else R.string.common_cancel, null)
    }
    val dialog = builder.create()
    dialog.window?.apply {
        // Before show(): the window's first frame must already be secure.
        if (request.secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (input != null) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    request.seen = true
    // Listeners set on the buttons themselves, not the builder's: the
    // builder's always take the dialog down, even for a tap the guard
    // ignores.
    val buttons = listOfNotNull(
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            setOnClickListener {
                if (!guard.accepts()) return@setOnClickListener
                answerBlock()
                request.confirm(input?.text?.toString())
                dialog.dismiss()
            }
        },
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.takeIf { kind != JsDialogKind.ALERT }?.apply {
            setOnClickListener {
                if (!guard.accepts()) return@setOnClickListener
                answerBlock()
                request.cancel()
                dialog.dismiss()
            }
        },
    )
    // The box waits too: a double tap meant for the prompt before
    // shouldn't tick it.
    val guarded = buttons + listOfNotNull(blockBox)
    guarded.forEach { it.isEnabled = false }
    dialog.window?.decorView?.let { decor ->
        val arm = Runnable {
            if (!dialog.isShowing) return@Runnable
            guarded.forEach { it.isEnabled = true }
            dialog.setCanceledOnTouchOutside(true)
        }
        // Count from the first frame the dialog is actually drawn in.
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var drawn = false
            override fun onDraw() {
                if (drawn) return
                drawn = true
                guard.onShown()
                decor.postDelayed(arm, guard.remainingMs())
                // Not from inside onDraw: the observer is being iterated.
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
    }
    return dialog
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
