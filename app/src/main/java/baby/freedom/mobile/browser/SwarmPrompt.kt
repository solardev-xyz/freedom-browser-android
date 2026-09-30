package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a `window.swarm` sheet says: its title, what the site wants, a
 * warning on what it means, the approve button, and the "always allow"
 * switch's label (null: the sheet has none).
 */
internal data class SwarmPromptCopy(
    val title: String,
    val request: String,
    val warning: String,
    val approve: String,
    val always: String?,
)

/** Desktop's wording, per tier (`swarm-connect.js`). */
internal fun swarmPromptCopy(ask: SwarmAsk): SwarmPromptCopy = when (ask) {
    is SwarmAsk.Connect -> SwarmPromptCopy(
        Strings.get(R.string.swarm_connect_title),
        Strings.get(R.string.swarm_connect_request),
        Strings.get(R.string.swarm_connect_warning),
        Strings.get(R.string.swarm_connect_approve),
        null,
    )
    is SwarmAsk.Publish -> SwarmPromptCopy(
        Strings.get(R.string.swarm_publish_title),
        Strings.get(R.string.swarm_publish_request),
        Strings.get(R.string.swarm_publish_warning),
        Strings.get(R.string.swarm_publish_approve),
        Strings.get(R.string.swarm_publish_always),
    )
    // Its own sheet (SwarmManifestSheet); this is only what it's called.
    is SwarmAsk.Manifest -> SwarmPromptCopy(
        Strings.get(R.string.swarm_manifest_title),
        Strings.get(R.string.swarm_manifest_request),
        "",
        Strings.get(R.string.swarm_allow_all),
        null,
    )
    is SwarmAsk.Sign -> if (ask.kind == SwarmProvider.AutoApprove.Signing) {
        SwarmPromptCopy(
            Strings.get(R.string.swarm_signing_title),
            Strings.get(R.string.swarm_signing_request),
            Strings.get(R.string.swarm_signing_warning),
            Strings.get(R.string.common_allow),
            Strings.get(R.string.swarm_signing_always),
        )
    } else {
        SwarmPromptCopy(
            Strings.get(R.string.swarm_feeds_title),
            Strings.get(R.string.swarm_feeds_request),
            Strings.get(R.string.swarm_feeds_warning),
            Strings.get(R.string.common_allow),
            Strings.get(R.string.swarm_feeds_always),
        )
    }
    is SwarmAsk.Message -> swarmMessagingCopy(ask)
}

/**
 * A messaging sheet (#121), desktop's `showSwarmMessagingApproval`: the
 * grant the first time — for the tier as a whole, whatever call asked —
 * and after that one per send, with messaging's "always allow".
 */
private fun swarmMessagingCopy(ask: SwarmAsk.Message): SwarmPromptCopy = when {
    ask.grant -> SwarmPromptCopy(
        Strings.get(R.string.swarm_messaging_title),
        Strings.get(R.string.swarm_messaging_request),
        Strings.get(R.string.swarm_messaging_warning),
        Strings.get(R.string.common_allow),
        null,
    )
    else -> SwarmPromptCopy(
        Strings.get(R.string.swarm_message_title),
        Strings.get(
            if (ask.send == SwarmAsk.Message.Kind.Pss) R.string.swarm_message_request_pss else R.string.swarm_message_request_gsoc,
        ),
        Strings.get(R.string.swarm_message_warning),
        Strings.get(R.string.swarm_message_approve),
        Strings.get(R.string.swarm_message_always),
    )
}

/**
 * A page's string as the sheet shows it — a messaging topic, a feed
 * name, a publish's name, content type or paths — with every control and
 * format character (bidi overrides and isolates, zero-width joiners,
 * line breaks and separators) written out as `<U+XXXX>`, so it can't
 * reorder or hide part of its row, or break it into rows of its own that
 * pass for the sheet's. The string itself goes to the node as is.
 */
internal fun swarmShownTopic(topic: String): String = buildString {
    var i = 0
    while (i < topic.length) {
        val cp = topic.codePointAt(i)
        val type = Character.getType(cp)
        val hidden = Character.isISOControl(cp) || type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        if (hidden) append("<U+%04X>".format(cp)) else appendCodePoint(cp)
        i += Character.charCount(cp)
    }
}

