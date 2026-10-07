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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import baby.freedom.mobile.l10n.Strings

/**
 * What the tab on screen shows in place of its page after the renderer
 * process the page ran in went away (#260, [BrowserState.rendererGone]):
 * whether the page crashed it or Android closed it to free memory, and
 * Reload, which brings the page back — with its back/forward history —
 * in a new renderer. Opaque, like the home overlay: there is no WebView
 * under it any more.
 */
@Composable
fun RendererGoneScreen(
    gone: BrowserState.RendererGone,
    onReload: () -> Unit,
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
        Icon(
            imageVector = if (gone.crashed) Icons.Outlined.ErrorOutline else Icons.Outlined.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = rendererGoneTitle(gone),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = rendererGoneBody(gone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onReload) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.errorpage_renderer_gone_reload))
        }
    }
}

/**
 * The headline for [gone]: the page closed after a crash, or to free
 * memory. Worded for the tab, not blaming its page: every tab's WebView
 * usually runs in the one renderer process, so every tab gets the same
 * verdict ([android.webkit.RenderProcessGoneDetail.didCrash]) whichever
 * page actually brought the process down (R3-M1).
 */
internal fun rendererGoneTitle(gone: BrowserState.RendererGone): String =
    Strings.get(
        if (gone.crashed) R.string.errorpage_renderer_gone_crashed_title else R.string.errorpage_renderer_gone_memory_title,
    )

/**
 * What happened, and what Reload does. Doesn't claim the other tabs are
 * fine: those sharing the process went with it and reload when shown
 * (R3-M1).
 */
internal fun rendererGoneBody(gone: BrowserState.RendererGone): String =
    Strings.get(
        if (gone.crashed) R.string.errorpage_renderer_gone_crashed_body else R.string.errorpage_renderer_gone_memory_body,
    )

/**
 * Whether the chrome's Back / Forward ([pending], [HISTORY_BACK_JS] /
 * [HISTORY_FORWARD_JS]) is dropped instead of handed to the WebView: it
 * has no entry to step to ([canStep] false) while a restore still has
 * the tab's page to put back ([putBackArmed], [BrowserState.afterBlank]).
 * That's a tab rebuilt after its renderer went away (#260) whose history
 * *Delete browsing data*'s *Cookies and site data* dropped: handed, the step would stop the
 * put-back and leave the tab on its blank entry, its page gone (R2-F1).
 * Dropped, the page comes back — as a live tab stays on its page when
 * the same clear left it nothing to go back to.
 */
internal fun stepDroppedForPutBack(pending: String, putBackArmed: Boolean, canStep: (Int) -> Boolean): Boolean {
    val step = when (pending) {
        HISTORY_BACK_JS -> -1
        HISTORY_FORWARD_JS -> 1
        else -> return false
    }
    return putBackArmed && !canStep(step)
}
