package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * The URL actions a long-press on the capsule's domain label offers:
 * copy, share, and — when the clipboard has something to offer —
 * paste-and-go.
 *
 * The gesture is deliberately confined to the label: the pill's trailing
 * slot (reload / stop / clear) and the flanking controls keep their own
 * behaviour, and while the field has focus there is no long-press
 * surface at all, so text selection inside the editor is untouched.
 */

/**
 * The URL a long-press acts on, or `null` when this tab has no address
 * to copy (the home tab).
 *
 * It is the tab's *committed* address — the same string the bold resting
 * label is derived from — falling back to the loaded page's display URL
 * if the two ever disagree. Copying what the capsule is currently
 * asserting is the only honest answer: the user long-pressed a label,
 * not a history entry, and anything else would hand them a URL they were
 * never shown.
 *
 * Note this is the *display* form (`vitalik.eth/docs`, `bzz://…`), not
 * the loopback gateway URL the WebView actually fetched — which is
 * exactly what another Freedom user can paste back in, and what the
 * desktop browser shares too.
 */
internal fun urlActionTarget(addressBarText: String, url: String): String? =
    addressBarText.trim().ifEmpty { url.trim() }.ifEmpty { null }

/**
 * What "Paste and go" would submit for a clipboard holding [clip], or
 * `null` when there is nothing usable on it.
 *
 * Whitespace runs (including the newlines that come with copying a link
 * out of a chat app or a PDF) collapse to single spaces and the result
 * is trimmed: the address field is single-line, and a stray trailing
 * newline is the difference between a URL that loads and a web search
 * for one. What survives that is handed to the ordinary submit path, so
 * a pasted URL navigates and pasted prose searches — exactly as if the
 * user had typed it.
 */
internal fun pasteAndGoTarget(clip: CharSequence?): String? {
    val text = clip?.toString() ?: return null
    return text.replace(WHITESPACE, " ").trim().ifEmpty { null }
}

private val WHITESPACE = Regex("\\s+")

/**
 * Does the clipboard hold text worth offering a paste for?
 *
 * Answered from the clip *description* only, never its contents: since
 * Android 12 reading the primary clip raises the system "pasted from…"
 * toast, and merely *opening* a menu must not spend that — nor claim a
 * clipboard read the user never asked for. The read happens when they
 * tap the item (see [pasteAndGoFromClipboard]).
 */
internal fun Context.clipboardHasText(): Boolean {
    val clipboard = clipboardManager() ?: return false
    val description = clipboard.primaryClipDescription
    val mimeTypes = (0 until (description?.mimeTypeCount ?: 0)).mapNotNull { i ->
        runCatching { description?.getMimeType(i) }.getOrNull()
    }
    return clipHasPasteableText(
        hasPrimaryClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false),
        mimeTypes = mimeTypes,
    )
}

/**
 * The gate itself, over a clip's advertised MIME types.
 *
 * `text/plain` is the ordinary case; `text/html` counts because a link
 * copied out of a rendered page arrives as HTML with a plain-text
 * alternative, which is what `coerceToText` hands back. `text/uri-list`
 * counts too, and is the most address-like clip of the three: it is what
 * `ClipData.newUri` advertises for a non-`content://` URI — a link
 * handed over by a file manager, a share target or another browser —
 * and `coerceToText` gives back the URI itself. A clipboard holding
 * only an image or some other binary stays off the menu rather than
 * offering an item that then does nothing.
 */
internal fun clipHasPasteableText(hasPrimaryClip: Boolean, mimeTypes: List<String>): Boolean {
    if (!hasPrimaryClip) return false
    return mimeTypes.any {
        it == ClipDescription.MIMETYPE_TEXT_PLAIN ||
            it == ClipDescription.MIMETYPE_TEXT_HTML ||
            it == ClipDescription.MIMETYPE_TEXT_URILIST
    }
}

/**
 * Read the clipboard and return what "Paste and go" should submit, or
 * `null` if it turned out to hold nothing usable after all (the clip can
 * change between the long-press that opened the menu and the tap that
 * used it).
 */
internal fun pasteAndGoFromClipboard(context: Context): String? {
    val clipboard = context.clipboardManager() ?: return null
    val item = runCatching { clipboard.primaryClip?.getItemAt(0) }.getOrNull() ?: return null
    return pasteAndGoTarget(runCatching { item.coerceToText(context) }.getOrNull())
}

/** Put [url] on the clipboard, with the confirmation the platform owes the user. */
internal fun copyUrlToClipboard(context: Context, url: String) {
    val clipboard = context.clipboardManager() ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, url))
    // Android 13 shows its own clipboard confirmation for every copy, and
    // a toast on top of it would be a second, redundant one. Below that
    // the copy is silent, which reads as the tap having missed.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hand [url] to the system share sheet. [title] is the page title, used
 * only as the sheet's preview caption — the shared payload is the URL.
 */
internal fun shareUrl(context: Context, url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (title.isNotBlank()) putExtra(Intent.EXTRA_TITLE, title)
    }
    // A device with no share target at all would throw; the capsule
    // losing its menu is a better outcome than the browser dying.
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private const val CLIP_LABEL = "URL"

private fun Context.clipboardManager(): ClipboardManager? =
    getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

/**
 * The long-press menu itself: the same small popup the overflow button
 * opens (same surface, same elevation, same [AnchoredAboveProvider]),
 * anchored above the capsule and against the label's own edge rather
 * than the screen's, so it reads as belonging to the thing that was
 * pressed.
 *
 * `focusable = true` is what makes the system back gesture *dismiss the
 * menu* rather than navigate the page underneath — the popup takes the
 * back press, exactly as the overflow menu does. Nothing here asks for a
 * gesture exclusion zone: the capsule floats clear of the navigation
 * inset and the back-swipe edges stay the system's.
 */
@Composable
internal fun CapsuleUrlActionsMenu(
    anchor: IntRect,
    canCopy: Boolean,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPasteAndGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { CapsuleMenuGap.roundToPx() }
    Popup(
        popupPositionProvider = AnchoredAboveProvider(anchor, gapPx, alignToEnd = false),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 8.dp),
            ) {
                if (canCopy) {
                    DropdownMenuItem(
                        text = { MenuItemLabel("Copy URL") },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            onDismiss()
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { MenuItemLabel("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            onDismiss()
                            onShare()
                        },
                    )
                }
                if (canPaste) {
                    DropdownMenuItem(
                        text = { MenuItemLabel("Paste and go") },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        },
                        onClick = {
                            onDismiss()
                            onPasteAndGo()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Gap between the pressed label and the menu above it — enough that the
 * popup floats clear of the capsule's own top edge rather than touching
 * it, matching the overflow menu's lift.
 */
private val CapsuleMenuGap = 16.dp
