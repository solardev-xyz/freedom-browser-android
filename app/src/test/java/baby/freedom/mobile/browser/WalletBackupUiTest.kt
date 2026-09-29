package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.BackupNotEncryptedException
import baby.freedom.mobile.wallet.BackupUnreadableException
import baby.freedom.mobile.wallet.PhraseBackup.Availability
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
        assertEquals("This phone only", walletBackupDetail(false, Status.CLOUD))
        assertEquals("This phone and Google (end-to-end encrypted)", walletBackupDetail(true, Status.CLOUD))
        assertEquals("This phone (Google backup paused)", walletBackupDetail(true, Status.PAUSED))
        assertEquals("This phone (Google backup missing)", walletBackupDetail(true, Status.NONE))
        assertFalse(walletBackupDetail(true, null).contains("encrypted"))
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
        assertTrue(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = false))
        assertFalse(showGoogleBackupOffer(offered = true, on = false, availability = Availability.READY, entryThere = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = true, availability = Availability.READY, entryThere = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.NOT_ENCRYPTED, entryThere = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.UNSUPPORTED, entryThere = false))
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = null, entryThere = false))
    }

    @Test
    fun `the offer never replaces another wallet's backup`() {
        // An entry there with this wallet's backup off is a different wallet's: Turn on would replace it.
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = true))
        // Not known yet: wait rather than offer.
        assertFalse(showGoogleBackupOffer(offered = false, on = false, availability = Availability.READY, entryThere = null))
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
    fun `with Google backup on, no not-backed-up reminder`() {
        val fresh = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = false)
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh)))
        assertNull(walletAttentionLine(Vault.State.Locked(fresh.copy(cloudBackup = true))))
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
