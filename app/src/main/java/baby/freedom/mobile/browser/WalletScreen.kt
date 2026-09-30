package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.DuplicateAccountException
import baby.freedom.mobile.wallet.BackupMissingException
import baby.freedom.mobile.wallet.BackupNotEncryptedException
import baby.freedom.mobile.wallet.BackupUnavailableException
import baby.freedom.mobile.wallet.BackupUnreadableException
import baby.freedom.mobile.wallet.Mnemonic
import baby.freedom.mobile.wallet.PhraseBackup
import baby.freedom.mobile.wallet.PhraseBackupJob
import baby.freedom.mobile.wallet.RestoreNeedsScreenLockException
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.PublisherIdentityStore
import baby.freedom.mobile.wallet.SafeAccounts
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthCancelledException
import baby.freedom.mobile.wallet.VaultAuthFailedException
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultLockedException
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import baby.freedom.mobile.wallet.TxHistory
import baby.freedom.mobile.data.X402Store
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.TooManyAccountsException
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.data.SwarmFeedStore
import baby.freedom.mobile.data.SwarmGrantStore
import baby.freedom.mobile.wallet.VaultProtection
import baby.freedom.mobile.wallet.VaultUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val WALLET_ROW_KEY = "wallet"

/** Between chain reads for a pending send while the wallet page is up: at first, doubling to [TX_HISTORY_POLL_MAX_MS]. */
private const val TX_HISTORY_POLL_MS = 15_000L
private const val TX_HISTORY_POLL_MAX_MS = 5 * 60_000L
internal const val WALLET_TITLE = "Wallet"
internal const val BACKUP_REMINDER = "Recovery phrase not backed up yet"
internal const val SHOW_PHRASE = "Show recovery phrase"
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
 *
 * Google backup (#231) never stands in for seeing the phrase, not even
 * with its entry reconciled as cloud-backed: Block Store only uploads it
 * with the phone's own Google backup, which may be off, and no app can
 * ask whether it's on (#244 R5-F1). So this phone may still hold the only
 * copy.
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

/**
 * The reminder card's warning. With Google backup on and end-to-end
 * encrypted ([googleBackup]) the phrase may not be on this phone only, but
 * Freedom can't tell: Block Store uploads it with the phone's own Google
 * backup, which may be off (#244 R5-F1).
 */
internal fun backupReminderText(protection: VaultProtection, googleBackup: Boolean): String =
    (
        if (googleBackup) {
            "Google backup reaches your Google account only if this phone’s Google backup is on in " +
                "Android settings, which Freedom can’t check. So this wallet may exist only on this phone."
        } else {
            "This wallet exists only on this phone."
        }
        ) + " Until you’ve written its recovery phrase down, the wallet " +
        (if (googleBackup) "may be" else "is") + " gone for good if the phone is lost, broken or reset" +
        (if (protection == VaultProtection.SCREEN_LOCK) ", or its screen lock is removed." else ".")

/** Settings' Wallet section rows, for settings search. */
internal fun walletSettingsRows(state: Vault.State) = listOf(
    settingsRow(
        WALLET_ROW_KEY, WALLET_TITLE, walletSummary(state), walletAttentionLine(state),
        "Recovery phrase", "Identity", "Back up", GOOGLE_BACKUP_TITLE, "Restore",
    ),
)

/**
 * The status card's Backup line: where the phrase is kept, by what Google
 * backup actually did ([PhraseBackup.reconcile]) — including an entry of
 * this wallet kept from before with backup now off (#244 R2-F2).
 */
internal fun walletBackupDetail(cloudBackup: Boolean, known: PhraseBackup.Known?, walletAddress: String?): String {
    val held = backupHeld(cloudBackup, known, walletAddress)
    return when {
        cloudBackup && known?.status == PhraseBackup.Status.NONE -> "This phone (Google backup missing)"
        // Written for the cloud, but whether it got there hangs on the phone's own Google
        // backup, which no app can see (#244 R5-F1).
        held == BackupHeld.CLOUD -> if (cloudBackup) {
            "This phone, and Google if this phone’s Google backup is on (end-to-end encrypted)"
        } else {
            "This phone, and Google if this phone’s Google backup is on (a backup kept from before, " +
                "end-to-end encrypted)"
        }
        held == BackupHeld.DEVICE -> if (cloudBackup) {
            "This phone (Google backup paused)"
        } else {
            "This phone (a Google backup kept from before, paused)"
        }
        held == BackupHeld.UNKNOWN && cloudBackup -> "This phone, and Google once it answers"
        else -> "This phone only"
    }
}

/**
 * Remove wallet's line for a backup it keeps, by where that backup is
 * (#244 R2-F1): a paused one is only in Google Play services here, and
 * even one written for the cloud is only off this phone if the phone's own
 * Google backup is on, which Freedom can't check (#244 R5-F1).
 */
internal fun removeWalletKeepsBackupText(backup: BackupHeld): String = when (backup) {
    BackupHeld.CLOUD -> "This deletes the wallet and its recovery phrase from this phone. Its Google " +
        "backup stays, and this page offers to restore it. It reaches your Google account only if this " +
        "phone’s Google backup is on in Android settings, which Freedom can’t check: without the " +
        "recovery phrase written down, losing or resetting this phone may still lose this wallet."
    BackupHeld.DEVICE -> "This deletes the wallet and its recovery phrase from this phone. Its Google " +
        "backup stays, but it’s paused: it’s only in Google Play services on this phone, not in your " +
        "Google account, until the phone has a screen lock and a Google account again. This page " +
        "offers to restore it."
    BackupHeld.UNATTRIBUTED -> "This deletes the wallet and its recovery phrase from this phone. Google " +
        "Play services holds a Google backup of a wallet, which may be this one or another — this " +
        "wallet can’t be read to tell. That backup stays, and this page offers to restore it. " +
        "Without the recovery phrase written down, this wallet may be gone for good."
    BackupHeld.MAYBE_UNATTRIBUTED -> "This deletes the wallet and its recovery phrase from this phone. Google " +
        "Play services hasn’t answered, so it isn’t known whether it holds a Google backup of a wallet " +
        "(this one or another — this wallet can’t be read to tell); if it does, it stays, and this page " +
        "offers to restore it. Without the recovery phrase written down, this wallet may be gone for good."
    BackupHeld.UNKNOWN -> "This deletes the wallet and its recovery phrase from this phone. Google Play " +
        "services hasn’t answered, so it isn’t known whether a Google backup of this wallet is there; " +
        "if it is, it stays, and this page offers to restore it. Without the recovery phrase written " +
        "down, this wallet may be gone for good."
    BackupHeld.NONE -> "This deletes the wallet and its recovery phrase from this phone. There is no " +
        "undo and no copy anywhere else: without the recovery phrase written down, this wallet and " +
        "everything in it are gone for good."
}

/**
 * The Unreadable wallet card: with a Block Store entry there (#231),
 * restoring it is the way back. [googleBackupThere] null: Play services
 * hasn't said yet whether there is one (#244 R6-M1).
 */
internal fun unreadableWalletDetail(googleBackupThere: Boolean?): String =
    "The wallet file on this phone isn’t one this version can open. " + when (googleBackupThere) {
        true -> "Google Play services holds a $GOOGLE_BACKUP_TITLE of a wallet: remove this one but keep that " +
            "backup, then restore it (with a screen lock set). Or, if you have the recovery phrase, " +
            "remove it and import the phrase."
        null -> "Google Play services hasn’t answered yet, so it isn’t known whether it holds a " +
            "$GOOGLE_BACKUP_TITLE of a wallet. If it does, remove this one but keep that backup, then " +
            "restore it (with a screen lock set). Or, if you have the recovery phrase, remove it and " +
            "import the phrase."
        false -> "If you have its recovery phrase, remove it and import the phrase to get your wallet back."
    }

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
internal fun walletErrorMessage(
    e: Throwable,
    action: String,
    phraseBackedUp: Boolean,
    googleBackup: Boolean = false,
    /** False when the backup there may be another wallet's (the vault can't be read to tell). */
    backupOwnerKnown: Boolean = true,
    /** False when Play services hasn't said yet whether there is a backup at all (#244 R6-M1). */
    backupThereKnown: Boolean = true,
): String? = when (e) {
    is VaultAuthCancelledException -> null
    is CancellationException -> null
    is VaultAuthFailedException -> "Couldn’t $action: ${e.message}"
    is VaultLockedException -> "Couldn’t $action: the wallet locked. Unlock it and try again."
    is TooManyAccountsException -> "Couldn’t $action: ${e.message}."
    is DuplicateAccountException -> "Couldn’t $action: the next account of this wallet is already on the list, added from a " +
        "Ledger that holds the same recovery phrase. Remove that Ledger account to add it here."
    is VaultKeyLostException -> "Android has erased this wallet’s key. That happens when the screen lock is removed. " +
        lostWalletAdvice(phraseBackedUp, googleBackup, backupOwnerKnown, backupThereKnown)
    is VaultUnreadableException -> "This wallet can’t be read. " + lostWalletAdvice(phraseBackedUp, googleBackup, backupOwnerKnown, backupThereKnown)
    // Google backup (#231). None of these messages can carry the phrase.
    is BackupUnavailableException -> "Couldn’t $action: ${e.message}."
    is BackupNotEncryptedException -> "Couldn’t $action: Google can’t end-to-end encrypt the backup on this " +
        "phone. That needs a screen lock and a Google account."
    is BackupUnreadableException -> "Couldn’t $action: the Google backup isn’t one this version can read."
    is BackupMissingException -> "Couldn’t $action: there’s no Google backup on this phone any more."
    is RestoreNeedsScreenLockException -> "Couldn’t $action: set a screen lock first, so the restored " +
        "wallet asks who you are before it opens."
    else -> "Couldn’t $action (${e.javaClass.simpleName})"
}

private fun lostWalletAdvice(
    phraseBackedUp: Boolean,
    googleBackup: Boolean,
    ownerKnown: Boolean,
    thereKnown: Boolean,
) = if (googleBackup && !thereKnown) {
    "Google Play services hasn’t answered yet, so it isn’t known whether it holds a $GOOGLE_BACKUP_TITLE " +
        "of a wallet (this one or another). If it does, remove this wallet but keep that backup, then " +
        "restore it (with a screen lock set). " +
        if (phraseBackedUp) "Or remove it and import your recovery phrase." else ""
} else if (googleBackup && !ownerKnown) {
    "Google Play services holds a $GOOGLE_BACKUP_TITLE of a wallet, which may be this one or another: " +
        "remove this wallet but keep that backup, then restore it (with a screen lock set). " +
        if (phraseBackedUp) "Or remove it and import your recovery phrase." else ""
} else if (googleBackup) {
    "Its $GOOGLE_BACKUP_TITLE can bring it back: remove the wallet but keep that backup, " +
        "then restore it (with a screen lock set)."
} else if (phraseBackedUp) {
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
 * protected, the backup reminder, Show recovery phrase (#78) and
 * Remove wallet.
 *
 * The phrase is only ever on screen on the import page and the
 * recovery-phrase page, both `FLAG_SECURE` ([SecureWindow]); the import
 * page also keeps the keyboard from learning what's typed. The phrase
 * lives in plain `remember` state on either page, never
 * `rememberSaveable`, so it can't end up in the saved-instance-state
 * bundle.
 */
@Composable
fun WalletScreen(
    request: Vault.SetupRequest?,
    currentSite: String?,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val state by vault.state.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val sender = remember(context) { WalletSender.get(context) }
    val sendStatus by sender.status.collectAsState()
    // The transaction history (#109) and its two pages: all sends, one send (by hash, so it follows the record).
    val history = remember(context) { TxHistory.get(context) }
    val txRecords by history.records.collectAsState()
    var historyOpen by remember { mutableStateOf(false) }
    var openTx by remember { mutableStateOf<String?>(null) }
    // Safe accounts (#141): one open by address, the create page, and a request scanned to co-sign.
    val safeAccounts = remember(context) { SafeAccounts.get(context) }
    val safeState by safeAccounts.state.collectAsState()
    var openSafe by remember { mutableStateOf<String?>(null) }
    var creatingSafe by remember { mutableStateOf(false) }
    var coSigning by remember { mutableStateOf<String?>(null) }
    // The receive and scan pages (#106).
    var receiving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    // Connect a Ledger (#142).
    var connectingLedger by remember { mutableStateOf(false) }
    val publishers = remember(context) { PublisherIdentityStore.get(context) }
    var publisherSites by remember { mutableStateOf(0) }
    // The site the user opened Wallet from, fixed at that moment: the tab
    // behind keeps running, and a redirect or script navigation there must
    // not change which origin "This site" → Set up applies to.
    val cameFrom = remember { currentSite }
    // No other app's overlay over the wallet (#240): Android 12+ hides them while it's open.
    HideOverlayWindows()
    var showingPhrase by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    // Google backup (#231): whether it's possible here, whether Block Store holds an
    // entry, where this wallet's backup stands, and the delete confirmations.
    val phraseBackup = remember(context) { PhraseBackup.get(context) }
    var backupAvailability by remember { mutableStateOf<PhraseBackup.Availability?>(null) }
    var backupEntry by remember { mutableStateOf<Boolean?>(null) }
    // Where the entry stands and whose it is, as last reconciled (here, on foreground, hourly).
    val backupKnown by phraseBackup.known.collectAsState()
    var backupCheck by remember { mutableStateOf(0) }
    var confirmBackupOff by remember { mutableStateOf(false) }
    var confirmBackupDelete by remember { mutableStateOf(false) }
    ReleaseCoveredFocus()

    // Accounts and balances (#104). Addresses are public, so both show
    // while the wallet is locked; only adding an account needs it open.
    val walletAccounts = remember(context) { WalletAccounts.get(context) }
    val accountList by walletAccounts.accounts.collectAsState()
    val accountSyncFailed by walletAccounts.syncFailed.collectAsState()
    val allBalances by walletAccounts.balances.byAddress.collectAsState()
    val chainStore = remember(context) { ChainStore.get(context) }
    val dappGrantStore = remember(context) { DappGrantStore.get(context) }
    val dappGrants by dappGrantStore.all.collectAsState(initial = emptyList())
    // x402 payments (#140): the sites allowed to pay without asking, and every payment.
    val x402 = remember(context) { X402Store.get(context) }
    val x402Allowances by x402.allowances.collectAsState(initial = emptyList())
    val x402Payments by x402.history.collectAsState(initial = emptyList())
    var x402HistoryOpen by remember { mutableStateOf(false) }
    // The connected site whose page is open (#111), by origin, so it follows the stored grant.
    var openSite by remember { mutableStateOf<String?>(null) }
    // The connected site whose Disconnect couldn't be saved: its line says so, as the site's page does.
    var disconnectFailed by remember { mutableStateOf<String?>(null) }
    val swarmGrants by remember(context) { SwarmGrantStore.get(context).all }.collectAsState(initial = emptyList())
    var swarmDisconnectFailed by remember { mutableStateOf<String?>(null) }
    // The rows each Swarm app's permission manifest manages (#122), read again whenever the grants change.
    var swarmAskEachTimeFailed by remember { mutableStateOf<String?>(null) }
    var swarmManifestRows by remember { mutableStateOf<Map<String, List<ManifestCapability>>>(emptyMap()) }
    LaunchedEffect(swarmGrants) {
        swarmManifestRows = withContext(Dispatchers.IO) { SwarmProviders.manifestRows() }
    }
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

    // Pending sends the sender isn't following (stopped tracking, no receipt in time, an
    // earlier run's) are settled from the chain: on opening, on Refresh, on a new pending
    // send, and again while the page is up for as long as a refresh says one could still
    // change — a pending send, or one judged replaced within the last minutes (whose receipt
    // a node behind may not have had yet). The wait grows while nothing settles, so a send
    // that never went out doesn't keep the page reading the chain every few seconds.
    // refresh() waits for the history file to be read, so the first answer here already
    // covers a replaced record the file brings back, even with nothing pending to restart this.
    val pendingCount = txRecords.count { it.pending }
    LaunchedEffect(refreshTick, pendingCount) {
        var wait = TX_HISTORY_POLL_MS
        while (history.refresh()) {
            delay(wait)
            wait = minOf(wait * 2, TX_HISTORY_POLL_MAX_MS)
        }
    }

    // A send that went through (or failed on chain) changed the balances: read them again.
    LaunchedEffect(sendStatus?.done) {
        if (sendStatus?.done == true) refreshTick++
    }

    // Re-read on every resume: the user may come back from setting a screen lock.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val deviceSecure = remember(lifecycleState) { vault.deviceSecure() }

    // Asked again on every resume too: a screen lock or Google account set (or removed)
    // meanwhile changes whether backup can be end-to-end encrypted. With backup on, the
    // entry is reconciled with that (PhraseBackup.reconcile) and reports where it stands.
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed, state, backupCheck) {
        if (!resumed) return@LaunchedEffect
        backupAvailability = phraseBackup.availability()
        // Whatever the wallet says: an entry kept after Remove wallet, or one this phone
        // received from the old one, must go device-only without E2EE all the same.
        val reconciled = phraseBackup.reconcileQuietly()
        PhraseBackupJob.sync(context, reconciled)
        backupEntry = phraseBackup.exists()
    }

    // Whether the user can have the phrase: false for a created wallet
    // whose phrase was never shown (#78), which the copy must not send
    // them to re-import.
    val phraseBackedUp = when (val s = state) {
        is Vault.State.Locked -> s.info.backedUp
        is Vault.State.Unlocked -> s.info.backedUp
        else -> true
    }
    // Whether Block Store's entry is this wallet, and where it holds the phrase (#244 R2-F1):
    // what the reminder, Remove wallet and the lost-key advice go by, not the vault's flag.
    val walletAddress = walletSeedAddress(accountList)
    val storedInfo = (state as? Vault.State.Locked)?.info ?: (state as? Vault.State.Unlocked)?.info
    val entryIsThisWallet = storedInfo?.let { backupEntryIsThisWallet(it.cloudBackup, backupKnown, walletAddress) }
    // An unreadable vault can't say whether its backup was on or whose the entry is: an
    // entry there is then the best guess that there's one to restore, worded as a backup
    // of *a* wallet, not of this one (#244 R4-F3).
    val googleBackupHeld = when {
        storedInfo != null -> backupHeld(storedInfo.cloudBackup, backupKnown, walletAddress)
        state == Vault.State.Unreadable -> unreadableBackupHeld(backupEntry)
        else -> BackupHeld.NONE
    }
    // The lost-key advice sends the user to restore the entry: only when it's there, and
    // theirs or possibly theirs.
    val googleBackupOn = googleBackupHeld == BackupHeld.CLOUD || googleBackupHeld == BackupHeld.DEVICE ||
        googleBackupHeld == BackupHeld.UNATTRIBUTED || googleBackupHeld == BackupHeld.MAYBE_UNATTRIBUTED

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
                error = walletErrorMessage(
                    e, action, phraseBackedUp, googleBackupOn,
                    backupOwnerKnown = googleBackupHeld != BackupHeld.UNATTRIBUTED &&
                        googleBackupHeld != BackupHeld.MAYBE_UNATTRIBUTED,
                    backupThereKnown = googleBackupHeld != BackupHeld.MAYBE_UNATTRIBUTED,
                )
                if (e is CancellationException) throw e
            } finally {
                busy = false
            }
        }
    }

    // Re-counted whenever the wallet changes and on coming back from the page.
    LaunchedEffect(state, publishing) {
        if (!publishing) publisherSites = withContext(Dispatchers.IO) { publishers.sites().size }
    }
    // The sub-page closes with the wallet, and for a feature's request, whose banner is on this page.
    LaunchedEffect(state, request) {
        if (request != null || (state !is Vault.State.Locked && state !is Vault.State.Unlocked)) {
            publishing = false
            sending = false
            receiving = false
            scanning = false
            connectingLedger = false
            historyOpen = false
            openTx = null
            openSafe = null
            creatingSafe = false
            coSigning = null
        }
        // A send goes with the wallet it came from, settled or not (one that may
        // still land leaves its nonce to be replaced, should that account come back),
        // and so does its history — after the discard, so nothing the sender still
        // had to report lands in the emptied list.
        if (state == Vault.State.Empty) {
            sender.discard()
            history.wipe()
        }
    }

    if (publishing && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        PublisherIdentitiesPage(currentSite = cameFrom, onBack = { publishing = false })
        return
    }
    val sendFrom = accountList?.active
    if (sending && sendFrom != null && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        SendPage(
            account = sendFrom,
            chains = walletChains.orEmpty(),
            balances = allBalances[sendFrom.address.lowercase()].orEmpty(),
            vault = vault,
            auth = auth,
            phraseBackedUp = phraseBackedUp,
            onOpenUrl = onOpenUrl,
            onBack = { sending = false },
        )
        return
    }
    val historyAccount = accountList?.active
    val accountTx = txRecordsFrom(txRecords, historyAccount?.address)
    if (historyAccount != null && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        // Looked up again on every change, so an open send moves from Pending to Confirmed in place.
        val tx = openTx?.let { hash -> accountTx.firstOrNull { it.hash == hash } }
        if (tx != null) {
            TxDetailPage(tx, onOpenUrl = onOpenUrl, onBack = { openTx = null })
            return
        }
        if (historyOpen) {
            TxHistoryPage(historyAccount.name, accountTx, onOpen = { openTx = it.hash }, onBack = { historyOpen = false })
            return
        }
    }
    val receivingAccount = accountList?.active
    if (receiving && receivingAccount != null && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        ReceivePage(account = receivingAccount, onBack = { receiving = false })
        return
    }
    if (connectingLedger && accountList != null && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        LedgerConnectPage(
            accounts = accountList?.accounts.orEmpty(),
            onAdded = { connectingLedger = false },
            onBack = { connectingLedger = false },
        )
        return
    }
    if (state is Vault.State.Locked || state is Vault.State.Unlocked) {
        coSigning?.let { raw ->
            SafeCoSignPage(
                raw = raw,
                accounts = accountList?.accounts.orEmpty(),
                chains = allChains.orEmpty(),
                vault = vault,
                auth = auth,
                phraseBackedUp = phraseBackedUp,
                onBack = { coSigning = null },
            )
            return
        }
        if (creatingSafe) {
            SafeCreatePage(
                accounts = accountList?.accounts.orEmpty(),
                onCreated = {
                    creatingSafe = false
                    openSafe = it.address
                },
                onBack = { creatingSafe = false },
            )
            return
        }
        openSafe?.let { address ->
            SafePage(
                address = address,
                accounts = accountList?.accounts.orEmpty(),
                chains = allChains.orEmpty(),
                vault = vault,
                auth = auth,
                phraseBackedUp = phraseBackedUp,
                onOpenUrl = onOpenUrl,
                onBack = { openSafe = null },
            )
            return
        }
    }
    if (scanning && (state is Vault.State.Locked || state is Vault.State.Unlocked)) {
        ScanPage(
            chains = allChains.orEmpty(),
            accounts = accountList?.accounts.orEmpty(),
            onSafeRequest = {
                scanning = false
                coSigning = it
            },
            onBack = { scanning = false },
        )
        return
    }
    if (openSite != null) {
        // Looked up again on every change: gone (disconnected here, by the site or from
        // Settings) closes the page. Shown whatever the vault's state, like the list.
        val grant = dappGrants.firstOrNull { it.origin == openSite }
        if (grant != null) {
            ConnectedSitePage(
                grant = grant,
                accounts = accountList?.accounts.orEmpty(),
                chains = allChains.orEmpty(),
                onDisconnect = { EthereumProviders.disconnect(context, it) },
                onBack = { openSite = null },
            )
            return
        }
        LaunchedEffect(openSite) { openSite = null }
    }
    val stored = storedInfo
    if (showingPhrase && stored != null) {
        RecoveryPhrasePage(
            protection = stored.protection,
            reveal = { vault.revealMnemonic(auth) },
            onSeen = { vault.markBackedUp() },
            errorMessage = { e -> walletErrorMessage(e, "show the recovery phrase", phraseBackedUp) },
            onBack = { showingPhrase = false },
        )
        return
    }
    // The wallet went away (removed, or unreadable) with the page up: nothing to show.
    LaunchedEffect(stored == null) { if (stored == null) showingPhrase = false }

    if (x402HistoryOpen) {
        X402HistoryPage(x402Payments, allChains.orEmpty(), onBack = { x402HistoryOpen = false })
        return
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
            if (state == Vault.State.Empty && backupEntry == true) item("restore") {
                RestoreFromBackupSection(
                    busy = busy,
                    deviceSecure = deviceSecure,
                    onRestore = {
                        run("restore the wallet") {
                            vault.restore(auth, phraseBackup)
                            backupCheck++
                        }
                    },
                    onDelete = { confirmBackupDelete = true },
                    noScreenLock = {
                        NoScreenLockWarning(
                            text = "Restoring asks for your fingerprint, face or screen lock, and this " +
                                "phone has none. Set a PIN, pattern or password first.",
                        )
                        ScreenLockSettingsButton()
                    },
                )
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
                        backupKnown = backupKnown,
                        walletAddress = walletAddress,
                        busy = busy,
                        onAction = { run("unlock the wallet") { vault.unlock(auth) } },
                    )
                    is Vault.State.Unlocked -> StatusSection(
                        locked = false,
                        info = s.info,
                        backupKnown = backupKnown,
                        walletAddress = walletAddress,
                        busy = busy,
                        onAction = { vault.lock() },
                    )
                    Vault.State.Unreadable -> SectionCard(title = WALLET_TITLE) {
                        StatusLine(
                            icon = Icons.Filled.ErrorOutline,
                            color = Color(0xFFEF4444),
                            title = "Can’t be read",
                            detail = unreadableWalletDetail(googleBackupThere = backupEntry),
                        )
                    }
                }
            }
            error?.let { message ->
                item("error") { ErrorText(message) }
            }
            val info = stored
            val turnOnBackup = {
                run("turn on Google backup") {
                    vault.enableCloudBackup(auth, phraseBackup)
                    backupCheck++
                }
            }
            if (info != null &&
                showGoogleBackupOffer(info.cloudBackupOffered, info.cloudBackup, backupAvailability, backupEntry, entryIsThisWallet)
            ) {
                item("backup-offer") {
                    GoogleBackupOffer(
                        busy = busy,
                        onTurnOn = turnOnBackup,
                        onNotNow = { run("save your answer") { vault.markCloudBackupOffered() } },
                    )
                }
            }
            if (info != null) {
                val list = accountList
                if (list == null) {
                    item("accounts") {
                        AccountsLockedSection(
                            locked = state is Vault.State.Locked,
                            failed = accountSyncFailed,
                            busy = busy,
                            onRetry = { run("find your accounts") { walletAccounts.retry() } },
                        )
                    }
                } else {
                    item("accounts") {
                        AccountsSection(
                            list = list,
                            locked = state is Vault.State.Locked,
                            busy = busy,
                            // Through run(): saving the choice can fail
                            // (a full disk), and that's an error line, not a crash.
                            onSelect = { index -> run("switch account") { walletAccounts.select(index) } },
                            onAdd = {
                                run("add an account") {
                                    if (!vault.unlockedNow()) vault.unlock(auth)
                                    walletAccounts.add()
                                }
                            },
                            onReceive = {
                                error = null
                                receiving = true
                            },
                            onConnectLedger = {
                                error = null
                                connectingLedger = true
                            },
                            onRemoveLedger = { account ->
                                run("remove the Ledger account") {
                                    // Its sites first (#220 R2-M2): told they lost it now, and not
                                    // quietly reconnected if the same Ledger account is added again.
                                    if (!EthereumProviders.accountRemoved(context, account.address)) {
                                        error = "Couldn’t remove the Ledger account: the sites connected to it " +
                                            "couldn’t be disconnected. Try again."
                                        return@run
                                    }
                                    // And desktop's OpenLV session, if it was given it (#220 R1-M2).
                                    OpenLvSession.accountRemovedFromWallet(account.address)
                                    walletAccounts.removeLedger(account.index)
                                }
                            },
                        )
                    }
                    item("send") {
                        SendEntrySection(
                            status = sendStatus,
                            enabled = !busy && walletChains != null,
                            onOpen = {
                                error = null
                                sending = true
                            },
                        )
                    }
                    item("history") {
                        TxHistorySection(
                            records = accountTx,
                            onOpen = {
                                error = null
                                openTx = it.hash
                            },
                            onShowAll = {
                                error = null
                                historyOpen = true
                            },
                        )
                    }
                    item("safes") {
                        SafeAccountsSection(
                            state = safeState,
                            enabled = !busy,
                            onOpen = {
                                error = null
                                openSafe = it
                            },
                            onCreate = {
                                error = null
                                creatingSafe = true
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
            if (info != null) item("scan") {
                SectionCard(title = "Scan") {
                    PageRow(
                        title = SCAN_TITLE,
                        subtitle = "An address, a payment request, a pairing code or a Safe request",
                        style = PageRowStyle.Inset,
                        leadingIcon = Icons.Filled.QrCodeScanner,
                        enabled = !busy,
                        onClick = {
                            error = null
                            scanning = true
                        },
                    )
                }
            }
            val openPhrase = {
                error = null
                showingPhrase = true
            }
            // Google backup doesn't end it: its upload hangs on Android's own Google backup,
            // which may be off (#244 R5-F1).
            if (info != null && !info.backedUp) item("backup") {
                BackupReminder(
                    info.protection,
                    googleBackup = googleBackupHeld == BackupHeld.CLOUD,
                    busy = busy,
                    onShow = openPhrase,
                )
            }
            if (info != null) item("phrase") {
                SectionCard(title = "Recovery phrase") {
                    PageRow(
                        title = SHOW_PHRASE,
                        subtitle = if (info.protection == VaultProtection.SCREEN_LOCK) {
                            "Asks for your fingerprint, face or PIN each time"
                        } else {
                            "Opens without asking: no screen lock"
                        },
                        style = PageRowStyle.Inset,
                        leadingIcon = Icons.Filled.Key,
                        enabled = !busy,
                        onClick = openPhrase,
                    )
                }
            }
            if (info != null) item("google-backup") {
                GoogleBackupSection(
                    on = info.cloudBackup,
                    availability = backupAvailability,
                    status = backupKnown?.status,
                    entryThere = backupEntry,
                    thisWallet = entryIsThisWallet,
                    busy = busy,
                    onToggle = { on -> if (on) turnOnBackup() else confirmBackupOff = true },
                    onDeleteKept = { confirmBackupDelete = true },
                    screenLockButton = { ScreenLockSettingsButton() },
                )
            }
            if (info?.protection == VaultProtection.DEVICE_ONLY) item("no-lock") {
                SectionCard(title = "No screen lock") {
                    NoScreenLockWarning(
                        text = "This wallet was made when the phone had no screen lock, so nothing " +
                            "asks who you are before it opens: anyone holding the phone can use it. " +
                            "To protect it, write down its recovery phrase and set a screen lock, " +
                            "then remove the wallet and import the phrase again.",
                    )
                    ScreenLockSettingsButton()
                }
            }
            // Sites connected through `window.ethereum` (#110), and the way to disconnect them.
            // Shown whatever the vault's state: a connection left behind (a remove whose
            // grant wipe failed) must never be out of the user's reach.
            if (dappGrants.isNotEmpty()) item("dapps") {
                DappSitesSection(
                    grants = dappGrants,
                    chains = allChains.orEmpty(),
                    accounts = accountList?.accounts.orEmpty(),
                    onOpen = { openSite = it },
                    disconnectFailed = disconnectFailed,
                    onRevoke = { origin ->
                        disconnectFailed = null
                        scope.launch {
                            if (!EthereumProviders.disconnect(context, origin)) disconnectFailed = origin
                        }
                    },
                )
            }
            // Sites connected through `window.swarm` (#120), likewise whatever the vault's state.
            if (swarmGrants.isNotEmpty()) item("swarm-sites") {
                SwarmSitesSection(
                    grants = swarmGrants,
                    disconnectFailed = swarmDisconnectFailed,
                    onRevoke = { origin ->
                        swarmDisconnectFailed = null
                        scope.launch {
                            if (!SwarmProviders.disconnect(context, origin)) swarmDisconnectFailed = origin
                        }
                    },
                    manifestRows = swarmManifestRows,
                    onAskEachTime = { origin ->
                        swarmAskEachTimeFailed = null
                        scope.launch {
                            if (!SwarmProviders.useIndividualApprovals(origin)) swarmAskEachTimeFailed = origin
                            swarmManifestRows = withContext(Dispatchers.IO) { SwarmProviders.manifestRows() }
                        }
                    },
                    askEachTimeFailed = swarmAskEachTimeFailed,
                )
            }
            // Shown whatever the vault's state once there's anything in it, like the connected sites.
            if (state is Vault.State.Locked || state is Vault.State.Unlocked || x402Allowances.isNotEmpty() || x402Payments.isNotEmpty()) {
                item("x402") {
                    X402Section(
                        allowances = x402Allowances,
                        payments = x402Payments.size,
                        chains = allChains.orEmpty(),
                        onRevoke = { a -> scope.launch { x402.revoke(a.origin, a.chainId, a.asset, a.account) } },
                        onOpenHistory = {
                            error = null
                            x402HistoryOpen = true
                        },
                    )
                }
            }
            if (state is Vault.State.Locked || state is Vault.State.Unlocked) item("publishing") {
                SectionCard(title = "Publishing") {
                    PageRow(
                        title = PUBLISHER_IDENTITIES_TITLE,
                        subtitle = publisherIdentitiesSummary(publisherSites),
                        style = PageRowStyle.Inset,
                        leadingIcon = Icons.Filled.Badge,
                        enabled = !busy,
                        onClick = {
                            error = null
                            publishing = true
                        },
                    )
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

    if (confirmBackupOff || confirmBackupDelete) {
        val turningOff = confirmBackupOff
        DeleteGoogleBackupDialog(
            turningOff = turningOff,
            walletStays = stored != null && entryIsThisWallet == true,
            onConfirm = {
                confirmBackupOff = false
                confirmBackupDelete = false
                if (turningOff) {
                    run("turn off Google backup") {
                        vault.disableCloudBackup(phraseBackup)
                        backupCheck++
                    }
                } else {
                    run("delete the Google backup") {
                        phraseBackup.delete()
                        backupCheck++
                    }
                }
            },
            onDismiss = {
                confirmBackupOff = false
                confirmBackupDelete = false
            },
        )
    }

    if (confirmRemove) {
        RemoveWalletDialog(
            backup = googleBackupHeld,
            onConfirm = { withBackup ->
                confirmRemove = false
                run("remove the wallet") {
                    var backupLeft: String? = null
                    var backupKept: PhraseBackup.DeleteIfOf? = null
                    // With backup on, only this wallet's phrase is ever stored, and an
                    // unattributed entry is deleted as "that backup, whichever wallet it's
                    // of". Otherwise whose the entry is is checked again under the lock:
                    // with Play services slow to answer the box shows before that's known,
                    // and another wallet's kept backup mustn't go with this one (#244 R5-F2).
                    val deleteAny = storedInfo?.cloudBackup == true || googleBackupHeld == BackupHeld.UNATTRIBUTED ||
                        googleBackupHeld == BackupHeld.MAYBE_UNATTRIBUTED
                    val ownAddress = walletAddress
                    // Its publisher identities (maintainer decision 9), its history and the
                    // sites connected to it (#110) — or importing the same phrase later would
                    // quietly reconnect them — go with it, inside remove()'s own
                    // non-cancellable wipe: the history file is deleted there and then
                    // (wipeNow), not by a write launched later that a process death could get
                    // ahead of.
                    suspend fun removeWith(entry: PhraseBackup.Held?) = vault.remove(
                        alsoWipe = {
                            // Its Google backup (#231). A failure doesn't stop the removal
                            // (a phone that lost Play services could never remove its
                            // wallet otherwise): the entry then shows below as a backup
                            // to restore or delete, and the page says so.
                            if (entry != null) {
                                try {
                                    if (deleteAny) {
                                        entry.delete()
                                    } else {
                                        backupKept = entry.deleteIfOf(ownAddress).takeIf {
                                            it == PhraseBackup.DeleteIfOf.OTHER_WALLET || it == PhraseBackup.DeleteIfOf.UNKNOWN
                                        }
                                    }
                                } catch (e: BackupUnavailableException) {
                                    backupLeft = e.message
                                }
                            }
                            publishers.wipe()
                            // The sites' feed access and records (#120): they name identities of this wallet.
                            SwarmFeedStore.get(context).wipe()
                            history.wipeNow()
                            EthereumProviders.walletRemoved(context)
                            // Its site allowances and payment history (#140).
                            x402.clear()
                        },
                    )
                    // With the backup: the entry's lock is taken before the vault's, so a
                    // reconcile stuck on Play services doesn't hold up unlocking meanwhile
                    // (#244 R4-F4).
                    if (withBackup) phraseBackup.exclusive { removeWith(this) } else removeWith(null)
                    backupLeft?.let {
                        error = "The wallet is removed, but its Google backup couldn’t be deleted ($it). " +
                            "Delete it below once Google Play services answers."
                    }
                    backupKeptMessage(backupKept)?.let { error = it }
                    backupCheck++
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/**
 * What Remove wallet says about a Google backup it kept because it wasn't
 * seen to be this wallet's: another wallet's, or one whose owner couldn't
 * be told (this wallet's address not read yet, or the entry unreadable),
 * which may well be this wallet's (#244 R1-M3). Null when none was kept.
 */
internal fun backupKeptMessage(kept: PhraseBackup.DeleteIfOf?): String? = when (kept) {
    PhraseBackup.DeleteIfOf.OTHER_WALLET ->
        "The wallet is removed. The Google backup on this phone is another wallet’s, so it was kept: " +
            "restore or delete it below."
    PhraseBackup.DeleteIfOf.UNKNOWN ->
        "The wallet is removed. Freedom couldn’t tell whether the Google backup on this phone is this " +
            "wallet’s, so it was kept: restore or delete it below."
    else -> null
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
            "It stays on this phone only unless you turn on $GOOGLE_BACKUP_TITLE. Once it’s made, " +
                "write down its recovery phrase ($SHOW_PHRASE): without it, the wallet can’t be " +
                "restored if the phone is lost, broken or reset.",
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
    backupKnown: PhraseBackup.Known?,
    walletAddress: String?,
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
        DetailRow(
            "Backup",
            walletBackupDetail(info.cloudBackup, backupKnown, walletAddress),
            singleLine = false,
        )
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
 * phrase has been shown once on [RecoveryPhrasePage] (#78). With a Google
 * backup written for the cloud ([googleBackup]) it says why that isn't
 * enough (#244 R5-F1).
 */
@Composable
private fun BackupReminder(protection: VaultProtection, googleBackup: Boolean, busy: Boolean, onShow: () -> Unit) {
    SectionCard(title = BACKUP_REMINDER) {
        NoScreenLockWarning(text = backupReminderText(protection, googleBackup))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onShow, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Back up now")
        }
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
 *
 * Against tapjacking (#240): both ticks and Remove ignore taps for the
 * dialog's first [PromptTapGuard.PROTECTION_MS] and drop a press another
 * app's window covered ([protectedPress]); other apps' overlays are
 * hidden while it's up (Android 12+).
 */
@Composable
private fun RemoveWalletDialog(backup: BackupHeld, onConfirm: (deleteBackup: Boolean) -> Unit, onDismiss: () -> Unit) {
    val cloudBackup = backup != BackupHeld.NONE
    // Whose the backup is can't be told (#244 R4-F3): "that" backup, never "its".
    val unattributed = backup == BackupHeld.UNATTRIBUTED || backup == BackupHeld.MAYBE_UNATTRIBUTED
    var acknowledged by remember { mutableStateOf(false) }
    val tap = rememberArmedTapGuard(Unit)
    val guard = tap.guard
    // Remove wallet keeps its Google backup (#231) unless the user ticks it away: the way
    // back for a wallet whose key Android erased is to remove it and restore that backup,
    // and a pre-ticked box there would delete what may be the only copy. A backup kept
    // shows on the empty page, which offers to restore or delete it.
    var deleteBackup by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null) },
        title = { Text("Remove wallet?") },
        text = {
            Column {
                Text(
                    if (cloudBackup && deleteBackup) {
                        "This deletes the wallet and its recovery phrase from this phone, and " +
                            (
                                when {
                                    backup == BackupHeld.MAYBE_UNATTRIBUTED ->
                                        "that Google backup, whichever wallet it’s of, if there is one, "
                                    unattributed -> "that Google backup, whichever wallet it’s of, "
                                    // Deleted only once it's seen to be this wallet's (#244 R5-F2).
                                    backup == BackupHeld.UNKNOWN -> "its Google backup, if there is one, "
                                    else -> "its Google backup "
                                }
                                ) +
                            "too. There is no undo: without the recovery phrase written " +
                            "down, this wallet and everything in it are gone for good."
                    } else if (cloudBackup) {
                        removeWalletKeepsBackupText(backup)
                    } else {
                        "This deletes the wallet and its recovery phrase from this phone. There is no " +
                            "undo and no copy anywhere else: without the recovery phrase written down, " +
                            "this wallet and everything in it are gone for good."
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .protectedPress(tap)
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            enabled = tap.armed,
                            onValueChange = { if (guard.accepts()) acknowledged = it },
                        ),
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("I have my recovery phrase, or I accept losing this wallet")
                }
                if (cloudBackup) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .protectedPress(tap)
                            .toggleable(
                                value = deleteBackup,
                                role = Role.Checkbox,
                                enabled = tap.armed,
                                onValueChange = { if (guard.accepts()) deleteBackup = it },
                            ),
                    ) {
                        Checkbox(checked = deleteBackup, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                backup == BackupHeld.MAYBE_UNATTRIBUTED -> "Also delete that Google backup, if there is one"
                                unattributed -> "Also delete that Google backup"
                                backup == BackupHeld.UNKNOWN -> "Also delete its Google backup, if there is one"
                                else -> "Also delete its Google backup"
                            },
                        )
                    }
                }
                ObscuredTapNotice(tap)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (guard.accepts()) onConfirm(cloudBackup && deleteBackup) },
                enabled = acknowledged && tap.armed,
                modifier = Modifier.protectedPress(tap),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove wallet") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * What "Copy" leaves on the clipboard, and for how long, in words the
 * page can show; also used by the unit test so the copy and
 * [PhraseClipboard.TTL_MS] can't drift apart.
 */
internal val COPY_NOTE = "Copying puts the words on the clipboard, where other apps can read them. " +
    "They’re taken off again after ${PhraseClipboard.TTL_MS / 1000 / 60} minute. If Freedom is in the " +
    "background by then, usually up to a minute later, but battery saving can hold it back until " +
    "you next open Freedom."

/**
 * Show recovery phrase (#78): the words behind a fresh authentication,
 * with the usual warnings, as on iOS (`RecoveryPhraseView`) and desktop
 * (`export-mnemonic.js`). Every reveal asks again ([Vault.revealMnemonic]
 * never keeps the words), whether or not the wallet is unlocked; a
 * wallet made on a phone with no screen lock is the one exception, and
 * says so.
 *
 * The page is `FLAG_SECURE` ([SecureWindow]): no screenshot, screen
 * recording, casting or Recents thumbnail. The words live in plain
 * `remember` state only — never `rememberSaveable` — and are dropped on
 * Hide, on Back, and as soon as the app goes to the background, so
 * coming back to it asks again. Showing them once clears the backup
 * reminder ([Vault.markBackedUp]).
 */
@Composable
private fun RecoveryPhrasePage(
    protection: VaultProtection,
    reveal: suspend () -> Mnemonic,
    onSeen: suspend () -> Unit,
    errorMessage: (Throwable) -> String?,
    onBack: () -> Unit,
) {
    SecureWindow()
    ReleaseCoveredFocus()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Follows the clipboard itself, so the button says "Copy" again as
    // soon as the minute is up and the words have been taken off.
    val copied by PhraseClipboard.copied.collectAsState()
    val hide = {
        words = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) hide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val back = {
        hide()
        onBack()
    }
    BackHandler(onBack = back)

    fun show() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                words = reveal().words
                try {
                    onSeen()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The reminder stays up; the words are on screen all the same.
                }
            } catch (e: Throwable) {
                error = errorMessage(e)
                if (e is CancellationException) throw e
            } finally {
                busy = false
            }
        }
    }

    FullScreenScaffold(title = "Recovery phrase", onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("warning") {
                SectionCard(title = "Keep it secret") {
                    NoScreenLockWarning(
                        text = "Anyone with these words can take everything in this wallet, from any " +
                            "device. Never share them or type them into a website or app — no one " +
                            "legitimate will ask. Write them down on paper, in order, and keep it " +
                            "somewhere safe. Don’t photograph them.",
                    )
                }
            }
            item("words") {
                val shown = words
                SectionCard(title = if (shown != null) "Your ${shown.size} words" else "Hidden") {
                    if (shown == null) {
                        Text(
                            "Make sure no one can see your screen. " + if (protection == VaultProtection.SCREEN_LOCK) {
                                "You’ll be asked for your fingerprint, face or screen lock."
                            } else {
                                "This phone has no screen lock, so they show without asking."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { show() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Waiting…" else SHOW_PHRASE)
                        }
                    } else {
                        PhraseGrid(shown)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    PhraseClipboard.copy(context, shown)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (copied) "Copied" else "Copy")
                            }
                            OutlinedButton(onClick = hide, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Hide")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            COPY_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            error?.let { message -> item("error") { ErrorText(message) } }
        }
    }
}

/**
 * The words numbered in two columns, read across (1 2 / 3 4 …) like
 * iOS's grid, so the numbers keep them in order when copied by hand.
 * Nothing is cut, ellipsised or broken mid-word: when the longest word
 * wouldn't fit half the width (a large font size), it's one column.
 */
@Composable
private fun PhraseGrid(words: List<String>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = MaterialTheme.typography.bodySmall
    val wordStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
    BoxWithConstraints {
        val gap = 8.dp
        // The widest a cell's content gets: "24." and the longest word shown.
        val needed = with(density) {
            val number = measurer.measure("${words.size}.", numberStyle).size.width.toDp()
            val word = words.maxOf { measurer.measure(it, wordStyle, softWrap = false).size.width }.toDp()
            maxOf(number, PHRASE_NUMBER_MIN) + PHRASE_NUMBER_GAP + word + PHRASE_CELL_PAD * 2
        }
        val columns = if (needed * 2 + gap <= maxWidth) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            words.chunked(columns).forEachIndexed { row, group ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    group.forEachIndexed { col, word ->
                        PhraseWord(
                            number = row * columns + col + 1,
                            word = word,
                            numberStyle = numberStyle,
                            wordStyle = wordStyle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val PHRASE_NUMBER_MIN = 24.dp
private val PHRASE_NUMBER_GAP = 6.dp
private val PHRASE_CELL_PAD = 10.dp

@Composable
private fun PhraseWord(
    number: Int,
    word: String,
    numberStyle: TextStyle,
    wordStyle: TextStyle,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = PHRASE_CELL_PAD, vertical = 10.dp),
    ) {
        Text(
            "$number.",
            style = numberStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = PHRASE_NUMBER_MIN),
        )
        Spacer(Modifier.width(PHRASE_NUMBER_GAP))
        Text(word, style = wordStyle)
    }
}
