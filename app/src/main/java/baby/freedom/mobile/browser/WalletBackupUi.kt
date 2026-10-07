package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.PhraseBackup
import baby.freedom.mobile.wallet.WalletAccountList

internal val GOOGLE_BACKUP_TITLE: String get() = Strings.get(R.string.wallet_backup_google_title)

/**
 * How Block Store's end-to-end encryption hangs on the screen lock, in
 * the words every Google backup surface uses (#231: "explain that
 * dependency") — and how its upload hangs on the phone's own Google
 * backup, which no app can check (#244 R5-F1).
 */
internal val GOOGLE_BACKUP_E2EE_NOTE: String get() = Strings.get(R.string.wallet_backup_e2ee_note)

/**
 * The Google backup row's status line, in six words or fewer (#421): whether
 * it's on and, if so, where the phrase actually is right now ([status], from
 * [PhraseBackup.reconcile]); if off, whether it can be turned on
 * ([availability]). Null inputs mean "not known yet". The why behind each
 * is under "How it works" ([GOOGLE_BACKUP_E2EE_NOTE]).
 *
 * Paused / not encrypted means no screen lock or no Google account: with
 * a screen lock set ([deviceSecure]) it's the account that's missing.
 */
internal fun googleBackupStatus(
    on: Boolean,
    availability: PhraseBackup.Availability?,
    status: PhraseBackup.Status?,
    entryKnown: Boolean = true,
    deviceSecure: Boolean = true,
): String = if (on) {
    when (status) {
        // Written for the cloud; it only gets there with the phone's own Google backup on,
        // which no app can see (#244 R5-F1) — so even the short line says so.
        PhraseBackup.Status.CLOUD -> Strings.get(R.string.wallet_backup_status_on_cloud)
        PhraseBackup.Status.PAUSED -> Strings.get(
            if (deviceSecure) R.string.wallet_backup_status_on_paused_account else R.string.wallet_backup_status_on_paused,
        )
        PhraseBackup.Status.NONE -> Strings.get(R.string.wallet_backup_status_on_missing)
        null -> Strings.get(R.string.wallet_backup_status_on_no_answer)
    }
} else {
    when (availability) {
        PhraseBackup.Availability.READY -> Strings.get(R.string.wallet_backup_status_off_ready)
        PhraseBackup.Availability.NOT_ENCRYPTED -> Strings.get(
            if (deviceSecure) {
                R.string.wallet_backup_status_unavailable_account
            } else {
                R.string.wallet_backup_status_unavailable_not_encrypted
            },
        )
        PhraseBackup.Availability.UNSUPPORTED -> Strings.get(R.string.wallet_backup_status_unavailable_unsupported)
        PhraseBackup.Availability.NO_ANSWER -> Strings.get(R.string.wallet_backup_status_unavailable_no_answer)
        null -> Strings.get(R.string.wallet_backup_status_checking)
    }.let {
        if (availability == PhraseBackup.Availability.READY && !entryKnown) {
            Strings.get(R.string.wallet_backup_status_checking)
        } else {
            it
        }
    }
}

/**
 * Whether the switch can be flipped: off always (to delete), on only when
 * the backup would be encrypted — and once it's known whether Block Store
 * already holds an entry ([entryKnown]), so the note that Turn on replaces
 * another wallet's backup is on screen before the switch can be tapped
 * (#244 R4-F2).
 */
internal fun googleBackupSwitchEnabled(on: Boolean, availability: PhraseBackup.Availability?, entryKnown: Boolean) =
    on || (availability == PhraseBackup.Availability.READY && entryKnown)

/**
 * Whether Block Store's entry ([PhraseBackup.known]) is the wallet on this
 * phone: true with Google backup [on] and an entry there (only this
 * wallet's phrase is ever stored while it's on), else by comparing the
 * entry's account-0 address with the wallet's own ([walletAddress]) — an
 * entry kept after Remove wallet and the same phrase imported again is
 * this wallet's (#244 R2-F2). False with no entry; null when that can't
 * be told (not reconciled yet, the entry unreadable, or the wallet's
 * address not known yet).
 */
internal fun backupEntryIsThisWallet(on: Boolean, known: PhraseBackup.Known?, walletAddress: String?): Boolean? = when {
    known == null -> null
    known.status == PhraseBackup.Status.NONE -> false
    on -> true
    known.address == null || walletAddress == null -> null
    else -> known.address.equals(walletAddress, ignoreCase = true)
}

/** Where Google backup holds this wallet's phrase right now, as far as the page can tell. */
internal enum class BackupHeld {
    /** No copy of this wallet in Block Store (none there, or another wallet's). */
    NONE,

