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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press context menus on page content (#84): links, images, and —
 * through the WebView's own text-selection toolbar — selected text.
 *
 * Links and images get a bottom sheet ([PageContextMenuSheet]), raised
 * only once the page has had its own `contextmenu` event and let it
 * through ([PageContextMenuPress]); a long-press anywhere else is left
 * to Chromium, which starts a text selection. The selection toolbar keeps
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
        // The hit test's own href stands when the reply's isn't usable,
        // so a link that was taken ([pageContextMenuIsCertain]) always
        // opens its menu.
        WebView.HitTestResult.SRC_ANCHOR_TYPE ->
            (focusHref?.trim()?.takeIf { isActionableLink(it) } ?: extra) to null
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

/**
 * Whether a long-press hit test of [type] / [extra] is sure to produce a
 * [PageContextTarget] once `requestFocusNodeHref` answers, whatever it
 * answers — i.e. whether the long-press may be taken from Chromium
 * before that reply is in. A target that already exists with no href
 * at all is one: the href only ever adds a link.
 */
internal fun pageContextMenuIsCertain(type: Int, extra: String?): Boolean =
    pageContextTargetFor(type, extra, focusHref = null, focusTitle = null) != null

private fun schemeOf(url: String): String? =
    Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):").find(url)?.groupValues?.get(1)?.lowercase()

private fun isActionableLink(url: String): Boolean =
    schemeOf(url) in OPENABLE_SCHEMES

/**
 * Schemes whose bytes the browser can fetch outside the page (see
 * [fetchImage]). A `data:` URL counts only when it says it is an image:
 * `<img src="data:,hello">` is text, and nothing to save as a picture.
 */
internal fun isFetchableImage(url: String): Boolean =
    schemeOf(url) in OPENABLE_SCHEMES || url.startsWith("data:image/", ignoreCase = true)

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
 * The document a long-press landed on, captured *at the press*. The
 * menu's target arrives later (asynchronously, from
 * `requestFocusNodeHref`), and a navigation can commit in between: a
 * request built from the tab's state at arrival would pin the old
 * page's link to the new document, where [pageContextMenuIsStale] could
 * no longer tell. Built from the pin, it describes the page pressed,
 * and a navigation since makes it stale as it should.
 */
internal class PageContextMenuPin(
    val tabId: Long,
    val pageUrl: String,
    val navCounter: Int,
) {
    fun request(target: PageContextTarget) =
        PageContextMenuRequest(tabId, pageUrl, navCounter, target)
}

/**
 * One long-press on a link or image, waiting for the two answers the
 * menu needs before it may open:
 *
 * - **the link** — `requestFocusNodeHref`'s reply ([onHref]);
 * - **the page's say** — whether the page's DOM `contextmenu` event for
 *   this press went through ([onPageVerdict]). A page that calls
 *   `preventDefault()` (a map's pin-drop, a gallery, a game with its own
 *   long-press) keeps the press, exactly as it would with no menu at all.
 *
 * The native long-press can't know the second: Chromium calls the
 * View's `performLongClick` *before* it hands the gesture to the page,
 * and taking the press there (returning `true`) is what stops the page
 * from ever seeing its `contextmenu`. So the press is never taken; the
 * page's event runs as usual, and [contextMenuVerdictJs] reports its
 * outcome. A verdict later than [PAGE_CONTEXT_MENU_VERDICT_WINDOW_MS]
 * after the press isn't this press's, and no verdict at all (the script
 * couldn't run) means no menu: the page's own behaviour wins.
 */
internal class PageContextMenuPress(
    val pin: PageContextMenuPin,
    private val type: Int,
    private val extra: String?,
    private val pressedAtMs: Long,
) {
    private var href: Pair<String?, String?>? = null
    private var allowed: Boolean? = null
    private var done = false

    /** `requestFocusNodeHref` answered; the target, if the page's verdict is in and says yes. */
    fun onHref(url: String?, title: String?): PageContextTarget? {
        if (href == null) href = url to title
        return settle()
    }

    /** The page's `contextmenu` for a press let through ([allowed]) or not. */
    fun onPageVerdict(allowed: Boolean, nowMs: Long): PageContextTarget? {
        if (this.allowed != null || nowMs - pressedAtMs > PAGE_CONTEXT_MENU_VERDICT_WINDOW_MS) return null
        this.allowed = allowed
        return settle()
    }

    private fun settle(): PageContextTarget? {
        val (url, title) = href ?: return null
        if (done || allowed != true) return null
        done = true
        return pageContextTargetFor(type, extra, focusHref = url, focusTitle = title)
    }
}

/** How long after a long-press the page's `contextmenu` verdict still counts as its. */
internal const val PAGE_CONTEXT_MENU_VERDICT_WINDOW_MS = 1_500L

/**
 * The page's verdict message on the web-message channel: `contextmenu 1`
 * (let through) or `contextmenu 0` (kept by the page). Anything else is
 * not one (`null`).
 */
internal fun parseContextMenuVerdict(data: String?): Boolean? = when (data) {
    "contextmenu 1" -> true
    "contextmenu 0" -> false
    else -> null
}

/**
 * Reports each trusted `contextmenu` event's outcome back through
 * [channel] (the `addWebMessageListener` object; no new global is made).
 * Installed with `addDocumentStartJavaScript`, so it runs before any of
 * the page's own scripts: its capture listener on `window` is the first
 * one there, and the channel is held in a closure the page can't swap.
 *
 * The outcome is read after dispatch (a task later), when every page
 * handler — including a bubbling one registered on `window` after ours —
 * has had its say. A press counts as let through when nothing called
 * `preventDefault()`. (`-webkit-touch-callout` needs no check: Android's
 * Blink doesn't parse it — `CSS.supports` is false on the API 36 AVD —
 * so it suppresses nothing there, with or without this menu.)
 */
internal fun contextMenuVerdictJs(channel: String): String = """
(function(){
  var c = globalThis[${org.json.JSONObject.quote(channel)}];
  if (!c || typeof c.postMessage !== 'function') return;
  var post = c.postMessage.bind(c);
  window.addEventListener('contextmenu', function(e){
    if (!e.isTrusted) return;
    setTimeout(function(){ post('contextmenu ' + (e.defaultPrevented ? 0 : 1)); }, 0);
  }, true);
})();
""".trimIndent()

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
        afterUnlessDisposed(scope, { sheetState.hide() }) {
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

/**
 * Run [first] in [scope], then [then] — unless [scope] itself was
 * cancelled first. The menu's scope ends when the sheet leaves
 * composition, which [BrowserScreen] does the moment the menu goes
 * stale (the page navigated, the tab closed); an action picked just
 * before that must not then run against a document that is gone. A
 * [first] cut short any other way (a drag interrupting the hide
 * animation) still goes on to [then].
 */
internal fun afterUnlessDisposed(scope: CoroutineScope, first: suspend () -> Unit, then: () -> Unit) {
    scope.launch { first() }.invokeOnCompletion {
        if (scope.isActive) then()
    }
}
