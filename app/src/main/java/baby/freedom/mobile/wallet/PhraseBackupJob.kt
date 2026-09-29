package baby.freedom.mobile.wallet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs [PhraseBackup.reconcile] about once an hour while a Block Store
 * entry exists (#231), so a screen lock removed while Freedom isn't
 * open takes the cloud copy down (rewrites the entry device-only) within
 * about an hour rather than at the next app open. Scheduled by [sync]
 * whenever a reconcile finds an entry, cancelled once one finds none, so
 * a phone that never turned Google backup on never runs it. Not
 * persisted across a restart (that would need `RECEIVE_BOOT_COMPLETED`):
 * the next app open reconciles and schedules it again.
 */
class PhraseBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            // reconcileQuietly never throws (a crash here would take the app down for a job nobody watches).
            val status = PhraseBackup.get(applicationContext).reconcileQuietly()
            if (status == PhraseBackup.Status.NONE) runCatching { cancel(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The only JobScheduler job this app has; #231's issue number. */
        const val JOB_ID = 231
        private const val PERIOD_MS = 60 * 60 * 1000L

        /**
         * After a reconcile: keep the hourly job while an entry exists
         * ([status] CLOUD or PAUSED), drop it once there's none; null (Play
         * services didn't answer) leaves it as it is.
         */
        fun sync(context: Context, status: PhraseBackup.Status?) {
            runCatching {
                when (status) {
                    PhraseBackup.Status.NONE -> cancel(context)
                    PhraseBackup.Status.CLOUD, PhraseBackup.Status.PAUSED -> schedule(context)
                    null -> Unit
                }
            }
        }

        private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

        private fun schedule(context: Context) {
            val scheduler = scheduler(context) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, PhraseBackupJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .build(),
            )
        }

        private fun cancel(context: Context) {
            scheduler(context)?.cancel(JOB_ID)
        }
    }
}
