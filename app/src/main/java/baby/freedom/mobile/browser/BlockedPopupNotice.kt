package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The pop-up blocker's notice (#261): which site's pop-ups were blocked
 * and where each was going, with **Open** for each and **Always allow
 * pop-ups on this site**. Non-modal, over the page just above the
 * address bar, until the user closes it or the tab's page changes.
 *
 * A page chooses when a pop-up is blocked, so it chooses when this
 * appears: its buttons ignore taps until it has been on screen, and left
 * alone, for [PromptTapGuard.PROTECTION_MS] — re-armed whenever a new
 * entry or an address arriving shifts what's under the finger
 * ([BlockedPopups.layoutKey]) — like the permission prompt's,
 * so a tap aimed at the page can't land on "Always allow".
 *
 * The site is named in full (wrapping, never ellipsised: a host's tail is
 * the part a spoof would hide). So is each address's scheme and host
 * ([PopupAddress.site]), on its own; only the path and query after it —
 * a page chooses them, and could make them a screenful — are cut after a
 * few lines.
 *
 * It takes no more height than it's given (#292 R5-F1): the site and the
 * rows scroll between the title and "Always allow", and each button
 * acts only while what it names — its row, or the site — is wholly in
 * view, so a row cut off mid-host by the card's edge can't be opened.
 */
@Composable
fun BlockedPopupNotice(
    popups: BlockedPopups,
    /** The tab is private: an allow lasts for the private session only. */
    private: Boolean,
    displayUrl: (String) -> String,
    onOpen: (BlockedPopup, String) -> Unit,
    onAlwaysAllow: (origin: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = popups.entries
    // Re-armed whenever the rows or buttons move ([BlockedPopups.layoutKey]):
    // a new entry, an address arriving, the "and N more" line — not for
    // a count ticking up, so a page blocking in a loop can't keep it
    // disarmed.
    val tap = rememberArmedTapGuard(popups.layoutKey)
    val origin = popups.origin
    val site = origin?.let(::permissionOriginDisplay)
    // The card never outgrows the space it's given (#292 R5-F1): the
    // site and rows scroll between the fixed title and "Always allow",
    // and a button acts only while what it names is wholly in view.
    val scroll = rememberScrollState()
    val inView = remember { FullyInView() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier
            .restartsTapGuard(tap.guard)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WebAsset, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Text(
                    blockedPopupsTitle(popups.count),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                // Closing only takes the notice down: no need to guard it.
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close the pop-up notice")
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .onGloballyPositioned(inView::viewport)
                    .verticalScroll(scroll),
            ) {
                if (site != null) {
                    Text(
                        site,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 36.dp, end = 12.dp, bottom = 4.dp)
                            .onGloballyPositioned { inView.item(SITE_KEY, it) },
                    )
                }
                entries.forEachIndexed { index, entry ->
                    val url = entry.url?.takeUnless { it == ABOUT_BLANK }
                    val shown = url?.let(displayUrl)
                    // A form's address opened as a plain GET would be a
                    // different request than the page made: named only.
                    val openable = shown != null && !entry.posted && isOpenableInTab(shown)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.onGloballyPositioned { inView.item(index, it) },
                    ) {
                        if (shown != null) {
                            PopupAddressLabel(shown, posted = entry.posted, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                blockedPopupLabel(entry, null),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (openable) {
                            val whole = inView[index]
                            TextButton(
                                enabled = tap.armed && whole,
                                onClick = { if (inView[index] && tap.guard.accepts()) onOpen(entry, shown!!) },
                                modifier = Modifier.protectedPress(tap),
                            ) { Text("Open") }
                        }
                    }
                }
                val more = popups.unlisted
                if (more > 0) {
                    Text(
                        "and $more more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (origin != null) {
                if (popups.allowed) {
                    Text(
                        "Pop-ups from this site will open from now on" +
                            if (private) ", until you close your private tabs." else ". Settings → Site permissions can undo it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, end = 12.dp),
                    )
                } else {
                    // Allows the site named above: only while it's in view.
                    val named = site == null || inView[SITE_KEY]
                    TextButton(
                        enabled = tap.armed && named,
                        onClick = { if ((site == null || inView[SITE_KEY]) && tap.guard.accepts()) onAlwaysAllow(origin) },
                        modifier = Modifier.protectedPress(tap),
                    ) { Text("Always allow pop-ups on this site") }
                }
            }
            ObscuredTapNotice(tap, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

private const val SITE_KEY = "site"

/**
 * Which items of a scrolling area are wholly inside its visible part
 * ([viewport]), by key — so a button can refuse to act for a row whose
 * name is scrolled half out of sight (#292 R5-F1). Written from
 * layout callbacks, read in composition; a flip only changes a button's
 * enabled colour, never the layout, so it can't feed back into itself.
 */
internal class FullyInView {
    private var viewport: LayoutCoordinates? = null
    private val items = HashMap<Any, LayoutCoordinates>()
    private val whole = mutableStateMapOf<Any, Boolean>()

    operator fun get(key: Any): Boolean = whole[key] == true

    fun viewport(coordinates: LayoutCoordinates) {
        viewport = coordinates
        items.keys.toList().forEach(::update)
    }

    fun item(key: Any, coordinates: LayoutCoordinates) {
        items[key] = coordinates
        update(key)
    }

    private fun update(key: Any) {
        val port = viewport
        val item = items[key]
        val value = if (port == null || item == null || !port.isAttached || !item.isAttached) {
            false
        } else {
            wholly(port.size.height.toFloat(), port.localBoundingBoxOf(item, clipBounds = false))
        }
        if (whole[key] != value) whole[key] = value
    }

    companion object {
        /** [box] (in the viewport's own coordinates) lies within a viewport [height] tall. */
        fun wholly(height: Float, box: Rect): Boolean = box.top >= -0.5f && box.bottom <= height + 0.5f
    }
}

/**
 * A blocked pop-up's address: its [PopupAddress.site] in full (wrapping,
 * never ellipsised — #292 R4-F1), then its path/query, cut after two
 * lines. A [posted] form's address says so, on a line of its own that no
 * cut can take away.
 */
@Composable
private fun PopupAddressLabel(shown: String, posted: Boolean, modifier: Modifier = Modifier) {
    val address = PopupAddress.of(shown)
    Column(modifier = modifier) {
        Text(
            if (posted) "A form sent to ${address.site}" else address.site,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        if (address.rest.isNotEmpty()) {
            Text(
                address.rest,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (posted) {
            Text(
                "Its data can't be sent again from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Pop-up blocked" / "3 pop-ups blocked". */
internal fun blockedPopupsTitle(count: Int): String =
    if (count <= 1) "Pop-up blocked" else "$count pop-ups blocked"
