package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R

/**
 * What the tab on screen shows while a dweb address the user named is
 * still being looked for (#616, [BrowserState.coversPageForProbe]): the
 * address, and that finding it can take a while — never the previous
 * page under the new address. Opaque, and it takes the touches, like
 * [RendererGoneScreen]: the previous page is still live under it.
 */
@Composable
fun ProbeLoadingScreen(
    address: String,
    ipfs: Boolean,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 32.dp + bottomContentPadding)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.browser_probe_cover_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // The whole address, wrapped: a hash is long, and a phone has
        // no tooltip to read the rest in.
        Text(
            text = address,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(if (ipfs) R.string.browser_probe_cover_ipfs else R.string.browser_probe_cover_swarm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
