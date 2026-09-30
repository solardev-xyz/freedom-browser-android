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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.WalletAccount
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
internal val WALLET_TITLE: String get() = Strings.get(R.string.wallet_title)
internal val BACKUP_REMINDER: String get() = Strings.get(R.string.wallet_reminder_line)
internal val SHOW_PHRASE: String get() = Strings.get(R.string.wallet_show_phrase)
private val NO_SCREEN_LOCK_LINE: String get() = Strings.get(R.string.wallet_no_screen_lock_line)

/** The Wallet row's one-line state, for Settings and its search. */
internal fun walletSummary(state: Vault.State): String = when (state) {
    Vault.State.Empty -> Strings.get(R.string.wallet_summary_empty)
    is Vault.State.Locked -> Strings.get(R.string.wallet_summary_locked)
    is Vault.State.Unlocked -> Strings.get(R.string.wallet_summary_unlocked)
    Vault.State.Unreadable -> Strings.get(R.string.wallet_summary_unreadable)
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
internal fun backupReminderText(protection: VaultProtection, googleBackup: Boolean): String {
    val screenLock = protection == VaultProtection.SCREEN_LOCK
    return Strings.get(
        when {
            googleBackup && screenLock -> R.string.wallet_reminder_google_screen_lock
            googleBackup -> R.string.wallet_reminder_google
            screenLock -> R.string.wallet_reminder_phone_only_screen_lock
            else -> R.string.wallet_reminder_phone_only
        },
    )
}

/** Settings' Wallet section rows, for settings search. */
internal fun walletSettingsRows(state: Vault.State) = listOf(
    settingsRow(
        WALLET_ROW_KEY, WALLET_TITLE, walletSummary(state), walletAttentionLine(state),
        Strings.get(R.string.wallet_search_recovery_phrase), Strings.get(R.string.wallet_search_identity),
        Strings.get(R.string.wallet_search_back_up), GOOGLE_BACKUP_TITLE, Strings.get(R.string.wallet_search_restore),
    ),
)

/**
 * The status card's Backup line: where the phrase is kept, by what Google
 * backup actually did ([PhraseBackup.reconcile]) — including an entry of
 * this wallet kept from before with backup now off (#244 R2-F2).
 */
internal fun walletBackupDetail(cloudBackup: Boolean, known: PhraseBackup.Known?, walletAddress: String?): String {
    val held = backupHeld(cloudBackup, known, walletAddress)
    return Strings.get(
        when {
            cloudBackup && known?.status == PhraseBackup.Status.NONE -> R.string.wallet_where_google_missing
            // Written for the cloud, but whether it got there hangs on the phone's own Google
            // backup, which no app can see (#244 R5-F1).
            held == BackupHeld.CLOUD -> if (cloudBackup) {
                R.string.wallet_where_google_if_on
            } else {
                R.string.wallet_where_google_if_on_kept
            }
            held == BackupHeld.DEVICE -> if (cloudBackup) {
                R.string.wallet_where_google_paused
            } else {
                R.string.wallet_where_google_paused_kept
            }
            held == BackupHeld.UNKNOWN && cloudBackup -> R.string.wallet_where_google_unknown
            else -> R.string.wallet_where_phone_only
        },
    )
}

/**
 * Remove wallet's line for a backup it keeps, by where that backup is
 * (#244 R2-F1): a paused one is only in Google Play services here, and
 * even one written for the cloud is only off this phone if the phone's own
 * Google backup is on, which Freedom can't check (#244 R5-F1).
 */
internal fun removeWalletKeepsBackupText(backup: BackupHeld): String = Strings.get(
    when (backup) {
        BackupHeld.CLOUD -> R.string.wallet_remove_keeps_cloud
        BackupHeld.DEVICE -> R.string.wallet_remove_keeps_device
        BackupHeld.UNATTRIBUTED -> R.string.wallet_remove_keeps_unattributed
        BackupHeld.MAYBE_UNATTRIBUTED -> R.string.wallet_remove_keeps_maybe_unattributed
        BackupHeld.UNKNOWN -> R.string.wallet_remove_keeps_unknown
        BackupHeld.NONE -> R.string.wallet_remove_keeps_none
    },
)

/**
 * The Unreadable wallet card: with a Block Store entry there (#231),
 * restoring it is the way back. [googleBackupThere] null: Play services
 * hasn't said yet whether there is one (#244 R6-M1).
 */
internal fun unreadableWalletDetail(googleBackupThere: Boolean?): String = Strings.get(
    when (googleBackupThere) {
        true -> R.string.wallet_unreadable_detail_google_backup
        null -> R.string.wallet_unreadable_detail_google_unknown
        false -> R.string.wallet_unreadable_detail_no_google_backup
    },
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
    if (count == 0) return ImportHint.Neutral(Strings.get(R.string.wallet_import_hint_empty))
    val words = Mnemonic.words(phrase)
    val finished = if (phrase.last().isWhitespace()) words else words.dropLast(1)
    val unknown = finished.indexOfFirst { it !in bip39Words }
    if (unknown >= 0) {
        return ImportHint.Problem(Strings.get(R.string.wallet_import_hint_unknown_word_named, unknown + 1, finished[unknown]))
    }
    return when (val problem = Mnemonic.problemWith(phrase)) {
        null -> ImportHint.Valid(Strings.plural(R.plurals.wallet_import_hint_valid, count, count))
        is Mnemonic.Problem.WordCount -> ImportHint.Neutral(Strings.plural(R.plurals.wallet_import_hint_word_count, count, count))
        is Mnemonic.Problem.UnknownWord ->
            ImportHint.Problem(Strings.get(R.string.wallet_import_hint_unknown_word, problem.position))
        // 12 words that don't check out may be the first half of 24.
        Mnemonic.Problem.Checksum -> if (count < Mnemonic.CREATE_WORD_COUNT) {
            ImportHint.Neutral(Strings.plural(R.plurals.wallet_import_hint_checksum_partial, count, count))
        } else {
            ImportHint.Problem(Strings.plural(R.plurals.wallet_import_hint_checksum, count, count))
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
    val tokens = clipWords(clip)
    return (0..tokens.size - words.size).any { start -> tokens.subList(start, start + words.size) == words }
}

/** [text]'s words, letters only and normalized the way [Mnemonic.words] normalizes. */
internal fun clipWords(text: CharSequence): List<String> =
    Mnemonic.normalized(text).split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }

/**
 * Whether to clear the clipboard as the Import page lets go of what was
 * pasted into it (#241): at Import (whatever the authentication then
 * does), on Back, when the page goes away, or when Freedom loses focus.
 * - Nothing [pasted]: left alone, and never read.
 * - Not [readable] (no window focus, so it can't be looked at): cleared
 *   unread only if [clipIsPaste] — a phrase or a piece of one
 *   ([PastedPhrases.phrasePiece]) pasted is the last thing
 *   seen happening to it — and otherwise left alone: the clip isn't ours,
 *   so unlike [PhraseClipboard] nothing else is cleared blind.
 * - Its description says no text ([hasText] false): left alone, and its
 *   items never read.
 * - Otherwise an item's plain `text` (never `coerceToText`, which opens a
 *   `content:` URI on the main thread; [readTexts] is only called here)
 *   is cleared if it's one of the [pasted] texts — the same words, so
 *   something copied since is left alone — or, after a successful
 *   import, holds the [imported] phrase ([clipHoldsPhrase]).
 */
internal fun shouldClearPasted(
    readable: Boolean,
    hasText: Boolean,
    readTexts: () -> List<CharSequence?>,
    pasted: List<List<String>>,
    imported: List<String>? = null,
    clipIsPaste: Boolean = false,
): Boolean {
    if (pasted.isEmpty()) return false
    if (!readable) return clipIsPaste
    if (!hasText) return false
    return readTexts().any { text ->
        text != null && (clipWords(text).let { it.isNotEmpty() && it in pasted } ||
            (imported != null && clipHoldsPhrase(text, imported)))
    }
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
internal fun insertedLength(before: TextFieldValue, after: TextFieldValue): Int = insertedText(before, after).length

/**
 * The text an edit from [before] to [after] inserted ([insertedLength]):
 * what went in at the cursor (or over the selection) when the edit kept
 * everything either side of it, so a pasted phrase is had whole even
 * where it starts or ends with words already in the field.
 *
 * [committed] is the text a Paste or the keyboard was just seen putting
 * in ([PastedPhrases.committing]). A paste over a selection of the very
 * same text changes nothing but the selection, and by the values alone
 * looks exactly like deselecting by tapping at the end of the selection
 * (or End): so a same-text edit only counts when [committed] is that
 * text, never from where the cursor ends up.
 */
internal fun insertedText(before: TextFieldValue, after: TextFieldValue, committed: CharSequence? = null): String {
    val old = before.text
    val new = after.text
    val sel = before.selection
    val head = old.substring(0, sel.min.coerceIn(0, old.length))
    val tail = old.substring(sel.max.coerceIn(0, old.length))
    val replaced = new.length > head.length + tail.length && new.startsWith(head) && new.endsWith(tail)
    if (old == new) {
        if (!replaced || committed == null || sel.collapsed) return ""
        val same = new.substring(head.length, new.length - tail.length)
        return if (committed.toString() == same) same else ""
    }
    if (replaced) return new.substring(head.length, new.length - tail.length)
    val prefix = old.commonPrefixWith(new).length
    val suffix = old.substring(prefix).commonSuffixWith(new.substring(prefix)).length
    return new.substring(prefix, new.length - suffix)
}

/**
 * What was pasted into the Import page's field and may still be on the
 * clipboard (#241): the words of each paste, and whether a phrase — or a
 * piece of one ([phrasePiece]) — is the last thing seen happening to the
 * clipboard while Freedom had focus.
 *
 * A phrase-sized paste, or one of BIP-39 words only (a phrase pasted in
 * chunks, a line at a time — [phrasePiece]), sets [clipIsPaste].
 *
 * A paste is text of more than one word inserted at once ([add]). The
 * keyboard also inserts a whole word at once — a swiped word, an accepted
 * suggestion (other keyboards than Gboard, which offers neither on this
 * password-type field) — and that is no paste: counting it would read the
 * clipboard on the way out (Android's "pasted from your clipboard" notice)
 * for a phrase that was only typed. So a single word pasted on its own is
 * left on the clipboard; one word of a phrase is no phrase. Any other
 * phrase-sized text put in at once without passing through the clipboard
 * reads as a paste too: a phrase from a keyboard's own clipboard history,
 * an Autofill service filling a stored phrase (the field sets no content
 * type, but may still be offered to one), or a keyboard's voice input that
 * commits a whole utterance in one go. The field's edit alone can't tell
 * these from a paste, so losing focus then clears whatever is on the
 * clipboard unread, and Back reads it (Android's notice) to match the
 * words — the same trade-off [PhraseClipboard] makes at its deadline.
 *
 * What the field itself puts on the clipboard — a Copy or a Cut, from its
 * menu, the keyboard or a hardware shortcut, all through
 * [WatchedClipboard] — is noted as if it had been pasted ([copied]): it
 * is the field's text on the clipboard, taken off it on the way out like
 * a paste, unread on focus loss too.
 */
internal class PastedPhrases {
    val words = mutableListOf<List<String>>()
    var clipIsPaste = false

    /**
     * The text a Paste read off the clipboard, or the keyboard committed,
     * for the edit about to follow ([edit]); see [insertedText].
     */
    fun committing(text: CharSequence?) {
        committed = text?.toString()
    }

    /** Notes the edit from [before] to [after], if it was a paste ([insertedText], [add]). */
    fun edit(before: TextFieldValue, after: TextFieldValue) {
        val text = committed
        committed = null
        add(insertedText(before, after, text))
    }

    /** Notes [inserted], the text one edit put in the field ([insertedText]), if it was a paste. */
    fun add(inserted: String) {
        // Letters-only words, as the clipboard is matched: a swiped word with
        // the space the keyboard adds after it is one word, and a phrase
        // pasted with commas and no spaces still counts as the paste it is.
        val pasted = clipWords(inserted)
        if (pasted.size < 2) return
        words += pasted
        if (phrasePiece(pasted)) clipIsPaste = true
    }

    /**
     * The field put [text] on the clipboard (a Copy or a Cut), as the clip
     * whose description is stamped [timestamp] (`ClipDescription.getTimestamp`,
     * null if it couldn't be looked at): it's the last thing on the
     * clipboard now, noted as if pasted ([add]), and the change Android
     * reports for it ([clipChanged]) is no change from it.
     */
    fun copied(text: CharSequence?, timestamp: Long?) {
        ownClipAt = timestamp
        clipIsPaste = false
        add(text?.toString().orEmpty())
    }

    /**
     * The clipboard changed, to a clip stamped [timestamp] (null if it
     * couldn't be looked at): no paste is the last thing on it, unless it's
     * the field's own Copy or Cut ([copied]) — reported after it was
     * noted, and perhaps more than once.
     */
    fun clipChanged(timestamp: Long?) {
        if (timestamp != null && timestamp == ownClipAt) return
        clipIsPaste = false
    }

    private var committed: String? = null
    private var ownClipAt: Long? = null

    fun forget() {
        words.clear()
        clipIsPaste = false
        committed = null
        ownClipAt = null
    }

    companion object {
        val MIN_PHRASE_WORDS = Mnemonic.IMPORT_WORD_COUNTS.min()

        /**
         * Whether the [words] of one paste are a recovery phrase or a piece of
         * one, cleared unread on focus loss ([clipIsPaste]): phrase-sized, or
         * every word a BIP-39 word — a phrase pasted in chunks (two lines of
         * six words from a note) leaves its last chunk on the clipboard, and
         * that is part of the phrase just the same (R3-M1).
         */
        fun phrasePiece(words: List<String>): Boolean =
            words.size >= MIN_PHRASE_WORDS || (words.size >= 2 && words.all { it in bip39Words })
    }
}

/**
 * The Import field's clipboard ([LocalClipboard]): the field's own Paste
 * (menu, keyboard, hardware shortcut) reads through [getClipEntry], and its
 * Copy and Cut write through [setClipEntry], so [pastes] hears each where
 * it happens instead of guessing it from the text ([PastedPhrases.committing],
 * [PastedPhrases.copied]). Only the item's plain text is looked at, never
 * coerced from a `content:` URI. What the field copies or cuts is its
 * text — a recovery phrase — so it goes on the clipboard flagged sensitive
 * ([PhraseClipboard.markSensitive]), like the Backup page's Copy: no
 * plain-text preview in the system's copy overlay, and no copy in a
 * keyboard's clipboard history that clearing the clipboard can't reach.
 */
internal class WatchedClipboard(private val inner: Clipboard, private val pastes: PastedPhrases) : Clipboard {
    override val nativeClipboard: ClipboardManager get() = inner.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? =
        inner.getClipEntry().also { pastes.committing(it?.plainText()) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clipEntry?.clipData?.let(PhraseClipboard::markSensitive)
        inner.setClipEntry(clipEntry)
        val stamp = runCatching { nativeClipboard.primaryClipDescription?.timestamp }.getOrNull()
        pastes.copied(clipEntry?.plainText(), stamp)
    }

    private fun ClipEntry.plainText(): CharSequence? =
        clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
}

/**
 * Takes what was pasted into the import field off the clipboard if it's
 * still there (#75, #241); see [shouldClearPasted]. Reads nothing when
 * nothing was pasted, so the clipboard isn't read (and Android's "pasted
 * from your clipboard" notice doesn't show) for a phrase that was typed.
 * The description is looked at first, so a clip that isn't text never has
 * its items read. Whether it's done with [pastes]: the clipboard could be
 * looked at, or was cleared unread.
 */
private fun clearPastedFromClipboard(context: Context, pastes: PastedPhrases, imported: List<String>? = null): Boolean {
    if (pastes.words.isEmpty()) return true
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching {
        // Null both when the clipboard is empty and when it can't be read
        // (no window focus): an empty one is then looked at again for nothing.
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        val clear = shouldClearPasted(
            readable = description != null,
            hasText = description?.hasMimeType("text/*") == true,
            readTexts = {
                val clip = runCatching { clipboard.primaryClip }.getOrNull()
                // `text` only — never `coerceToText`, which opens a `content:` URI.
                clip?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).text } }.orEmpty()
            },
            pasted = pastes.words.toList(),
            imported = imported,
            clipIsPaste = pastes.clipIsPaste,
        )
        if (clear) clipboard.clearPrimaryClip()
        description != null || clear
    }.getOrDefault(false)
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
    is VaultAuthFailedException -> Strings.get(R.string.wallet_error_couldnt_reason, action, e.message)
    is VaultLockedException -> Strings.get(R.string.wallet_error_couldnt_locked, action)
    is TooManyAccountsException -> Strings.get(R.string.wallet_error_couldnt_reason_sentence, action, e.message)
    is DuplicateAccountException -> Strings.get(R.string.wallet_error_couldnt_duplicate_account, action)
    is VaultKeyLostException -> Strings.get(
        R.string.wallet_error_key_lost,
        lostWalletAdvice(phraseBackedUp, googleBackup, backupOwnerKnown, backupThereKnown),
    )
    is VaultUnreadableException -> Strings.get(
        R.string.wallet_error_unreadable,
        lostWalletAdvice(phraseBackedUp, googleBackup, backupOwnerKnown, backupThereKnown),
    )
    // Google backup (#231). None of these messages can carry the phrase.
    is BackupUnavailableException -> Strings.get(R.string.wallet_error_couldnt_reason_sentence, action, e.message)
    is BackupNotEncryptedException -> Strings.get(R.string.wallet_error_couldnt_not_encrypted, action)
    is BackupUnreadableException -> Strings.get(R.string.wallet_error_couldnt_backup_unreadable, action)
    is BackupMissingException -> Strings.get(R.string.wallet_error_couldnt_backup_missing, action)
    is RestoreNeedsScreenLockException -> Strings.get(R.string.wallet_error_couldnt_restore_needs_screen_lock, action)
    else -> Strings.get(R.string.wallet_error_couldnt_other, action, e.javaClass.simpleName)
}

