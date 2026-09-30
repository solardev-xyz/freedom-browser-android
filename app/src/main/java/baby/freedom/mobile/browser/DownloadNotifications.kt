package baby.freedom.mobile.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "downloads"
private const val TAG = "download"
private const val LOG_TAG = "Downloads"

/** Notification updates are rate-limited by the system; one a second per round is plenty. */
private const val UPDATE_INTERVAL_MS = 1_000L

/** How often to look whether notifications have been allowed (or blocked) since. */
private const val ENABLED_POLL_MS = 5_000L

private const val ACTION_PAUSE = "baby.freedom.mobile.download.PAUSE"
private const val ACTION_RESUME = "baby.freedom.mobile.download.RESUME"
private const val ACTION_CANCEL = "baby.freedom.mobile.download.CANCEL"
private const val ACTION_DISMISSED = "baby.freedom.mobile.download.DISMISSED"
private const val EXTRA_ID = "id"
private const val EXTRA_STATUS = "status"

private const val PREFS = "downloads"
private const val PREF_ASKED_NOTIFICATIONS = "askedNotifications"
private const val PREF_DISMISSED = "dismissedNotifications"

/**
 * One notification per running or paused download (#265), with its
 * progress and Pause / Resume / Cancel, so a download can be paused
 * and resumed without opening the browser. Private downloads (#86;
 * negative ids) get none: the notification shade is outside the
 * private session's screen guard, and would name the file there.
 *
 * Notifications of a previous process (which died with its downloads
 * running) are withdrawn on start; the paused rows it left get new ones.
 *
 * One the user swipes away stays away while its download stays in the
 * state it was dismissed in — across restarts too (the dismissal is
 * kept by [dismissalKey]); it comes back once the download is resumed
 * or paused — a Pause or Resume ends it at once ([forgetDismissals]),
 * however quickly the download comes back to the dismissed state, even
 * to the very same paused row. And a paused one is only posted again
 * when what it shows changes or it's paused or resumed, not on every
 * progress tick of another download — nor counted as posted while
 * notifications aren't allowed, so it appears once they are.
 */
