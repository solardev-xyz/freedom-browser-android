package baby.freedom.mobile.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen, Chrome-style tab switcher.
 *
 * A top bar with "+ New tab" and an × to dismiss, followed by a 2-column
 * grid of tab cards. Each card shows its page title, a close (×) button,
 * and a preview thumbnail of the page (or a letter placeholder when no
 * snapshot has been captured yet).
 */
@Composable
fun TabSwitcherScreen(
    tabs: TabsState,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
) {
    // Snapshot the currently-active tab right before we render so the
    // user sees an up-to-date preview of whatever they were last reading.
    LaunchedEffect(Unit) { tabs.captureActiveThumbnail?.invoke() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                onNewTab()
                onDismiss()
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("New tab", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.Close, contentDescription = "Close tab switcher")
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = tabs.tabs,
                key = { _, tab -> tab.id },
            ) { index, tab ->
                TabCard(
                    tab = tab,
                    isActive = index == tabs.activeIndex,
                    onClick = {
                        tabs.switchTo(index)
                        onDismiss()
                    },
                    onClose = { tabs.closeTab(index) },
                )
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isActive) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = MaterialTheme.shapes.large,
            )
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = tab.title.ifBlank {
                tab.url.ifBlank { "New tab" }
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClose,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close tab",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            val thumb = tab.thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ThumbnailPlaceholder(tab)
            }
        }
    }
}

@Composable
private fun ThumbnailPlaceholder(tab: BrowserState) {
    val letter = firstLetterFor(tab)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
    }
}

private fun firstLetterFor(tab: BrowserState): String {
    val source = tab.title.ifBlank { tab.url }
    if (source.isBlank()) return "•"
    // Strip scheme and any leading `www.`.
    val stripped = source
        .substringAfter("://")
        .removePrefix("www.")
    val ch = stripped.firstOrNull { it.isLetterOrDigit() } ?: return "•"
    return ch.uppercase()
}

/**
 * The tabs-count badge that lives in the bottom toolbar's trailing round
 * button. Tapping it opens the switcher. Renders as a bordered square
 * with the tab count inside.
 *
 * Nineteen dp across with a 1.2 dp stroke, down from 22 and 1.5: the
 * badge now sits inside a 44 dp circle of its own rather than loose on a
 * bar, and the split-bar mockup draws it at 58 px square with a 3.5 px
 * outline at 3× density (19.3 dp / 1.17 dp). Lighter and smaller, so the
 * circle around it reads as the control and the badge as its content.
 *
 * The count is the one piece of type in the chrome that does *not* take
 * the system font scale: it is an icon's fill, not text the user reads a
 * sentence of, and the square it fills is a fixed 19 dp. Sized in `sp`
 * it overflowed its own outline at `font_scale 2.0` (seen on the AVD);
 * sized off the density it stays the same fraction of the badge at every
 * accessibility setting, while the address label beside it scales as it
 * always has.
 */
@Composable
fun TabsCountButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stroke = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(
                    width = 1.2.dp,
                    color = stroke,
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "∞" else count.toString(),
                color = stroke,
                style = MaterialTheme.typography.labelSmall,
                fontSize = with(density) { 11.dp.toSp() },
                lineHeight = with(density) { 13.dp.toSp() },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
