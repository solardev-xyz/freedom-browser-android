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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.l10n.Strings
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
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Publish setup (#114): the guided checklist that takes the Swarm node
 * from browsing (ultra-light) to publishing — desktop's publish-setup.js /
 * swarm-readiness.js and iOS's PublishSetupView / BeeReadiness, adapted to
 * ant. Unlike bee, ant switches to light mode without funds (it only
 * starts reading Gnosis) and deploys no chequebook by itself, so the order
 * differs: identity, light mode, xDAI, chequebook, postage stamp. The
 * stamp step opens the stamp pages (#116, [StampsScreen]); buying the
 * first stamp also deploys the chequebook, whose step then shows what it
 * holds and opens the deposit page (#117, [ChequebookScreen]). The fund
 * step also offers funding the node and buying its first stamp in one
 * wallet transaction (#115, [FundNodeScreen]).
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
    /** What the chequebook holds, in PLUR (#117). */
    val chequebookBalancePlur: BigInteger? = null,
    /** How many of the node's postage batches are usable. */
    val usableStamps: Int? = null,
)

/**
 * Why nothing on the checklist can move right now — the node isn't
 * running — or null when it can.
 */
internal fun publishBlockedReason(node: NodeInfo): String? = when (node.status) {
    NodeStatus.Running -> null
    NodeStatus.Starting -> Strings.get(R.string.publish_blocked_node_starting)
    NodeStatus.Stopped -> Strings.get(R.string.publish_setup_blocked_node_off)
    NodeStatus.Error -> Strings.get(R.string.publish_setup_blocked_node_error)
}

/**
 * The address to fund, or null while there's none to show: the node isn't
 * running, or it still runs as the device-only key. That key can't be
 * restored anywhere else and is replaced the moment a wallet is set up
 * (step 1), so the checklist never offers it for funding.
 */
internal fun fundingAddress(node: NodeInfo): String? =
    node.accountAddress.takeIf { node.status == NodeStatus.Running && node.walletIdentity && it.isNotBlank() }

/**
 * The five steps. Each is done on its own evidence, whatever the order; the
 * first one not done is the one to work on (active, or waiting when there's
 * nothing for the user to do but wait), and the rest are pending — except
 * the chequebook, which the first stamp brings, so it's never the one. While the
 * node isn't running nothing is known, so every step not done is pending.
 */
internal fun publishSteps(r: PublishReadiness): List<PublishStep> {
    val running = r.node.status == NodeStatus.Running
    val light = running && r.node.lightMode
    val chequebookDeployed = !r.chequebook.isNullOrEmpty()
    // Only the wallet identity's funds count: the device-only key's are
    // about to be left behind (see [fundingAddress]).
    val walletIdentity = running && r.node.walletIdentity
    val funded = walletIdentity && ((r.xdaiWei?.signum() ?: 0) > 0 || chequebookDeployed)
    val stamped = (r.usableStamps ?: 0) > 0

    val done = mapOf(
        PublishStepKey.Identity to walletIdentity,
        PublishStepKey.LightMode to light,
        PublishStepKey.Fund to (running && funded),
        PublishStepKey.Chequebook to (light && chequebookDeployed),
        PublishStepKey.Stamp to (light && stamped),
    )
    // The chequebook has nothing to do of its own: it comes with the first
    // stamp, so the stamp step is the one to work on meanwhile.
    val current = if (running) {
        PublishStepKey.entries.firstOrNull { done[it] != true && it != PublishStepKey.Chequebook }
    } else {
        null
    }
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
            Strings.get(R.string.publish_setup_identity_title),
            if (identityDone) {
                Strings.get(R.string.publish_setup_identity_done)
            } else {
                Strings.get(R.string.publish_setup_identity_todo)
            },
            status(PublishStepKey.Identity),
        ),
        PublishStep(
            PublishStepKey.LightMode,
            Strings.get(R.string.publish_setup_light_title),
            when {
                light -> Strings.get(R.string.publish_setup_light_done)
                r.lightModeWanted -> Strings.get(R.string.publish_setup_light_restarting)
                else -> Strings.get(R.string.publish_setup_light_todo)
            },
            // Switched on and the node restarting into it: nothing to do but wait.
            status(PublishStepKey.LightMode, waiting = r.lightModeWanted),
        ),
        PublishStep(
            PublishStepKey.Fund,
            Strings.get(R.string.publish_setup_fund_title),
            when {
                !walletIdentity -> Strings.get(R.string.publish_setup_fund_needs_identity)
                r.xdaiWei != null && r.xdaiWei.signum() > 0 -> Strings.get(R.string.publish_setup_fund_funded, formatXdai(r.xdaiWei))
                chequebookDeployed -> Strings.get(R.string.publish_setup_fund_chequebook)
                r.xdaiUnavailable -> Strings.get(R.string.publish_setup_fund_todo_unavailable)
                else -> Strings.get(R.string.publish_setup_fund_todo)
            },
            // The balance still being read: wait for it before asking for funds.
            status(PublishStepKey.Fund, waiting = r.xdaiWei == null && !r.xdaiUnavailable),
        ),
        PublishStep(
            PublishStepKey.Chequebook,
            Strings.get(R.string.publish_setup_chequebook_title),
            if (chequebookDeployed) {
                r.chequebookBalancePlur?.let { Strings.get(R.string.publish_setup_chequebook_deployed_holds, r.chequebook, formatBzz(it)) }
                    ?: Strings.get(R.string.publish_setup_chequebook_deployed, r.chequebook)
            } else {
                Strings.get(R.string.publish_setup_chequebook_todo)
            },
            // Nothing to tap here: it comes with the stamp.
            if (done[PublishStepKey.Chequebook] == true) StepStatus.Done else StepStatus.Pending,
        ),
        PublishStep(
            PublishStepKey.Stamp,
            Strings.get(R.string.publish_setup_stamp_title),
            when {
                stamped -> (r.usableStamps ?: 0).let { Strings.plural(R.plurals.publish_setup_stamp_usable, it, it) }
                else -> Strings.get(R.string.publish_setup_stamp_todo)
            },
            status(PublishStepKey.Stamp),
        ),
    )
}

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
internal suspend fun gatewayGet(path: String, timeoutMs: Int = GATEWAY_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(SwarmNode.GATEWAY_URL + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
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
 * wallet page for the identity step, [onBuyStamp] the stamp buy page,
 * [onFundAndBuy] the one-transaction fund-and-buy page (#115).
 */
@Composable
internal fun PublishSetupScreen(
    nodeInfo: NodeInfo,
    lightModeWanted: Boolean,
    onSwitchToLightMode: () -> Unit,
    onOpenWallet: () -> Unit,
    onBuyStamp: () -> Unit,
    onOpenChequebook: () -> Unit,
    onDismiss: () -> Unit,
    onFundAndBuy: () -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val running = nodeInfo.status == NodeStatus.Running
    // Null until the node runs as the wallet's identity: no balance, no
    // address, nothing to copy for the device-only key.
    val address = fundingAddress(nodeInfo)
    val light = running && nodeInfo.lightMode

    // The node account's xDAI, read through the app's own chain-data
    // router — so it's there in ultra-light mode too — every 15 s while
    // the app is in the foreground. A failed read keeps the last good
    // value, or says it's unavailable.
    val lifecycle = currentLifecycle()
    val balance by produceState(BalanceRead(), address, lifecycle) {
        value = BalanceRead()
        if (address == null) return@produceState
        val rpc = WalletRpc(ChainDataRouter.get(context))
        lifecycle.pollWhileStarted(BALANCE_POLL_MS) {
            value = try {
                BalanceRead(wei = rpc.balance(BuiltInChains.GNOSIS.id, address).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "reading the node's xDAI balance failed (${e.javaClass.simpleName})")
                value.copy(failed = value.wei == null)
            }
        }
    }
    val xdai = balance.wei
    // What only a light node's gateway knows, every 5 s while it runs, as
    // before the deposit (#117): the checklist waits on the chequebook.
    val chequebook = rememberChequebookState(light, pollMs = GATEWAY_POLL_MS)
    val usableStamps by produceState<Int?>(null, light, lifecycle) {
        value = null
        if (!light) return@produceState
        lifecycle.pollWhileStarted(GATEWAY_POLL_MS) {
            gatewayGet("/stamps")?.let(::usableStampsFrom)?.let { value = it }
        }
    }

    val steps = publishSteps(
        PublishReadiness(
            nodeInfo, lightModeWanted, xdai, balance.failed, chequebook.address, chequebook.balancePlur, usableStamps,
        ),
    )
    val blocked = publishBlockedReason(nodeInfo)
    // A stamp the wallet bought for the node (#115), until the node has connected it.
    // Read off the main thread: the first one in a process reads its file and starts the sender.
    val funding by produceState(SwarmFunding.loaded(), context) { value = SwarmFunding.load(context) }
    val pendingStamp = funding?.pending?.collectAsState()?.value
    val superseded = funding?.superseded?.collectAsState()?.value
    val connectOwed = funding?.connectOwed?.collectAsState()?.value
    val spend by StampClient.spend.collectAsState()

    FullScreenScaffold(title = stringResource(R.string.publish_set_up), onDismiss = onDismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Nothing until the record's been read (once per process, briefly): a card added above
            // the list's first item later would land out of sight, the list keeping that item in place.
            if (funding == null) return@LazyColumn
            if (blocked != null) {
                item("blocked") {
                    Text(
                        blocked,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val f = funding
            if (pendingStamp != null && f != null) {
                item("pendingStamp") {
                    PendingStampCard(
                        pendingStamp, nodeInfo, spend, superseded == pendingStamp.batchId, connectOwed == pendingStamp.batchId,
                        onConnect = { f.connectNow() }, onForget = f::forget,
                    )
                }
            }
            steps.forEachIndexed { index, step ->
                item(step.key.name) {
                    StepCard(number = index + 1, step = step) {
                        when (step.key) {
                            PublishStepKey.Identity -> if (step.status == StepStatus.Active) {
                                Button(onClick = onOpenWallet) { Text(stringResource(R.string.publish_setup_set_up_wallet)) }
                            }
                            PublishStepKey.LightMode -> if (step.status == StepStatus.Active) {
                                Button(onClick = onSwitchToLightMode) { Text(stringResource(R.string.publish_setup_light_title)) }
                            }
                            PublishStepKey.Fund -> if (address != null) {
                                // The address in full, selectable: it's what the user
                                // copies into another wallet to send to.
                                DetailRow(stringResource(R.string.publish_setup_node_address), address, mono = true, singleLine = false)
                                DetailRow(
                                    stringResource(R.string.publish_setup_balance),
                                    xdai?.let(::formatXdai) ?: stringResource(
                                        if (balance.failed) R.string.publish_setup_balance_unavailable else R.string.publish_setup_balance_checking,
                                    ),
                                )
                                if (step.status != StepStatus.Done) {
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedButton(onClick = { copyNodeAddress(context, address) }) {
                                        Text(stringResource(R.string.common_copy_address))
                                    }
                                    // One wallet transaction instead (#115): needs light mode, for the node's price.
                                    if (light) {
                                        Button(onClick = onFundAndBuy) { Text(stringResource(R.string.publish_setup_fund_and_buy)) }
                                    }
                                }
                            }
                            PublishStepKey.Chequebook -> if (step.status == StepStatus.Done) {
                                OutlinedButton(onClick = onOpenChequebook) { Text(stringResource(R.string.publish_setup_deposit)) }
                            }
                            PublishStepKey.Stamp -> if (step.status == StepStatus.Active) {
                                Button(onClick = onBuyStamp) { Text(stringResource(R.string.publish_setup_stamp_title)) }
                                // Or have the wallet pay for it, in one transaction (#115).
                                OutlinedButton(onClick = onFundAndBuy) { Text(stringResource(R.string.publish_setup_pay_from_wallet)) }
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
    SectionCard(title = stringResource(R.string.publish_setup_step, number)) {
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
            Icons.Outlined.Circle, contentDescription = stringResource(R.string.publish_setup_status_pending), modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepStatus.Active -> Icon(
            Icons.Filled.PlayCircle, contentDescription = stringResource(R.string.publish_setup_status_active), modifier = modifier,
            tint = MaterialTheme.colorScheme.primary,
        )
        StepStatus.Waiting -> CircularProgressIndicator(
            strokeWidth = 2.dp, modifier = Modifier.padding(2.dp).size(20.dp),
        )
        StepStatus.Done -> Icon(
            Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.publish_setup_status_done), modifier = modifier,
            tint = Color(0xFF22C55E),
        )
    }
}

/** A balance read: the last good value, and whether reading has only failed so far. */
private data class BalanceRead(val wei: BigInteger? = null, val failed: Boolean = false)

internal fun copyNodeAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.publish_setup_clip_label), address))
    // Android 13+ confirms every copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, Strings.get(R.string.publish_setup_address_copied), Toast.LENGTH_SHORT).show()
    }
}

private const val TAG = "PublishSetup"
private const val BALANCE_POLL_MS = 15_000L
private const val GATEWAY_POLL_MS = 5_000L
private const val GATEWAY_TIMEOUT_MS = 5_000
