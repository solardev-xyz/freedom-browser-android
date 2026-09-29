package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.Mnemonic
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthCancelledException
import baby.freedom.mobile.wallet.VaultAuthFailedException
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultLockedException
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.TooManyAccountsException
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.wallet.VaultProtection
import baby.freedom.mobile.wallet.VaultUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val WALLET_ROW_KEY = "wallet"
internal const val WALLET_TITLE = "Wallet"
internal const val BACKUP_REMINDER = "Recovery phrase not backed up yet"
private const val NO_SCREEN_LOCK_LINE = "No screen lock protects this wallet"

/** The Wallet row's one-line state, for Settings and its search. */
internal fun walletSummary(state: Vault.State): String = when (state) {
    Vault.State.Empty -> "Not set up · create or import a recovery phrase"
    is Vault.State.Locked -> "Locked"
    is Vault.State.Unlocked -> "Unlocked"
    Vault.State.Unreadable -> "Can’t be read"
}

/**
 * The line under the Wallet row that has to stay in view: the backup
 * reminder until the phrase has been seen (#78), else the no-screen-lock
 * warning while that holds.
 */
internal fun walletAttentionLine(state: Vault.State): String? {
    val info = when (state) {
        is Vault.State.Locked -> state.info
        is Vault.State.Unlocked -> state.info
        else -> return null
    }
    return when {
        !info.backedUp -> BACKUP_REMINDER
        info.protection == VaultProtection.DEVICE_ONLY -> NO_SCREEN_LOCK_LINE
        else -> null
    }
}

/** Settings' Wallet section rows, for settings search. */
internal fun walletSettingsRows(state: Vault.State) = listOf(
    settingsRow(
        WALLET_ROW_KEY, WALLET_TITLE, walletSummary(state), walletAttentionLine(state),
        "Recovery phrase", "Identity", "Back up",
    ),
)

/** What the import field says under the phrase as it's typed. */
internal sealed class ImportHint(val text: String) {
    class Neutral(text: String) : ImportHint(text)
    class Problem(text: String) : ImportHint(text)
    class Valid(text: String) : ImportHint(text)
}

/**
 * The import field's live verdict. A word that isn't on the list is
 * called out as soon as it's finished (followed by a space), not while
 * it's still being typed; the count and the checksum only once there are
 * enough words to judge.
 */
internal fun importHint(phrase: String): ImportHint {
    val count = Mnemonic.wordCount(phrase)
    if (count == 0) return ImportHint.Neutral("Enter 12, 15, 18, 21 or 24 words, separated by spaces")
    val words = Mnemonic.words(phrase)
    val finished = if (phrase.last().isWhitespace()) words else words.dropLast(1)
    val unknown = finished.indexOfFirst { it !in bip39Words }
    if (unknown >= 0) {
        return ImportHint.Problem("Word ${unknown + 1} (“${finished[unknown]}”) isn’t a recovery-phrase word")
    }
    return when (val problem = Mnemonic.problemWith(phrase)) {
        null -> ImportHint.Valid("Valid $count-word recovery phrase")
        is Mnemonic.Problem.WordCount -> ImportHint.Neutral(if (count == 1) "1 word" else "$count words")
        is Mnemonic.Problem.UnknownWord ->
            ImportHint.Problem("Word ${problem.position} isn’t a recovery-phrase word")
        // 12 words that don't check out may be the first half of 24.
        Mnemonic.Problem.Checksum -> if (count < Mnemonic.CREATE_WORD_COUNT) {
            ImportHint.Neutral("$count words · not a valid recovery phrase (yet) — check spelling and order")
        } else {
            ImportHint.Problem("These $count words aren’t a valid recovery phrase — check their spelling and order")
        }
    }
}

/**
 * Whether [clip] holds the recovery phrase [words], alone or inside other
 * text (a note it was copied from), so a successful import can take it
 * off the clipboard without wiping something unrelated the user copied.
 */
internal fun clipHoldsPhrase(clip: CharSequence?, words: List<String>): Boolean {
    if (clip.isNullOrBlank() || words.isEmpty()) return false
    // Normalized the way Mnemonic.words normalizes, so a phrase pasted in
    // compatibility letters (fullwidth, say) or carrying a soft hyphen
    // inside a word still matches its words.
    val tokens = Mnemonic.normalized(clip)
        .split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }
    return (0..tokens.size - words.size).any { start -> tokens.subList(start, start + words.size) == words }
}

