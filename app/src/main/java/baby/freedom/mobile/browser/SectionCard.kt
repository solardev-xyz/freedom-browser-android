package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared visual grammar for the full-screen "page" surfaces
 * ([SettingsScreen], [NodeScreen], etc.): a rounded-corner card on
 * `surfaceVariant`, a section title, and a content slot.
 */
@Composable
internal fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Each card is one section of the page; its title is what
            // TalkBack's heading navigation jumps between (#279).
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Label / value row used inside a [SectionCard] for static property
 * listings (version, peer count, gateway URL, etc.). Label is muted
 * on the left; value takes the remaining space on the right.
 *
 * A wrapping ([singleLine] false) plain-text value that doesn't fit next
 * to its label moves under it at the full width instead, so a large font
 * scale never squeezes it into a narrow column and breaks a figure like
 * "0.000001 xBZZ" mid-number. A [mono] value (an address, a hash) is one
 * long token that wraps anywhere anyway, so it keeps the side-by-side row.
 */
@Composable
internal fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    singleLine: Boolean = true,
) {
    val labelText: @Composable () -> Unit = {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 12.dp),
        )
    }
    if (!singleLine && !mono) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labelText()
            Text(value)
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labelText()
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
