package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.MyotisUpgradeAdvisory
import java.text.DateFormat
import java.util.Date

/**
 * What the light client's fork watch (#542, desktop #586) says about one
 * chain: [text] names the upgrade and what to do, [detail] that peers
 * reported it unverified.
 */
internal data class MyotisUpgradeNotice(val network: MyotisNetwork, val text: String, val detail: String)

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun defaultDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/**
 * [chain]'s upgrade notice, or `null` without an advisory. The advisory
 * is peer-supplied and never read by verification, so an ACTIVE one only
 * says the light client can't verify when its own state agrees (not
 * [MyotisChainStatus.ready]); while it's still ready, reads "may stop".
 * A fork id of `0x00000000` (unknown, likely blob-parameter-only) reads
 * the same: upstream doesn't say such a fork leaves reads working.
 */
internal fun myotisUpgradeNotice(
    network: MyotisNetwork,
    chain: MyotisChainStatus,
    nowMs: Long,
    formatDate: (Long) -> String = ::defaultDate,
): MyotisUpgradeNotice? {
    val advisory = chain.upgradeAdvisory ?: return null
    val name = network.displayName
    val detail = Strings.plural(R.plurals.node_upgrade_reported_by, advisory.observedPeers, advisory.observedPeers)
    if (advisory.phase == MyotisUpgradeAdvisory.ACTIVE) {
        val outcome = if (chain.ready) {
            Strings.get(R.string.node_upgrade_active_ready)
        } else {
            Strings.get(R.string.node_upgrade_active_not_ready, name)
        }
        return MyotisUpgradeNotice(network, Strings.get(R.string.node_upgrade_active, name, outcome), detail)
    }
    val (whenText, deadline) = if (advisory.activationTime <= 0) {
        Strings.get(R.string.node_upgrade_when_soon) to R.string.node_upgrade_update_soon
    } else {
        val at = advisory.activationTime * 1000
        val date = formatDate(at)
        val days = Math.floorDiv(at - nowMs, DAY_MS)
        when {
            at <= nowMs -> Strings.get(R.string.node_upgrade_when_due, date) to R.string.node_upgrade_update_now
            days < 1 -> Strings.get(R.string.node_upgrade_when_within_day, date) to R.string.node_upgrade_update_before
            else -> {
                val n = days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Strings.plural(R.plurals.node_upgrade_when_days, n, n, date) to R.string.node_upgrade_update_before
            }
        }
    }
    return MyotisUpgradeNotice(
        network,
        Strings.get(R.string.node_upgrade_scheduled, name, whenText, Strings.get(deadline)),
        detail,
    )
}

/**
 * Every switched-on, running chain's notice, in [MyotisNetwork] order: a
 * chain that is off verifies nothing, so there's nothing to update for
 * (desktop's `off`/`disabled` rule). Both the node page and the home
 * update notice read this.
 */
internal fun myotisUpgradeNotices(
    info: MyotisInfo,
    running: Set<MyotisNetwork>,
    nowMs: Long,
    formatDate: (Long) -> String = ::defaultDate,
): List<MyotisUpgradeNotice> {
    val shown = lightClientInfoFor(info, running)
    if (shown.status != MyotisStatus.Running) return emptyList()
    return MyotisNetwork.entries.mapNotNull { network ->
        if (network !in running) return@mapNotNull null
        shown.chain(network)?.let { myotisUpgradeNotice(network, it, nowMs, formatDate) }
    }
}
