package baby.freedom.mobile.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import kotlinx.coroutines.delay

/**
 * The "Open without gateway" pill (#537), desktop's address-bar pill
 * (freedom-browser#70): shown over the page, just above the address bar,
 * while the active tab is on a gateway page that has a native version
 * ([GatewayOfferTab.offer]). Its icon is the target network's; one tap
 * opens [destination] through the ordinary submit flow, so the name is
 * resolved and checked as if the user had typed it.
 *
 * The destination is spelled out for TalkBack; on screen the pill keeps
 * to its label, since a `bzz://` reference is 64 hex characters.
 *
 * The page decides when the pill appears, and its tap is the user's own
 * submit (which lifts the tab's declined-download and prompt blocks), so
 * a press that began before it had been on screen, left alone, for
 * [PromptTapGuard.PROTECTION_MS] — or one through another app's window —
 * is dropped ([protectedPress]), re-armed for each new offer
 * ([offerKey]). Unlike [rememberArmedTapGuard] it doesn't hide other
 * apps' overlays: it is up for as long as a gateway page is.
 *
 * The × beside it ([onDismiss]) closes it for the page, which it may
 * otherwise cover for good on a page that never scrolls (R1-M4).
 */
@Composable
internal fun GatewayOfferPill(
    protocol: String,
    destination: String,
    /** The offer the pill is for: a new one re-arms the tap guard. */
    offerKey: Any?,
    onOpen: () -> Unit,
    /** Closes the pill for this page (R1-M4): it may cover the page's own controls. */
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val described = stringResource(R.string.gateway_offer_open_described, destination)
    val tap = remember(offerKey) { ArmedTapGuard(PromptTapGuard(SystemClock::uptimeMillis)) }
    LaunchedEffect(tap) {
        withFrameNanos { }
        tap.guard.onShown()
        while (!tap.guard.accepts()) delay(tap.guard.remainingMs().coerceAtLeast(1))
        tap.armed = true
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 2.dp,
            // Never squeezes the × out on a narrow screen or a large font.
            modifier = Modifier
                .weight(1f, fill = false)
                .restartsTapGuard(tap.guard)
                .protectedPress(tap)
                .heightIn(min = 48.dp)
                .testTag("gateway-offer-pill")
                .semantics {
                    role = Role.Button
                    contentDescription = described
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(if (protocol == "bzz") R.drawable.ic_swarm else R.drawable.ic_ipfs),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                // Read out through the pill's own description, which names the destination.
                Text(
                    stringResource(R.string.gateway_offer_open),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        // Closing only takes the pill down: no need to guard it. A 48 dp
        // target, drawn as a small disc beside the pill.
        Surface(
            onClick = onDismiss,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(48.dp)
                .testTag("gateway-offer-dismiss")
                .semantics { role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.gateway_offer_dismiss),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
