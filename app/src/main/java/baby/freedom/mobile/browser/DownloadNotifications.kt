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
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "downloads"
private const val TAG = "download"
private const val LOG_TAG = "Downloads"

/** Notification updates are rate-limited by the system; one a second per round is plenty. */
private const val UPDATE_INTERVAL_MS = 1_000L

private const val ACTION_PAUSE = "baby.freedom.mobile.download.PAUSE"
private const val ACTION_RESUME = "baby.freedom.mobile.download.RESUME"
private const val ACTION_CANCEL = "baby.freedom.mobile.download.CANCEL"
private const val EXTRA_ID = "id"

private const val PREFS = "downloads"
private const val PREF_ASKED_NOTIFICATIONS = "askedNotifications"

/**
 * One notification per running or paused download (#265), with its
 * progress and Pause / Resume / Cancel, so a download can be paused
 * and resumed without opening the browser. Private downloads (#86;
 * negative ids) get none: the notification shade is outside the
 * private session's screen guard, and would name the file there.
 *
 * Notifications of a previous process (which died with its downloads
 * running) are withdrawn on start; the paused rows it left get new ones.
 */
internal class DownloadNotifications(
    context: Context,
    private val scope: CoroutineScope,
    private val downloads: Flow<List<DownloadEntry>>,
    private val progress: StateFlow<Map<Long, DownloadProgress>>,
    private val canPause: (DownloadEntry) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun start() {
        val mgr = manager ?: return
        runCatching {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of downloads, with pause, resume and cancel."
                    setShowBadge(false)
                },
            )
            for (n in mgr.activeNotifications) if (n.tag == TAG) mgr.cancel(TAG, n.id)
        }.onFailure { Log.w(LOG_TAG, "download notifications unavailable", it) }
        scope.launch {
            var shown = emptySet<Long>()
            combine(downloads, progress) { list, live -> list to live }
                .conflate()
                .collect { (list, live) ->
                    val want = list.filter {
                        it.id > 0 && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED)
                    }
                    runCatching {
                        for (entry in want) mgr.notify(TAG, notificationId(entry.id), build(entry, live[entry.id]))
                        val ids = want.mapTo(HashSet()) { it.id }
                        for (gone in shown - ids) mgr.cancel(TAG, notificationId(gone))
                        shown = ids
                    }.onFailure { Log.w(LOG_TAG, "couldn't update download notifications", it) }
                    delay(UPDATE_INTERVAL_MS)
                }
        }
    }

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
            builder.addAction(action(entry.id, ACTION_PAUSE, "Pause", android.R.drawable.ic_media_pause))
        }
        if (!running) {
            builder.addAction(action(entry.id, ACTION_RESUME, "Resume", android.R.drawable.ic_media_play))
        }
        builder.addAction(action(entry.id, ACTION_CANCEL, "Cancel", android.R.drawable.ic_menu_close_clear_cancel))
        return builder.build()
    }

    private fun action(id: Long, action: String, label: String, icon: Int): Notification.Action {
        val intent = Intent(appContext, DownloadActionReceiver::class.java)
            .setAction(action)
            // The data makes each download's intent its own PendingIntent
            // (extras don't count when PendingIntents are matched).
            .setData(Uri.parse("freedom-download:$id"))
            .putExtra(EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(appContext, icon), label, pending).build()
    }

    private fun notificationId(id: Long): Int = (id % Int.MAX_VALUE).toInt()

    companion object {
        /**
         * Whether the user has been asked once for the notification
         * permission (API 33+) on behalf of downloads — asked on their
         * first accepted download, never again after that.
         */
        fun askedForPermission(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ASKED_NOTIFICATIONS, false)

        fun markAskedForPermission(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ASKED_NOTIFICATIONS, true)
                .apply()
        }
    }
}

/** The download notifications' Pause / Resume / Cancel buttons. Not exported. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        // Private downloads have no notification, so no button of one.
        if (id <= 0) return
        val downloads = DownloadManager.get(context)
        when (intent.action) {
            ACTION_PAUSE -> downloads.pause(id)
            ACTION_RESUME -> downloads.resume(id)
            ACTION_CANCEL -> downloads.cancel(id)
        }
    }
}
