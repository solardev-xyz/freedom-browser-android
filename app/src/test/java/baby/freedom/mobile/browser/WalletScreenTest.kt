package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthCancelledException
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultProtection
import baby.freedom.mobile.wallet.VaultUnreadableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletScreenTest {
    private val twelve = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `import hint walks through typing a phrase`() {
        assertTrue(importHint("") is ImportHint.Neutral)
        // A half-typed word isn't flagged…
        assertTrue(importHint("aband") is ImportHint.Neutral)
        // …a finished one that isn't on the list is, by position.
        val bad = importHint("abandon notaword ")
        assertTrue(bad is ImportHint.Problem)
        assertTrue(bad.text.startsWith("Word 2"))
        assertEquals("3 words", importHint("abandon abandon abandon").text)
        assertTrue(importHint(twelve) is ImportHint.Valid)
        assertEquals("Valid 12-word recovery phrase", importHint("  $twelve\n").text)
        // 12 words failing the checksum may be half of 24: not red yet.
        assertTrue(importHint(twelve.replace("about", "abandon")) is ImportHint.Neutral)
        assertTrue(importHint(List(24) { "abandon" }.joinToString(" ")) is ImportHint.Problem)
    }

    @Test
    fun `settings row states and the reminder that stays under it`() {
        val fresh = Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = true, backedUp = false)
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Locked(fresh)))
        assertEquals(BACKUP_REMINDER, walletAttentionLine(Vault.State.Unlocked(fresh)))
        assertNull(walletAttentionLine(Vault.State.Unlocked(fresh.copy(backedUp = true))))
        assertEquals(
            "No screen lock protects this wallet",
            walletAttentionLine(Vault.State.Locked(fresh.copy(protection = VaultProtection.DEVICE_ONLY, backedUp = true))),
        )
        assertNull(walletAttentionLine(Vault.State.Empty))
        assertEquals("Locked", walletSummary(Vault.State.Locked(fresh)))
        // Findable from settings search by the reminder, and by "recovery phrase".
        val rows = walletSettingsRows(Vault.State.Locked(fresh))
        assertEquals(setOf(WALLET_ROW_KEY), visibleSettingsRows("back up", "Wallet", rows))
        assertEquals(setOf(WALLET_ROW_KEY), visibleSettingsRows("recovery", "Wallet", rows))
    }

    @Test
    fun `a cancelled prompt says nothing, a lost key says what to do`() {
        assertNull(walletErrorMessage(VaultAuthCancelledException(), "unlock the wallet", phraseBackedUp = true))
        assertTrue(
            walletErrorMessage(VaultKeyLostException(), "unlock the wallet", phraseBackedUp = true)!!
                .contains("import your recovery phrase"),
        )
        // A created wallet whose phrase was never shown: no phrase to re-import.
        val neverShown = walletErrorMessage(VaultKeyLostException(), "unlock the wallet", phraseBackedUp = false)!!
        assertFalse(neverShown.contains("import"))
        assertTrue(neverShown.contains("can’t be restored"))
        assertFalse(walletErrorMessage(VaultUnreadableException(IllegalStateException()), "unlock the wallet", false)!!.contains("import"))
    }

    @Test
    fun `an imported phrase is found on the clipboard, and nothing else is`() {
        val words = twelve.split(" ")
        assertTrue(clipHoldsPhrase(twelve, words))
        assertTrue(clipHoldsPhrase("  ${twelve.uppercase()}\n", words))
        assertTrue(clipHoldsPhrase("My phrase: $twelve. Keep it safe!", words))
        assertTrue(clipHoldsPhrase(twelve.replace(" ", "\n"), words))
        assertFalse(clipHoldsPhrase("https://example.com", words))
        assertFalse(clipHoldsPhrase(twelve.substringBeforeLast(" "), words))
        assertFalse(clipHoldsPhrase(twelve.replace("about", "abandon"), words))
        assertFalse(clipHoldsPhrase(null, words))
        assertFalse(clipHoldsPhrase("", words))
    }
}
