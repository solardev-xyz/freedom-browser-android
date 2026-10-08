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
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.annotation.StringRes
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.mobile.ens.NameSystem
import baby.freedom.mobile.l10n.Strings

/**
 * How far the answer behind a name-addressed page was checked (#97) —
 * the trust shield on the address bar's protocol badge, and the details
 * behind it. Mirrors iOS's `TrustShield.swift` / `ENSTrustLevel`, with
 * the tiers this browser's resolver can produce (#96, #100):
 *
 * - [Proven]: the Colibri verifier checked a proof of the record
 *   against Ethereum's sync committee on this device, or the Myotis
 *   light client ran the lookup itself against state proven to that
 *   committee ([EnsTrust.lightClient], #101) — the chain's own
 *   consensus, not servers agreeing. A seal rather than a shield, as on
 *   iOS, so the two verified tiers are told apart at a glance.
 * - [Verified]: at least [baby.freedom.mobile.ens.EnsQuorum.M]
 *   independent RPC servers returned the byte-identical record at a
 *   block a majority of them agreed on.
 * - [Unverified]: only one server's word — the user let it through the
 *   *Not cross-checked* warning, or a re-check served the answer the
 *   session already had.
 *
 * The quorum's two tiers cover every name system the browser resolves —
 * ENS, WNS (`.wei`), GNS (`.gwei`) and Tezos Domains (`.tez`, #176),
 * whose providers vote the same way ([NameTrust.system] names whose
 * record it is). [Proven] is Ethereum's alone: `.tez` records live on
 * Tezos, which neither Colibri nor Myotis covers.
 *
 * iOS's other two tiers have no page to sit on here: servers that
 * *disagree* (a conflict, #174's third verdict) never load anything —
 * the tab shows the `ens_conflict` warning instead — and name
 * resolution doesn't use the user's own RPCs (the chain settings' RPCs,
 * #187/#189, serve dapps, not names). A page that isn't reached through
 * a name (plain `https://`, a raw `bzz://<hash>`, a `.tez` website
 * record on the ordinary web) makes no name claim and gets no shield.
 */
internal enum class TrustTier(
    @StringRes private val titleRes: Int,
    /** [title] as it follows the badge's own name in the mark's content description. */
    @StringRes private val markLabelRes: Int,
    val icon: ImageVector,
    val color: Color,
) {
    Proven(R.string.names_tier_proven, R.string.names_tier_proven_mark, Icons.Filled.Verified, Color(0xFF3FB950)),
    Verified(R.string.names_tier_verified, R.string.names_tier_verified_mark, Icons.Filled.VerifiedUser, Color(0xFF3FB950)),
    Unverified(
        R.string.names_tier_unverified,
        R.string.names_tier_unverified_mark,
        Icons.Filled.GppMaybe,
        Color(0xFFF0A020),
    ),
    ;

    /** Short state, as the menu row and the dialog title say it. */
    val title: String get() = Strings.get(titleRes)

    val markLabel: String get() = Strings.get(markLabelRes)
}

/**
 * The trust behind [name] (`vitalik.eth`) on the page on screen, and the
 * [answer] (`ipfs://<cid>`) it was given for — taken together, when the
 * document committed, so the details dialog never pairs one answer's
 * servers and block with a later answer's URI.
 */
internal data class NameTrust(val name: String, val trust: EnsTrust, val answer: String? = null) {
    val tier: TrustTier
        get() = when {
            trust.proven -> TrustTier.Proven
            trust.verified -> TrustTier.Verified
            else -> TrustTier.Unverified
        }

    /** ENS, WNS, GNS or Tezos Domains — whose records these are. */
    val system: String get() = NameSystem.forName(name).label

    private val block: String
        get() = trust.block?.let { Strings.get(R.string.names_trust_at_block, it.toString()) }
            ?: Strings.get(R.string.names_trust_at_latest_block)

    /** Who gave a proof: the prover's host(s), or the prover by name. */
    private val prover: String
        get() = trust.shownAgreed.joinToString(Strings.get(R.string.names_trust_prover_separator))
            .ifEmpty { Strings.get(R.string.names_trust_colibri_prover) }

    /** The one server that answered, by host when known. */
    private val loneServer: String
        get() = trust.shownAgreed.singleOrNull() ?: Strings.get(R.string.names_trust_one_rpc_server)

    /**
     * [name] as these sentences print it: a lookalike `.tez` name as
     * `xn--`, the way the address bar shows it (#465).
     */
    val shown: String get() = EnsNormalize.tezosDisplay(name)