internal class DownloadNotifications(
    context: Context,
    private val scope: CoroutineScope,
    private val downloads: Flow<List<DownloadEntry>>,
    private val progress: StateFlow<Map<Long, DownloadProgress>>,
    private val canPause: (DownloadEntry) -> Boolean,
    /** Whether posted notifications are shown at all (POST_NOTIFICATIONS, API 33+); a test's stand-in. */
    private val enabledForTest: (() -> Boolean)? = null,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun start() {
        val mgr = manager ?: return
        runCatching {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, Strings.get(R.string.library_download_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = Strings.get(R.string.library_download_channel_description)
                    setShowBadge(false)
                },
            )
            for (n in mgr.activeNotifications) if (n.tag == TAG) mgr.cancel(TAG, n.id)
        }.onFailure { Log.w(LOG_TAG, "download notifications unavailable", it) }
        scope.launch {
            var shown = emptySet<Long>()
            // The row each paused notification was last posted from, and
            // how many Pause/Resumes of it ([pauseResumes]) there had been.
            val postedPaused = HashMap<Long, Pair<DownloadEntry, Long>>()
            // A notify() while they aren't allowed is dropped: looked at
            // again now and then, so the paused ones are posted once the
            // user allows them, with no row changing.
            val enabled = flow {
                while (true) {
                    emit(enabledForTest?.invoke() ?: runCatching { mgr.areNotificationsEnabled() }.getOrDefault(true))
                    delay(ENABLED_POLL_MS)
                }
            }.distinctUntilChanged()
            combine(downloads, progress, enabled, pauseResumes) { list, live, on, turns ->
                Pass(list, live, on, turns)
            }
                .conflate()
                .collect { (list, live, on, turns) ->
                    val active = list.filter {
                        it.id > 0 && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED)
                    }
                    runCatching {
                        // Dismissals of a state the download has left are over.
                        val dismissed = keepDismissals(appContext, active.mapTo(HashSet(), ::dismissalKey))
                        val want = active.filter { dismissalKey(it) !in dismissed }
                        // Nothing posted now is shown: none counts as posted.
                        if (!on) postedPaused.clear()
                        for (entry in want) {
                            if (entry.status == DownloadStatus.PAUSED) {
                                // A Pause/Resume since means it may have been
                                // swiped away, even if the row is the same one
                                // (resumed and failed back within one pass).
                                val posted = entry to (turns[entry.id] ?: 0L)
                                if (postedPaused[entry.id] == posted) continue
                                if (on) postedPaused[entry.id] = posted
                            } else {
                                postedPaused -= entry.id
                            }
                            mgr.notify(TAG, notificationId(entry.id), build(entry, live[entry.id]))
                        }
                        val ids = want.mapTo(HashSet()) { it.id }
                        for (gone in shown - ids) mgr.cancel(TAG, notificationId(gone))
                        postedPaused.keys.retainAll(ids)
                        shown = ids
                    }.onFailure { Log.w(LOG_TAG, "couldn't update download notifications", it) }
                    delay(UPDATE_INTERVAL_MS)
                }
        }
    }

    private data class Pass(
        val list: List<DownloadEntry>,
        val live: Map<Long, DownloadProgress>,
        val on: Boolean,
        val turns: Map<Long, Long>,
    )

    private fun build(entry: DownloadEntry, live: DownloadProgress?): Notification {
        val running = entry.status == DownloadStatus.RUNNING
        val received = if (running) live?.received ?: 0 else entry.receivedBytes
        val total = (if (running) live?.total else null)?.takeIf { it > 0 } ?: entry.totalBytes.takeIf { it > 0 }
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(if (running) android.R.drawable.stat_sys_download else android.R.drawable.ic_media_pause)
            .setContentTitle(entry.fileName)
            .setContentText(downloadStatusLine(entry, live, ""))
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (total != null) {
            builder.setProgress(1_000, ((received.toDouble() / total) * 1_000).toInt().coerceIn(0, 1_000), false)
        } else if (running) {
            builder.setProgress(0, 0, true)
        }
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(appContext, 0, launch, PendingIntent.FLAG_IMMUTABLE),
            )
        }
        if (running && canPause(entry)) {
            builder.addAction(action(entry.id, ACTION_PAUSE, Strings.get(R.string.library_download_action_pause), android.R.drawable.ic_media_pause))
        }
        if (!running) {
            builder.addAction(action(entry.id, ACTION_RESUME, Strings.get(R.string.library_download_action_resume), android.R.drawable.ic_media_play))
        }
        builder.addAction(action(entry.id, ACTION_CANCEL, Strings.get(R.string.common_cancel), android.R.drawable.ic_menu_close_clear_cancel))
        builder.setDeleteIntent(
            PendingIntent.getBroadcast(
                appContext,
                0,
                receiverIntent(entry.id, ACTION_DISMISSED).putExtra(EXTRA_STATUS, entry.status),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun action(id: Long, action: String, label: String, icon: Int): Notification.Action {
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            receiverIntent(id, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(appContext, icon), label, pending).build()
    }

    private fun receiverIntent(id: Long, action: String): Intent =
        Intent(appContext, DownloadActionReceiver::class.java)
            .setAction(action)
            // The data makes each download's intent its own PendingIntent
            // (extras don't count when PendingIntents are matched).
            .setData(Uri.parse("freedom-download:$id"))
            .putExtra(EXTRA_ID, id)

    private fun notificationId(id: Long): Int = (id % Int.MAX_VALUE).toInt()

    companion object {
        /**
         * Whether the user has been asked once for the notification
         * permission (API 33+) on behalf of downloads — asked on their
         * first accepted download, never again after that.
         */
        fun askedForPermission(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ASKED_NOTIFICATIONS, false)

        private val dismissalsLock = Any()

        /**
         * How many times each download has been paused or resumed in this
         * process ([forgetDismissals]): a paused notification last posted
         * before one is posted again even if its row reads the same.
         */
        private val pauseResumes = MutableStateFlow<Map<Long, Long>>(emptyMap())

        /** A dismissal holds for this download in this state only. */
        internal fun dismissalKey(entry: DownloadEntry): String = dismissalKey(entry.id, entry.status)

        private fun dismissalKey(id: Long, status: String): String = "$id:$status"

        /** The user swiped away [id]'s notification while it was [status]. */
        internal fun noteDismissed(context: Context, id: Long, status: String) = synchronized(dismissalsLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val kept = prefs.getStringSet(PREF_DISMISSED, emptySet()).orEmpty()
            prefs.edit().putStringSet(PREF_DISMISSED, kept + dismissalKey(id, status)).apply()
        }

        /**
         * [id] is being paused or resumed: whatever the user swiped away
         * is over, even if the download is back in that state before the
         * (once a second) notifications see it leave it — and a paused
         * notification swiped away is posted again even if the row it's
         * back to is the very one it was posted from.
         */
        internal fun forgetDismissals(context: Context, id: Long) = synchronized(dismissalsLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val all = prefs.getStringSet(PREF_DISMISSED, emptySet()).orEmpty()
            val kept = all.filterTo(HashSet()) { !it.startsWith("$id:") }
            if (kept.size != all.size) prefs.edit().putStringSet(PREF_DISMISSED, kept).apply()
            // After the dismissal is gone, so the pass this starts sees it gone.
            pauseResumes.update { it + (id to (it[id] ?: 0L) + 1) }
        }

        /**
         * The dismissals still in force: those of [current] states.
         * The rest are dropped. Returns what's kept.
         */
        internal fun keepDismissals(context: Context, current: Set<String>): Set<String> = synchronized(dismissalsLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val all = prefs.getStringSet(PREF_DISMISSED, emptySet()).orEmpty()
            val kept = all.filterTo(HashSet()) { it in current }
            if (kept.size != all.size) prefs.edit().putStringSet(PREF_DISMISSED, kept).apply()
            kept
        }

        fun markAskedForPermission(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ASKED_NOTIFICATIONS, true)
                .apply()
        }
    }
}

/**
 * The download notifications' Pause / Resume / Cancel buttons, and the
 * note that one was swiped away. Not exported.
 *
 * Downloads run in the app's own process, with no foreground service:
 * a Resume tapped while the app is in the background starts the job,
 * but once this returns the process is a cached one again, which the
 * system can freeze (and so stall the download, its notification
 * showing it running at the last progress it reached) or kill (the
 * next start finds it paused, as *interrupted*). It goes on once the
 * app is in the foreground again.
 */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        // Private downloads have no notification, so no button of one.
        if (id <= 0) return
        if (intent.action == ACTION_DISMISSED) {
            intent.getStringExtra(EXTRA_STATUS)?.let { DownloadNotifications.noteDismissed(context, id, it) }
            return
        }
        val downloads = DownloadManager.get(context)
        when (intent.action) {
            ACTION_PAUSE -> downloads.pause(id)
            ACTION_RESUME -> downloads.resume(id)
            ACTION_CANCEL -> downloads.cancel(id)
        }
    }
}
