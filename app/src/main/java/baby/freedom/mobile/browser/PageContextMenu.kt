package baby.freedom.mobile.browser

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Long-press context menus on page content (#84): links, images, and —
 * through the WebView's own text-selection toolbar — selected text.
 *
 * Links and images get a bottom sheet ([PageContextMenuSheet]) raised
 * from the WebView's long-click; a long-press anywhere else is left to
 * Chromium, which starts a text selection. The selection toolbar keeps
 * Chromium's own Copy / Share / Select all and gains a "Search" item
 * that searches with the browser's engine ([UrlParser.searchUrl]) in a
 * new tab, in place of Chromium's "Web search", which hands the text to
 * whatever app answers `ACTION_WEB_SEARCH` instead.
 */

/**
 * What a long-press landed on. [linkUrl] and [imageUrl] are the URLs
 * the WebView actually loaded (gateway / virtual-origin form, not the
 * display form); either may be null, not both.
 */
internal data class PageContextTarget(
    val linkUrl: String?,
    val linkText: String?,
    val imageUrl: String?,
)

/**
 * The [PageContextTarget] for a `WebView.HitTestResult`, or `null` when
 * the press wasn't on a link or image the menu can do anything with —
 * in which case the long-press stays Chromium's (text selection).
 *
 * [focusHref] is `requestFocusNodeHref`'s `url`: for an image inside a
 * link the hit test's `extra` is the image, and that is the only place
 * the link's own address is reported.
 *
 * `javascript:` links have no address to open or copy, and `blob:`
 * images exist only inside the page that made them, so neither counts.
 */
internal fun pageContextTargetFor(
    type: Int,
    extra: String?,
    focusHref: String?,
    focusTitle: String?,
): PageContextTarget? {
    val (link, image) = when (type) {
        WebView.HitTestResult.SRC_ANCHOR_TYPE -> (focusHref?.ifBlank { null } ?: extra) to null
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> focusHref to extra
        WebView.HitTestResult.IMAGE_TYPE -> null to extra
        else -> return null
    }
    val usableLink = link?.trim()?.takeIf { it.isNotEmpty() && isActionableLink(it) }
    val usableImage = image?.trim()?.takeIf { it.isNotEmpty() && isFetchableImage(it) }
    if (usableLink == null && usableImage == null) return null
    return PageContextTarget(
        linkUrl = usableLink,
        linkText = focusTitle?.trim()?.ifEmpty { null }.takeIf { usableLink != null },
        imageUrl = usableImage,
    )
}

private fun schemeOf(url: String): String? =
    Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):").find(url)?.groupValues?.get(1)?.lowercase()

private fun isActionableLink(url: String): Boolean =
    schemeOf(url) in OPENABLE_SCHEMES

/** Schemes whose bytes the browser can fetch outside the page (see [fetchImage]). */
internal fun isFetchableImage(url: String): Boolean =
    schemeOf(url) in OPENABLE_SCHEMES || schemeOf(url) == "data"

/** An image that can also be opened on its own in a tab: not a `data:` blob of bytes. */
internal fun isOpenableImage(url: String): Boolean = schemeOf(url) in OPENABLE_SCHEMES

private val OPENABLE_SCHEMES = setOf("http", "https", "bzz", "ipfs", "ipns", "ens")

/**
 * A raised menu, pinned to the document it was raised over: [tabId]
 * and that tab's [pageUrl] / [navCounter] at the time. Everything on
 * the menu was read off that page, so the moment the tab navigates or
 * stops being the one on screen, the menu is stale (see
 * [pageContextMenuIsStale]) — it must not go on offering "Open in new
 * tab" for a link on a page that is gone.
 */
internal class PageContextMenuRequest(
    val tabId: Long,
    val pageUrl: String,
    val navCounter: Int,
    val target: PageContextTarget,
)

/**
 * Whether [request] no longer describes what is on screen: its tab is
 * closed or in the background, or has navigated since.
 */
internal fun pageContextMenuIsStale(
    request: PageContextMenuRequest,
    activeTabId: Long,
    tabUrl: String?,
    tabNavCounter: Int?,
): Boolean =
    request.tabId != activeTabId ||
        tabUrl != request.pageUrl ||
        tabNavCounter != request.navCounter

