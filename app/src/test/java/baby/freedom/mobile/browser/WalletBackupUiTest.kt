package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.BackupNotEncryptedException
import baby.freedom.mobile.wallet.BackupUnreadableException
import baby.freedom.mobile.wallet.PhraseBackup.Availability
import baby.freedom.mobile.wallet.PhraseBackup.Known
import baby.freedom.mobile.wallet.PhraseBackup.Status
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultProtection
import baby.freedom.mobile.wallet.VaultUnreadableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wallet page's Google backup copy and gates (#231). */
class WalletBackupUiTest {
    private companion object {
        const val A = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        const val B = "0x1111111111111111111111111111111111111111"
    }

    @Test
    fun `status line says where the backup is, or why it can't be on`() {
        assertEquals("On, if Android’s backup is on", googleBackupStatus(true, Availability.READY, Status.CLOUD))
        assertEquals("Paused – set a screen lock", googleBackupStatus(true, Availability.NOT_ENCRYPTED, Status.PAUSED, deviceSecure = false))
        // A screen lock is set: what's missing is the Google account.
        assertEquals("Paused – add a Google account", googleBackupStatus(true, Availability.NOT_ENCRYPTED, Status.PAUSED, deviceSecure = true))
        assertTrue(googleBackupStatus(true, Availability.READY, Status.NONE).contains("Missing"))
        assertTrue(googleBackupStatus(true, null, null).contains("not answering"))
        assertEquals("Off", googleBackupStatus(false, Availability.READY, null))
        assertEquals("Unavailable – set a screen lock", googleBackupStatus(false, Availability.NOT_ENCRYPTED, null, deviceSecure = false))
        assertEquals("Unavailable – add a Google account", googleBackupStatus(false, Availability.NOT_ENCRYPTED, null, deviceSecure = true))
        assertTrue(googleBackupStatus(false, Availability.UNSUPPORTED, null).contains("Google Play services"))
        // #244 R2-M1: not answering isn't "this phone doesn't have it".
        assertTrue(googleBackupStatus(false, Availability.NO_ANSWER, null).contains("not answering"))
        assertFalse(googleBackupStatus(false, Availability.NO_ANSWER, null).contains("no Google Play services"))
        assertFalse(googleBackupSwitchEnabled(false, Availability.NO_ANSWER, entryKnown = true))
        assertEquals("Checking…", googleBackupStatus(false, null, null))
    }

    @Test
    fun `every status line is six words or fewer`() {
        // #421: the long explanation is behind "How it works", not in the line.
        val lines = buildList {
            for (on in listOf(true, false)) {
                for (availability in Availability.entries.map { it as Availability? } + null) {
                    for (status in Status.entries.map { it as Status? } + null) {
                        for (entryKnown in listOf(true, false)) {
                            for (secure in listOf(true, false)) {
                                add(googleBackupStatus(on, availability, status, entryKnown, secure))
                            }
                        }
                    }
                }
            }
        }.distinct()
        for (line in lines) {
            val words = line.split(Regex("\\s+")).count { word -> word.any { it.isLetterOrDigit() } }
            assertTrue("\"$line\" has $words words", words <= 6)
        }
    }

    @Test
    fun `the status card's Backup line claims Google only when it's really there`() {
        assertEquals("This phone only", walletBackupDetail(false, null, A))
        assertEquals("This phone only", walletBackupDetail(false, Known(Status.CLOUD, B), A))
        assertEquals(
            "This phone, and Google if this phone’s Google backup is on (end-to-end encrypted)",
            walletBackupDetail(true, Known(Status.CLOUD, A), A),
        )
        assertEquals("This phone (Google backup paused)", walletBackupDetail(true, Known(Status.PAUSED, A), A))
        assertEquals("This phone (Google backup missing)", walletBackupDetail(true, Known(Status.NONE, null), A))
        assertFalse(walletBackupDetail(true, null, A).contains("encrypted"))
        // This wallet's entry kept from before, backup off (#244 R2-F2).
        assertTrue(walletBackupDetail(false, Known(Status.CLOUD, A.lowercase()), A).contains("kept from before"))
        assertTrue(walletBackupDetail(false, Known(Status.PAUSED, A), A).contains("paused"))
    }

