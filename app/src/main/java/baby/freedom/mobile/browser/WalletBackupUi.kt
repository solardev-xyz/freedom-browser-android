package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.wallet.PhraseBackup
import baby.freedom.mobile.wallet.WalletAccountList

internal const val GOOGLE_BACKUP_TITLE = "Google backup"

/**
 * How Block Store's end-to-end encryption hangs on the screen lock, in
 * the words every Google backup surface uses (#231: "explain that dependency").
 */
internal const val GOOGLE_BACKUP_E2EE_NOTE =
    "Google encrypts the backup on this phone with a key protected by your screen lock, so Google " +
        "can’t read it. Restoring it on a new phone asks for this phone’s PIN, pattern or password " +
        "while you set that phone up. Without a screen lock (or a Google account) there’s no " +
        "encryption, so no backup."

/**
 * The Google backup row's status line: whether it's on and, if so, where
 * the phrase actually is right now ([status], from
 * [PhraseBackup.reconcile]); if off, whether it can be turned on
 * ([availability]). Null inputs mean "not known yet".
 */
internal fun googleBackupStatus(
    on: Boolean,
    availability: PhraseBackup.Availability?,
    status: PhraseBackup.Status?,
    entryKnown: Boolean = true,
): String = if (on) {
    when (status) {
        PhraseBackup.Status.CLOUD -> "On · end-to-end encrypted in your Google account backup"
        PhraseBackup.Status.PAUSED -> "On, paused · this phone can’t end-to-end encrypt it now (no screen " +
            "lock or no Google account), so it’s kept on this phone only until it can"
        PhraseBackup.Status.NONE -> "On, but the backup is missing from Google Play services · turn it " +
            "off and on again to back up"
        null -> "On · Google Play services isn’t answering right now"
    }
} else {
    when (availability) {
        PhraseBackup.Availability.READY -> "Off · back up the recovery phrase to your Google account, " +
            "end-to-end encrypted"
        PhraseBackup.Availability.NOT_ENCRYPTED -> "Unavailable · needs a screen lock and a Google " +
            "account on this phone"
        PhraseBackup.Availability.UNSUPPORTED -> "Unavailable · needs Google Play services, which this " +
            "phone doesn’t have"
        null -> "Checking…"
    }.let { if (availability == PhraseBackup.Availability.READY && !entryKnown) "Checking…" else it }
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

    /** In the Google account's backup, end-to-end encrypted: a copy off this phone. */
    CLOUD,

    /**
     * Block Store holds an entry, but whose can't be told: the wallet on
     * this phone can't be read, so there's no address to compare it with
     * (#244 R4-F3). It may be this wallet's or another's.
     */
    UNATTRIBUTED,

    /**
     * Block Store hasn't said whether it holds this wallet's phrase (not
     * reconciled yet, Play services not answering, or whose the entry is
     * can't be told). With backup on, there should be one; with it off,
     * one kept from before may be there all the same (#244 R3-F2).
     */
    UNKNOWN,
}

/**
 * What the reminder, Remove wallet and the lost-key advice go by (#244
 * R2-F1): the reconciled entry ([known]) and whose it is, never the
 * vault's flag alone — backup on says nothing about an entry since
 * paused or gone.
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
    thisWallet == true -> "Google Play services still holds a backup of this wallet from before. Turn " +
        "backup on to keep it up to date here, or delete it."
    thisWallet == false -> "Google Play services already holds a backup of a different wallet from " +
        "before. Turning backup on replaces it."
    else -> "Google Play services already holds a wallet backup from before, which may be of a " +
        "different wallet. Turning backup on replaces it."
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
) {
    val enabled = !busy && googleBackupSwitchEnabled(on, availability, entryKnown = entryThere != null)
    SectionCard(title = GOOGLE_BACKUP_TITLE) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        ) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Back up recovery phrase", fontWeight = FontWeight.Medium)
                Text(
                    googleBackupStatus(on, availability, status, entryKnown = entryThere != null),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = null, enabled = enabled)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            GOOGLE_BACKUP_E2EE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                TextButton(onClick = onDeleteKept, enabled = !busy) { Text("Delete that backup") }
            }
        }
        if (availability == PhraseBackup.Availability.NOT_ENCRYPTED ||
            (on && status == PhraseBackup.Status.PAUSED)
        ) {
            screenLockButton()
        }
    }
}

/** The one-time offer after create or import (#231): off unless the user says so. */
@Composable
internal fun GoogleBackupOffer(busy: Boolean, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    SectionCard(title = "Back up with Google?") {
        Text(
            "Keep an end-to-end encrypted copy of this wallet’s recovery phrase in your Google " +
                "account backup, so a new phone can restore the wallet. It stays off unless you " +
                "turn it on, and you can turn it off at any time here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            GOOGLE_BACKUP_E2EE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onTurnOn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Turn on Google backup")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow, enabled = !busy) { Text("Not now") }
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
    SectionCard(title = "Restore from Google backup") {
        Text(
            "Google Play services holds a wallet backed up from Freedom — on this phone before, or " +
                "on the phone this one was set up from. Restore it to use that wallet here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!deviceSecure) {
            Spacer(Modifier.height(12.dp))
            noScreenLock()
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRestore, enabled = !busy && deviceSecure, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Restoring…" else "Restore wallet")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete, enabled = !busy) { Text("Delete backup") }
        }
    }
}

/** Turning Google backup off, or deleting a backup no wallet here uses: both delete the Block Store entry. */
@Composable
internal fun DeleteGoogleBackupDialog(
    turningOff: Boolean,
    /** The entry is this wallet's (or backup is being turned off): the wallet stays here either way. */
    walletStays: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
        title = { Text(if (turningOff) "Turn off Google backup?" else "Delete Google backup?") },
        text = {
            Text(
                if (turningOff || walletStays) {
                    "The backup is deleted from this phone now, and from your Google account at its " +
                        "next sync. The wallet stays on this phone."
                } else {
                    "The backed-up wallet is deleted from this phone now, and from your Google " +
                        "account at its next sync. Without its recovery phrase written down, " +
                        "that wallet is gone for good."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (turningOff) "Turn off" else "Delete backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
