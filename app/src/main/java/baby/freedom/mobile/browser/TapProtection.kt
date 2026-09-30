package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.util.WeakHashMap
import kotlinx.coroutines.delay

/**
 * Tapjacking protection for the wallet's approval and confirmation
 * surfaces (#240, security audit #229). Three layers:
 *
 * - **Overlays hidden.** While one is composed ([rememberArmedTapGuard],
 *   [HideOverlayWindows]) the Activity's window asks Android to hide
 *   every other app's overlay (`setHideOverlayWindows`, Android 12+,
 *   `HIDE_OVERLAY_WINDOWS`) — no bubble, "screen filter" or fake dialog
 *   can sit over the sheet and steer the user's tap.
 * - **Obscured taps dropped.** Android 11 (minSdk) has no way to hide
 *   them, so a confirm button ([protectedPress]) drops a press that
 *   Android marks as having come through, or while, another app's window
 *   covered ours ([touchObscured]) and says why on the surface itself
 *   ([ObscuredTapNotice]) — not in a toast, which is a window of the
 *   system's over ours and would get the user's retry refused too.
 * - **Armed late, and only after a pause.** The buttons listen only once
 *   the surface has been on screen and untouched for its protection
 *   period ([PromptTapGuard]; Sign and Send
 *   [PromptTapGuard.SPEND_PROTECTION_MS]), and a press that *began*
 *   before that is dropped even if it ends after — so a page's "tap fast
 *   here" game can't time a tap onto Send.
 *
 * A TalkBack / Switch Access activation isn't a touch: it reaches the
 * button's `onClick` directly and is judged by the time rule alone.
 */

/**
 * Who currently wants other apps' overlays hidden over this Activity.
 * Kept per holder, like [setWindowSecureFor], so two surfaces up at once
 * don't unhide for each other. Main thread only.
 */
private val overlayHiders = WeakHashMap<Activity, MutableSet<Any>>()

internal fun Activity.setHideOverlaysFor(holder: Any, hide: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val holders = overlayHiders.getOrPut(this) { HashSet() }
    if (hide) holders.add(holder) else holders.remove(holder)
    window.setHideOverlayWindows(holders.isNotEmpty())
}

/**
 * Hides other apps' overlay windows (Android 12+) while this is
 * composed. Android hides them for as long as a window asking for it is
 * visible, whichever of our windows that is, so the Activity's own
 * window covers a sheet or dialog shown over it.
 */
@Composable
internal fun HideOverlayWindows() {
    val activity = LocalContext.current.findActivity() ?: return
    val token = remember { Any() }
    SideEffect { activity.setHideOverlaysFor(token, true) }
    DisposableEffect(activity, token) {
        onDispose { activity.setHideOverlaysFor(token, false) }
    }
}

/**
 * Whether a touch with these `MotionEvent` [flags] passed through
 * another app's window ([MotionEvent.FLAG_WINDOW_IS_OBSCURED]) — an
 * overlay that shows one thing while the tap lands on our button. On
 * Android 11, where overlays can't be hidden, also any touch while
 * another app's window covers part of ours
 * ([MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED]): it may be drawn
 * over the amount or the recipient rather than the button. From
 * Android 12 other apps' overlays are hidden, so a partial cover left
 * is a system window or a picture-in-picture one and not a reason to
 * refuse.
 */
internal fun touchObscured(flags: Int, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
        (sdk < Build.VERSION_CODES.S && flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)

internal val OBSCURED_TAP_MESSAGE: String
    get() = Strings.get(R.string.browser_obscured_tap)

/**
 * For a confirm button: drops a whole press — down to up, so the button
 * never sees it — that began before [tap]'s guard armed, or that Android
 * marks as obscured by another app's window ([touchObscured]). The
 * latter turns on [ArmedTapGuard.obscuredTap] for the surface's
 * [ObscuredTapNotice], since the button looks ready and nothing else
 * would explain why it didn't answer.
 */
internal fun Modifier.protectedPress(tap: ArmedTapGuard): Modifier = pointerInput(tap) {
    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent(PointerEventPass.Initial)
            if (first.changes.none { it.pressed && !it.previousPressed }) continue
            val obscured = first.motionEvent?.let { touchObscured(it.flags) } == true
            val drop = obscured || !tap.guard.accepts()
            tap.obscuredTap = obscured
            var event = first
            while (true) {
                if (drop) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
        }
    }
}

/** Why the confirm button ignored the last tap, while it's because another app covered it. */
@Composable
internal fun ObscuredTapNotice(tap: ArmedTapGuard, modifier: Modifier = Modifier) {
    if (!tap.obscuredTap) return
    Text(
        stringResource(R.string.browser_obscured_tap),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(vertical = 4.dp).testTag("obscured-tap-notice"),
    )
}

/**
 * For a surface's content: every touch on it restarts [guard]'s
 * protection period until it arms ([PromptTapGuard.noteInput]).
 * Observes only; the touch goes on to wherever it was going.
 */