/**
 * The query "Search" sends for a selection: whitespace runs (a selection
 * across lines or table cells) collapsed to single spaces, trimmed, and
 * clamped to [SEARCH_SELECTION_MAX] code points the way the desktop
 * browser clamps it — a select-all must not build a query the size of
 * the document, which would be navigated to and stored in history
 * verbatim. The cut goes back to the last word break inside the budget,
 * unless that would leave less than half of it (a selection with no
 * breaks — a hash, a base64 blob), which is cut hard. `null` for an
 * empty selection: there is nothing to search for.
 */
internal fun searchSelectionQuery(selection: String?): String? {
    val collapsed = selection?.replace(WHITESPACE_RUN, " ")?.trim()
    if (collapsed.isNullOrEmpty()) return null
    if (collapsed.codePointCount(0, collapsed.length) <= SEARCH_SELECTION_MAX) return collapsed
    val headEnd = collapsed.offsetByCodePoints(0, SEARCH_SELECTION_MAX)
    val head = collapsed.substring(0, headEnd)
    // Budget ran out exactly on a break: the head is whole words already.
    if (collapsed[headEnd] == ' ') return head.trimEnd()
    val boundary = head.lastIndexOf(' ')
    val minBoundary = collapsed.offsetByCodePoints(0, SEARCH_SELECTION_MAX / 2)
    return if (boundary >= minBoundary) head.substring(0, boundary) else head
}

internal const val SEARCH_SELECTION_MAX = 1024

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Reads the current selection out of the page for "Search". A focused
 * text field's selection is not part of `window.getSelection()`, so it
 * is read from the field itself — except a password field, whose
 * selection is never worth sending anywhere. Walks into open shadow
 * roots for the focused field; a selection inside a cross-origin
 * `<iframe>` is out of reach of the top document and reads as empty.
 */
internal const val SELECTION_TEXT_SCRIPT = """
(function () {
  var e = document.activeElement;
  while (e && e.shadowRoot && e.shadowRoot.activeElement) e = e.shadowRoot.activeElement;
  if (e && (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT')) {
    if (String(e.type).toLowerCase() === 'password') return '';
    try {
      var s = e.selectionStart, t = e.selectionEnd;
      if (typeof s === 'number' && typeof t === 'number' && t > s) return String(e.value).substring(s, t);
    } catch (x) {}
  }
  var sel = window.getSelection ? window.getSelection() : null;
  return sel ? String(sel) : '';
})()
"""

/**
 * The link / image menu: a bottom sheet headed by the address it acts
 * on (link first, since that is what a tap would have followed), then
 * the items that apply.
 *
 * Addresses are shown, copied and shared in their *display* form
 * ([displayUrl]: `name.eth/path`, `bzz://…`), never as the loopback
 * gateway URL the WebView fetched — the same rule as the capsule's own
 * Copy URL ([urlActionTarget]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageContextMenuSheet(
    target: PageContextTarget,
    displayUrl: (String) -> String,
    onOpenInNewTab: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onShareLink: (url: String, title: String) -> Unit,
    onOpenImage: (String) -> Unit,
    onCopyImage: (String) -> Unit,
    onSaveImage: (String) -> Unit,
    onShareImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Take the sheet down, then act: an action that opens a tab makes
    // this menu stale, and the sheet would otherwise vanish mid-slide.
    fun act(action: () -> Unit): () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    val link = target.linkUrl
    val image = target.imageUrl
    val linkDisplay = link?.let(displayUrl)
    val header = linkDisplay ?: image?.let { if (isOpenableImage(it)) displayUrl(it) else "Image" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            if (header != null) {
                if (link != null && !target.linkText.isNullOrBlank()) {
                    Text(
                        text = target.linkText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                Text(
                    text = header,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            if (link != null && linkDisplay != null) {
                SheetItem("Open in new tab", Icons.AutoMirrored.Filled.OpenInNew, act { onOpenInNewTab(link) })
                SheetItem("Copy link address", Icons.Filled.Link, act { onCopyLink(linkDisplay) })
                SheetItem(
                    "Share link",
                    Icons.Filled.Share,
                    act { onShareLink(linkDisplay, target.linkText.orEmpty()) },
                )
            }
            if (image != null) {
                if (link != null) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (isOpenableImage(image)) {
                    SheetItem("Open image in new tab", Icons.Filled.Image, act { onOpenImage(image) })
                }
                SheetItem("Copy image", Icons.Filled.ContentCopy, act { onCopyImage(image) })
                SheetItem("Save image", Icons.Filled.Download, act { onSaveImage(image) })
                SheetItem("Share image", Icons.Filled.Share, act { onShareImage(image) })
            }
        }
    }
}

@Composable
private fun SheetItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
