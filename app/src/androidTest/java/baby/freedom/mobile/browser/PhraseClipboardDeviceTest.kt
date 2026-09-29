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
        PhraseClipboard::class.java.getDeclaredField("pendingHash").apply { isAccessible = true }
            .set(PhraseClipboard, null)
        PhraseClipboard.clearIfDue(a, now + 10 * PhraseClipboard.TTL_MS)
        assertEquals("note", clipboard(a).primaryClipDescription?.label)
        assertFalse(a.getSharedPreferences("phrase_clipboard", Context.MODE_PRIVATE).contains("due_at_elapsed"))
    }
}
