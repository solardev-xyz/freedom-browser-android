package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Publish setup (#114): the guided checklist that takes the Swarm node
 * from browsing (ultra-light) to publishing — desktop's publish-setup.js /
 * swarm-readiness.js and iOS's PublishSetupView / BeeReadiness, adapted to
 * ant. Unlike bee, ant switches to light mode without funds (it only
 * starts reading Gnosis) and deploys no chequebook by itself, so the order
 * differs: identity, light mode, xDAI, chequebook, postage stamp. Funding
 * in one transaction (#115), buying stamps (#116) and the chequebook
 * deposit (#117) come in their own issues; until then those steps say so.
 */

/** A checklist step's state, as on iOS: ○ pending, ▶ active (yours to do), ⏳ waiting, ✓ done. */
internal enum class StepStatus { Pending, Active, Waiting, Done }

internal enum class PublishStepKey { Identity, LightMode, Fund, Chequebook, Stamp }

internal data class PublishStep(val key: PublishStepKey, val title: String, val detail: String, val status: StepStatus)

/**
 * What the checklist is computed from. `null` means not known (yet): a
 * balance still being read, a gateway read that failed, or one that
 * doesn't apply — the chequebook and stamps are only readable in light mode.
 */
internal data class PublishReadiness(
    val node: NodeInfo,
    /** The light-mode setting: on while the node is (re)starting into it. */
    val lightModeWanted: Boolean,
    /** The node account's xDAI balance in wei. */
    val xdaiWei: BigInteger? = null,
    /** The balance couldn't be read (its last read failed), rather than not read yet. */
    val xdaiUnavailable: Boolean = false,
    /** The deployed chequebook's address, `""` for none. */
    val chequebook: String? = null,
    /** How many of the node's postage batches are usable. */
    val usableStamps: Int? = null,
)

/**
 * Why nothing on the checklist can move right now — the node isn't
 * running — or null when it can.
 */
internal fun publishBlockedReason(node: NodeInfo): String? = when (node.status) {
    NodeStatus.Running -> null
    NodeStatus.Starting -> "The Swarm node is starting…"
    NodeStatus.Stopped -> "Turn on the Swarm node to set up publishing."
    NodeStatus.Error -> "The Swarm node reported an error. Turn it off and on again on the node page."
}

/**
 * The five steps. Each is done on its own evidence, whatever the order; the
 * first one not done is the one to work on (active, or waiting when there's
 * nothing for the user to do but wait), and the rest are pending. While the
 * node isn't running nothing is known, so every step not done is pending.
 */
internal fun publishSteps(r: PublishReadiness): List<PublishStep> {
    val running = r.node.status == NodeStatus.Running
    val light = running && r.node.lightMode
    val chequebookDeployed = !r.chequebook.isNullOrEmpty()
    val funded = (r.xdaiWei?.signum() ?: 0) > 0 || chequebookDeployed
    val stamped = (r.usableStamps ?: 0) > 0

    val done = mapOf(
        PublishStepKey.Identity to (running && r.node.walletIdentity),
        PublishStepKey.LightMode to light,
        PublishStepKey.Fund to (running && funded),
        PublishStepKey.Chequebook to (light && chequebookDeployed),
        PublishStepKey.Stamp to (light && stamped),
    )
    val current = if (running) PublishStepKey.entries.firstOrNull { done[it] != true } else null
    fun status(key: PublishStepKey, waiting: Boolean = false) = when {
        done[key] == true -> StepStatus.Done
        key != current -> StepStatus.Pending
        waiting -> StepStatus.Waiting
        else -> StepStatus.Active
    }

    val identityDone = done[PublishStepKey.Identity] == true
    return listOf(
        PublishStep(
            PublishStepKey.Identity,
            "Use your wallet's identity",
            if (identityDone) {
                "The node runs as the Swarm account from your recovery phrase."
            } else {
                "Publishing pays from the node's own account. Set up a wallet so that account comes " +
                    "from your recovery phrase, and can be restored on another device."
            },
            status(PublishStepKey.Identity),
        ),
        PublishStep(
            PublishStepKey.LightMode,
            "Switch to light mode",
            when {
                light -> "The node is connected to Gnosis Chain."
                r.lightModeWanted -> "Restarting the node in light mode…"
                else -> "Light mode connects the node to Gnosis Chain, which publishing needs. " +
                    "Browsing works the same in either mode."
            },
            // Switched on and the node restarting into it: nothing to do but wait.
            status(PublishStepKey.LightMode, waiting = r.lightModeWanted),
        ),
        PublishStep(
            PublishStepKey.Fund,
            "Fund the node with xDAI",
            when {
                r.xdaiWei != null && r.xdaiWei.signum() > 0 -> "Funded with ${formatXdai(r.xdaiWei)}."
                chequebookDeployed -> "Funded: the node has deployed its chequebook."
                else -> "Send xDAI on Gnosis Chain to the node's address below. It pays the gas for the " +
                    "chequebook and the postage stamps." +
                    if (r.xdaiUnavailable) " Its balance can't be read right now; this updates once it can." else ""
            },
            // The balance still being read: wait for it before asking for funds.
            status(PublishStepKey.Fund, waiting = r.xdaiWei == null && !r.xdaiUnavailable),
        ),
        PublishStep(
            PublishStepKey.Chequebook,
            "Chequebook",
            if (chequebookDeployed) {
                "Deployed at ${r.chequebook}."
            } else {
                "The chequebook pays other nodes for storing your data. The node deploys it with " +
                    "its first postage stamp."
            },
            // Nothing to tap here: it comes with the stamp.
            if (done[PublishStepKey.Chequebook] == true) StepStatus.Done else StepStatus.Pending,
        ),
        PublishStep(
            PublishStepKey.Stamp,
            "Buy a postage stamp",
            when {
                stamped -> "${plural(r.usableStamps ?: 0, "usable postage batch", "usable postage batches")}."
                else -> "Postage stamps pre-pay the network for storing your data. Buying one isn't " +
                    "in this version of Freedom yet."
            },
            // Not buyable here yet (#116): never shown as the user's to do.
            if (done[PublishStepKey.Stamp] == true) StepStatus.Done else StepStatus.Pending,
        ),
    )
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/** Wei as xDAI, to at most 6 decimals (rounded down, so a dust balance never reads as more). */
internal fun formatXdai(wei: BigInteger): String {
    val xdai = BigDecimal(wei).movePointLeft(18)
    val shown = xdai.setScale(6, RoundingMode.DOWN).stripTrailingZeros()
    val text = if (shown.signum() == 0 && wei.signum() > 0) "< 0.000001" else shown.toPlainString()
    return "$text xDAI"
}

/** The chequebook address from a `/chequebook/address` body: `""` for none (the all-zero sentinel). */
internal fun chequebookFrom(body: String): String? {
    val address = runCatching { JSONObject(body).optString("chequebookAddress") }.getOrNull() ?: return null
    val hex = address.removePrefix("0x").removePrefix("0X")
    if (hex.length != 40 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    return if (hex.all { it == '0' }) "" else "0x$hex"
}

/** How many batches in a `/stamps` body are usable. */
internal fun usableStampsFrom(body: String): Int? {
    val stamps = runCatching { JSONObject(body).optJSONArray("stamps") }.getOrNull() ?: return null
    return (0 until stamps.length()).count { stamps.optJSONObject(it)?.optBoolean("usable") == true }
}

/** GET [path] from the embedded node's gateway; the body of a 200, else null. */
private suspend fun gatewayGet(path: String): String? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(SwarmNode.GATEWAY_URL + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = GATEWAY_TIMEOUT_MS
            conn.readTimeout = GATEWAY_TIMEOUT_MS
            conn.useCaches = false
            if (conn.responseCode != 200) return@withContext null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * The publish setup page, over the node page. [lightModeWanted] is the
 * setting; [onSwitchToLightMode] turns it on, [onOpenWallet] opens the
 * wallet page for the identity step.
 */
@Composable
internal fun PublishSetupScreen(
    nodeInfo: NodeInfo,
    lightModeWanted: Boolean,
    onSwitchToLightMode: () -> Unit,
    onOpenWallet: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val running = nodeInfo.status == NodeStatus.Running
    val address = nodeInfo.accountAddress.takeIf { running && it.isNotBlank() }
    val light = running && nodeInfo.lightMode

    // The node account's xDAI, read through the app's own chain-data
    // router — so it's there in ultra-light mode too — every 15 s. A
    // failed read keeps the last good value, or says it's unavailable.
    val balance by produceState(BalanceRead(), address) {
        value = BalanceRead()
        val rpc = WalletRpc(ChainDataRouter.get(context))
        while (address != null) {
            value = try {
                BalanceRead(wei = rpc.balance(BuiltInChains.GNOSIS.id, address).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "reading the node's xDAI balance failed (${e.javaClass.simpleName})")
                value.copy(failed = value.wei == null)
            }
            delay(BALANCE_POLL_MS)
        }
    }
    val xdai = balance.wei
    // What only a light node's gateway knows, every 5 s while it runs.
    val chequebook by produceState<String?>(null, light) {
        value = null
        while (light) {
            gatewayGet("/chequebook/address")?.let(::chequebookFrom)?.let { value = it }
            delay(GATEWAY_POLL_MS)
        }
    }
    val usableStamps by produceState<Int?>(null, light) {
        value = null
        while (light) {
            gatewayGet("/stamps")?.let(::usableStampsFrom)?.let { value = it }
            delay(GATEWAY_POLL_MS)
        }
    }

    val steps = publishSteps(
        PublishReadiness(nodeInfo, lightModeWanted, xdai, balance.failed, chequebook, usableStamps),
    )
    val blocked = publishBlockedReason(nodeInfo)

    FullScreenScaffold(title = "Set up publishing", onDismiss = onDismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (blocked != null) {
                item("blocked") {
                    Text(
                        blocked,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            steps.forEachIndexed { index, step ->
                item(step.key.name) {
                    StepCard(number = index + 1, step = step) {
                        when (step.key) {
                            PublishStepKey.Identity -> if (step.status == StepStatus.Active) {
                                Button(onClick = onOpenWallet) { Text("Set up wallet") }
                            }
                            PublishStepKey.LightMode -> if (step.status == StepStatus.Active) {
                                Button(onClick = onSwitchToLightMode) { Text("Switch to light mode") }
                            }
                            PublishStepKey.Fund -> if (address != null) {
                                // The address in full, selectable: it's what the user
                                // copies into another wallet to send to.
                                DetailRow("Node address", address, mono = true, singleLine = false)
                                DetailRow(
                                    "Balance",
                                    xdai?.let(::formatXdai) ?: if (balance.failed) "Unavailable" else "Checking…",
                                )
                                if (step.status != StepStatus.Done) {
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedButton(onClick = { copyNodeAddress(context, address) }) {
                                        Text("Copy address")
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, step: PublishStep, actions: @Composable () -> Unit) {
    SectionCard(title = "Step $number") {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (step.status == StepStatus.Pending) 0.6f else 1f),
        ) {
            StepIcon(step.status)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(step.title, fontWeight = FontWeight.Medium)
                SelectionContainer {
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = if (step.key == PublishStepKey.Chequebook && step.status == StepStatus.Done) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        },
                    )
                }
                actions()
            }
        }
    }
}

@Composable
private fun StepIcon(status: StepStatus) {
    val modifier = Modifier.size(24.dp)
    when (status) {
        StepStatus.Pending -> Icon(
            Icons.Outlined.Circle, contentDescription = "Not yet", modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepStatus.Active -> Icon(
            Icons.Filled.PlayCircle, contentDescription = "To do", modifier = modifier,
            tint = MaterialTheme.colorScheme.primary,
        )
        StepStatus.Waiting -> CircularProgressIndicator(
            strokeWidth = 2.dp, modifier = Modifier.padding(2.dp).size(20.dp),
        )
        StepStatus.Done -> Icon(
            Icons.Filled.CheckCircle, contentDescription = "Done", modifier = modifier,
            tint = Color(0xFF22C55E),
        )
    }
}

/** A balance read: the last good value, and whether reading has only failed so far. */
private data class BalanceRead(val wei: BigInteger? = null, val failed: Boolean = false)

private fun copyNodeAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Swarm node address", address))
    // Android 13+ confirms every copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
    }
}

private const val TAG = "PublishSetup"
private const val BALANCE_POLL_MS = 15_000L
private const val GATEWAY_POLL_MS = 5_000L
private const val GATEWAY_TIMEOUT_MS = 5_000