    /** In Block Store on this phone only: backup is paused (no end-to-end encryption now). */
    DEVICE,

    /**
     * Written for the Google account's backup, end-to-end encrypted. Not
     * proof of a copy off this phone: Block Store only uploads it with the
     * phone's own Google backup, which may be off and which no app can
     * check (#244 R5-F1). So it never stands in for the phrase written down.
     */
    CLOUD,

    /**
     * Block Store holds an entry, but whose can't be told: the wallet on
     * this phone can't be read, so there's no address to compare it with
     * (#244 R4-F3). It may be this wallet's or another's.
     */
    UNATTRIBUTED,

    /**
     * The wallet on this phone can't be read, and Block Store hasn't said
     * yet whether it holds an entry at all (not looked up yet, or Play
     * services not answering). There may be one, of this wallet or
     * another; nothing may be claimed either way (#244 R6-M1).
     */
    MAYBE_UNATTRIBUTED,

    /**
     * Block Store hasn't said whether it holds this wallet's phrase (not
     * reconciled yet, Play services not answering, or whose the entry is
     * can't be told). With backup on, there should be one; with it off,
     * one kept from before may be there all the same (#244 R3-F2).
     */
    UNKNOWN,
}

/**
 * What Remove wallet, the Backup line and the lost-key advice go by (#244
 * R2-F1): the reconciled entry ([known]) and whose it is, never the
 * vault's flag alone — backup on says nothing about an entry since
 * paused or gone. Not the not-backed-up reminder: even [BackupHeld.CLOUD]
 * doesn't prove a copy off the phone (#244 R5-F1).
 */
internal fun backupHeld(on: Boolean, known: PhraseBackup.Known?, walletAddress: String?): BackupHeld =
    when (backupEntryIsThisWallet(on, known, walletAddress)) {
        true -> if (known?.status == PhraseBackup.Status.CLOUD) BackupHeld.CLOUD else BackupHeld.DEVICE
        false -> BackupHeld.NONE
        // Not "none" with backup off either: an entry of this wallet kept after Remove
        // wallet may be there, and Remove wallet must neither deny it nor hide its
        // delete box while Play services is slow to say (#244 R3-F2).
        null -> BackupHeld.UNKNOWN
    }

/**
 * [BackupHeld] for a wallet that can't be read (#244 R4-F3, R6-M1): no
 * address to compare with, so an entry there ([entryThere]) is of *a*
 * wallet, and one not looked up yet (null) may or may not be there — never
 * "no copy anywhere else".
 */
internal fun unreadableBackupHeld(entryThere: Boolean?): BackupHeld = when (entryThere) {
    true -> BackupHeld.UNATTRIBUTED
    false -> BackupHeld.NONE
    null -> BackupHeld.MAYBE_UNATTRIBUTED
}

/** Account 0 of the wallet's own seed: what a Block Store entry's phrase is compared against. */
internal fun walletSeedAddress(list: WalletAccountList?): String? =
    list?.accounts?.firstOrNull { it.index == 0 && it.ledger == null }?.address

/**
 * Whether to show the one-time offer after create or import (#231).
 * Not while Block Store holds another wallet's entry, or one whose owner
 * isn't known yet ([entryThere] or [thisWallet] null): with backup off,
 * Turn on would replace it — the settings section says so, a one-tap
 * offer can't. The offer stays unanswered, so it shows once that entry is
 * gone. An entry that is this wallet (kept after Remove wallet, then the
 * phrase imported again) is no reason to hold it back: Turn on rewrites
 * the same phrase.
 */
internal fun showGoogleBackupOffer(
    offered: Boolean,
    on: Boolean,
    availability: PhraseBackup.Availability?,
    entryThere: Boolean?,
    thisWallet: Boolean?,
) = !offered && !on && availability == PhraseBackup.Availability.READY &&
    (entryThere == false || thisWallet == true)

/**
 * The settings section's note on an entry Google backup (off) didn't
 * write for this wallet's current setting: this wallet's own, kept from
 * before, or another's that Turn on would replace. Null with no entry.
 */
internal fun keptBackupNote(entryThere: Boolean, thisWallet: Boolean?): String? = when {
    !entryThere -> null
    thisWallet == true -> Strings.get(R.string.wallet_backup_kept_this_wallet)
    thisWallet == false -> Strings.get(R.string.wallet_backup_kept_other_wallet)
    else -> Strings.get(R.string.wallet_backup_kept_unknown_wallet)
}

