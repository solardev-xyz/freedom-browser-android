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
        assertTrue(googleBackupStatus(true, Availability.READY, Status.CLOUD).startsWith("On · end-to-end encrypted"))
        assertTrue(googleBackupStatus(true, Availability.NOT_ENCRYPTED, Status.PAUSED).startsWith("On, paused"))
        assertTrue(googleBackupStatus(true, Availability.READY, Status.NONE).contains("missing"))
        assertTrue(googleBackupStatus(true, null, null).contains("isn’t answering"))
        assertTrue(googleBackupStatus(false, Availability.READY, null).startsWith("Off"))
        assertTrue(googleBackupStatus(false, Availability.NOT_ENCRYPTED, null).contains("screen lock"))
        assertTrue(googleBackupStatus(false, Availability.UNSUPPORTED, null).contains("Google Play services"))
        assertEquals("Checking…", googleBackupStatus(false, null, null))
    }

    @Test
    fun `the status card's Backup line claims Google only when it's really there`() {
        assertEquals("This phone only", walletBackupDetail(false, null, A))
        assertEquals("This phone only", walletBackupDetail(false, Known(Status.CLOUD, B), A))
        assertEquals("This phone and Google (end-to-end encrypted)", walletBackupDetail(true, Known(Status.CLOUD, A), A))
        assertEquals("This phone (Google backup paused)", walletBackupDetail(true, Known(Status.PAUSED, A), A))
        assertEquals("This phone (Google backup missing)", walletBackupDetail(true, Known(Status.NONE, null), A))
        assertFalse(walletBackupDetail(true, null, A).contains("encrypted"))
        // This wallet's entry kept from before, backup off (#244 R2-F2): it is in Google.
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
        assertEquals(BackupHeld.NONE, backupHeld(false, null, A))
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
        assertTrue(googleBackupSwitchEnabled(false, Availability.READY))
        assertFalse(googleBackupSwitchEnabled(false, Availability.NOT_ENCRYPTED))
        assertFalse(googleBackupSwitchEnabled(false, Availability.UNSUPPORTED))
        assertFalse(googleBackupSwitchEnabled(false, null))
        for (a in listOf(Availability.READY, Availability.NOT_ENCRYPTED, Availability.UNSUPPORTED, null)) {
            assertTrue(googleBackupSwitchEnabled(true, a))
        }
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
    fun `the not-backed-up reminder goes only while Google really holds this wallet`() {
        val fresh = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = false)
        val on = Vault.State.Locked(fresh.copy(cloudBackup = true))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh)))
        assertNull(walletAttentionLine(on, Known(Status.CLOUD, A), A))
        // #244 R2-F1: backup on, but paused (device-only), gone, or not known: the phone is the only copy.
        assertEquals(BACKUP_REMINDER, walletAttentionLine(on, Known(Status.PAUSED, A), A))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(on, Known(Status.NONE, null), A))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(on))
        // Backup off, but this wallet's entry kept from before is in Google; another wallet's isn't.
        assertNull(walletAttentionLine(Vault.State.Locked(fresh), Known(Status.CLOUD, A), A))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh), Known(Status.CLOUD, B), A))
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
