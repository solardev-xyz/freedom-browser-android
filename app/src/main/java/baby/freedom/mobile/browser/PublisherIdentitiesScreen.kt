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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
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

internal val PUBLISHER_IDENTITIES_TITLE: String get() = Strings.get(R.string.publish_identities_title)

/** The Wallet page row's line under "Publisher identities". */
internal fun publisherIdentitiesSummary(siteCount: Int): String = when (siteCount) {
    0 -> Strings.get(R.string.publish_identities_summary_none)
    else -> Strings.plural(R.plurals.publish_identities_summary_sites, siteCount, siteCount)
}

/** A site row's line: the identity it publishes as, and how many it has. */
internal fun publisherSiteSummary(site: SitePublisher): String {
    val count = site.identities.size
    return Strings.plural(R.plurals.publish_identities_site_summary, count, site.active.label, count)
}

/**
 * What an identity's row says under its name: its kind ("App-scoped",
 * "Ant wallet"). The key path is an expert's detail, so not on the row (#425, W46).
 */
internal fun publisherIdentityDetail(identity: PublisherIdentity): String = identity.kind

/**
 * Whether a site row opens: always but mid-action. Locked, the site's page
 * is still readable — which identity it publishes as, and its others —
 * only switching and creating wait for the unlock (#425, W46).
 */
internal fun publisherSiteRowEnabled(busy: Boolean): Boolean = !busy

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
        is Vault.State.Locked -> s.info.phraseKnown
        is Vault.State.Unlocked -> s.info.phraseKnown
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
                    is VaultLockedException -> Strings.get(R.string.publish_identities_error_locked)
                    else -> walletErrorMessage(e, action, phraseBackedUp)
                }
            } finally {
                busy = false
            }
        }
    }

    val unlock = { run(Strings.get(R.string.publish_identities_action_unlock)) { vault.unlock(auth) } }

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
                run(Strings.get(R.string.publish_identities_action_switch)) {
                    withContext(Dispatchers.IO) { store.activate(openSite.origin, id) }
                    sites = withContext(Dispatchers.IO) { store.sites() }
                }
            },
            onCreate = { label, done ->
                run(Strings.get(R.string.publish_identities_action_create)) {
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
            // One line (#425, W46): the feature works (window.swarm feeds sign with these keys), so the entry stays.
            item("intro") {
                Text(
                    stringResource(R.string.publish_identities_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            // Only a wallet that exists can be locked; with none yet there's nothing to unlock.
            if (state is Vault.State.Locked) item("locked") { LockedNote(stringResource(R.string.publish_identities_locked_body), busy, unlock) }
            val known = sites
            if (currentSite != null && known != null && known.none { it.origin == currentSite }) item("current") {
                SectionCard(title = stringResource(R.string.publish_identities_this_site)) {
                    Text(permissionOriginDisplay(currentSite), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.publish_identities_this_site_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            run(Strings.get(R.string.publish_identities_action_set_up_site)) {
                                withContext(Dispatchers.IO) { store.ensureSite(currentSite) }
                                sites = withContext(Dispatchers.IO) { store.sites() }
                                open = currentSite
                            }
                        },
                        enabled = unlocked && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.publish_identities_set_up)) }
                }
            }
            error?.let { message -> item("error") { PublisherErrorText(message) } }
            if (known != null) item("sites") {
                SectionCard(title = stringResource(R.string.publish_identities_sites)) {
                    if (known.isEmpty()) {
                        Text(
                            stringResource(R.string.publish_identities_sites_empty),
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
                            enabled = publisherSiteRowEnabled(busy),
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
                SectionCard(title = stringResource(R.string.publish_identities_site)) {
                    Text(permissionOriginDisplay(site.origin), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.publish_identities_site_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!unlocked) item("locked") { LockedNote(stringResource(R.string.publish_identities_site_locked_body), busy, onUnlock) }
            item("choices") {
                SectionCard(title = stringResource(R.string.publish_identities_publishes_as)) {
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
                    ) { Text(stringResource(R.string.publish_identities_create_new)) }
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
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (selected) stringResource(R.string.publish_identities_label_active, identity.label) else identity.label,
                fontWeight = FontWeight.Medium,
                // Full contrast while locked too: the row is read, only the choice waits (#425, W46).
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                publisherIdentityDetail(identity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                owner ?: stringResource(if (unlocked) R.string.publish_identities_owner_working else R.string.publish_identities_owner_locked),
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
        title = { Text(stringResource(R.string.publish_identities_new_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.publish_identities_new_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.publish_identities_name)) },
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
                Text(stringResource(if (busy) R.string.publish_identities_creating else R.string.publish_identities_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The wallet is locked: a line saying what waits for it, and a quiet way to unlock — not the page's main action. */
@Composable
private fun LockedNote(text: String, busy: Boolean, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onUnlock, enabled = !busy) {
            Text(stringResource(if (busy) R.string.publish_identities_unlocking else R.string.publish_identities_unlock))
        }
    }
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
