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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    val onSurface = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    val (bgColor, corner) = when (style) {
        PageRowStyle.Listed -> MaterialTheme.colorScheme.surfaceVariant to 10.dp
        PageRowStyle.Inset -> null to 8.dp
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .let { if (bgColor != null) it.background(bgColor) else it }
            .clickable(enabled = enabled, onClick = onClick)
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
                maxLines = 1,
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
