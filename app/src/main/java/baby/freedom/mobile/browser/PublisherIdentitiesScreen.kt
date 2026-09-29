package baby.freedom.mobile.browser

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.PublisherIdentity
import baby.freedom.mobile.wallet.PublisherIdentityStore
import baby.freedom.mobile.wallet.PublisherKeys
import baby.freedom.mobile.wallet.SitePublisher
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PUBLISHER_IDENTITIES_TITLE = "Publisher identities"

/** The Wallet page row's line under "Publisher identities". */
internal fun publisherIdentitiesSummary(siteCount: Int): String = when (siteCount) {
    0 -> "The keys sites sign their Swarm feeds with"
    1 -> "1 site"
    else -> "$siteCount sites"
}

/** A site row's line: the identity it publishes as, and how many it has. */
internal fun publisherSiteSummary(site: SitePublisher): String {
    val count = site.identities.size
    return "Publishes as ${site.active.label} · ${if (count == 1) "1 identity" else "$count identities"}"
}

/** What an identity's row says under its name: its kind and key path. */
internal fun publisherIdentityDetail(identity: PublisherIdentity): String =
    "${identity.kind} · ${identity.derivationPath}"

/**
 * Publisher identities (#119), from Wallet: which key each site signs its
 * Swarm feeds with. The list is readable while the wallet is locked;
 * owner addresses, creating and switching need it unlocked, as on
 * desktop. [currentSite] is the provider origin key of the page the
 * user came from (never a private tab's), offered for setup when it has
 * no identity yet.
 */
