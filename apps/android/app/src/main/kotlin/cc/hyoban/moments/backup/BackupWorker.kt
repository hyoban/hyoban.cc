package cc.hyoban.moments.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.hyoban.moments.MomentsApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** One backup, daily or asked for, as [Backup.run] makes it; a dropped link is tried again a few times. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as MomentsApplication).container.backup
        if (!backup.configured) return Result.success()
        return try {
            backup.run()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (Backup.failureOf(error) == BackupFailure.Unreachable && runAttemptCount + 1 < TRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val DAILY = "backup-daily"
        private const val NOW = "backup-now"
        private const val TRIES = 3

        /** Backs up once a day while there is a directory and 每天自动备份 is on, else not; [replace] when what it is set to changed. */
        fun schedule(context: Context, backup: Backup, replace: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!backup.configured || !backup.auto) {
                work.cancelUniqueWork(DAILY)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(DAILY, if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Backs up now, once the network is there. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
