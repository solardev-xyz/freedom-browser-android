package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import baby.freedom.mobile.wallet.Mnemonic
import java.security.MessageDigest

/**
 * "Copy" on the recovery-phrase page (#78), as iOS does it: the words go
 * on the clipboard for [TTL_MS] (one minute), marked sensitive so
 * Android 13+ doesn't preview them and keyboards that honour the flag
 * keep them out of their clipboard history, and are taken off again
 * after that minute if they're still what the clipboard holds.
 *
 * Android only lets an app read the clipboard while it has window focus,
 * so the minute can't always be enforced on time: if the app is in the
 * background then, the clear waits for the next time it has focus
 * ([clearIfDue] from `MainActivity.onWindowFocusChanged`). Only a SHA-256 of the
 * phrase is kept to recognise it — never the words — and only in memory.
 * Main thread only.
 */
internal object PhraseClipboard {
    const val TTL_MS = 60_000L

    /** `ClipDescription.EXTRA_IS_SENSITIVE`, a plain string key, so it's set on every API level. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val main = Handler(Looper.getMainLooper())
    private var pendingHash: ByteArray? = null
    private var dueAt = 0L

    fun copy(context: Context, words: List<String>, now: Long = SystemClock.elapsedRealtime()) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Recovery phrase", words.joinToString(" "))
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        pendingHash = phraseHash(words)
        dueAt = now + TTL_MS
        val app = context.applicationContext
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ clearIfDue(app) }, TTL_MS)
    }

    /**
     * Clears the clipboard if the copied phrase is due to go and is still
     * what it holds — something the user copied since is left alone. Does
     * nothing, and keeps waiting, if the clipboard can't be read right
     * now (no window focus).
     */
    fun clearIfDue(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val hash = pendingHash ?: return
        if (now < dueAt) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            // Without window focus Android hands back nothing (API 29+),
            // which can't be told from an empty clipboard: keep waiting.
            val clip = clipboard.primaryClip ?: return
            val holds = (0 until clip.itemCount).any { i ->
                clipIsPhrase(clip.getItemAt(i).coerceToText(context), hash)
            }
            if (holds) clipboard.clearPrimaryClip()
            pendingHash = null
        }
    }

    internal fun phraseHash(words: List<String>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(words.joinToString(" ").toByteArray(Charsets.UTF_8))

    /** Whether [text] is the phrase [hash] was taken of, spacing and letter forms aside. */
    internal fun clipIsPhrase(text: CharSequence?, hash: ByteArray): Boolean {
        if (text.isNullOrBlank()) return false
        return MessageDigest.isEqual(phraseHash(Mnemonic.words(text.toString())), hash)
    }
}