@Composable
internal fun PublisherIdentitiesPage(currentSite: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val store = remember(context) { PublisherIdentityStore.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val state by vault.state.collectAsState()
    val unlocked = state is Vault.State.Unlocked
    val phraseBackedUp = when (val s = state) {
        is Vault.State.Locked -> s.info.backedUp
        is Vault.State.Unlocked -> s.info.backedUp
        else -> true
    }
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<SitePublisher>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { sites = withContext(Dispatchers.IO) { store.sites() } }

    fun run(action: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = when (e) {
                    is IllegalArgumentException -> e.message
                    is VaultLockedException -> "The wallet locked. Unlock it and try again."
                    else -> walletErrorMessage(e, action, phraseBackedUp)
                }
            } finally {
                busy = false
            }
        }
    }

    val unlock = { run("unlock the wallet") { vault.unlock(auth) } }

    val openOrigin = open
    val openSite = sites?.firstOrNull { it.origin == openOrigin }
    if (openSite != null) {
        SitePublisherPage(
            site = openSite,
            vault = vault,
            unlocked = unlocked,
            busy = busy,
            error = error,
            onUnlock = unlock,
            onActivate = { id ->
                run("switch identity") {
                    withContext(Dispatchers.IO) { store.activate(openSite.origin, id) }
                    sites = withContext(Dispatchers.IO) { store.sites() }
                }
            },
            onCreate = { label, done ->
                run("create the identity") {
                    withContext(Dispatchers.IO) { store.createAppScoped(openSite.origin, label) }
                    sites = withContext(Dispatchers.IO) { store.sites() }
                    done()
                }
            },
            onBack = {
                open = null
                error = null
            },
        )
        return
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = PUBLISHER_IDENTITIES_TITLE, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("intro") {
                SectionCard(title = "Publishing on Swarm") {
                    Text(
                        "A site signs its Swarm feeds with a key from your wallet. Each site gets an " +
                            "app-scoped identity of its own, so its feeds can’t be tied to your other " +
                            "sites or accounts. You can give a site more identities and switch between " +
                            "them, or have it publish as your Ant wallet identity — your Swarm node’s " +
                            "account, which every site using it shares.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sites can’t publish feeds from this browser yet; when they can, they’ll use " +
                            "the identity chosen here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!unlocked) item("locked") {
                SectionCard(title = "Wallet locked") {
                    Text(
                        "Unlock the wallet to see each identity’s owner address, create identities " +
                            "or switch between them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = unlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Unlocking…" else "Unlock")
                    }
                }
            }
            val known = sites
            if (currentSite != null && known != null && known.none { it.origin == currentSite }) item("current") {
                SectionCard(title = "This site") {
                    Text(permissionOriginDisplay(currentSite), fontWeight = FontWeight.Medium)
                    Text(
                        "Has no publisher identity yet. Setting one up gives it a new app-scoped identity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            run("set up the site") {
                                withContext(Dispatchers.IO) { store.ensureSite(currentSite) }
                                sites = withContext(Dispatchers.IO) { store.sites() }
                                open = currentSite
                            }
                        },
                        enabled = unlocked && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Set up a publisher identity") }
                }
            }
            error?.let { message -> item("error") { PublisherErrorText(message) } }
            if (known != null) item("sites") {
                SectionCard(title = "Sites") {
                    if (known.isEmpty()) {
                        Text(
                            "No site has a publisher identity yet. Open a site, then come back here to " +
                                "set one up for it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    known.forEach { site ->
                        PageRow(
                            title = permissionOriginDisplay(site.origin),
                            subtitle = publisherSiteSummary(site),
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.Badge,
                            enabled = unlocked && !busy,
                            onClick = {
                                error = null
                                open = site.origin
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One site's identities: which one signs, switch, or create another. */
@Composable
private fun SitePublisherPage(
    site: SitePublisher,
    vault: Vault,
    unlocked: Boolean,
    busy: Boolean,
    error: String?,
    onUnlock: () -> Unit,
    onActivate: (String) -> Unit,
    onCreate: (label: String, done: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val choices = site.choices
    var owners by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(choices, unlocked) {
        owners = if (!unlocked) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) {
                try {
                    PublisherKeys.owners(vault, choices)
                } catch (_: VaultLockedException) {
                    emptyMap()
                }
            }
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = PUBLISHER_IDENTITIES_TITLE, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("site") {
                SectionCard(title = "Site") {
                    Text(permissionOriginDisplay(site.origin), fontWeight = FontWeight.Medium)
                    Text(
                        "Its Swarm feeds are signed by the identity chosen below. Feeds belong to the key " +
                            "that signs them, so after a switch the site publishes under another owner " +
                            "address; what it published before stays with the old one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!unlocked) item("locked") {
                SectionCard(title = "Wallet locked") {
                    Text(
                        "Unlock the wallet to see owner addresses and switch identities.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Unlocking…" else "Unlock")
                    }
                }
            }
            item("choices") {
                SectionCard(title = "Publishes as") {
                    choices.forEach { identity ->
                        IdentityChoice(
                            identity = identity,
                            selected = identity.id == site.activeId,
                            owner = owners[identity.id],
                            unlocked = unlocked,
                            enabled = unlocked && !busy,
                            onSelect = { onActivate(identity.id) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { creating = true },
                        enabled = unlocked && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create new identity") }
                }
            }
            error?.let { message -> item("error") { PublisherErrorText(message) } }
        }
    }

    if (creating) {
        CreateIdentityDialog(
            busy = busy,
            error = error,
            onCreate = { label -> onCreate(label) { creating = false } },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun IdentityChoice(
    identity: PublisherIdentity,
    selected: Boolean,
    owner: String?,
    unlocked: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val alpha = if (enabled || selected) 1f else 0.6f
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                identity.label + if (selected) " (active)" else "",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                publisherIdentityDetail(identity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                owner ?: if (unlocked) "Working out the address…" else "Unlock to see its address",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (owner != null) FontFamily.Monospace else null),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Names a new app-scoped identity; Create makes it the site's active one. */
@Composable
private fun CreateIdentityDialog(busy: Boolean, error: String?, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    // Not a secret, but no reason to keep it past the dialog either.
    var label by remember { mutableStateOf("") }
    val problem = if (label.isBlank()) null else PublisherIdentity.checkLabel(label).exceptionOrNull()?.message
    val submit = {
        if (!busy && label.isNotBlank() && problem == null) onCreate(label)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New publisher identity") },
        text = {
            Column {
                Text(
                    "A new app-scoped key for this site, with an owner address of its own. It becomes " +
                        "the identity the site publishes as.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { PublisherErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !busy && label.isNotBlank() && problem == null) {
                Text(if (busy) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PublisherErrorText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