/** "3 files", "text/html", "Swarm chunk": what a publish is. */
internal fun swarmPublishWhat(ask: SwarmAsk.Publish): String = when (ask.kind) {
    SwarmAsk.Publish.Kind.Files -> Strings.plural(R.plurals.swarm_publish_files, ask.paths.size, ask.paths.size)
    SwarmAsk.Publish.Kind.Chunk -> Strings.get(R.string.swarm_publish_chunk)
    SwarmAsk.Publish.Kind.Data -> ask.contentType?.let(::swarmShownTopic) ?: Strings.get(R.string.swarm_publish_data)
}

/** The first few paths of a files publish, and how many more: desktop's preview. */
internal fun swarmPathsPreview(paths: List<String>): String {
    val shown = paths.take(3).joinToString(", ", transform = ::swarmShownTopic)
    if (paths.size <= 3) return shown
    val more = paths.size - 3
    return Strings.plural(R.plurals.swarm_paths_preview_more, more, shown, more)
}

/** What a signing sheet's request row says: the method's own detail, or the feed it's on. */
internal fun swarmSignRequest(ask: SwarmAsk.Sign): String =
    ask.detail ?: ask.feedName?.let(::swarmShownTopic) ?: Strings.get(R.string.swarm_sign_request_feed_operation)

/** The note on a signing sheet with no wallet on the device yet. */
internal fun swarmNeedsWalletNote(copy: SwarmPromptCopy): String =
    Strings.get(R.string.swarm_needs_wallet_note, copy.approve)

/** Which identity signs: the one a feed was created with, the site's active one, or the new one its first grant will make. */
internal fun swarmSignIdentity(ask: SwarmAsk.Sign): String =
    ask.identity?.let { Strings.get(R.string.swarm_sign_identity, it.label, it.kind) }
        ?: Strings.get(R.string.swarm_sign_identity_new)

