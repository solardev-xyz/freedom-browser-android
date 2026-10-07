package baby.freedom.mobile.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R

/** The glyph a site capability is listed with, here and in Settings. */
internal fun sitePermissionIcon(capability: SiteCapability): ImageVector = when (capability) {
    SitePermission.CAMERA -> Icons.Filled.Videocam
    SitePermission.MICROPHONE -> Icons.Filled.Mic
    SitePermission.LOCATION -> Icons.Filled.LocationOn
    SitePermission.MIDI -> Icons.Filled.Piano
    SitePermission.POPUPS -> Icons.Filled.WebAsset
    is ExternalScheme -> Icons.AutoMirrored.Filled.OpenInNew
}

/**
 * Android's privacy-indicator green: the colour the system's own
 * camera/microphone dot uses, so the two read as the same signal.
 */
private val InUseGreen = Color(0xFF34A853)

/**
 * The page's own **Site permissions** (#266), opened from the page menu
 * or the in-use indicator: every decision the site on screen holds —
 * camera, microphone, location, MIDI, pop-ups, links to other apps, a
 * dismissal block — and those of any frame inside the page that asked,
 * each with a × that removes it so the site has to ask again.
 *
 * Opened over one document ([pageOrigin] captured when it opened): the
 * caller closes it when the tab's document changes, so a × can never
 * reach a site the user didn't open it for. Each row carries its own
 * origin, so a removal acts on exactly the row tapped.
 *
 * A private tab's rows are its private session's (removed there only);
 * a normal tab's are the remembered and this-run decisions Settings
 * lists too.
 *
 * Removing the camera or microphone from a page that is using it, or
 * location or MIDI SysEx from a page that was given it (its `MIDIAccess`
 * keeps working), doesn't take away what the document already holds —
 * WebView has no way to ([stillHeldAfterRemoval]) — so the sheet says so
 * and offers a reload, which ends it — for as long as that document
 * holds it ([document], kept by the broker per document and per origin,
 * so reopening the sheet still says it).
 */
@Composable
fun PageSitePermissionsSheet(
    pageOrigin: String?,
    entries: List<SitePermissionEntry>,
    inUse: Set<SitePermission>,
    document: SitePermissionBroker.DocumentPermissions?,
    private: Boolean,
    onRevoke: (SitePermissionEntry) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Camera/microphone removed while the page kept using them, or a
    // location or MIDI SysEx it was given — kept with the tab's document, not this
    // dialog, so it's still said after the sheet is closed and opened
    // again.
    val stillHeld = document?.stillHeld(inUse).orEmpty()
    val heldNote = stillHeldNote(stillHeld)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.library_site_permissions_title))
                if (pageOrigin != null) {
                    // In full, wrapping: a host's tail is what a spoof hides.
                    Text(
                        permissionOriginDisplay(pageOrigin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        if (heldNote == null) {
                            stringResource(R.string.library_site_permissions_none)
                        } else {
                            // Not "no permissions": the page still has what's below.
                            stringResource(R.string.library_site_permissions_nothing_saved)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (entry in entries) {
                    PageSitePermissionRow(
                        entry = entry,
                        inUse = document?.inUse(entry, inUse) == true,
                        private = private,
                        onRevoke = { onRevoke(entry) },
                    )
                }
                if (heldNote != null) {
                    Text(
                        heldNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    if (private) {
                        stringResource(R.string.library_site_permissions_private)
                    } else {
                        stringResource(R.string.library_site_permissions_removing_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = if (heldNote != null) {
            {
                TextButton(onClick = {
                    onDismiss()
                    onReload()
                }) { Text(stringResource(R.string.library_site_permissions_reload)) }
            }
        } else {
            null
        },
    )
}

@Composable
private fun PageSitePermissionRow(
    entry: SitePermissionEntry,
    inUse: Boolean,
    private: Boolean,
    onRevoke: () -> Unit,
) {
    val site = permissionOriginDisplay(entry.origin)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            sitePermissionIcon(entry.permission),
            contentDescription = null,
            tint = if (inUse) InUseGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.permission.label, fontWeight = FontWeight.Medium)
            Text(
                sitePermissionStateLabel(entry, private).let {
                    if (inUse) stringResource(R.string.library_site_permissions_in_use_now, it) else it
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A frame from another origin asked for this one; it was
            // decided for this site (#363).
            if (entry.scope.embedded) {
                Text(
                    stringResource(R.string.library_site_permissions_embedded, site),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRevoke) {
            Icon(
                Icons.Filled.Close,
                contentDescription = if (entry.scope.embedded) {
                    stringResource(
                        R.string.library_site_permissions_remove_embedded,
                        entry.permission.label,
                        site,
                        permissionOriginDisplay(entry.top),
                    )
                } else {
                    stringResource(R.string.library_site_permissions_remove, entry.permission.label, site)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Camera in use" (#266): shown over the page, just above the address
 * bar, while the page on screen is using the camera or microphone it was
 * given — on and off with Android's own privacy indicator, whose signal
 * it reads ([SitePermissionBroker.mediaInUse]), but naming it as this
 * page's. A tap opens the page's Site permissions.
 */
@Composable
fun MediaInUseIndicator(
    inUse: Set<SitePermission>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = mediaInUseLabel(inUse) ?: return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.library_site_permissions_title), role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (p in listOf(SitePermission.CAMERA, SitePermission.MICROPHONE)) {
                if (p !in inUse) continue
                Icon(
                    sitePermissionIcon(p),
                    contentDescription = null,
                    tint = InUseGreen,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