internal fun Modifier.restartsTapGuard(guard: PromptTapGuard): Modifier = pointerInput(guard) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed && !it.previousPressed }) guard.noteInput()
        }
    }
}

/**
 * For a surface shown in its own window (a `ModalBottomSheet`, a
 * `Dialog`): every touch *anywhere* in that window restarts [guard]'s
 * protection period until it arms — the scrim above a sheet, the space
 * around a dialog, a tap outside it — not only a touch on its content
 * ([restartsTapGuard]). A sheet's scrim tap never reaches its
 * `onDismissRequest` while the sheet refuses to hide (its
 * `confirmValueChange`), so without this a page's tap game played over
 * the scrim would leave the period running and arm Sign mid-stream.
 * Observes only, at the window's own dispatch, so every touch still
 * goes where it was going. The period also starts over when the window
 * first draws, since the guard's own start is the host's first frame
 * and a sheet's window can come up after it. Call it inside the sheet's
 * or dialog's content.
 */
@Composable
internal fun RestartsTapGuardInWindow(guard: PromptTapGuard) {
    val view = LocalView.current
    DisposableEffect(view, guard) {
        val window = view.dialogWindow()
        val original = window?.callback
        val watcher = original?.let { TouchWatcher(it, guard) }
        if (watcher != null) window.callback = watcher
        // The guard starts on the host's first frame, and this window can come up
        // later than that: until it has drawn, what's on screen isn't the sheet.
        val root = view.rootView
        val firstDraw = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                guard.noteInput()
                root.post { root.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        root.viewTreeObserver.addOnDrawListener(firstDraw)
        onDispose {
            root.viewTreeObserver.removeOnDrawListener(firstDraw)
            if (watcher != null && window.callback === watcher) window.callback = original
        }
    }
}

/** Passes everything on to [original]; notes every new finger down on [guard] first. */
internal class TouchWatcher(
    private val original: Window.Callback,
    private val guard: PromptTapGuard,
) : Window.Callback by original {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (startsPress(event.actionMasked)) guard.noteInput()
        return original.dispatchTouchEvent(event)
    }
}

/** Whether a touch event with this masked action puts a new finger down. */
internal fun startsPress(actionMasked: Int): Boolean =
    actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN

private fun View.dialogWindow(): Window? {
    var v: Any? = this
    while (v is View) {
        if (v is DialogWindowProvider) return v.window
        v = v.parent
    }
    return null
}

/**
 * For a switch or tick on a guarded surface whose state the confirm acts
 * on (an "always approve" switch, a delete-the-backup tick): the same
 * [protectedPress] as a confirm button, so a press that began before the
 * guard armed or that passed through another app's window can't flip it
 * (#287 R3-M1), plus a guard check on the change itself for activations
 * that come with no press (TalkBack, a keyboard). Enabled only once
 * [tap] is armed and [enabled].
 */
internal fun Modifier.protectedToggle(
    tap: ArmedTapGuard,
    value: Boolean,
    role: Role,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = protectedPress(tap).toggleable(
    value = value,
    enabled = tap.armed && enabled,
    role = role,
    onValueChange = { if (tap.guard.accepts()) onValueChange(it) },
)

/**
 * For a choice row on a guarded surface whose pick the confirm acts on
 * (the account a Connect sheet shares): guarded like [protectedToggle],
 * so a press that began before the guard armed or that passed through
 * another app's window can't change which account the later, clean
 * Connect tap discloses (#287 R4-M1). Enabled only once [tap] is armed
 * and [enabled].
 */
internal fun Modifier.protectedSelectable(
    tap: ArmedTapGuard,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = protectedPress(tap).selectable(
    selected = selected,
    enabled = tap.armed && enabled,
    role = Role.RadioButton,
    onClick = { if (tap.guard.accepts()) onClick() },
)

/** A [PromptTapGuard] and whether its surface's buttons are enabled yet. */
@Stable
internal class ArmedTapGuard(val guard: PromptTapGuard) {
    var armed by mutableStateOf(false)
        internal set

    /** The last press on a confirm button was dropped as obscured ([protectedPress]). */
    var obscuredTap by mutableStateOf(false)
        internal set
}

/**
 * The tap protection for one showing of an approval or confirmation
 * surface, keyed by what it shows ([key]): its guard starts on the
 * first drawn frame, [ArmedTapGuard.armed] turns true once the guard
 * accepts (later again for every touch before that), and other apps'
 * overlays are hidden while it's composed.
 */
@Composable
internal fun rememberArmedTapGuard(key: Any?, protectionMs: Long = PromptTapGuard.PROTECTION_MS): ArmedTapGuard {
    val state = remember(key) { ArmedTapGuard(PromptTapGuard(protectionMs, SystemClock::uptimeMillis)) }
    LaunchedEffect(state) {
        withFrameNanos { }
        state.guard.onShown()
        while (!state.guard.accepts()) delay(state.guard.remainingMs().coerceAtLeast(1))
        state.armed = true
    }
    HideOverlayWindows()
    return state
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
