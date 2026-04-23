package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Label / value row used inside a [SectionCard] for static property
 * listings (version, peer count, gateway URL, etc.). Label is muted
 * on the left; value takes the remaining space on the right.
 */
@Composable
internal fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    singleLine: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