/**
 * How many characters an edit from [before] to [after] inserted. Unlike
 * the net change in length it counts a paste that replaced a selection (a
 * phrase pasted over a mistyped one of about the same length): when text
 * was selected and the edit kept everything outside it, the whole
 * replacement counts, even where it happens to match what it replaced;
 * otherwise it's what's left of [after] once the text the two share at the
 * start and at the end is taken off.
 */
internal fun insertedLength(before: TextFieldValue, after: TextFieldValue): Int {
    val old = before.text
    val new = after.text
    if (old == new) return 0
    val sel = before.selection
    if (!sel.collapsed) {
        val head = old.substring(0, sel.min)
        val tail = old.substring(sel.max)
        if (new.length >= head.length + tail.length && new.startsWith(head) && new.endsWith(tail)) {
            return new.length - head.length - tail.length
        }
    }
    val prefix = old.commonPrefixWith(new).length
    val suffix = old.substring(prefix).commonSuffixWith(new.substring(prefix)).length
    return new.length - prefix - suffix
}

/**
 * Takes an imported recovery phrase off the clipboard if it's still there
 * (#75). Only called when the user pasted into the import field, so the
 * clipboard isn't read (and Android's "pasted from your clipboard" notice
 * doesn't show) for a phrase that was typed.
 */
private fun clearPhraseFromClipboard(context: Context, words: List<String>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching {
        val clip = clipboard.primaryClip ?: return
        val holds = (0 until clip.itemCount).any { clipHoldsPhrase(clip.getItemAt(it).coerceToText(context), words) }
        if (holds) clipboard.clearPrimaryClip()
    }
}

private val bip39Words: Set<String> by lazy { baby.freedom.mobile.wallet.Bip39English.words.toHashSet() }

/**
 * What to tell the user about a failed create, import or unlock; null
 * when they cancelled. [phraseBackedUp] is whether the user can have the
 * recovery phrase at all: false for a wallet created here whose phrase
 * has never been shown (#78), so a dead wallet isn't met with "import
 * your recovery phrase" they never had.
 */
internal fun walletErrorMessage(e: Throwable, action: String, phraseBackedUp: Boolean): String? = when (e) {
    is VaultAuthCancelledException -> null
    is CancellationException -> null
    is VaultAuthFailedException -> "Couldn’t $action: ${e.message}"
    is VaultLockedException -> "Couldn’t $action: the wallet locked. Unlock it and try again."
    is TooManyAccountsException -> "Couldn’t $action: ${e.message}."
    is VaultKeyLostException -> "Android has erased this wallet’s key. That happens when the screen lock is removed. " +
        lostWalletAdvice(phraseBackedUp)
    is VaultUnreadableException -> "This wallet can’t be read. " + lostWalletAdvice(phraseBackedUp)
    else -> "Couldn’t $action (${e.javaClass.simpleName})"
}

private fun lostWalletAdvice(phraseBackedUp: Boolean) = if (phraseBackedUp) {
    "Remove the wallet and import your recovery phrase to get it back."
} else {
    "Its recovery phrase was never shown, so it can’t be restored: remove it and set up a new wallet."
}

/**
 * The wallet page (#75, #76), from Settings → Wallet, or opened by a
 * feature that needs an identity ([Vault.requireUnlocked], carried in
 * as [request]). With no wallet it offers Create (one tap, then the
 * screen-lock prompt: 24 new words, no write-down quiz) and Import (12
 * to 24 words, checksum checked); with one, Unlock or Lock, how it's
 * protected, the backup reminder, and Remove wallet.
 *
 * The phrase is only ever on screen on the import page, which is
 * `FLAG_SECURE` ([SecureWindow]) and keeps the keyboard from learning
 * what's typed. The typed phrase lives in plain `remember` state, never
 * `rememberSaveable`, so it can't end up in the saved-instance-state
 * bundle.
 */
