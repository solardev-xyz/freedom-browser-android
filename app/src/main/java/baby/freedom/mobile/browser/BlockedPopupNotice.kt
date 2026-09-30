package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * the part a spoof would hide). An address is cut after a few lines — a
 * page chooses it, and could make it a screenful — with its start, the
 * scheme and host, always shown.
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
                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        blockedPopupsTitle(popups.count),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (site != null) {
                        Text(
                            site,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Closing only takes the notice down: no need to guard it.
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close the pop-up notice")
                }
            }
            for (entry in entries) {
                val url = entry.url?.takeUnless { it == ABOUT_BLANK }
                val shown = url?.let(displayUrl)
                val openable = shown != null && isOpenableInTab(shown)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        blockedPopupLabel(entry, shown),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (shown != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (openable) {
                        TextButton(
                            enabled = tap.armed,
                            onClick = { if (tap.guard.accepts()) onOpen(entry, shown!!) },
                            modifier = Modifier.protectedPress(tap),
                        ) { Text("Open") }
                    }
                }
            }
            val more = popups.count - entries.size
            if (more > 0) {
                Text(
                    "and $more more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
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
                    TextButton(
                        enabled = tap.armed,
                        onClick = { if (tap.guard.accepts()) onAlwaysAllow(origin) },
                        modifier = Modifier.protectedPress(tap),
                    ) { Text("Always allow pop-ups on this site") }
                }
            }
            ObscuredTapNotice(tap, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** "Pop-up blocked" / "3 pop-ups blocked". */
internal fun blockedPopupsTitle(count: Int): String =
    if (count <= 1) "Pop-up blocked" else "$count pop-ups blocked"
