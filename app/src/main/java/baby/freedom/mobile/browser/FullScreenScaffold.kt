package baby.freedom.mobile.browser

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R

/**
 * Unified chrome for every full-screen "page" in the browser
 * (History, Bookmarks, Settings, Nodes, Wallet …). Provides:
 *   • background-coloured root with system-bar insets applied once
 *   • a ← back arrow at the start, before the title, as Android and
 *     Chrome put it on a page you navigated into (#400, item 11). It
 *     calls [onDismiss], the page's own way out; the icon is
 *     auto-mirrored, so it points right and sits on the right in RTL.
 *   • the title, after the arrow
 *   • an optional [trailing] slot for extra actions, at the end
 *   • a content area that fills the remaining space
 *
 * A page that is really a full-screen dialog — it commits with an action
 * in the header (Add, Save) and its way out throws the form away — passes
 * [exit] = [PageExit.Close] and gets Material's full-screen-dialog × in
 * the same place instead.
 *
 * The body slot is where each page drops in its own LazyColumn / grid /
 * SectionCard stack; this helper is deliberately layout-agnostic below
 * the header.
 */
@Composable
internal fun FullScreenScaffold(
    title: String,
    onDismiss: () -> Unit,
    exit: PageExit = PageExit.Back,
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // First in the row, so TalkBack reaches it before the title, as
            // on a Material top app bar. A full 48 dp target.
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(exit.icon, contentDescription = stringResource(exit.label))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    // The page's name: TalkBack's heading navigation
                    // starts here (#279).
                    .semantics { heading() },
            )
            trailing()
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

/**
 * The way out of a full-screen page, drawn at the start of its header:
 * ← for a page the user navigated into (Android's and Chrome's Back), ×
 * for a full-screen dialog that commits from its header.
 */
internal enum class PageExit(val icon: ImageVector, @StringRes val label: Int) {
    Back(Icons.AutoMirrored.Filled.ArrowBack, R.string.common_back),
    Close(Icons.Filled.Close, R.string.common_close),
}
