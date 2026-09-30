package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Visual container mode for a [PageRow].
 *
 *  • [Listed] — row lives directly on the page background (History,
 *    Bookmarks). Carries its own `surfaceVariant` fill so it reads as a
 *    distinct item.
 *  • [Inset] — row lives inside a [SectionCard] that already provides
 *    the `surfaceVariant` backdrop (Settings). Renders transparent so
 *    we don't stack two variants on top of each other.
 */
internal enum class PageRowStyle { Listed, Inset }

/**
 * Single clickable-row primitive used by every "icon + title + subtitle
 * (+ optional third line) + trailing" pattern across the full-screen
 * pages. Unifies what used to be three bespoke rows (History entries,
 * Bookmarks entries, Settings actions) under one implementation.
 */
@Composable
internal fun PageRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PageRowStyle = PageRowStyle.Listed,
    leadingIcon: ImageVector? = null,
    thirdLine: String? = null,
    enabled: Boolean = true,
    // Set for a row whose [trailing] is a switch: the row is then one
    // switch to TalkBack — "Title, subtitle, switch, on" — instead of a
    // button next to an unlabelled switch (#279). The trailing [Switch]
    // takes `onCheckedChange = null`; the row's tap flips it via [onClick].
    checked: Boolean? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    val onSurface = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    val (bgColor, shape) = when (style) {
        PageRowStyle.Listed ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.shapes.medium
        PageRowStyle.Inset -> null to MaterialTheme.shapes.small
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .let { if (bgColor != null) it.background(bgColor) else it }
            .let {
                if (checked != null) {
                    it.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                } else {
                    it.clickable(enabled = enabled, onClick = onClick)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = onSurface,
            )
            Spacer(Modifier.width(12.dp))
        }
        // A listed row (a history entry, a bookmark) is one line per
        // field, its URL cut short like any browser's. An inset row is a
        // setting: its name and state wrap instead, so a large font scale
        // doesn't cut them to "Search en…" / "Not set up · …" (#279).
        val lines = if (style == PageRowStyle.Listed) 1 else Int.MAX_VALUE
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = onSurface,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
            if (thirdLine != null) {
                Text(
                    thirdLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * A row holding a label and a [androidx.compose.material3.Switch]: the
 * whole row is the switch, so TalkBack reads the label, the role and the
 * state as one control, and the row's tap toggles it (#279). Put the
 * [androidx.compose.material3.Switch] inside with `onCheckedChange = null`.
 *
 * [label] names the switch where the row's own text is a status ("Running")
 * rather than what the switch turns on and off.
 */
internal fun Modifier.switchRow(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    label: String? = null,
): Modifier = toggleable(
    value = checked,
    enabled = enabled && onCheckedChange != null,
    role = Role.Switch,
    onValueChange = { onCheckedChange?.invoke(it) },
).let { if (label != null) it.semantics { contentDescription = label } else it }
