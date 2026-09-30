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
    fun `a paste is had whole, even where it shares words with the field`() {
        fun at(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))
        // The first word typed, the whole phrase pasted after it by mistake:
        // what went in at the cursor, not what a shared prefix leaves over.
        assertEquals(twelve, insertedText(at("abandon "), at("abandon $twelve", "abandon $twelve".length)))
        // Pasted at the start, in front of words it ends with.
        assertEquals("$twelve ", insertedText(at("about", 0), at("$twelve about", twelve.length + 1)))
        assertEquals("", insertedText(at("abandon"), at("abando")))
    }

    @Test
    fun `a phrase pasted over the same phrase selected is still a paste (#241)`() {
        fun at(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))
        fun all(text: String) = TextFieldValue(text, TextRange(0, text.length))
        // Select all, paste the identical phrase: only the selection changes,
        // and the Paste was seen putting that text in.
        assertEquals(twelve, insertedText(all(twelve), at(twelve), committed = twelve))
        // Part of it selected and pasted over with the same words.
        val middle = TextFieldValue(twelve, TextRange(8, 24))
        assertEquals(twelve.substring(8, 24), insertedText(middle, at(twelve, 24), committed = twelve.substring(8, 24)))
        // Merely selecting, moving the cursor or narrowing a selection isn't one.
        assertEquals("", insertedText(at(twelve), all(twelve)))
        assertEquals("", insertedText(all(twelve), at(twelve, 0)))
        assertEquals("", insertedText(all(twelve), at(twelve, 30)))
        assertEquals("", insertedText(all(twelve), TextFieldValue(twelve, TextRange(0, 30))))
        assertEquals("", insertedText(at(twelve), at(twelve, 3)))
        // R3-F1: deselecting at the end (a tap there, End) looks the same as
        // that paste, but no paste was seen: nothing went in.
        assertEquals("", insertedText(all(twelve), at(twelve)))
        assertEquals("", insertedText(middle, at(twelve, 24)))
        // Something else committed (a letter) is not that text.
        assertEquals("", insertedText(all(twelve), at(twelve), committed = "a"))
        // A typed phrase selected and deselected at its end: no paste noted.
        val pastes = PastedPhrases()
        pastes.edit(at(twelve), all(twelve))
        pastes.edit(all(twelve), at(twelve))
        assertTrue(pastes.words.isEmpty())
        assertFalse(pastes.clipIsPaste)
        // The same with a real Paste of it: noted.
        pastes.edit(at(twelve), all(twelve))
        pastes.committing(twelve)
        pastes.edit(all(twelve), at(twelve))
        assertEquals(1, pastes.words.size)
        assertTrue(pastes.clipIsPaste)
        // What a Paste read is for the edit right after it only.
        pastes.forget()
        pastes.committing(twelve)
        pastes.edit(at(twelve), all(twelve))
        pastes.edit(all(twelve), at(twelve))
        assertTrue(pastes.words.isEmpty())
    }

    @Test
    fun `what the field copies or cuts to the clipboard counts as pasted (#241)`() {
        val pastes = PastedPhrases()
        // Pasted, then copied (or cut) back out: the field's own clip, noted
        // as pasted, and the change Android reports for it (twice, even)
        // doesn't undo that.
        pastes.add(twelve)
        pastes.copied(twelve, timestamp = 1_000)
        pastes.clipChanged(1_000)
        pastes.clipChanged(1_000)
        assertTrue(pastes.clipIsPaste)
        assertEquals(2, pastes.words.size)
        // Something copied elsewhere since: no longer the last thing on it.
        pastes.clipChanged(2_000)
        assertFalse(pastes.clipIsPaste)
        pastes.clipChanged(null)
        assertFalse(pastes.clipIsPaste)
        // A word copied out of the field is no phrase on the clipboard.
        pastes.forget()
        pastes.add(twelve)
        pastes.copied("abandon", timestamp = 3_000)
        pastes.clipChanged(3_000)
        assertFalse(pastes.clipIsPaste)
        assertEquals(1, pastes.words.size)
        // A copy whose stamp couldn't be read is still noted; any change heard is then taken as someone else's.
        pastes.forget()
        pastes.copied(twelve, timestamp = null)
        assertTrue(pastes.clipIsPaste)
        pastes.clipChanged(null)
        assertFalse(pastes.clipIsPaste)
    }

    @Test
    fun `leaving the import page clears only what was pasted into it (#241)`() {
        val words = twelve.split(" ")
        val pasted = listOf(clipWords(twelve))
        fun clear(
            vararg texts: CharSequence?,
            readable: Boolean = true,
            hasText: Boolean = true,
            pasted: List<List<String>> = listOf(clipWords(twelve)),
            imported: List<String>? = null,
        ): Boolean = shouldClearPasted(readable, hasText, { texts.toList() }, pasted, imported)
        // The pasted phrase, still on the clipboard: cleared, whatever its spacing or case.
        assertTrue(clear(twelve))
        assertTrue(clear("  " + twelve.uppercase().replace(" ", "\n") + "\n"))
        // Pasted from a note: the whole note went in, and is what's cleared.
        val note = "My phrase: $twelve. Keep it safe!"
        assertTrue(clear(note, pasted = listOf(clipWords(note))))
        // Something copied since the paste: left alone.
        assertFalse(clear("https://example.com"))
        assertFalse(clear(twelve.replace("about", "abandon")))
        assertFalse(clear(twelve.substringBeforeLast(" ")))
        // Something containing the paste but not it: left alone before an import…
        assertFalse(clear(note))
        // …and cleared after one, when it holds the imported phrase (#75).
        assertTrue(clear(note, imported = words))
        assertFalse(clear("https://example.com", imported = words))
        // Nothing pasted (typed), unreadable (no focus) or not text: the
        // clipboard's items are never read at all.
        var read = false
        fun never(): List<CharSequence?> { read = true; return listOf(twelve) }
        assertFalse(shouldClearPasted(true, true, ::never, emptyList(), words))
        assertFalse(shouldClearPasted(false, true, ::never, pasted))
        assertFalse(shouldClearPasted(true, false, ::never, pasted))
        assertFalse(read)
        // A non-text item (a `content:` URI) has no `text`, and isn't opened.
        assertFalse(clear(null))
        assertTrue(clear(null, twelve))
        // Focus lost with the page up (Home): unreadable, so cleared unread
        // only while a paste of a phrase, or a piece of one, is the last thing seen on it.
        assertTrue(shouldClearPasted(false, false, ::never, pasted, clipIsPaste = true))
        assertFalse(shouldClearPasted(false, false, ::never, emptyList(), clipIsPaste = true))
        assertFalse(read)
        // Readable, the read decides: something else there isn't cleared.
        assertFalse(shouldClearPasted(true, true, { listOf("https://example.com") }, pasted, clipIsPaste = true))
    }

    @Test
    fun `a word the keyboard puts in is no paste, and only a phrase or a piece of one may be cleared unread (#241)`() {
        val pastes = PastedPhrases()
        // Swipe typing and keyboard suggestions insert a word at a time:
        // no paste at all, so leaving the page never reads the clipboard.
        pastes.add("abandon")
        pastes.add("ability ")
        pastes.add(" about")
        pastes.add("")
        pastes.add("…")
        assertTrue(pastes.words.isEmpty())
        assertFalse(pastes.clipIsPaste)
        // Two words at once are a paste, but, not BIP-39 words, no piece of a phrase.
        pastes.add("meeting tomorrow")
        assertEquals(1, pastes.words.size)
        assertFalse(pastes.clipIsPaste)
        // R3-M1: a phrase pasted in chunks (a line of six words at a time)
        // leaves its last chunk on the clipboard: a piece of the phrase.
        pastes.add("abandon ability able about above absent")
        assertTrue(pastes.clipIsPaste)
        pastes.forget()
        pastes.add("abandon ability")
        assertTrue(pastes.clipIsPaste)
        // One word not in the list: no piece of a phrase, unless phrase-sized.
        pastes.forget()
        pastes.add("abandon ability zzz")
        assertFalse(pastes.clipIsPaste)
        pastes.add(twelve.replace(" ", ","))
        assertTrue(pastes.clipIsPaste)
        pastes.forget()
        pastes.add(twelve)
        assertTrue(pastes.clipIsPaste)
        pastes.forget()
        assertTrue(pastes.words.isEmpty())
        assertFalse(pastes.clipIsPaste)
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
        val label = PhraseClipboard.CLIP_LABEL
        assertTrue(PhraseClipboard.shouldClear(true, label, { listOf(twelve) }, hash))
        assertTrue(PhraseClipboard.shouldClear(true, label, { listOf("https://example.com", twelve) }, hash))
        assertFalse(PhraseClipboard.shouldClear(true, label, { listOf("https://example.com") }, hash))
        assertFalse(PhraseClipboard.shouldClear(true, label, { emptyList() }, hash))
        assertTrue(PhraseClipboard.shouldClear(false, null, { error("unreadable") }, hash))
        assertTrue(PhraseClipboard.shouldClear(true, label, { error("no hash to compare") }, null))
    }

    @Test
    fun `another app's clip is left alone without being read`() {
        val hash = PhraseClipboard.phraseHash(twelve.split(" "))
        // Reading it would show Android 12+'s paste toast and could open a
        // content: URI on the main thread: only the description is looked at.
        var reads = 0
        val read = { reads++; listOf<CharSequence?>(twelve) }
        assertFalse(PhraseClipboard.shouldClear(true, "Password", read, hash))
        assertFalse(PhraseClipboard.shouldClear(true, null, read, hash))
        assertFalse(PhraseClipboard.shouldClear(true, "Password", read, null))
        assertEquals(0, reads)
        // Our own label is read, and a lookalike that isn't the phrase stays.
        assertTrue(PhraseClipboard.shouldClear(true, PhraseClipboard.CLIP_LABEL, read, hash))
        assertFalse(PhraseClipboard.shouldClear(true, PhraseClipboard.CLIP_LABEL, { listOf("hi") }, hash))
        assertEquals(1, reads)
    }

    @Test
    fun `a deadline from an earlier boot is dropped, not acted on`() {
        val ttl = PhraseClipboard.TTL_MS
        val d = PhraseClipboard.Deadline.DUE
        val p = PhraseClipboard.Deadline.PENDING
        val s = PhraseClipboard.Deadline.STALE
        // Same boot: pending until the minute is up, then due.
        assertEquals(p, PhraseClipboard.deadline(10 * ttl, 7, 7, 10 * ttl - 1))
        assertEquals(d, PhraseClipboard.deadline(10 * ttl, 7, 7, 10 * ttl))
        assertEquals(d, PhraseClipboard.deadline(10 * ttl, 7, 7, 30 * ttl))
        // Copied at 10 min, rebooted, opened after 11 min of the new boot:
        // the old number has passed, but it belongs to the other boot.
        assertEquals(s, PhraseClipboard.deadline(11 * ttl, 7, 8, 20 * ttl))
        assertEquals(s, PhraseClipboard.deadline(11 * ttl, 7, 8, 11 * ttl - 1))
        // Boot count unreadable: the old "too far off for a fresh copy" check.
        assertEquals(s, PhraseClipboard.deadline(10 * ttl, -1, -1, 2 * ttl))
        assertEquals(d, PhraseClipboard.deadline(10 * ttl, -1, -1, 10 * ttl))
        // The note on the page matches the timer.
        assertEquals(60_000L, PhraseClipboard.TTL_MS)
        assertTrue(COPY_NOTE.contains("after 1 minute. If Freedom is in the background by then, usually up to a minute later"))
    }
}