    @Test
    fun `whose entry it is and where it holds the phrase`() {
        assertEquals(null, backupEntryIsThisWallet(false, null, A))
        assertEquals(false, backupEntryIsThisWallet(true, Known(Status.NONE, null), A))
        assertEquals(true, backupEntryIsThisWallet(true, Known(Status.CLOUD, B), A)) // on: only ever this wallet's
        assertEquals(true, backupEntryIsThisWallet(false, Known(Status.CLOUD, A), A))
        assertEquals(false, backupEntryIsThisWallet(false, Known(Status.CLOUD, B), A))
        assertEquals(null, backupEntryIsThisWallet(false, Known(Status.CLOUD, B), null))

        assertEquals(BackupHeld.CLOUD, backupHeld(true, Known(Status.CLOUD, A), A))
        assertEquals(BackupHeld.DEVICE, backupHeld(true, Known(Status.PAUSED, A), A))
        assertEquals(BackupHeld.NONE, backupHeld(true, Known(Status.NONE, null), A))
        assertEquals(BackupHeld.UNKNOWN, backupHeld(true, null, A))
        // Backup off and Block Store not heard from: a kept entry of this wallet may be
        // there, so Remove wallet must not say there's no copy, nor hide its delete box (#244 R3-F2).
        assertEquals(BackupHeld.UNKNOWN, backupHeld(false, null, A))
        assertEquals(BackupHeld.UNKNOWN, backupHeld(false, Known(Status.CLOUD, null), A))
        assertEquals(BackupHeld.UNKNOWN, backupHeld(false, Known(Status.CLOUD, B), null))
        assertEquals(BackupHeld.NONE, backupHeld(false, Known(Status.NONE, null), A))
        assertEquals(BackupHeld.CLOUD, backupHeld(false, Known(Status.CLOUD, A), A))
        assertEquals(BackupHeld.NONE, backupHeld(false, Known(Status.CLOUD, B), A))
    }

    @Test
    fun `Remove wallet promises a backup only where there is one`() {
        assertTrue(removeWalletKeepsBackupText(BackupHeld.CLOUD).contains("Google backup stays"))
        assertTrue(removeWalletKeepsBackupText(BackupHeld.DEVICE).contains("not in your Google account"))
        assertTrue(removeWalletKeepsBackupText(BackupHeld.UNKNOWN).contains("isn’t known"))
        assertTrue(removeWalletKeepsBackupText(BackupHeld.NONE).contains("no copy anywhere else"))
        assertFalse(removeWalletKeepsBackupText(BackupHeld.NONE).contains("restore"))
    }

    @Test
    fun `a kept entry is described by whose it is`() {
        assertNull(keptBackupNote(entryThere = false, thisWallet = null))
        assertTrue(keptBackupNote(entryThere = true, thisWallet = true)!!.contains("this wallet"))
        assertTrue(keptBackupNote(entryThere = true, thisWallet = false)!!.contains("different wallet"))
        assertTrue(keptBackupNote(entryThere = true, thisWallet = null)!!.contains("may be"))
    }

    @Test
    fun `the switch turns on only when encrypted, and off always`() {
        assertTrue(googleBackupSwitchEnabled(false, Availability.READY, entryKnown = true))
        assertFalse(googleBackupSwitchEnabled(false, Availability.NOT_ENCRYPTED, entryKnown = true))
        assertFalse(googleBackupSwitchEnabled(false, Availability.UNSUPPORTED, entryKnown = true))
        assertFalse(googleBackupSwitchEnabled(false, null, entryKnown = true))
        for (a in listOf(Availability.READY, Availability.NOT_ENCRYPTED, Availability.UNSUPPORTED, null)) {
            assertTrue(googleBackupSwitchEnabled(true, a, entryKnown = false))
        }
    }

    @Test
    fun `turning on waits until it's known whether another backup would be replaced`() {
        // #244 R4-F2: ready, but the entry not looked up yet — the "replaces it" note can't show yet.
        assertFalse(googleBackupSwitchEnabled(false, Availability.READY, entryKnown = false))
        assertEquals("Checking…", googleBackupStatus(false, Availability.READY, null, entryKnown = false))
        assertTrue(googleBackupStatus(false, Availability.READY, null, entryKnown = true).startsWith("Off"))
    }

    @Test
    fun `an unreadable wallet's kept backup isn't called its own`() {
        // #244 R4-F3: whose the entry is can't be told.
        val text = removeWalletKeepsBackupText(BackupHeld.UNATTRIBUTED)
        assertTrue(text.contains("may be this one or another"))
        assertFalse(text.contains("Its Google backup"))
        val msg = walletErrorMessage(
            VaultUnreadableException(), "unlock the wallet", phraseBackedUp = true, googleBackup = true,
            backupOwnerKnown = false,
        )!!
        assertTrue(msg.contains("may be this one or another"))
        assertFalse(msg.contains("Its Google backup"))
    }

    @Test
    fun `an unreadable wallet with Play services not answered claims no backup either way`() {
        // #244 R6-M1: not looked up yet (or timed out) is neither "no copy anywhere else" nor "there is one".
        assertEquals(BackupHeld.MAYBE_UNATTRIBUTED, unreadableBackupHeld(null))
        assertEquals(BackupHeld.UNATTRIBUTED, unreadableBackupHeld(true))
        assertEquals(BackupHeld.NONE, unreadableBackupHeld(false))
        val remove = removeWalletKeepsBackupText(BackupHeld.MAYBE_UNATTRIBUTED)
        assertTrue(remove.contains("isn’t known"))
        assertTrue(remove.contains("this one or another"))
        assertFalse(remove.contains("no copy anywhere else"))
        assertFalse(remove.contains("Its Google backup"))
        val detail = unreadableWalletDetail(googleBackupThere = null)
        assertTrue(detail.contains("hasn’t answered"))
        assertTrue(detail.contains("keep that backup"))
        val msg = walletErrorMessage(
            VaultUnreadableException(), "unlock the wallet", phraseBackedUp = true, googleBackup = true,
            backupOwnerKnown = false, backupThereKnown = false,
        )!!
        assertTrue(msg.contains("isn’t known whether"))
        assertTrue(msg.contains("keep that backup"))
        assertFalse(msg.contains("holds a Google backup of a wallet, which"))
    }

