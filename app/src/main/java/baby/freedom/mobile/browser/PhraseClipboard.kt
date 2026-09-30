package baby.freedom.mobile.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import baby.freedom.mobile.wallet.Mnemonic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - with focus, the clipboard is cleared only if it still holds the
 *   phrase — something the user copied since is left alone. Its
 *   [ClipDescription] is looked at first: a clip without our
 *   [CLIP_LABEL] is someone else's and is left alone *unread*, so
 *   Android 12+ shows no "Freedom pasted from your clipboard" toast and
 *   no other app's `content:` item is opened on the main thread. Only
 *   a clip carrying our label has its (plain-text) items hashed;
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
 * measured on API 36 its window is 45 s past the minute. That is only
 * the normal case, though: in Doze an allow-while-idle alarm can be
 * held back further (Android rations them to about one per 9 minutes
 * per app), a rarely-used app's standby bucket defers it, and with the
 * battery setting "Restricted" (background-restricted) it isn't
 * delivered at all while Freedom is in the background. A force stop
 * cancels it outright. In those cases the clear waits for Freedom's
 * next window focus, and the page says so ([COPY_NOTE]).
 * The deadline itself is kept in a private preference, with the boot
 * it was set in ([Settings.Global.BOOT_COUNT], since `elapsedRealtime`
 * restarts from 0 on a reboot), so a new process still knows a clear is
 * owed ([clearIfDue] from the alarm or from
 * `MainActivity.onWindowFocusChanged`) and a deadline left over from an
 * earlier boot is dropped instead of clearing whatever was copied
 * since. Only a SHA-256 of the phrase is
 * kept to recognise it — never the words — and only in memory; a
 * process that lost it clears outright. Main thread only.
 *
 * One account's private key (#323) goes the same way ([copyKey]), under
 * its own label ([KEY_CLIP_LABEL]) so each page's button follows only
 * its own secret ([copiedLabel]; a key page also checks it's *this*
 * account's key, [holdsKey]). Only one secret is owed a clear at a
 * time: copying another replaces the one before on the clipboard and
 * takes over its deadline.
 */
internal object PhraseClipboard {
    const val TTL_MS = 60_000L

    /** The label our clip carries; how a readable clipboard is told to be ours without reading it. */
    internal const val CLIP_LABEL = "Recovery phrase"

    /** The label a copied private key (#323) carries. */
    internal const val KEY_CLIP_LABEL = "Private key"

    private val LABELS = setOf(CLIP_LABEL, KEY_CLIP_LABEL)

    /** `ClipDescription.EXTRA_IS_SENSITIVE`, a plain string key, so it's set on every API level. */
    internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val PREFS = "phrase_clipboard"
    private const val KEY_DUE_AT = "due_at_elapsed"
    private const val KEY_BOOT = "due_at_boot"
    private const val KEY_LABEL = "label"

    private val main = Handler(Looper.getMainLooper())
    private val _copiedHash = MutableStateFlow<ByteArray?>(null)

    private val _copied = MutableStateFlow(false)
    private val _copiedLabel = MutableStateFlow<String?>(null)

    /**
     * Whether *some* copied secret — the phrase or a private key, see
     * [copiedLabel] for which — is still owed its clear: true from [copy]
     * until the deadline's [clearIfDue] (or a stale deadline) takes it
     * off. A process started while a clear is still pending (the saved
     * deadline) learns it from its first [clearIfDue], which
     * `MainActivity.onWindowFocusChanged` runs as soon as Freedom has
     * focus. No page reads this to show "Copied": each page's Copy button
     * reads [copiedLabel] and matches its own label, so a pending private
     * key never reads as a copied phrase (or the reverse).
     */
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    /**
     * Which secret [copied] is about: [CLIP_LABEL] (the phrase) or
     * [KEY_CLIP_LABEL] (a private key); null when nothing is owed a clear.
     */
    val copiedLabel: StateFlow<String?> = _copiedLabel.asStateFlow()

    /**
     * The SHA-256 ([phraseHash]) of the secret owed a clear, while this
     * process knows it; null otherwise, including in a process started
     * after the copy. A page whose label can stand for more than one
     * secret — the private-key page, one per account — matches it with
     * [holdsKey], so Account 1's page never reads "Copied" while Account
     * 2's key is on the clipboard.
     */
    val copiedHash: StateFlow<ByteArray?> = _copiedHash.asStateFlow()

    /**
     * Whether [key] is the private key owed a clear, given [label] and
     * [hash] as read from [copiedLabel] and [copiedHash]. False when the
     * hash was lost with the process that did the copy: a "Copied" that
     * can't be told to be this key's isn't shown.
     */
    internal fun holdsKey(label: String?, hash: ByteArray?, key: String): Boolean =
        label == KEY_CLIP_LABEL && hash != null && MessageDigest.isEqual(hash, phraseHash(listOf(key)))

    private fun setCopied(label: String?) {
        _copied.value = label != null
        _copiedLabel.value = label
    }

    /**
     * Flags [clip] as sensitive ([EXTRA_IS_SENSITIVE]), keeping whatever
     * else its description carries: on API 33+ the system's copy preview
     * then shows dots instead of the text, and keyboards that honour the
     * flag (Gboard) keep it out of their clipboard history.
     */
    fun markSensitive(clip: ClipData) {
        val extras = clip.description.extras?.let(::PersistableBundle) ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
    }

    /** One account's private key (#323), `0x…`, with the phrase's protections. */
    fun copyKey(context: Context, key: String, now: Long = SystemClock.elapsedRealtime()) =
        copy(context, listOf(key), now, KEY_CLIP_LABEL)

    fun copy(
        context: Context,
        words: List<String>,
        now: Long = SystemClock.elapsedRealtime(),
        label: String = CLIP_LABEL,
    ) {
        require(label in LABELS)
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, words.joinToString(" "))
        markSensitive(clip)
        clipboard.setPrimaryClip(clip)
        _copiedHash.value = phraseHash(words)
        setCopied(label)
        val dueAt = now + TTL_MS
        prefs(app).edit().putLong(KEY_DUE_AT, dueAt).putInt(KEY_BOOT, bootCount(app)).putString(KEY_LABEL, label).commit()
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
     * the clipboard can be read, only when it still holds the phrase
     * (see [shouldClear]); if it can't (no window focus), outright.
     */
    fun clearIfDue(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val dueAt = prefs.getLong(KEY_DUE_AT, 0L)
        if (dueAt == 0L) return
        when (deadline(dueAt, prefs.getInt(KEY_BOOT, -1), bootCount(app), now)) {
            Deadline.STALE -> return forget(app)
            Deadline.PENDING -> {
                // Still owed, possibly to a new process (swiped from
                // Recents and reopened within the minute): the button reads
                // "Copied" again, and this process's own Handler brings it
                // back to "Copy" on time rather than the inexact alarm.
                setCopied(prefs.getString(KEY_LABEL, null)?.takeIf { it in LABELS } ?: CLIP_LABEL)
                main.removeCallbacksAndMessages(null)
                main.postDelayed({ clearIfDue(app) }, dueAt - now)
                return
            }
            Deadline.DUE -> Unit
        }
        runCatching {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            if (clipboard != null) {
                // The description, not the clip: reading it neither shows
                // Android 12+'s paste toast nor opens any item's content.
                val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
                val clear = shouldClear(
                    readable = description != null,
                    label = description?.label,
                    readTexts = {
                        val clip = runCatching { clipboard.primaryClip }.getOrNull()
                        // `text` only — never `coerceToText`, which opens a `content:` URI.
                        clip?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).text } }.orEmpty()
                    },
                    hash = _copiedHash.value,
                )
                if (clear) clipboard.clearPrimaryClip()
            }
        }
        forget(app)
    }

    internal enum class Deadline { PENDING, DUE, STALE }

    /**
     * Where a saved deadline [dueAt] (in `elapsedRealtime`, set in boot
     * [savedBoot]) stands at [now] in boot [currentBoot]. A deadline from
     * another boot is stale: `elapsedRealtime` restarted from 0, so its
     * number means nothing now, and the clipboard it was about didn't
     * survive the reboot. A boot count that can't be read (-1) falls back
     * to "further off than a fresh copy's deadline means another boot".
     */
    internal fun deadline(dueAt: Long, savedBoot: Int, currentBoot: Int, now: Long): Deadline = when {
        savedBoot != currentBoot -> Deadline.STALE
        dueAt > now + TTL_MS -> Deadline.STALE
        now < dueAt -> Deadline.PENDING
        else -> Deadline.DUE
    }

    private fun bootCount(app: Context): Int =
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun forget(app: Context) {
        _copiedHash.value = null
        setCopied(null)
        main.removeCallbacksAndMessages(null)
        prefs(app).edit().remove(KEY_DUE_AT).remove(KEY_LABEL).commit()
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
     * Whether to clear the clipboard at the deadline.
     * - Not [readable] (no focus, which Android can't tell apart from
     *   empty): clear — the words must not outlive the minute.
     * - Readable, but its [label] is neither our [CLIP_LABEL] nor
     *   [KEY_CLIP_LABEL]: someone else's
     *   clip, left alone and never read ([readTexts] isn't called).
     * - Ours, and this process knows the phrase's [hash]: clear only if
     *   an item still hashes to it (the label alone could be a lookalike
     *   from another app).
     * - Ours, hash lost with the process that did the copy: clear.
     */
    internal fun shouldClear(
        readable: Boolean,
        label: CharSequence?,
        readTexts: () -> List<CharSequence?>,
        hash: ByteArray?,
    ): Boolean {
        if (!readable) return true
        if (label?.toString() !in LABELS) return false
        if (hash == null) return true
        return readTexts().any { clipIsPhrase(it, hash) }
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
