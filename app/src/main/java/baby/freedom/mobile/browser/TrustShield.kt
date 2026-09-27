package baby.freedom.mobile.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.mobile.ens.NameSystem

/**
 * How far the answer behind a name-addressed page was checked (#97) —
 * the trust shield on the address bar's protocol badge, and the details
 * behind it. Mirrors iOS's `TrustShield.swift` / `ENSTrustLevel`, with
 * the tiers this browser's resolver can produce (#96):
 *
 * - [Verified]: at least [baby.freedom.mobile.ens.EnsQuorum.M]
 *   independent RPC servers returned the byte-identical record at a
 *   block a majority of them agreed on.
 * - [Unverified]: only one server's word — the user let it through the
 *   *Not cross-checked* warning, or a re-check served the answer the
 *   session already had.
 *
 * iOS's other two tiers have no page to sit on here: servers that
 * *disagree* (a conflict) never load anything — the tab shows the
 * `ens_conflict` warning instead — and there is no user-configured RPC
 * endpoint yet. A page that isn't reached through a name (plain
 * `https://`, a raw `bzz://<hash>`) makes no name claim and gets no
 * shield.
 */
internal enum class TrustTier(
    /** Short state, as the menu row and the dialog title say it. */
    val title: String,
    val icon: ImageVector,
    val color: Color,
) {
    Verified("Verified name", Icons.Filled.VerifiedUser, Color(0xFF3FB950)),
    Unverified("Name not cross-checked", Icons.Filled.GppMaybe, Color(0xFFF0A020)),
}

/** The trust behind [name] (`vitalik.eth`) on the page on screen. */
internal data class NameTrust(val name: String, val trust: EnsTrust) {
    val tier: TrustTier get() = if (trust.verified) TrustTier.Verified else TrustTier.Unverified

    /** ENS, WNS or GNS — whose records these are. */
    val system: String get() = NameSystem.forName(name).label

    private val block: String get() = trust.block?.let { "block #$it" } ?: "the latest block"

    /** One sentence on what the tier means for this answer. */
    val summary: String
        get() = when (tier) {
            TrustTier.Verified -> {
                val n = trust.agreed.size
                val agreed = if (n >= 2) "$n independent RPC servers" else "Independent RPC servers"
                "$agreed returned the same $system record for $name at $block."
            }
            TrustTier.Unverified -> {
                val who = trust.agreed.singleOrNull() ?: "one RPC server"
                "Only $who answered for $name, so its $system record wasn't checked " +
                    "against another server. A single misbehaving server could have picked " +
                    "where this page comes from."
            }
        }
}

/**
 * The trust behind the name [displayUrl] shows (`ipfs://vitalik.eth/p`,
 * `swarm.eth`), as this session last checked it — `null` for an address
 * that isn't a name, or a name with no recorded answer.
 *
 * Read once per committed document by the tab's WebView client
 * ([BrowserState.nameTrust]), so the shield describes the answer the
 * page on screen was served from, not whatever another tab's re-check
 * of the same name found later.
 */
internal fun nameTrustFor(displayUrl: String): NameTrust? {
    val name = EnsInput.parse(displayUrl)?.name
        ?: EnsInput.parseConstrained(displayUrl)?.name
        ?: return null
    val trust = KnownEnsNames.trustFor(name) ?: return null
    return NameTrust(name, trust)
}

/** Diameter of the shield riding the protocol badge's corner. */
private val ShieldMarkSize = 11.dp

/**
 * The protocol badge (Swarm hex / IPFS cube), with the trust shield on
 * its bottom-end corner when the page was reached through a name.
 *
 * The shield rides the badge rather than taking a slot of its own: the
 * badge is already the mark on the domain that says *how* the page got
 * here, and the capsule's geometry (label centring, the collapse, the
 * Back pill's reservation) is built around exactly one such mark. Drawn
 * past the badge's box, not laid out — [modifier]'s size is the badge's
 * — so nothing around it moves when a shield appears. A ring in the
 * field's colour keeps it legible over the badge's own art.
 */
@Composable
internal fun ProtocolBadgeMark(
    badge: ProtocolBadge,
    trust: NameTrust?,
    modifier: Modifier = Modifier,
) {
    val description = if (trust != null) {
        "${badge.contentDescription}, ${trust.tier.title.lowercase()}"
    } else {
        badge.contentDescription
    }
    Box(modifier = modifier.semantics { contentDescription = description }) {
        Image(
            painter = painterResource(badge.drawableRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        if (trust != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(ShieldMarkSize + 2.dp)
                    .background(capsuleFill(MaterialTheme.colorScheme).copy(alpha = 1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = trust.tier.icon,
                    contentDescription = null,
                    tint = trust.tier.color,
                    modifier = Modifier.size(ShieldMarkSize),
                )
            }
        }
    }
}

/**
 * What the shield stands for, spelled out: the tier, what it means, and
 * the evidence — the name's answer, the servers that gave it, the ones
 * outvoted, the block. Every value is in the text, selectable, none
 * behind a tap or cut short.
 */
@Composable
internal fun TrustDetailsDialog(
    trust: NameTrust,
    answer: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = trust.tier.icon,
                contentDescription = null,
                tint = trust.tier.color,
            )
        },
        title = { Text(trust.tier.title) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(trust.summary)
                    TrustFact("Name", trust.name)
                    if (answer != null) TrustFact("Resolves to", answer)
                    TrustFact(
                        "Block",
                        trust.trust.block?.let { "#$it" } ?: "latest",
                    )
                    if (trust.trust.agreed.isNotEmpty()) {
                        TrustFact(
                            if (trust.tier == TrustTier.Verified) "Agreed (${trust.trust.agreed.size})" else "Answered by",
                            trust.trust.agreed.joinToString("\n"),
                        )
                    }
                    if (trust.trust.dissented.isNotEmpty()) {
                        TrustFact(
                            "Outvoted (${trust.trust.dissented.size})",
                            trust.trust.dissented.joinToString("\n"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun TrustFact(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** The menu row's leading icon: the shield, in its tier's colour. */
@Composable
internal fun TrustShieldIcon(trust: NameTrust) {
    Icon(
        imageVector = trust.tier.icon,
        contentDescription = null,
        tint = trust.tier.color,
    )
}
