package baby.freedom.mobile.browser

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.View

/**
 * The browser's hardware-keyboard shortcuts (#270): tablets, Chromebooks,
 * Samsung DeX. The defaults of desktop's registry
 * (`freedom-browser/src/shared/shortcuts.js`), minus what has no
 * counterpart here (windows, the sidebar, DevTools); remapping stays a
 * desktop feature.
 *
 * [reserved] shortcuts are the browser's before the page's: they never
 * reach the page, even from one of its text fields (as in Chrome, a page
 * can't keep the user from opening, closing or leaving a tab, or from
 * the address bar). Every other one goes first to a page text field
 * that has focus, and only acts if the page didn't use the key — a web
 * editor's own Ctrl+F wins ([KeyboardShortcutRouter]).
 *
 * [repeats]: held down, the key keeps acting (stepping through tabs or
 * zoom levels). The others act once per press — a held Ctrl+W closes
 * one tab, not every tab.
 *
 * [caretKey]: the key is also a text field's own caret movement (Alt+←/→
 * jumps to the line's start or end), so while one of the browser's own
 * fields — the address bar, the find bar — is being edited, it's the
 * field's, not the shortcut's.
 */
enum class Shortcut(
    val label: String,
    val group: Group,
    val reserved: Boolean = false,
    val repeats: Boolean = false,
    val caretKey: Boolean = false,
) {
    NewTab("New tab", Group.Tabs, reserved = true),
    NewPrivateTab("New private tab", Group.Tabs, reserved = true),
    CloseTab("Close tab", Group.Tabs, reserved = true),
    ReopenClosedTab("Reopen closed tab", Group.Tabs, reserved = true),
    NextTab("Next tab", Group.Tabs, reserved = true, repeats = true),
    PreviousTab("Previous tab", Group.Tabs, reserved = true, repeats = true),
    FocusAddressBar("Focus address bar", Group.Page, reserved = true),
    Reload("Reload", Group.Page),
    HardReload("Hard reload", Group.Page),
    FindInPage("Find in page", Group.Page),
    ZoomIn("Zoom in", Group.Page, repeats = true),
    ZoomOut("Zoom out", Group.Page, repeats = true),
    ZoomReset("Reset zoom", Group.Page),
    Back("Back", Group.Navigation, caretKey = true),
    Forward("Forward", Group.Navigation, caretKey = true),
    History("History", Group.Navigation),
    Downloads("Downloads", Group.Navigation),
    ;

    enum class Group(val label: String) { Tabs("Tabs"), Page("Page"), Navigation("Navigation") }
}

/**
 * A key with exactly these modifiers held — no others: Ctrl+Shift+T is
 * not Ctrl+T. Meta (the Search / Windows / Command key) is never part of
 * one; Meta combinations are the system's.
 */
data class KeyBinding(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** For [KeyboardShortcutInfo]. */
    val modifiers: Int
        get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON else 0)
}

private fun ctrl(keyCode: Int, shift: Boolean = false) = KeyBinding(keyCode, ctrl = true, shift = shift)

/**
 * Every binding, per shortcut; the first is the one the system's shortcut
 * helper (Meta+/) lists. The rest are the usual aliases: Ctrl+Tab next
 * to Ctrl+PageDown, F5 for Reload, and — for zoom in — `=` with or without
 * Shift, a layout's own `+` key and the keypad's.
 */