/** The wallet page's Google backup section (#231): the switch, where the backup stands, and what it depends on. */
@Composable
internal fun GoogleBackupSection(
    on: Boolean,
    availability: PhraseBackup.Availability?,
    status: PhraseBackup.Status?,
    entryThere: Boolean?,
    thisWallet: Boolean?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onDeleteKept: () -> Unit,
    screenLockButton: @Composable () -> Unit,
    deviceSecure: Boolean = true,
) {
    val enabled = !busy && googleBackupSwitchEnabled(on, availability, entryKnown = entryThere != null)
    SectionCard(title = stringResource(R.string.wallet_backup_google_title)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        ) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wallet_backup_switch_label), fontWeight = FontWeight.Medium)
                Text(
                    googleBackupStatus(on, availability, status, entryKnown = entryThere != null, deviceSecure = deviceSecure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = null, enabled = enabled)
        }
        HowItWorks()
        val kept = if (on) null else keptBackupNote(entryThere == true, thisWallet)
        if (kept != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                kept,
                style = MaterialTheme.typography.bodySmall,
                color = if (thisWallet == true) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            // The only way to remove a kept entry while a wallet is here (#244 R2-F3).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDeleteKept, enabled = !busy) { Text(stringResource(R.string.wallet_backup_delete_kept)) }
            }
        }
        if (availability == PhraseBackup.Availability.NOT_ENCRYPTED ||
            (on && status == PhraseBackup.Status.PAUSED)
        ) {
            screenLockButton()
        }
    }
}

/**
 * Google backup's long note ([GOOGLE_BACKUP_E2EE_NOTE]) behind "How it
 * works" (#421): the status line stays short, the dependencies are a tap away.
 */
@Composable
private fun HowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (open) R.string.wallet_backup_how_it_works_hide else R.string.wallet_backup_how_it_works))
    }
    if (open) {
        Text(
            GOOGLE_BACKUP_E2EE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The one-time offer after create or import (#231): off unless the user says so. */
@Composable
internal fun GoogleBackupOffer(busy: Boolean, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_backup_offer_title)) {
        Text(
            stringResource(R.string.wallet_backup_offer_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        HowItWorks()
        Spacer(Modifier.height(4.dp))
        Button(onClick = onTurnOn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wallet_backup_offer_turn_on))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow, enabled = !busy) { Text(stringResource(R.string.common_not_now)) }
        }
    }
}

/**
 * No wallet, but Block Store holds one (#231): a fresh install, or a new
 * phone restored from this one. Restoring asks for the screen lock, so
 * it's only offered with one set.
 */
@Composable
internal fun RestoreFromBackupSection(
    busy: Boolean,
    deviceSecure: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    noScreenLock: @Composable () -> Unit,
) {
    SectionCard(title = stringResource(R.string.wallet_backup_restore_title)) {
        Text(
            stringResource(R.string.wallet_backup_restore_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!deviceSecure) {
            Spacer(Modifier.height(12.dp))
            noScreenLock()
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRestore, enabled = !busy && deviceSecure, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.wallet_backup_restoring else R.string.wallet_backup_restore_button))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.wallet_backup_delete)) }
        }
    }
}

/**
 * Turning Google backup off, or deleting a backup no wallet here uses:
 * both delete the Block Store entry.
 *
 * Against tapjacking (#240, #287 R2-M1), like Remove wallet's own
 * delete-the-backup tick: the confirm button ignores taps for the
 * dialog's first [PromptTapGuard.PROTECTION_MS], so a second tap on
 * "Delete backup" / "Turn off" can't land on it as it appears, and drops
 * a press another app's window covered ([protectedPress]); other apps'
 * overlays are hidden while it's up (Android 12+).
 */
@Composable
internal fun DeleteGoogleBackupDialog(
    turningOff: Boolean,
    /** The entry is this wallet's (or backup is being turned off): the wallet stays here either way. */
    walletStays: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tap = rememberArmedTapGuard(turningOff)
    val guard = tap.guard
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (turningOff) R.string.wallet_backup_dialog_turn_off_title else R.string.wallet_backup_dialog_delete_title,
                ),
            )
        },
        text = {
            Column {
                Text(
                    if (turningOff || walletStays) {
                        stringResource(R.string.wallet_backup_dialog_wallet_stays)
                    } else {
                        stringResource(R.string.wallet_backup_dialog_wallet_gone)
                    },
                )
                ObscuredTapNotice(tap)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (guard.accepts()) onConfirm() },
                enabled = tap.armed,
                modifier = Modifier.protectedPress(tap),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (turningOff) R.string.wallet_backup_dialog_turn_off else R.string.wallet_backup_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
