package cc.hyoban.moments.update

import cc.hyoban.moments.res.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import java.util.concurrent.TimeUnit

/** 每天检查更新: asks GitHub once a day and, the first time a release is newer, says so in a notification that opens 同步与来源. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as MomentsApplication).container.updates
        if (!updates.auto) return Result.success()
        val release = updates.check() ?: return Result.success()
        if (updates.firstNotice(release)) notify(applicationContext, release)
        return Result.success()
    }

    companion object {
        private const val DAILY = "update-daily"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 7301

        /** Checks once a day on a network while [on], else not. */
        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(DAILY)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, release: Release) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, textOf(Res.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(Intent.ACTION_VIEW, "moments://sync".toUri(), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_update)
                .setContentTitle(textOf(Res.string.update_notification_title, release.version))
                .setContentText(textOf(Res.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