val SHORTCUT_BINDINGS: Map<Shortcut, List<KeyBinding>> = mapOf(
    Shortcut.NewTab to listOf(ctrl(KeyEvent.KEYCODE_T)),
    Shortcut.NewPrivateTab to listOf(ctrl(KeyEvent.KEYCODE_N, shift = true)),
    Shortcut.CloseTab to listOf(ctrl(KeyEvent.KEYCODE_W), ctrl(KeyEvent.KEYCODE_F4)),
    Shortcut.ReopenClosedTab to listOf(ctrl(KeyEvent.KEYCODE_T, shift = true)),
    Shortcut.NextTab to listOf(ctrl(KeyEvent.KEYCODE_TAB), ctrl(KeyEvent.KEYCODE_PAGE_DOWN)),
    Shortcut.PreviousTab to listOf(ctrl(KeyEvent.KEYCODE_TAB, shift = true), ctrl(KeyEvent.KEYCODE_PAGE_UP)),
    Shortcut.FocusAddressBar to listOf(ctrl(KeyEvent.KEYCODE_L)),
    Shortcut.Reload to listOf(ctrl(KeyEvent.KEYCODE_R), KeyBinding(KeyEvent.KEYCODE_F5)),
    Shortcut.HardReload to listOf(
        ctrl(KeyEvent.KEYCODE_R, shift = true),
        KeyBinding(KeyEvent.KEYCODE_F5, shift = true),
        ctrl(KeyEvent.KEYCODE_F5),
    ),
    Shortcut.FindInPage to listOf(ctrl(KeyEvent.KEYCODE_F)),
    Shortcut.ZoomIn to listOf(
        ctrl(KeyEvent.KEYCODE_EQUALS),
        ctrl(KeyEvent.KEYCODE_EQUALS, shift = true),
        ctrl(KeyEvent.KEYCODE_PLUS),
        ctrl(KeyEvent.KEYCODE_PLUS, shift = true),
        ctrl(KeyEvent.KEYCODE_NUMPAD_ADD),
    ),
    Shortcut.ZoomOut to listOf(ctrl(KeyEvent.KEYCODE_MINUS), ctrl(KeyEvent.KEYCODE_NUMPAD_SUBTRACT)),
    Shortcut.ZoomReset to listOf(ctrl(KeyEvent.KEYCODE_0), ctrl(KeyEvent.KEYCODE_NUMPAD_0)),
    Shortcut.Back to listOf(KeyBinding(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)),
    Shortcut.Forward to listOf(KeyBinding(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true)),
    Shortcut.History to listOf(ctrl(KeyEvent.KEYCODE_H)),
    Shortcut.Downloads to listOf(ctrl(KeyEvent.KEYCODE_J, shift = true)),
)

private val BY_BINDING: Map<KeyBinding, Shortcut> = buildMap {
    for ((shortcut, bindings) in SHORTCUT_BINDINGS) {
        for (binding in bindings) {
            check(put(binding, shortcut) == null) { "$binding is bound twice" }
        }
    }
}

/**
 * The shortcut [keyCode] with [metaState] held is bound to, or null.
 * Either Ctrl, Shift or Alt key counts; lock states (Caps, Num, Scroll)
 * and Fn don't matter, and Meta (or Sym) held means no shortcut.
 */
fun shortcutFor(keyCode: Int, metaState: Int): Shortcut? {
    if (metaState and (KeyEvent.META_META_MASK or KeyEvent.META_SYM_ON) != 0) return null
    return BY_BINDING[
        KeyBinding(
            keyCode = keyCode,
            ctrl = metaState and KeyEvent.META_CTRL_MASK != 0,
            shift = metaState and KeyEvent.META_SHIFT_MASK != 0,
            alt = metaState and KeyEvent.META_ALT_MASK != 0,
        ),
    ]
}

/**
 * What the browser does with a shortcut. Returns false when shortcuts
 * don't apply right now (a full-screen panel is up), so the key goes
 * on to whatever has focus as if it were bound to nothing. [repeat] is
 * a held key's auto-repeat: a shortcut that doesn't [Shortcut.repeats]
 * should take it (return true) without acting again.
 */
fun interface ShortcutTarget {
    fun onShortcut(shortcut: Shortcut, repeat: Boolean): Boolean
}

/**
 * Routes hardware key presses to [target] (#270). Two doors:
 *
 * - [beforeViews], from `Activity.dispatchKeyEvent`, sees every key
 *   before the focused view does. It takes a [Shortcut.reserved] one
 *   wherever focus is — so a page never sees Ctrl+T/W/L at all — and
 *   any shortcut unless a page's text field has focus ([pageEditing]).
 * - [unhandledInPage], from `WebViewClient.onUnhandledKeyEvent`, gets a
 *   key that text field was handed and didn't use: no `preventDefault()`
 *   from the page's script, no editing command. Only then does Ctrl+F,
 *   Ctrl+R and the rest act while the user is typing into a page.
 *
 * Page first only for a text field, not for any focused page: WebView
 * doesn't reliably hand a key back from a page with no editable focus —
 * Alt+←/→ and Ctrl+=/−/0 there came back to `onUnhandledKeyEvent` in
 * some runs and not in others on the API 36 emulator. From a text field
 * it does hand them back, except Ctrl+=/−/0, which WebView keeps as the
 * field's even though nothing is typed: zoom from the keyboard acts once
 * focus is off the field.
 *
 * A [Shortcut.caretKey] shortcut is left to one of the browser's own text
 * fields while it's being edited ([fieldEditing]).
 *
 * Shortcuts act on the press. A press [beforeViews] took takes its
 * release with it, so the view that has focus — a page included — never
 * sees a key-up whose key-down it never saw. A press the page was handed
 * first keeps its release where it goes anyway: the page saw the down.
 */
