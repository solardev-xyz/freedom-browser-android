package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PhraseClipboard.clearIfDue] against the real clipboard service, from
 * a focused window (the only state in which the clipboard is readable).
 */
@RunWith(AndroidJUnit4::class)
class PhraseClipboardDeviceTest {
    private val words = ("abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about").split(" ")

    private fun withFocus(block: (ComponentActivity) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 10_000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (!focused) Thread.sleep(100)
            }
            assertTrue("activity never got window focus", focused)
            scenario.onActivity(block)
        }
    }

    private fun clipboard(c: Context) = c.getSystemService(ClipboardManager::class.java)

    @Test
    fun ownPhraseIsClearedAtTheDeadline() = withFocus { a ->
        val now = SystemClock.elapsedRealtime()
        PhraseClipboard.copy(a, words, now)
        assertTrue(clipboard(a).hasPrimaryClip())
        assertTrue(PhraseClipboard.copied.value)
        // Only these words' page reads "Copied", not another wallet's (#334 R3-M2).
        val other = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        assertTrue(PhraseClipboard.holdsPhrase(PhraseClipboard.copiedLabel.value, PhraseClipboard.copiedHash.value, words))
        assertFalse(PhraseClipboard.holdsPhrase(PhraseClipboard.copiedLabel.value, PhraseClipboard.copiedHash.value, other))
        // Not yet due: still on the clipboard, button still "Copied".
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS - 1)
        assertTrue(clipboard(a).hasPrimaryClip())
        assertTrue(PhraseClipboard.copied.value)
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS)
        assertFalse(clipboard(a).hasPrimaryClip())
        // The page's Copy button follows this back to "Copy" (R3-F1).
        assertFalse(PhraseClipboard.copied.value)
    }

    @Test
    fun aNewProcessStillOwedTheClearShowsCopied() = withFocus { a ->
        val now = SystemClock.elapsedRealtime()
        PhraseClipboard.copy(a, words, now)
        // As a new process (swiped from Recents, reopened within the
        // minute): no hash, no in-memory "copied", only the saved deadline.
        @Suppress("UNCHECKED_CAST")
        (PhraseClipboard::class.java.getDeclaredField("_copiedHash").apply { isAccessible = true }
            .get(PhraseClipboard) as kotlinx.coroutines.flow.MutableStateFlow<ByteArray?>).value = null
        @Suppress("UNCHECKED_CAST")
        (PhraseClipboard::class.java.getDeclaredField("_copied").apply { isAccessible = true }
            .get(PhraseClipboard) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = false
        assertFalse(PhraseClipboard.copied.value)
        // Focus regained before the deadline: the clear is still owed (R4-F1)…
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS - 1)
        assertTrue(PhraseClipboard.copied.value)
        // …but without the hash no page can tell it's its own words, so the
        // phrase page reads "Copy", like the key page (#334 R3-M2).
        assertFalse(PhraseClipboard.holdsPhrase(PhraseClipboard.copiedLabel.value, PhraseClipboard.copiedHash.value, words))
        assertTrue(clipboard(a).hasPrimaryClip())
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS)
        assertFalse(clipboard(a).hasPrimaryClip())
        assertFalse(PhraseClipboard.copied.value)
    }

    @Test
    fun aCopiedPrivateKeyIsSensitiveAndClearedAtTheDeadline() = withFocus { a ->
        val key = "0x1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727"
        val now = SystemClock.elapsedRealtime()
        PhraseClipboard.copyKey(a, key, now)
        val desc = clipboard(a).primaryClipDescription!!
        assertEquals(PhraseClipboard.KEY_CLIP_LABEL, desc.label)
        assertTrue(desc.extras!!.getBoolean(PhraseClipboard.EXTRA_IS_SENSITIVE))
        assertEquals(key, clipboard(a).primaryClip!!.getItemAt(0).text.toString())
        // The key page's button, not the phrase page's, reads "Copied" (#323).
        assertEquals(PhraseClipboard.KEY_CLIP_LABEL, PhraseClipboard.copiedLabel.value)
        // …and only for this key: another account's page doesn't (#334 R2-F1).
        val other = "0x318470c8" + "00".repeat(28)
        assertTrue(PhraseClipboard.holdsKey(PhraseClipboard.copiedLabel.value, PhraseClipboard.copiedHash.value, key))
        assertFalse(PhraseClipboard.holdsKey(PhraseClipboard.copiedLabel.value, PhraseClipboard.copiedHash.value, other))
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS - 1)
        assertTrue(clipboard(a).hasPrimaryClip())
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS)
        assertFalse(clipboard(a).hasPrimaryClip())
        assertEquals(null, PhraseClipboard.copiedLabel.value)
        assertEquals(null, PhraseClipboard.copiedHash.value)
    }

    @Test
    fun anotherClipCopiedSinceIsLeftAlone() = withFocus { a ->
        val now = SystemClock.elapsedRealtime()
        PhraseClipboard.copy(a, words, now)
        // Even one holding the same words: a clip without our label is
        // never read, so never cleared.
        clipboard(a).setPrimaryClip(ClipData.newPlainText("Password", words.joinToString(" ")))
        PhraseClipboard.clearIfDue(a, now + PhraseClipboard.TTL_MS)
        val desc = clipboard(a).primaryClipDescription
        assertNotNull(desc)
        assertEquals("Password", desc!!.label)
    }

    @Test
    fun aDeadlineFromAnEarlierBootIsDropped() = withFocus { a ->
        val now = SystemClock.elapsedRealtime()
        PhraseClipboard.copy(a, words, now)
        clipboard(a).setPrimaryClip(ClipData.newPlainText("note", "copied after the reboot"))
        // As if the copy had been made in the previous boot.
        val boot = Settings.Global.getInt(a.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertTrue("BOOT_COUNT unreadable", boot >= 0)
        a.getSharedPreferences("phrase_clipboard", Context.MODE_PRIVATE).edit()
            .putInt("due_at_boot", boot - 1).commit()
        // …and as a new process, which doesn't know the phrase's hash (the
        // case that used to clear outright).
        @Suppress("UNCHECKED_CAST")
        (PhraseClipboard::class.java.getDeclaredField("_copiedHash").apply { isAccessible = true }
            .get(PhraseClipboard) as kotlinx.coroutines.flow.MutableStateFlow<ByteArray?>).value = null
        PhraseClipboard.clearIfDue(a, now + 10 * PhraseClipboard.TTL_MS)
        assertEquals("note", clipboard(a).primaryClipDescription?.label)
        assertFalse(a.getSharedPreferences("phrase_clipboard", Context.MODE_PRIVATE).contains("due_at_elapsed"))
    }
}
