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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        "Connect to Swarm",
        "wants to connect to your Swarm node",
        "It can ask to publish data and files to Swarm with your postage stamps, to create and update " +
            "feeds signed with a publisher identity of its own, and to send and receive messages. Each of those asks first.",
        "Connect",
        null,
    )
    is SwarmAsk.Publish -> SwarmPromptCopy(
        "Publish to Swarm",
        "wants to publish to Swarm",
        "Publishing uses your postage stamps, and what's published can't be taken back: anyone with its " +
            "address can read it.",
        "Publish",
        "Always allow this site to publish without asking",
    )
    // Its own sheet (SwarmManifestSheet); this is only what it's called.
    is SwarmAsk.Manifest -> SwarmPromptCopy(
        "App permissions",
        "asks for Swarm permissions",
        "",
        "Allow all",
        null,
    )
    is SwarmAsk.Sign -> if (ask.kind == SwarmProvider.AutoApprove.Signing) {
        SwarmPromptCopy(
            "Publisher signing",
            "wants to use your publisher identity",
            "Publisher signing can create Single Owner Chunks and reveal the active publisher owner for this site.",
            "Allow",
            "Always allow this site to use this publisher identity without asking",
        )
    } else {
        SwarmPromptCopy(
            "Feed access",
            "wants to create and manage feeds",
            "Feeds provide stable URLs that this app can update over time. Uses your stamps and bandwidth.",
            "Allow",
            "Always allow this site to manage feeds without asking",
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
        "Messaging access",
        "wants to send and receive real-time messages",
        "Messaging discloses your node's identity key to this site. It's the same key every site with messaging " +
            "access sees, so those sites can tell they're talking to the same person. Sending uses your stamps; open subscriptions use " +
            "bandwidth while the page is loaded. A subscription can also read any PSS traffic your node decrypts for " +
            "the topic it joins, not only this site's own messages.",
        "Allow",
        null,
    )
    else -> SwarmPromptCopy(
        "Confirm message",
        if (ask.send == SwarmAsk.Message.Kind.Pss) "wants to send a private message (PSS)" else "wants to broadcast a message (GSOC)",
        "Sending this message uses your stamps and is visible to the Swarm network.",
        "Send",
        "Always allow this site to send messages without asking",
    )
}

/**
 * A messaging topic as the sheet shows it: the page's string, with every
 * control and format character (bidi overrides and isolates, zero-width
 * joiners, line separators) written out as `<U+XXXX>`, so it can't
 * reorder or hide part of the row. The topic itself goes to the node as is.
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
    SwarmAsk.Publish.Kind.Files -> if (ask.paths.size == 1) "1 file" else "${ask.paths.size} files"
    SwarmAsk.Publish.Kind.Chunk -> "Swarm chunk"
    SwarmAsk.Publish.Kind.Data -> ask.contentType ?: "Data"
}

/** The first few paths of a files publish, and how many more: desktop's preview. */
internal fun swarmPathsPreview(paths: List<String>): String =
    paths.take(3).joinToString(", ") + if (paths.size > 3) " …and ${paths.size - 3} more" else ""

/** What a signing sheet's request row says: the method's own detail, or the feed it's on. */
internal fun swarmSignRequest(ask: SwarmAsk.Sign): String = ask.detail ?: ask.feedName ?: "Feed operation"

/** The note on a signing sheet with no wallet on the device yet. */
internal fun swarmNeedsWalletNote(copy: SwarmPromptCopy): String =
    "You don't have a wallet yet: ${copy.approve} opens wallet setup first, and the site's identity comes from it."

/** Which identity signs: the one a feed was created with, the site's active one, or the new one its first grant will make. */
internal fun swarmSignIdentity(ask: SwarmAsk.Sign): String =
    ask.identity?.let { "${it.label} (${it.kind})" } ?: "A new app-scoped identity for this site"

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
                error = walletErrorMessage(e, "unlock the wallet", phraseBackedUp = true)
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
                        DetailRow("What", swarmPublishWhat(ask))
                        DetailRow("Size", formatStampBytes(ask.size))
                        ask.name?.let { DetailRow("Name", it) }
                        if (ask.kind == SwarmAsk.Publish.Kind.Files) DetailRow("Files", swarmPathsPreview(ask.paths), mono = true)
                    }
                    is SwarmAsk.Sign -> {
                        if (ask.kind == SwarmProvider.AutoApprove.Signing) {
                            DetailRow("Request", swarmSignRequest(ask), mono = ask.detail?.startsWith("Single") == true)
                        } else {
                            DetailRow("Feed name", swarmSignRequest(ask))
                        }
                        DetailRow("Signs as", swarmSignIdentity(ask))
                    }
                    is SwarmAsk.Message -> {
                        ask.topic?.let { DetailRow("Topic", swarmShownTopic(it)) }
                        ask.address?.let { DetailRow("Room address", it) }
                        if (ask.send != null) DetailRow("Size", formatStampBytes(ask.size.toLong()))
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
                        "Your wallet is locked: ${copy.approve} asks for your screen lock first.",
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
                            .toggleable(value = always, enabled = armed && !busy, role = Role.Switch, onValueChange = { always = it })
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = ::reject,
                    enabled = armed && !busy,
                    modifier = Modifier.weight(1f).testTag("swarm-reject"),
                ) { Text("Reject") }
                Button(
                    onClick = ::approve,
                    enabled = armed && !busy,
                    modifier = Modifier.weight(1f).protectedPress(tap).testTag("swarm-approve"),
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