class KeyboardShortcutRouter {
    /** Installed by the browser screen while it's composed; null otherwise. */
    @Volatile
    var target: ShortcutTarget? = null

    /**
     * The view a page's HTML5 fullscreen is shown in
     * (`WebChromeClient.onShowCustomView`), while it's up. It stands in
     * for the page's WebView: Chromium moves the page — its focus and
     * input connection included — into it, so a text field focused in
     * fullscreen is the page's even though no [android.webkit.WebView]
     * has focus (#307 R3-F1).
     */
    @Volatile
    var fullscreenPage: View? = null

    /** Keys whose press [beforeViews] took, and whose release it still owes itself. */
    private val takenDown = mutableSetOf<Int>()

    /**
     * From `Activity.dispatchKeyEvent`; [pageEditing]: an editable element
     * in a page has focus (`WebView.onCheckIsTextEditor`); [fieldEditing]:
     * one of the browser's own text fields has. True if taken.
     */
    fun beforeViews(event: KeyEvent, pageEditing: Boolean, fieldEditing: Boolean = false): Boolean =
        beforeViews(Press.of(event), pageEditing, fieldEditing)

    /** From `WebViewClient.onUnhandledKeyEvent`. True if taken. */
    fun unhandledInPage(event: KeyEvent): Boolean = unhandledInPage(Press.of(event))

    internal fun beforeViews(press: Press, pageEditing: Boolean, fieldEditing: Boolean = false): Boolean {
        when (press.action) {
            KeyEvent.ACTION_UP -> return takenDown.remove(press.keyCode)
            KeyEvent.ACTION_DOWN -> Unit
            else -> return false
        }
        val shortcut = press.shortcut()
        val taken = shortcut != null &&
            (!pageEditing || shortcut.reserved) &&
            !(fieldEditing && shortcut.caretKey) &&
            run(shortcut, press)
        // A press not taken — also one after a taken press whose release
        // never came this way — lets its release go on as well.
        if (taken) takenDown += press.keyCode else takenDown -= press.keyCode
        return taken
    }

    internal fun unhandledInPage(press: Press): Boolean {
        val shortcut = press.shortcut() ?: return false
        // Taken before the page ever saw it; nothing arrives here twice.
        if (shortcut.reserved) return false
        return run(shortcut, press)
    }

    private fun run(shortcut: Shortcut, press: Press): Boolean =
        target?.onShortcut(shortcut, repeat = press.repeatCount > 0) ?: false

    /** The parts of a [KeyEvent] the routing reads. */
    internal data class Press(val action: Int, val keyCode: Int, val metaState: Int, val repeatCount: Int = 0) {
        fun shortcut(): Shortcut? =
            if (action == KeyEvent.ACTION_DOWN) shortcutFor(keyCode, metaState) else null

        companion object {
            fun of(event: KeyEvent) = Press(event.action, event.keyCode, event.metaState, event.repeatCount)
        }
    }
}

/**
 * The shortcuts as the system's keyboard-shortcut helper lists them
 * (`Activity.onProvideKeyboardShortcuts`, Meta+/), one group per
 * [Shortcut.Group], each shortcut under its first binding.
 * [privateTabs]: whether "New private tab" can run on this WebView.
 */
fun keyboardShortcutGroups(privateTabs: Boolean): List<KeyboardShortcutGroup> =
    Shortcut.Group.entries.map { group ->
        KeyboardShortcutGroup(
            group.label,
            Shortcut.entries
                .filter { it.group == group && (privateTabs || it != Shortcut.NewPrivateTab) }
                .map { shortcut ->
                    val binding = SHORTCUT_BINDINGS.getValue(shortcut).first()
                    KeyboardShortcutInfo(shortcut.label, binding.keyCode, binding.modifiers)
                },
        )
    }

/**
 * The activity a page's WebView lives in, told about a key the page
 * didn't use (`WebViewClient.onUnhandledKeyEvent`) — see
 * [KeyboardShortcutRouter.unhandledInPage]. True if it took the key.
 */
interface PageKeyEvents {
    fun onUnhandledPageKey(event: KeyEvent): Boolean
}
