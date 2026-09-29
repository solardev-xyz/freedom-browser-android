package baby.freedom.mobile.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * after that minute.
 *
 * Android only lets an app *read* the clipboard while it has window
 * focus (API 29+), but clearing it needs no focus. So at the deadline:
 * - with focus, the clipboard is read and cleared only if it still holds
 *   the phrase — something the user copied since is left alone;
 * - without focus (the user went off to paste the words somewhere, the
 *   usual case), what's on it can't be seen, so it's cleared outright.
 *   Something copied in another app during that minute goes too — the
 *   same trade-off password managers make; leaving the words on the
 *   clipboard until Freedom next has focus (or forever, if it never
 *   does) would break the promise the page makes.
 *
 * Two timers carry the deadline: a main-thread [Handler], on time while
 * the app is in front, and an [AlarmManager] alarm to [ClearReceiver]
 * for when it isn't — a backgrounded process is soon frozen by the
 * cached-app freezer (the Handler then waits for the thaw) or killed
 * (swiped from Recents, reclaimed), and the alarm wakes or restarts it.
 * Without the exact-alarm permission (API 31+) that alarm is inexact:
 * measured on API 36 its window is 45 s past the minute, which is why
 * the page says "or up to a minute later". A force stop cancels it;
 * the clear then waits for the next focus.
 * The deadline itself is kept in a private preference so a new process
 * still knows a clear is owed ([clearIfDue] from the alarm or from
 * `MainActivity.onWindowFocusChanged`). Only a SHA-256 of the phrase is
 * kept to recognise it — never the words — and only in memory; a
 * process that lost it clears outright. Main thread only.
 */
internal object PhraseClipboard {
    const val TTL_MS = 60_000L

    /** `ClipDescription.EXTRA_IS_SENSITIVE`, a plain string key, so it's set on every API level. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val PREFS = "phrase_clipboard"
    private const val KEY_DUE_AT = "due_at_elapsed"

    private val main = Handler(Looper.getMainLooper())
    private var pendingHash: ByteArray? = null

    fun copy(context: Context, words: List<String>, now: Long = SystemClock.elapsedRealtime()) {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Recovery phrase", words.joinToString(" "))
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        pendingHash = phraseHash(words)
        val dueAt = now + TTL_MS
        prefs(app).edit().putLong(KEY_DUE_AT, dueAt).commit()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ clearIfDue(app) }, TTL_MS)
        runCatching {
            val alarms = app.getSystemService(AlarmManager::class.java)
            val pi = alarmIntent(app)
            // Exact needs a permission from API 31; without it the alarm
            // is inexact — up to ~45 s late (75% of the delay).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, dueAt, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, dueAt, pi)
            }
        }
    }

    /**
     * Takes the copied phrase off the clipboard if its minute is up: if
     * the clipboard can be read, only when it still holds the phrase;
     * if it can't (no window focus) or this process no longer knows the
     * phrase's hash, outright.
     */
    fun clearIfDue(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val app = context.applicationContext
        val dueAt = prefs(app).getLong(KEY_DUE_AT, 0L)
        if (dueAt == 0L) return
        // After a reboot `elapsedRealtime` starts again from 0 (and the
        // clipboard is empty anyway): a deadline further off than a
        // fresh copy's is from an earlier boot.
        if (dueAt > now + TTL_MS) return forget(app)
        if (now < dueAt) return
        runCatching {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            if (clipboard != null) {
                val clip = runCatching { clipboard.primaryClip }.getOrNull()
                val texts = clip?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).coerceToText(app) } }
                if (shouldClear(texts, pendingHash)) clipboard.clearPrimaryClip()
            }
        }
        forget(app)
    }

    private fun forget(app: Context) {
        pendingHash = null
        main.removeCallbacksAndMessages(null)
        prefs(app).edit().remove(KEY_DUE_AT).commit()
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app)) }
    }

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, ClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Whether to clear, given the clipboard's item texts ([texts] null
     * when it can't be read — no focus, which Android can't tell apart
     * from empty) and the phrase's [hash] (null in a process that
     * didn't do the copy). Unknown means clear: the words must not
     * outlive the minute.
     */
    internal fun shouldClear(texts: List<CharSequence?>?, hash: ByteArray?): Boolean {
        if (texts == null || hash == null) return true
        return texts.any { clipIsPhrase(it, hash) }
    }

    internal fun phraseHash(words: List<String>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(words.joinToString(" ").toByteArray(Charsets.UTF_8))

    /** Whether [text] is the phrase [hash] was taken of, spacing and letter forms aside. */
    internal fun clipIsPhrase(text: CharSequence?, hash: ByteArray): Boolean {
        if (text.isNullOrBlank()) return false
        return MessageDigest.isEqual(phraseHash(Mnemonic.words(text.toString())), hash)
    }

    /** The alarm backstop: runs [clearIfDue] in whatever process is (re)started for it. */
    class ClearReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            clearIfDue(context)
        }
    }
}
