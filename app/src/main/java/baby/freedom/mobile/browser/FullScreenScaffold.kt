package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Unified chrome for every full-screen "page" in the browser
 * (History, Bookmarks, Settings, Node). Provides:
 *   • background-coloured root with system-bar insets applied once
 *   • a title on the left
 *   • an optional [trailing] slot for extra actions (rendered to the
 *     left of the × close button)
 *   • a × close button on the right
 *   • a content area that fills the remaining space
 *
 * The body slot is where each page drops in its own LazyColumn / grid /
 * SectionCard stack; this helper is deliberately layout-agnostic below
 * the header.
 */
@Composable
internal fun FullScreenScaffold(
    title: String,
    onDismiss: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
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
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    // The page's name: TalkBack's heading navigation
                    // starts here (#279).
                    .semantics { heading() },
            )
            trailing()
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        // The activity is edge-to-edge with adjustResize, so the keyboard
        // doesn't shrink this window by itself: pad the body by the IME
        // inset (less the navigation bar the root already consumed) so a
        // page with a text field, like Settings search, can scroll its
        // last rows above the keyboard.
        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            content()
        }
    }
}