private fun lostWalletAdvice(
    phraseBackedUp: Boolean,
    googleBackup: Boolean,
    ownerKnown: Boolean,
    thereKnown: Boolean,
) = Strings.get(
    if (googleBackup && !thereKnown) {
        if (phraseBackedUp) R.string.wallet_error_advice_google_unknown_or_import else R.string.wallet_error_advice_google_unknown
    } else if (googleBackup && !ownerKnown) {
        if (phraseBackedUp) R.string.wallet_error_advice_google_unattributed_or_import else R.string.wallet_error_advice_google_unattributed
    } else if (googleBackup) {
        R.string.wallet_error_advice_google
    } else if (phraseBackedUp) {
        R.string.wallet_error_advice_import
    } else {
        R.string.wallet_error_advice_never_shown
    },
)

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
    // Show private key (#323): the account it was opened for, fixed then, by address.
    var showingKeyOf by remember { mutableStateOf<String?>(null) }
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
            errorMessage = { e -> walletErrorMessage(e, Strings.get(R.string.wallet_action_show_phrase), phraseBackedUp) },
            onBack = { showingPhrase = false },
        )
        return
    }
    // The wallet went away (removed, or unreadable) with the page up: nothing to show.
    LaunchedEffect(stored == null) { if (stored == null) showingPhrase = false }

    // Looked up by the address it was opened for, never the active account: a switch
    // meanwhile must not change whose key the page shows. Gone from the list (or no key
    // here) closes it.
    val keyAccount = showingKeyOf?.let { address ->
        accountList?.accounts?.firstOrNull { it.hasLocalKey && it.address.equals(address, ignoreCase = true) }
    }
    if (keyAccount != null && stored != null) {
        PrivateKeyPage(
            account = keyAccount,
            protection = stored.protection,
            reveal = { vault.revealPrivateKey(auth, keyAccount) },
            errorMessage = { e -> walletErrorMessage(e, Strings.get(R.string.wallet_action_show_key), phraseBackedUp) },
            onBack = { showingKeyOf = null },
        )
        return
    }
    LaunchedEffect(showingKeyOf != null && (keyAccount == null || stored == null)) {
        if (keyAccount == null || stored == null) showingKeyOf = null
    }

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
                run(Strings.get(R.string.wallet_action_import)) {
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
    FullScreenScaffold(title = stringResource(R.string.wallet_title), onDismiss = dismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (request != null) item("request") {
                SectionCard(title = stringResource(R.string.wallet_needed_title)) {
                    Text(request.reason, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // Declines only the feature's request: the page stays
                        // if the user opened it themselves (Settings → Wallet),
                        // and closes with the request if the request opened it.
                        TextButton(onClick = { request.finish(false) }) { Text(stringResource(R.string.common_not_now)) }
                    }
                }
            }
            if (state == Vault.State.Empty && backupEntry == true) item("restore") {
                RestoreFromBackupSection(
                    busy = busy,
                    deviceSecure = deviceSecure,
                    onRestore = {
                        run(Strings.get(R.string.wallet_action_restore)) {
                            vault.restore(auth, phraseBackup)
                            backupCheck++
                        }
                    },
                    onDelete = { confirmBackupDelete = true },
                    noScreenLock = {
                        NoScreenLockWarning(text = stringResource(R.string.wallet_restore_no_screen_lock))
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
                            run(Strings.get(R.string.wallet_action_create)) {
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
                        onAction = { run(Strings.get(R.string.wallet_action_unlock)) { vault.unlock(auth) } },
                    )
                    is Vault.State.Unlocked -> StatusSection(
                        locked = false,
                        info = s.info,
                        backupKnown = backupKnown,
                        walletAddress = walletAddress,
                        busy = busy,
                        onAction = { vault.lock() },
                    )
                    Vault.State.Unreadable -> SectionCard(title = stringResource(R.string.wallet_title)) {
                        StatusLine(
                            icon = Icons.Filled.ErrorOutline,
                            color = Color(0xFFEF4444),
                            title = stringResource(R.string.wallet_summary_unreadable),
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
                run(Strings.get(R.string.wallet_action_turn_on_google_backup)) {
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
                        onNotNow = { run(Strings.get(R.string.wallet_action_save_answer)) { vault.markCloudBackupOffered() } },
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
                            onRetry = { run(Strings.get(R.string.wallet_action_find_accounts)) { walletAccounts.retry() } },
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
                            onSelect = { index -> run(Strings.get(R.string.wallet_action_switch_account)) { walletAccounts.select(index) } },
                            onAdd = {
                                run(Strings.get(R.string.wallet_action_add_account)) {
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
                            onShowKey = { account ->
                                error = null
                                showingKeyOf = account.address
                            },
                            onRemoveLedger = { account ->
                                run(Strings.get(R.string.wallet_action_remove_ledger_account)) {
                                    // Its sites first (#220 R2-M2): told they lost it now, and not
                                    // quietly reconnected if the same Ledger account is added again.
                                    if (!EthereumProviders.accountRemoved(context, account.address)) {
                                        error = Strings.get(R.string.wallet_error_remove_ledger_sites)
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
                SectionCard(title = stringResource(R.string.wallet_scan_section)) {
                    PageRow(
                        title = SCAN_TITLE,
                        subtitle = stringResource(R.string.wallet_scan_subtitle),
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
                SectionCard(title = stringResource(R.string.wallet_phrase_section)) {
                    PageRow(
                        title = stringResource(R.string.wallet_show_phrase),
                        subtitle = if (info.protection == VaultProtection.SCREEN_LOCK) {
                            stringResource(R.string.wallet_phrase_asks)
                        } else {
                            stringResource(R.string.wallet_phrase_opens_without_asking)
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
                SectionCard(title = stringResource(R.string.wallet_no_screen_lock_section)) {
                    NoScreenLockWarning(text = stringResource(R.string.wallet_no_screen_lock_detail))
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
                SectionCard(title = stringResource(R.string.wallet_publishing_section)) {
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
                SectionCard(title = stringResource(R.string.wallet_remove_section)) {
                    PageRow(
                        title = stringResource(R.string.wallet_remove_title),
                        subtitle = stringResource(R.string.wallet_remove_subtitle),
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
                    run(Strings.get(R.string.wallet_action_turn_off_google_backup)) {
                        vault.disableCloudBackup(phraseBackup)
                        backupCheck++
                    }
                } else {
                    run(Strings.get(R.string.wallet_action_delete_google_backup)) {
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
                run(Strings.get(R.string.wallet_action_remove)) {
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
                        error = Strings.get(R.string.wallet_removed_backup_not_deleted, it)
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
    PhraseBackup.DeleteIfOf.OTHER_WALLET -> Strings.get(R.string.wallet_removed_kept_other_wallets)
    PhraseBackup.DeleteIfOf.UNKNOWN -> Strings.get(R.string.wallet_removed_kept_unknown)
    else -> null
}

@Composable
private fun SetupSection(
    busy: Boolean,
    deviceSecure: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.wallet_setup_title)) {
        Text(
            stringResource(R.string.wallet_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.wallet_setup_write_down, stringResource(R.string.wallet_show_phrase)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!deviceSecure) {
            Spacer(Modifier.height(12.dp))
            NoScreenLockWarning(text = stringResource(R.string.wallet_setup_no_screen_lock))
            ScreenLockSettingsButton()
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.wallet_creating else R.string.wallet_create))
        }
        OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wallet_import_phrase_button))
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
    SectionCard(title = stringResource(R.string.wallet_title)) {
        if (locked) {
            StatusLine(
                icon = Icons.Filled.Lock,
                color = Color(0xFF94A3B8),
                title = stringResource(R.string.wallet_summary_locked),
                detail = if (info.protection == VaultProtection.SCREEN_LOCK) {
                    stringResource(R.string.wallet_locked_detail)
                } else {
                    stringResource(R.string.wallet_locked_detail_no_screen_lock)
                },
            )
        } else {
            StatusLine(
                icon = Icons.Filled.LockOpen,
                color = Color(0xFF22C55E),
                title = stringResource(R.string.wallet_summary_unlocked),
                detail = stringResource(R.string.wallet_unlocked_detail),
            )
        }
        Spacer(Modifier.height(8.dp))
        DetailRow(
            stringResource(R.string.wallet_detail_unlock_with),
            stringResource(
                if (info.protection == VaultProtection.SCREEN_LOCK) {
                    R.string.wallet_detail_unlock_with_screen_lock
                } else {
                    R.string.wallet_detail_unlock_with_nothing
                },
            ),
            singleLine = false,
        )
        DetailRow(
            stringResource(R.string.wallet_detail_key_kept_in),
            stringResource(if (info.strongBox) R.string.wallet_detail_key_strongbox else R.string.wallet_detail_key_keystore),
            singleLine = false,
        )
        DetailRow(
            stringResource(R.string.wallet_detail_backup),
            walletBackupDetail(info.cloudBackup, backupKnown, walletAddress),
            singleLine = false,
        )
        Spacer(Modifier.height(8.dp))
        if (locked) {
            Button(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.wallet_unlocking else R.string.wallet_unlock))
            }
        } else {
            OutlinedButton(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wallet_lock_now))
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
    SectionCard(title = stringResource(R.string.wallet_reminder_line)) {
        NoScreenLockWarning(text = backupReminderText(protection, googleBackup))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onShow, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wallet_back_up_now))
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
        }) { Text(stringResource(R.string.wallet_screen_lock_settings)) }
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
internal fun ImportPhrasePage(
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
    // The words of each paste (more than one word inserted at once, also
    // over a selection it replaced — [PastedPhrases.add]), in memory only: what may still be
    // on the clipboard (#241). Taken off it at Import — the words are in
    // the field now, whatever the authentication does — and, whatever else
    // happens (Back, the page closed from outside), when the page goes;
    // forgotten once the clipboard could be looked at, kept for the next
    // try while it couldn't. Freedom losing window focus with the page up
    // (Home, another app, the notification shade) is the one exit where it
    // can't be looked at: then it's cleared unread if a paste of a phrase,
    // or of a piece of one, is the last thing that happened to it
    // ([PastedPhrases.clipIsPaste]).
    val pastes = remember { PastedPhrases() }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val clearPasted = { imported: List<String>? ->
        if (clearPastedFromClipboard(context, pastes, imported)) pastes.forget()
    }
    DisposableEffect(clipboard) {
        // Only heard while Freedom has window focus (API 29+) — which is
        // why clipIsPaste is dropped once focus goes.
        // The field's own Copy or Cut is a clipboard change too, one that
        // puts its text on it ([PastedPhrases.copied]).
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            pastes.clipChanged(runCatching { clipboard?.primaryClipDescription?.timestamp }.getOrNull())
        }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboard?.removePrimaryClipChangedListener(listener)
            clearPasted(null)
        }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (!focused) {
                clearPasted(null)
                pastes.clipIsPaste = false
            }
        }
    }
    val back = {
        field = TextFieldValue("")
        onBack()
    }
    BackHandler(onBack = back)
    val hint = importHint(phrase)
    val submit = {
        if (!busy && hint is ImportHint.Valid) {
            runCatching { Mnemonic.parse(phrase) }.getOrNull()?.let { mnemonic ->
                // Before the authentication, which may be cancelled or fail.
                clearPasted(mnemonic.words)
                onImport(mnemonic) { field = TextFieldValue("") }
            }
        }
    }
    FullScreenScaffold(title = stringResource(R.string.wallet_import_title), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("field") {
                SectionCard(title = stringResource(R.string.wallet_phrase_section)) {
                    Text(
                        stringResource(R.string.wallet_import_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val appClipboard = LocalClipboard.current
                    val watched = remember(appClipboard) { WatchedClipboard(appClipboard, pastes) }
                    TabTextInput(private = true, onCommitText = pastes::committing) {
                        CompositionLocalProvider(LocalClipboard provides watched) {
                            OutlinedTextField(
                                value = field,
                                onValueChange = {
                                    pastes.edit(field, it)
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
                        NoScreenLockWarning(text = stringResource(R.string.wallet_import_no_screen_lock))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = submit,
                        enabled = !busy && hint is ImportHint.Valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(if (busy) R.string.wallet_importing else R.string.wallet_import_button)) }
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
        title = { Text(stringResource(R.string.wallet_remove_dialog_title)) },
        text = {
            Column {
                Text(
                    if (cloudBackup && deleteBackup) {
                        stringResource(
                            when {
                                backup == BackupHeld.MAYBE_UNATTRIBUTED -> R.string.wallet_remove_deletes_maybe_unattributed
                                unattributed -> R.string.wallet_remove_deletes_unattributed
                                // Deleted only once it's seen to be this wallet's (#244 R5-F2).
                                backup == BackupHeld.UNKNOWN -> R.string.wallet_remove_deletes_unknown
                                else -> R.string.wallet_remove_deletes_own
                            },
                        )
                    } else if (cloudBackup) {
                        removeWalletKeepsBackupText(backup)
                    } else {
                        stringResource(R.string.wallet_remove_keeps_none)
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .protectedToggle(tap, value = acknowledged, role = Role.Checkbox) { acknowledged = it },
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wallet_remove_acknowledge))
                }
                if (cloudBackup) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .protectedToggle(tap, value = deleteBackup, role = Role.Checkbox) { deleteBackup = it },
                    ) {
                        Checkbox(checked = deleteBackup, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                when {
                                    backup == BackupHeld.MAYBE_UNATTRIBUTED -> R.string.wallet_remove_also_delete_maybe_unattributed
                                    unattributed -> R.string.wallet_remove_also_delete_unattributed
                                    backup == BackupHeld.UNKNOWN -> R.string.wallet_remove_also_delete_unknown
                                    else -> R.string.wallet_remove_also_delete_own
                                },
                            ),
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
            ) { Text(stringResource(R.string.wallet_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * What "Copy" leaves on the clipboard, and for how long, in words the
 * page can show; also used by the unit test so the copy and
 * [PhraseClipboard.TTL_MS] can't drift apart.
 */
internal val COPY_NOTE: String get() = (PhraseClipboard.TTL_MS / 1000 / 60).toInt().let { minutes ->
    Strings.plural(R.plurals.wallet_copy_note, minutes, minutes)
}

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
    // soon as the minute is up and the words have been taken off — and
    // only for these words, not a removed wallet's (#334 R3-M2).
    val copiedLabel by PhraseClipboard.copiedLabel.collectAsState()
    val copiedHash by PhraseClipboard.copiedHash.collectAsState()
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

    FullScreenScaffold(title = stringResource(R.string.wallet_phrase_section), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("warning") {
                SectionCard(title = stringResource(R.string.wallet_phrase_keep_secret_title)) {
                    NoScreenLockWarning(text = stringResource(R.string.wallet_phrase_keep_secret))
                }
            }
            item("words") {
                val shown = words
                SectionCard(
                    title = if (shown != null) {
                        pluralText(R.plurals.wallet_phrase_your_words, shown.size, shown.size)
                    } else {
                        stringResource(R.string.wallet_phrase_hidden)
                    },
                ) {
                    if (shown == null) {
                        Text(
                            stringResource(
                                if (protection == VaultProtection.SCREEN_LOCK) {
                                    R.string.wallet_phrase_reveal_asks
                                } else {
                                    R.string.wallet_phrase_reveal_no_screen_lock
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { show() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) stringResource(R.string.wallet_waiting) else stringResource(R.string.wallet_show_phrase))
                        }
                    } else {
                        PhraseGrid(shown)
                        Spacer(Modifier.height(12.dp))
                        SheetButtonRow {
                            OutlinedButton(
                                onClick = {
                                    PhraseClipboard.copy(context, shown)
                                },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                CopyLabel(PhraseClipboard.holdsPhrase(copiedLabel, copiedHash, shown))
                            }
                            OutlinedButton(onClick = hide) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.wallet_hide))
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

/** What "Copy" on the private-key page leaves on the clipboard, and for how long; see [COPY_NOTE]. */
internal val KEY_COPY_NOTE: String get() = (PhraseClipboard.TTL_MS / 1000 / 60).toInt().let { minutes ->
    Strings.plural(R.plurals.wallet_key_copy_note, minutes, minutes)
}

/**
 * Show private key (#323): one account's key, for importing just that
 * account into another wallet, as desktop's wallet settings do — rather
 * than the whole phrase, which gives every account and identity away.
 * Only for an account whose key is derived here ([WalletAccount.hasLocalKey]);
 * a Ledger's never leaves the device, and Safes aren't wallet accounts.
 *
 * Held to the recovery phrase page's protections ([RecoveryPhrasePage]):
 * `FLAG_SECURE` ([SecureWindow]); hidden until Show, which asks for the
 * fingerprint, face or screen lock every time ([Vault.revealPrivateKey]
 * keeps nothing); the key in plain `remember` state only, never
 * `rememberSaveable`, dropped on Hide, Back and as soon as the app goes
 * to the background; not selectable (a selection's own Copy would skip
 * the timed, sensitive clip); Copy through [PhraseClipboard.copyKey].
 *
 * The warning says the key "on its own" opens this account only. Other
 * accounts sit under their own hardened `{index}'`, but Account 1's
 * `m/44'/60'/0'/0/0` shares a non-hardened parent with the Swarm node
 * key ([baby.freedom.mobile.wallet.NodeIdentity.SWARM_PATH]): the claim
 * holds only while that parent's xpub is never given out.
 */
@Composable
private fun PrivateKeyPage(
    account: WalletAccount,
    protection: VaultProtection,
    reveal: suspend () -> String,
    errorMessage: (Throwable) -> String?,
    onBack: () -> Unit,
) {
    SecureWindow()
    ReleaseCoveredFocus()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val copiedLabel by PhraseClipboard.copiedLabel.collectAsState()
    val copiedHash by PhraseClipboard.copiedHash.collectAsState()
    val hide = {
        key = null
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
                key = reveal()
            } catch (e: Throwable) {
                error = errorMessage(e)
                if (e is CancellationException) throw e
            } finally {
                busy = false
            }
        }
    }

    FullScreenScaffold(title = stringResource(R.string.wallet_key_title), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("warning") {
                SectionCard(title = stringResource(R.string.wallet_key_keep_secret_title)) {
                    NoScreenLockWarning(text = stringResource(R.string.wallet_key_keep_secret, accountLabel(account)))
                }
            }
            item("account") {
                SectionCard(title = stringResource(R.string.wallet_key_account)) {
                    Text(accountLabel(account), fontWeight = FontWeight.Medium)
                    AddressText(
                        account.address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        accountPathLine(account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("key") {
                val shown = key
                SectionCard(
                    title = stringResource(if (shown != null) R.string.wallet_key_shown else R.string.wallet_key_hidden),
                ) {
                    if (shown == null) {
                        Text(
                            stringResource(
                                if (protection == VaultProtection.SCREEN_LOCK) {
                                    R.string.wallet_key_reveal_asks
                                } else {
                                    R.string.wallet_key_reveal_no_screen_lock
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { show() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("wallet-key-reveal"),
                        ) {
                            Text(if (busy) stringResource(R.string.wallet_waiting) else stringResource(R.string.wallet_key_show))
                        }
                    } else {
                        Text(
                            shown,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.testTag("wallet-key-text"),
                        )
                        Spacer(Modifier.height(12.dp))
                        SheetButtonRow {
                            OutlinedButton(onClick = { PhraseClipboard.copyKey(context, shown) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                CopyLabel(PhraseClipboard.holdsKey(copiedLabel, copiedHash, shown))
                            }
                            OutlinedButton(onClick = hide) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.wallet_hide))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            KEY_COPY_NOTE,
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
 * "Copy", or "Copied" once tapped, in a slot always as wide as the wider
 * of the two: both are laid out, the unshown one invisible and silent.
 * [SheetButtonRow] picks side by side or stacked from the buttons' widths,
 * so a label that grew on the tap could flip the layout under the finger
 * (#279).
 */
@Composable
internal fun CopyLabel(copied: Boolean, modifier: Modifier = Modifier) {
    val hidden = Modifier.alpha(0f).clearAndSetSemantics {}
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(stringResource(R.string.wallet_copy), modifier = if (copied) hidden else Modifier)
        Text(stringResource(R.string.wallet_copied), modifier = if (copied) Modifier else hidden)
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