/**
 * A `window.swarm` approval sheet (#120), one per tier as on desktop:
 * connect, publish (what, how big, its name or paths), feed access /
 * publisher signing (the feed or request, and the identity that signs),
 * and messaging (#121: the tier, or one message — its topic and size).
 * It always starts with the site asking — in full, wrapped rather than
 * ellipsised, since the tail of a host is what a spoof hides — then the
 * request, what it means, the "always allow" switch where the tier has
 * one (off every time the sheet comes up), and Reject / the action.
 *
 * Signing needs the wallet open: the action asks for the screen lock
 * first when it isn't, and with no wallet on the device at all it opens
 * wallet setup once approved. Like the other approval sheets, the buttons, a
 * swipe down, a tap outside and Back all ignore input until it has been
 * on screen, untouched, for [PromptTapGuard.PROTECTION_MS] — a signature
 * [PromptTapGuard.SPEND_PROTECTION_MS] — counted from its first drawn
 * frame and restarted by every touch anywhere in its window until then
 * (#240). Everything but the action rejects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwarmPromptSheet(request: SwarmPromptRequest) {
    val ask = request.ask
    if (ask is SwarmAsk.Manifest) {
        SwarmManifestSheet(request, ask)
        return
    }
    val copy = swarmPromptCopy(ask)
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val vaultState by vault.state.collectAsState()
    // A signature can't be taken back: it arms later, like the wallet's Sign (#240).
    val tap = rememberArmedTapGuard(
        request,
        if (ask is SwarmAsk.Sign) PromptTapGuard.SPEND_PROTECTION_MS else PromptTapGuard.PROTECTION_MS,
    )
    val guard = tap.guard
    val armed = tap.armed
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var always by remember(request) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (guard.accepts() && !busy) },
    )
    // No wallet yet: approving opens wallet setup (SwarmProviders.askOnTab), so there's nothing to unlock here.
    val needsWallet = ask is SwarmAsk.Sign && ask.needsWallet && vaultState is Vault.State.Empty
    val needsUnlock = ask is SwarmAsk.Sign && !needsWallet && vaultState !is Vault.State.Unlocked

    fun reject() {
        if (guard.accepts() && !busy) request.respond(SwarmProvider.Answer.REJECTED)
    }

    fun approve() {
        if (!guard.accepts() || busy) return
        val answer = SwarmProvider.Answer(allowed = true, always = copy.always != null && always)
        if (ask !is SwarmAsk.Sign || needsWallet) {
            request.respond(answer)
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (!vault.unlockedNow()) vault.unlock(BiometricVaultAuthenticator(context))
                request.respond(answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = walletErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp = true)
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::reject,
        sheetState = sheetState,
        modifier = Modifier.testTag("swarm-approval"),
    ) {
        RestartsTapGuardInWindow(guard)
        Column(
            modifier = Modifier
                .restartsTapGuard(guard)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (ask) {
                        is SwarmAsk.Connect, is SwarmAsk.Manifest -> Icons.Filled.Hub
                        is SwarmAsk.Publish -> Icons.Filled.CloudUpload
                        is SwarmAsk.Sign -> if (ask.kind == SwarmProvider.AutoApprove.Signing) Icons.Filled.Draw else Icons.Filled.DynamicFeed
                        is SwarmAsk.Message -> Icons.Filled.Forum
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(copy.title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("swarm-origin"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            permissionOriginDisplay(ask.origin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(copy.request, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (ask) {
                    is SwarmAsk.Connect, is SwarmAsk.Manifest -> Unit
                    is SwarmAsk.Publish -> {
                        DetailRow(stringResource(R.string.swarm_detail_what), swarmPublishWhat(ask))
                        DetailRow(stringResource(R.string.swarm_detail_size), formatStampBytes(ask.size))
                        ask.name?.let { DetailRow(stringResource(R.string.swarm_detail_name), swarmShownTopic(it)) }
                        if (ask.kind == SwarmAsk.Publish.Kind.Files) DetailRow(stringResource(R.string.swarm_detail_files), swarmPathsPreview(ask.paths), mono = true)
                    }
                    is SwarmAsk.Sign -> {
                        if (ask.kind == SwarmProvider.AutoApprove.Signing) {
                            DetailRow(
                                stringResource(R.string.swarm_detail_request),
                                swarmSignRequest(ask),
                                mono = ask.method == "swarm_writeSingleOwnerChunk",
                            )
                        } else {
                            DetailRow(stringResource(R.string.swarm_detail_feed_name), swarmSignRequest(ask))
                        }
                        DetailRow(stringResource(R.string.swarm_detail_signs_as), swarmSignIdentity(ask))
                    }
                    is SwarmAsk.Message -> {
                        ask.topic?.let { DetailRow(stringResource(R.string.swarm_detail_topic), swarmShownTopic(it)) }
                        ask.address?.let { DetailRow(stringResource(R.string.swarm_detail_room_address), it) }
                        if (ask.send != null) DetailRow(stringResource(R.string.swarm_detail_size), formatStampBytes(ask.size.toLong()))
                    }
                }
                Text(
                    copy.warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (needsWallet) {
                    Text(
                        swarmNeedsWalletNote(copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("swarm-wallet-note"),
                    )
                }
                if (needsUnlock) {
                    Text(
                        stringResource(R.string.swarm_unlock_note, copy.approve),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("swarm-unlock-note"),
                    )
                }
                copy.always?.let { label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .protectedToggle(tap, value = always, role = Role.Switch, enabled = !busy) { always = it }
                            .testTag("swarm-always-approve"),
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = always, onCheckedChange = null, enabled = armed && !busy)
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            ObscuredTapNotice(tap)
            SheetButtonRow {
                OutlinedButton(
                    onClick = ::reject,
                    enabled = armed && !busy,
                    modifier = Modifier.testTag("swarm-reject"),
                ) { Text(stringResource(R.string.common_reject)) }
                Button(
                    onClick = ::approve,
                    enabled = armed && !busy,
                    modifier = Modifier.protectedPress(tap).testTag("swarm-approve"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(copy.approve)
                    }
                }
            }
        }
    }
}

/** A label over its value, the value wrapping in full. */
@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}
