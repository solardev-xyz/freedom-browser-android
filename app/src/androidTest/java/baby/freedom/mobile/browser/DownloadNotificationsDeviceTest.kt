package baby.freedom.mobile.browser

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A download notification the user swipes away (#265) stays away while
 * its download stays in that state — through other downloads' progress
 * ticks and a new process — and comes back once the state changes.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotificationsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mgr = context.getSystemService(NotificationManager::class.java)
    private val scopes = mutableListOf<CoroutineScope>()
    private val id = 9_000_000_000L + System.nanoTime() % 1_000_000
    private val notificationId = (id % Int.MAX_VALUE).toInt()

    private val paused = DownloadEntry(
        id = id,
        fileName = "dismissed-$id.bin",
        displayUrl = "https://example.com/f.bin",
        sourceUrl = "https://example.com/f.bin",
        mimeType = "application/octet-stream",
        contentUri = null,
        status = DownloadStatus.PAUSED,
        totalBytes = 1000,
        receivedBytes = 400,
        error = null,
        startedAt = 0,
        finishedAt = null,
        validator = "\"v1\"",
        resumable = true,
    )
    private val downloads = MutableStateFlow(listOf(paused))
    private val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())

    @Before
    fun grant() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        mgr.cancel("download", notificationId)
        DownloadNotifications.keepDismissals(context, emptySet())
    }

    @Volatile private var enabled = true

    private fun startNotifications() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        DownloadNotifications(context, scope, downloads, progress, { true }, { enabled }).start()
    }

    private fun posted() = mgr.activeNotifications.firstOrNull { it.tag == "download" && it.id == notificationId }

    private fun awaitPosted() = runBlocking { withTimeoutOrNull(5_000) { while (posted() == null) delay(50); posted() } }

    /** Another download's progress ticks for [ms]; whether ours came back meanwhile. */
    private fun ticksBringItBack(ms: Long): Boolean = runBlocking {
        val until = System.currentTimeMillis() + ms
        var n = 0L
        while (System.currentTimeMillis() < until) {
            progress.value = mapOf(1L to DownloadProgress(++n, 10_000))
            delay(200)
            if (posted() != null) return@runBlocking true
        }
        false
    }

    @Test
    fun aSwipedAwayPausedNotificationStaysAwayUntilItsStateChanges() {
        startNotifications()
        val shown = awaitPosted()
        assertTrue("the paused download gets a notification", shown != null)

        // What a swipe does: the delete intent, and the notification gone.
        shown!!.notification.deleteIntent.send()
        mgr.cancel("download", notificationId)
        assertFalse("reposted by progress ticks", ticksBringItBack(3_000))

        // A new process doesn't bring it back either.
        startNotifications()
        assertFalse("reposted by a new process", ticksBringItBack(2_500))

        // Resumed, it's shown again; and paused once more, too.
        downloads.value = listOf(paused.copy(status = DownloadStatus.RUNNING))
        assertTrue("shown once resumed", awaitPosted() != null)
        downloads.value = listOf(paused.copy(receivedBytes = 700))
        runBlocking { delay(2_500) }
        assertTrue("shown once paused again", posted()?.notification?.actions?.any { it.title == "Resume" } == true)
    }

    @Test
    fun aPauseOrResumeEndsADismissalEvenIfTheStateComesBackWithinASecond() {
        startNotifications()
        val shown = awaitPosted()
        assertTrue(shown != null)
        shown!!.notification.deleteIntent.send()
        mgr.cancel("download", notificationId)
        assertFalse(ticksBringItBack(2_000))

        // Resumed, and paused again (a 503) before the once-a-second
        // collector sees it running: Resume already ended the dismissal.
        DownloadNotifications.forgetDismissals(context, id)
        downloads.value = listOf(paused.copy(status = DownloadStatus.RUNNING))
        downloads.value = listOf(paused.copy(note = "Server error 503"))
        assertTrue("shown again once re-paused", awaitPosted() != null)
    }

    @Test
    fun aResumeThatFailsBackToTheSamePausedRowWithinOnePassShowsItAgain() {
        startNotifications()
        assertTrue(awaitPosted() != null)
        // Just after the pass that posted it: swiped away, resumed, and
        // back (a 503) to the very row it was posted from, all before the
        // next pass — which sees only that row, and must post it again.
        DownloadNotifications.noteDismissed(context, id, DownloadStatus.PAUSED)
        mgr.cancel("download", notificationId)
        DownloadNotifications.forgetDismissals(context, id)
        downloads.value = listOf(paused.copy(status = DownloadStatus.RUNNING))
        downloads.value = listOf(paused)
        assertTrue("shown again once re-paused", awaitPosted() != null)
    }

    @Test
    fun aPausedNotificationDroppedForLackOfPermissionIsPostedOnceAllowed() {
        // Not allowed yet: what's posted is dropped by the system.
        enabled = false
        startNotifications()
        assertTrue(awaitPosted() != null)
        mgr.cancel("download", notificationId)

        // Allowed later, with no row changing: it appears.
        enabled = true
        val back = runBlocking { withTimeoutOrNull(10_000) { while (posted() == null) delay(100); posted() } }
        assertTrue("posted once notifications are allowed", back != null)
    }
}
