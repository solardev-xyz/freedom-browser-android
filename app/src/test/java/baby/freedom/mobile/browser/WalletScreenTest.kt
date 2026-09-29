package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.Mnemonic
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthCancelledException
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultProtection
import baby.freedom.mobile.wallet.VaultUnreadableException
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    fun `import hint splits on the same whitespace as the parser`() {
        // Separators a paste from a web page or note can carry.
        for (sep in listOf("\u00A0", "\u2009", "\u202F", "\u3000", "\t", "\n")) {
            val phrase = twelve.replaceFirst(" ", sep)
            assertNull(baby.freedom.mobile.wallet.Mnemonic.problemWith(phrase))
            assertTrue("separator U+%04X".format(sep[0].code), importHint(phrase) is ImportHint.Valid)
        }
        // A word ended by a no-break space counts as finished.
        val bad = importHint("abandon notaword\u00A0")
        assertTrue(bad is ImportHint.Problem)
        assertTrue(bad.text.startsWith("Word 2"))
        assertEquals("2 words", importHint("abandon\u3000abandon").text)
    }

    @Test
    fun `a paste is seen even when it replaces a selection`() {
        fun at(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))
        fun all(text: String) = TextFieldValue(text, TextRange(0, text.length))
        // Typing and deleting aren't pastes.
        assertEquals(1, insertedLength(at("aban"), at("aband")))
        assertEquals(0, insertedLength(at("abandon"), at("abando")))
        assertEquals(0, insertedLength(at("abandon"), at("abandon", 2)))
        // A letter typed over a one-letter selection.
        assertEquals(1, insertedLength(TextFieldValue("abandon", TextRange(0, 1)), at("xbandon", 1)))
        // A plain paste into an empty field or at the cursor.
        assertEquals(twelve.length, insertedLength(at(""), at(twelve)))
        assertEquals(8, insertedLength(at("abandon "), at("abandon abandon ")))
        // Select all over a mistyped phrase, paste one of the same length —
        // even one differing by a single letter.
        val typo = twelve.replace("about", "abouf")
        assertEquals(twelve.length, insertedLength(all(typo), at(twelve)))
        // Selecting all and deleting isn't a paste.
        assertEquals(0, insertedLength(all(typo), at("")))
        // Repeated text at the seam isn't double-counted.
        assertEquals(8, insertedLength(at("abandon abandon"), at("abandon abandon abandon")))
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
        // Compatibility letters (fullwidth) are normalized the way the parser does.
        val fullwidth = twelve.map { if (it in 'a'..'z') (it.code - 'a'.code + 0xFF41).toChar() else it }.joinToString("")
        assertTrue(clipHoldsPhrase(fullwidth, Mnemonic.words(fullwidth)))
        assertTrue(clipHoldsPhrase(fullwidth, words))
        // Copied from hyphenated web text: a soft hyphen inside a word.
        val hyphenated = twelve.replace("about", "abo\u00ADut").replaceFirst("abandon", "aban\u00ADdon")
        assertEquals(twelve.split(" "), Mnemonic.words(hyphenated))
        assertTrue(clipHoldsPhrase(hyphenated, words))
        assertFalse(clipHoldsPhrase(null, words))
        assertFalse(clipHoldsPhrase("", words))
    }

    @Test
    fun `a copied phrase is recognised on the clipboard by its hash`() {
        val words = twelve.split(" ")
        val hash = PhraseClipboard.phraseHash(words)
        assertTrue(PhraseClipboard.clipIsPhrase(twelve, hash))
        // Spacing and letter forms a paste can pick up don't matter…
        assertTrue(PhraseClipboard.clipIsPhrase("  " + twelve.replace(" ", "\u00A0") + "\n", hash))
        assertTrue(PhraseClipboard.clipIsPhrase(twelve.uppercase(), hash))
        // …but something else the user copied since is left alone.
        assertFalse(PhraseClipboard.clipIsPhrase(twelve.replace("about", "abandon"), hash))
        assertFalse(PhraseClipboard.clipIsPhrase("https://example.com", hash))
        assertFalse(PhraseClipboard.clipIsPhrase("", hash))
        assertFalse(PhraseClipboard.clipIsPhrase(null, hash))
        // At the deadline: a readable clipboard is cleared only if it still
        // holds the phrase; an unreadable one (no focus) or a process that
        // lost the hash clears outright, so the words never outlive the minute.
        assertTrue(PhraseClipboard.shouldClear(listOf(twelve), hash))
        assertTrue(PhraseClipboard.shouldClear(listOf("https://example.com", twelve), hash))
        assertFalse(PhraseClipboard.shouldClear(listOf("https://example.com"), hash))
        assertFalse(PhraseClipboard.shouldClear(emptyList(), hash))
        assertTrue(PhraseClipboard.shouldClear(null, hash))
        assertTrue(PhraseClipboard.shouldClear(listOf("https://example.com"), null))
        // The note on the page matches the timer.
        assertEquals(60_000L, PhraseClipboard.TTL_MS)
        assertTrue(COPY_NOTE.contains("after 1 minute, or up to a minute later"))
    }
}
