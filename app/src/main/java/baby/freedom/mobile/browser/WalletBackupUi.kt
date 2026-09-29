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
    }
}

/** Whether the switch can be flipped: off always (to delete), on only when the backup would be encrypted. */
internal fun googleBackupSwitchEnabled(on: Boolean, availability: PhraseBackup.Availability?) =
    on || availability == PhraseBackup.Availability.READY

/** Whether to show the one-time offer after create or import (#231). */
internal fun showGoogleBackupOffer(
    offered: Boolean,
    on: Boolean,
    availability: PhraseBackup.Availability?,
) = !offered && !on && availability == PhraseBackup.Availability.READY

/** The wallet page's Google backup section (#231): the switch, where the backup stands, and what it depends on. */
@Composable
internal fun GoogleBackupSection(
    on: Boolean,
    availability: PhraseBackup.Availability?,
    status: PhraseBackup.Status?,
    otherBackupThere: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    screenLockButton: @Composable () -> Unit,
) {
    val enabled = !busy && googleBackupSwitchEnabled(on, availability)
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
                    googleBackupStatus(on, availability, status),
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
        if (!on && otherBackupThere) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Google Play services already holds a backup of a different wallet from before. " +
                    "Turning backup on replaces it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
internal fun DeleteGoogleBackupDialog(turningOff: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
        title = { Text(if (turningOff) "Turn off Google backup?" else "Delete Google backup?") },
        text = {
            Text(
                if (turningOff) {
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