    /** One sentence on what the tier means for this answer. */
    val summary: String
        get() = when (tier) {
            TrustTier.Proven -> if (trust.lightClient) {
                Strings.get(R.string.names_summary_light_client, system, shown, block)
            } else if (trust.offchain) {
                Strings.get(R.string.names_summary_proven_offchain, shown, system, prover, block)
            } else {
                Strings.get(R.string.names_summary_proven, prover, shown, system, block)
            }
            TrustTier.Verified -> {
                val n = trust.agreed.size
                if (n >= 2) {
                    Strings.plural(R.plurals.names_summary_verified, n, n, system, shown, block)
                } else {
                    Strings.get(R.string.names_summary_verified_servers, system, shown, block)
                }
            }
            TrustTier.Unverified -> Strings.get(R.string.names_summary_unverified, loneServer, shown, system)
        }

    /**
     * [summary] for a send's recipient (#277): the same tiers, said of
     * the name's address record — and of where the money goes, not
     * where a page comes from.
     */
    val recipientSummary: String
        get() = when (tier) {
            TrustTier.Proven -> if (trust.lightClient) {
                Strings.get(R.string.names_recipient_light_client, shown, system, block)
            } else if (trust.offchain) {
                Strings.get(R.string.names_recipient_proven_offchain, shown, prover, block)
            } else {
                Strings.get(R.string.names_recipient_proven, prover, shown, system, block)
            }
            TrustTier.Verified -> {
                val n = trust.agreed.size
                if (n >= 2) {
                    Strings.plural(R.plurals.names_recipient_verified, n, n, shown, block)
                } else {
                    Strings.get(R.string.names_recipient_verified_servers, shown, block)
                }
            }
            TrustTier.Unverified -> Strings.get(R.string.names_recipient_unverified, loneServer, shown)
        }
}

/**
 * The trust behind the name [displayUrl] shows (`ipfs://vitalik.eth/p`,
 * `swarm.eth`), as this session last checked it — `null` for an address
 * that isn't a name, or a name with no recorded answer.
 *
 * Read once per committed document by the tab's WebView client
 * ([BrowserState.nameTrust], via [committedNameTrust]), so the shield
 * doesn't follow another tab's later re-check of the same name. A
 * document the tab's own re-check served takes its trust from the
 * answer it was served from ([EnsDocumentPins.answerFor]) instead —
 * after a failed lookup, an older one than this.
 */
internal fun nameTrustFor(displayUrl: String): NameTrust? {
    val name = nameIn(displayUrl) ?: return null
    val (answer, trust) = KnownEnsNames.answerFor(name) ?: return null
    return NameTrust(name, trust, answer)
}

/** The name [displayUrl] (`ipfs://vitalik.eth/p`, `swarm.eth`) shows, if any. */
internal fun nameIn(displayUrl: String): String? =
    EnsInput.parse(displayUrl)?.name ?: EnsInput.parseConstrained(displayUrl)?.name

/**
 * What the protocol badge is read out as: "via Swarm", or with its
 * shield, "via Swarm, proven name" — on the mark itself, and on the
 * address bar's Page info button that carries it (#442).
 */
@Composable
internal fun protocolBadgeDescription(badge: ProtocolBadge, trust: NameTrust?): String =
    if (trust != null) {
        stringResource(R.string.names_badge_with_trust, badge.contentDescription, trust.tier.markLabel)
    } else {
        badge.contentDescription
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
    val description = protocolBadgeDescription(badge, trust)
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
                    TrustFacts(trust)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}

/**
 * The evidence behind [trust]: the name, its answer, the block, the
 * servers that gave it and the ones outvoted — in the shield's details
 * dialog, and under the name in Page info (#442).
 */
@Composable
internal fun TrustFacts(trust: NameTrust) {
    val answer = trust.answer
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The shown form, as in the capsule and [NameTrust.summary] (#490 R1-F1).
        TrustFact(stringResource(R.string.names_fact_name), trust.shown)
        if (answer != null) TrustFact(stringResource(R.string.names_fact_resolves_to), answer)
        TrustFact(
            stringResource(R.string.names_fact_block),
            trust.trust.block?.let { "#$it" } ?: stringResource(R.string.names_fact_block_latest),
        )
        if (trust.trust.agreed.isNotEmpty()) {
            TrustFact(
                when (trust.tier) {
                    TrustTier.Proven -> stringResource(
                        if (trust.trust.lightClient) R.string.names_fact_verified_by else R.string.names_fact_proof_from,
                    )
                    TrustTier.Verified -> stringResource(R.string.names_fact_agreed, trust.trust.agreed.size)
                    TrustTier.Unverified -> stringResource(R.string.names_fact_answered_by)
                },
                trust.trust.shownAgreed.joinToString("\n"),
            )
        }
        if (trust.trust.dissented.isNotEmpty()) {
            TrustFact(
                stringResource(R.string.names_fact_outvoted, trust.trust.dissented.size),
                trust.trust.dissented.joinToString("\n"),
            )
        }
    }
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
