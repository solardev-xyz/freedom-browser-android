package baby.freedom.mobile.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import baby.freedom.mobile.MainActivity
import baby.freedom.mobile.R

/**
 * Keeps the app's process awake while Sign in with ChatGPT (#602) waits
 * for the browser to come back to its listener on 127.0.0.1:1455.
 *
 * While the user is in the Custom Tab nothing of Freedom's is on screen,
 * so Android caches the process and, a few seconds later, freezes it
 * (seen on an API 36 emulator: `isFrozen=true` with the tab on top, and
 * a request to the listener hanging unanswered). A frozen process can't
 * answer the browser, so OpenAI's redirect would hang until the user came
 * back. A foreground service in the same process keeps it running.
 *
 * It's a `shortService` (Android 14+): meant for a short task the user
 * started, needs no extra permission and spends none of the `dataSync`
 * time the node's own service lives on. Android gives it about three
 * minutes ([onTimeout]); a sign-in that takes longer still finishes,
 * once the user is back in Freedom, from what the browser left with the
 * listener, or from the address pasted on the page. Before Android 14
 * it's a plain foreground service, held for as long as the sign-in waits.
 *
 * Held only from the moment the listener is open until the sign-in
 * ends, however it ends ([AgentProviders.runChatGpt]).
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start owes Android a startForeground, even one no longer wanted.
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: RuntimeException) {
            // Refused (a start from the background): the sign-in goes on without it.
            Log.w(TAG, "sign-in hold refused (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        val keep = synchronized(lock) {
            if (wanted) running = this
            wanted
        }
        if (!keep) stop()
        return START_NOT_STICKY
    }

    /** Android 14's end of a `shortService`'s time. */
    override fun onTimeout(startId: Int) = stop()

    /** Android 15+'s. */
    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        synchronized(lock) { if (running === this) running = null }
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.agent_sign_in_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.agent_sign_in_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_sign_in_notification_title))
            .setContentText(getString(R.string.agent_sign_in_notification_text))
            .setSmallIcon(R.drawable.ic_sign_in)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Holds the process for one sign-in; [release] ends it. */
    internal class Hold(private val context: Context) : ProcessHold {
        override fun hold() {
            synchronized(lock) { wanted = true }
            try {
                context.startForegroundService(Intent(context, SignInService::class.java))
            } catch (e: RuntimeException) {
                // Not allowed now (the app isn't in front): nothing held.
                Log.w(TAG, "sign-in hold not started (${e.javaClass.simpleName})")
                synchronized(lock) { wanted = false }
            }
        }

        override fun release() {
            synchronized(lock) { wanted = false }
            main.post {
                // Checked again on the main thread: a new sign-in may have
                // started since, and holds the same service.
                val service = synchronized(lock) { running.takeIf { !wanted } }
                service?.stop()
            }
        }
    }

    private companion object {
        const val TAG = "SignInService"
        const val CHANNEL_ID = "freedom_agent_sign_in"
        const val NOTIFICATION_ID = 0x5160

        val lock = Any()
        val main = Handler(Looper.getMainLooper())

        /** Whether a sign-in wants the process held. Under [lock]. */
        var wanted = false

        /** The service while it holds the process. Under [lock]. */
        var running: SignInService? = null
    }
}

/** Keeps the process running while a sign-in waits for the browser; tests use [None]. */
internal interface ProcessHold {
    fun hold()
    fun release()

    object None : ProcessHold {
        override fun hold() = Unit
        override fun release() = Unit
    }
}