    @Test
    fun `the offer shows once, only where backup is possible`() {
        assertTrue(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = false, thisWallet = false))
        assertFalse(showGoogleBackupOffer(offered = true, on = false, availability = Availability.READY, entryThere = false, thisWallet = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = true, availability = Availability.READY, entryThere = false, thisWallet = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.NOT_ENCRYPTED, entryThere = false, thisWallet = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.UNSUPPORTED, entryThere = false, thisWallet = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = null, entryThere = false, thisWallet = false))
    }

    @Test
    fun `the offer never replaces another wallet's backup`() {
        // Another wallet's entry: Turn on would replace it.
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = true, thisWallet = false))
        // Not known yet (whether there's one, or whose): wait rather than offer.
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = null, thisWallet = null))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = true, thisWallet = null))
        // This wallet's own entry kept from before (#244 R2-F2): offering it rewrites the same phrase.
        assertTrue(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = true, thisWallet = true))
    }

    @Test
    fun `an unreadable wallet with a Google backup there points at restoring it`() {
        assertTrue(unreadableWalletDetail(googleBackupThere = true).contains("keep that backup"))
        assertFalse(unreadableWalletDetail(googleBackupThere = false).contains("Google"))
        val msg = walletErrorMessage(VaultUnreadableException(), "unlock the wallet", phraseBackedUp = true, googleBackup = true)!!
        assertTrue(msg.contains("keep that backup"))
    }

    @Test
    fun `the E2EE note explains the screen-lock dependency`() {
        assertTrue(GOOGLE_BACKUP_E2EE_NOTE.contains("screen lock"))
        assertTrue(GOOGLE_BACKUP_E2EE_NOTE.contains("can’t read it"))
    }

    @Test
    fun `Google backup never ends the not-backed-up reminder`() {
        // #244 R5-F1: an entry written for the cloud only gets there with the phone's own
        // Google backup on, which no app can check — the phone may hold the only copy.
        val fresh = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = false)
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh)))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh.copy(cloudBackup = true))))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Unlocked(fresh.copy(cloudBackup = true))))
        assertNull(walletAttentionLine(Vault.State.Locked(fresh.copy(cloudBackup = true, backedUp = true))))
        // The card says why Google backup isn't enough, and doesn't claim the phone is the only copy.
        val withGoogle = backupReminderText(VaultProtection.SCREEN_LOCK, googleBackup = true)
        assertTrue(withGoogle.contains("Freedom can’t check"))
        assertTrue(withGoogle.contains("may be gone for good"))
        assertTrue(backupReminderText(VaultProtection.SCREEN_LOCK, googleBackup = false).startsWith("This wallet exists only on this phone."))
    }

    @Test
    fun `nothing claims a backup written for the cloud is in the Google account`() {
        // #244 R5-F1: every CLOUD line names the phone's own Google backup as the condition.
        assertTrue(googleBackupStatus(true, Availability.READY, Status.CLOUD).contains("if Android’s backup is on"))
        assertTrue(walletBackupDetail(true, Known(Status.CLOUD, A), A).contains("if this phone’s Google backup is on"))
        assertTrue(walletBackupDetail(false, Known(Status.CLOUD, A), A).contains("if this phone’s Google backup is on"))
        val remove = removeWalletKeepsBackupText(BackupHeld.CLOUD)
        assertTrue(remove.contains("only if this phone’s Google backup is on"))
        assertTrue(remove.contains("may still lose this wallet"))
        assertTrue(GOOGLE_BACKUP_E2EE_NOTE.contains("Freedom can’t check it"))
    }

    @Test
    fun `a lost key with a Google backup points at keeping and restoring it`() {
        val msg = walletErrorMessage(VaultKeyLostException(), "unlock the wallet", phraseBackedUp = false, googleBackup = true)!!
        assertTrue(msg.contains("keep that backup"))
        assertFalse(msg.contains("can’t be restored"))
    }

    @Test
    fun `backup errors read as sentences`() {
        assertTrue(walletErrorMessage(BackupNotEncryptedException(), "turn on Google backup", true)!!.contains("screen lock"))
        assertTrue(walletErrorMessage(BackupUnreadableException(), "restore the wallet", true)!!.contains("can read"))
    }
}