@Composable
fun WalletScreen(
    request: Vault.SetupRequest?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val state by vault.state.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    ReleaseCoveredFocus()

    // Accounts and balances (#104). Addresses are public, so both show
    // while the wallet is locked; only adding an account needs it open.
    val walletAccounts = remember(context) { WalletAccounts.get(context) }
    val accountList by walletAccounts.accounts.collectAsState()
    val allBalances by walletAccounts.balances.byAddress.collectAsState()
    val chainStore = remember(context) { ChainStore.get(context) }
    val allChains by chainStore.chains.collectAsState(initial = null)
    val walletChains = allChains?.filter { it.id in TokenRegistry.WALLET_CHAIN_IDS }
    val activeAddress = accountList?.active?.address
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val refreshGeneration = remember { intArrayOf(0) }
    // Re-read on opening, on switching account and on Refresh; a newer
    // read cancels the one before (the effect restarts) and owns the spinner.
    LaunchedEffect(activeAddress, walletChains, refreshTick) {
        val address = activeAddress ?: return@LaunchedEffect
        val chains = walletChains ?: return@LaunchedEffect
        val mine = ++refreshGeneration[0]
        refreshing = true
        try {
            walletAccounts.balances.refresh(address, chains.flatMap { TokenRegistry.tokens(it) })
        } finally {
            if (refreshGeneration[0] == mine) refreshing = false
        }
    }

    // Re-read on every resume: the user may come back from setting a screen lock.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val deviceSecure = remember(lifecycleState) { vault.deviceSecure() }

    // Whether the user can have the phrase: false for a created wallet
    // whose phrase was never shown (#78), which the copy must not send
    // them to re-import.
    val phraseBackedUp = when (val s = state) {
        is Vault.State.Locked -> s.info.backedUp
        is Vault.State.Unlocked -> s.info.backedUp
        else -> true
    }

    // A feature asked for the wallet: hand it back as soon as it's open.
    LaunchedEffect(request, state) {
        // unlockedNow() re-checks the auto-lock deadline (a timer delayed
        // by deep sleep); if it's passed, the vault locks and state follows.
        if (request != null && state is Vault.State.Unlocked && vault.unlockedNow()) request.finish(true)
    }

    fun run(action: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                error = walletErrorMessage(e, action, phraseBackedUp)
                if (e is CancellationException) throw e
            } finally {
                busy = false
            }
        }
    }

    if (importing) {
        ImportPhrasePage(
            busy = busy,
            error = error,
            deviceSecure = deviceSecure,
            onImport = { mnemonic, clear ->
                run("import the wallet") {
                    vault.create(mnemonic, auth, imported = true)
                    clear()
                    importing = false
                }
            },
            onBack = {
                importing = false
                error = null
            },
        )
        return
    }

    val dismiss = {
        request?.finish(false)
        onDismiss()
    }
    BackHandler(onBack = dismiss)
    FullScreenScaffold(title = WALLET_TITLE, onDismiss = dismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (request != null) item("request") {
                SectionCard(title = "Wallet needed") {
                    Text(request.reason, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // Declines only the feature's request: the page stays
                        // if the user opened it themselves (Settings → Wallet),
                        // and closes with the request if the request opened it.
                        TextButton(onClick = { request.finish(false) }) { Text("Not now") }
                    }
                }
            }
            item("state") {
                when (val s = state) {
                    Vault.State.Empty -> SetupSection(
                        busy = busy,
                        deviceSecure = deviceSecure,
                        onCreate = {
                            run("create the wallet") {
                                vault.create(Mnemonic.generate(), auth, imported = false)
                            }
                        },
                        onImport = {
                            error = null
                            importing = true
                        },
                    )
                    is Vault.State.Locked -> StatusSection(
                        locked = true,
                        info = s.info,
                        busy = busy,
                        onAction = { run("unlock the wallet") { vault.unlock(auth) } },
                    )
                    is Vault.State.Unlocked -> StatusSection(
                        locked = false,
                        info = s.info,
                        busy = busy,
                        onAction = { vault.lock() },
                    )
                    Vault.State.Unreadable -> SectionCard(title = WALLET_TITLE) {
                        StatusLine(
                            icon = Icons.Filled.ErrorOutline,
                            color = Color(0xFFEF4444),
                            title = "Can’t be read",
                            detail = "The wallet file on this phone isn’t one this version can open. " +
                                "If you have its recovery phrase, remove it and import the phrase to " +
                                "get your wallet back.",
                        )
                    }
                }
            }
            error?.let { message ->
                item("error") { ErrorText(message) }
            }
            val info = (state as? Vault.State.Locked)?.info ?: (state as? Vault.State.Unlocked)?.info
            if (info != null) {
                val list = accountList
                if (list == null) {
                    item("accounts") { AccountsLockedSection(locked = state is Vault.State.Locked) }
                } else {
                    item("accounts") {
                        AccountsSection(
                            list = list,
                            locked = state is Vault.State.Locked,
                            busy = busy,
                            onSelect = { index -> scope.launch { walletAccounts.select(index) } },
                            onAdd = {
                                run("add an account") {
                                    if (!vault.unlockedNow()) vault.unlock(auth)
                                    walletAccounts.add()
                                }
                            },
                        )
                    }
                    item("balances") {
                        BalancesSection(
                            chains = walletChains.orEmpty(),
                            balances = allBalances[list.active.address.lowercase()].orEmpty(),
                            refreshing = refreshing,
                            onRefresh = { refreshTick++ },
                        )
                    }
                }
            }
            if (info != null && !info.backedUp) item("backup") { BackupReminder(info.protection) }
            if (info?.protection == VaultProtection.DEVICE_ONLY) item("no-lock") {
                SectionCard(title = "No screen lock") {
                    NoScreenLockWarning(
                        text = "This wallet was made when the phone had no screen lock, so nothing " +
                            "asks who you are before it opens: anyone holding the phone can use it. " +
                            if (info.backedUp) {
                                "To protect it, set a screen lock, then remove the wallet and import " +
                                    "your recovery phrase again."
                            } else {
                                "Its recovery phrase can’t be shown yet, so it can’t be moved to a " +
                                    "protected wallet: set a screen lock and set up a new wallet if " +
                                    "you need that protection."
                            },
                    )
                    ScreenLockSettingsButton()
                }
            }
            if (state != Vault.State.Empty) item("remove") {
                SectionCard(title = "Remove") {
                    PageRow(
                        title = "Remove wallet",
                        subtitle = "Erases it from this phone",
                        style = PageRowStyle.Inset,
                        leadingIcon = Icons.Filled.DeleteForever,
                        enabled = !busy,
                        onClick = { confirmRemove = true },
                    )
                }
            }
        }
    }

    if (confirmRemove) {
        RemoveWalletDialog(
            onConfirm = {
                confirmRemove = false
                run("remove the wallet") { vault.remove() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun SetupSection(
    busy: Boolean,
    deviceSecure: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    SectionCard(title = "Set up your wallet") {
        Text(
            "Your wallet is your identity for dApps, publishing and payments: a 24-word " +
                "recovery phrase, encrypted on this phone. You don’t need one to browse.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "It stays on this phone only — nothing is backed up to the cloud. This version can’t " +
                "show a new wallet’s recovery phrase yet, so a wallet created here can’t be " +
                "restored if the phone is lost, broken or reset. Import a phrase you already " +
                "have if you need to be able to restore it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!deviceSecure) {
            Spacer(Modifier.height(12.dp))
            NoScreenLockWarning(
                text = "This phone has no screen lock. You can still make a wallet, but nothing " +
                    "will ask who you are before it opens: anyone holding the phone can use it. " +
                    "Set a PIN, pattern or password first to protect it with your fingerprint, " +
                    "face or screen lock.",
            )
            ScreenLockSettingsButton()
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Creating…" else "Create wallet")
        }
        OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Import recovery phrase")
        }
    }
}

@Composable
private fun StatusSection(
    locked: Boolean,
    info: Vault.Info,
    busy: Boolean,
    onAction: () -> Unit,
) {
    SectionCard(title = WALLET_TITLE) {
        if (locked) {
            StatusLine(
                icon = Icons.Filled.Lock,
                color = Color(0xFF94A3B8),
                title = "Locked",
                detail = if (info.protection == VaultProtection.SCREEN_LOCK) {
                    "Unlock with your fingerprint, face or screen lock"
                } else {
                    "Unlocks without asking — this phone has no screen lock"
                },
            )
        } else {
            StatusLine(
                icon = Icons.Filled.LockOpen,
                color = Color(0xFF22C55E),
                title = "Unlocked",
                detail = "Locks after 15 minutes without wallet activity, 1 minute after you " +
                    "leave the app, and whenever the app restarts",
            )
        }
        Spacer(Modifier.height(8.dp))
        DetailRow(
            "Unlock with",
            if (info.protection == VaultProtection.SCREEN_LOCK) "Biometrics or screen lock" else "Nothing (no screen lock)",
            singleLine = false,
        )
        DetailRow("Key kept in", if (info.strongBox) "StrongBox secure chip" else "Android Keystore", singleLine = false)
        DetailRow("Backup", "This phone only", singleLine = false)
        Spacer(Modifier.height(8.dp))
        if (locked) {
            Button(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Unlocking…" else "Unlock")
            }
        } else {
            OutlinedButton(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Lock now")
            }
        }
    }
}

@Composable
private fun StatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    title: String,
    detail: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The persistent backup-reminder card (maintainer decision 3), until the
 * phrase is seen (#78). This version can't show the phrase yet, so the
 * card says what's at stake rather than asking the user to write down
 * words no screen shows.
 */
@Composable
private fun BackupReminder(protection: VaultProtection) {
    SectionCard(title = BACKUP_REMINDER) {
        NoScreenLockWarning(
            text = "This wallet exists only on this phone, and this version can’t show its " +
                "recovery phrase yet. Until you’ve written the phrase down, the wallet is gone " +
                "for good if the phone is lost, broken or reset" +
                (if (protection == VaultProtection.SCREEN_LOCK) ", or its screen lock is removed." else "."),
        )
    }
}

@Composable
private fun NoScreenLockWarning(text: String) {
    // Amber, as Settings' unverified-endpoint warning.
    val color = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ScreenLockSettingsButton() {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }) { Text("Screen lock settings") }
    }
}

/**
 * Take focus and the keyboard away from whatever text field the page
 * opened over. The panels underneath stay composed (Settings with its
 * search field, the address bar), so a field focused there keeps the
 * IME attached while hidden: without this, recovery-phrase words typed
 * on the Import page before tapping its field would land in the covered
 * Settings search (saved instance state, learning keyboard, no
 * `FLAG_SECURE` once the user goes back). The pages' WebViews are
 * handled for the whole time the panel is up, not just here: page
 * script can re-take focus whenever it likes, so [BrowserWebViewHost]
 * blocks focus for them while any panel covers them.
 */
@Composable
private fun ReleaseCoveredFocus() {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * Import a recovery phrase (#75): 12, 15, 18, 21 or 24 words, checked
 * against the BIP-39 list and checksum as they're typed ([importHint]).
 * `FLAG_SECURE` while open; the keyboard gets no suggestions and
 * [android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]
 * so it doesn't learn the words.
 */
@Composable
private fun ImportPhrasePage(
    busy: Boolean,
    error: String?,
    deviceSecure: Boolean,
    onImport: (Mnemonic, clear: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    SecureWindow()
    ReleaseCoveredFocus()
    val context = LocalContext.current
    // Plain remember: the phrase must not reach saved instance state.
    // A TextFieldValue so a paste over a selection can be told from typing.
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val phrase = field.text
    // Something was pasted in (more than one character inserted at once,
    // also over a selection it replaced): the
    // phrase may still be on the clipboard after the import.
    var pasted by remember { mutableStateOf(false) }
    val back = {
        field = TextFieldValue("")
        onBack()
    }
    BackHandler(onBack = back)
    val hint = importHint(phrase)
    val submit = {
        if (!busy && hint is ImportHint.Valid) {
            runCatching { Mnemonic.parse(phrase) }.getOrNull()?.let { mnemonic ->
                onImport(mnemonic) {
                    if (pasted) clearPhraseFromClipboard(context, mnemonic.words)
                    field = TextFieldValue("")
                    pasted = false
                }
            }
        }
    }
    FullScreenScaffold(title = "Import wallet", onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("field") {
                SectionCard(title = "Recovery phrase") {
                    Text(
                        "Type or paste the words, separated by spaces. They’re encrypted on this " +
                            "phone only and never sent anywhere. A pasted phrase is taken off the " +
                            "clipboard once it’s imported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TabTextInput(private = true) {
                        OutlinedTextField(
                            value = field,
                            onValueChange = {
                                if (insertedLength(field, it) > 1) pasted = true
                                field = it
                            },
                            enabled = !busy,
                            minLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        hint.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (hint) {
                            is ImportHint.Problem -> MaterialTheme.colorScheme.error
                            is ImportHint.Valid -> Color(0xFF22C55E)
                            is ImportHint.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (!deviceSecure) {
                        Spacer(Modifier.height(12.dp))
                        NoScreenLockWarning(
                            text = "This phone has no screen lock, so anyone holding it will be able " +
                                "to open this wallet.",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = submit,
                        enabled = !busy && hint is ImportHint.Valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Importing…" else "Import wallet") }
                }
            }
            error?.let { message -> item("error") { ErrorText(message) } }
        }
    }
}

/**
 * Remove wallet's strong confirmation (maintainer decision 9): the
 * consequence spelled out, and Remove stays disabled until the user
 * ticks that they have the phrase or accept losing the wallet.
 */
@Composable
private fun RemoveWalletDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null) },
        title = { Text("Remove wallet?") },
        text = {
            Column {
                Text(
                    "This deletes the wallet and its recovery phrase from this phone. There is no " +
                        "undo and no copy anywhere else: without the recovery phrase written down, " +
                        "this wallet and everything in it are gone for good.",
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        ),
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("I have my recovery phrase, or I accept losing this wallet")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = acknowledged,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove wallet") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
