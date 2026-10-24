package cc.hyoban.moments.publish

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.api.NoTokenException
import cc.hyoban.moments.data.store.PublishProgress
import cc.hyoban.moments.ui.format.failureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

// Publishing runs as WorkManager's work rather than in the app's own scope, so leaving the
// app, or the system stopping it, doesn't stop it: it is a foreground service while it runs,
// its notification saying how far it has got, and a link that drops waits for the network
// and goes on, the originals already sent not sent again.

/** Publishes every change kept on the phone, as [cc.hyoban.moments.AppContainer.publishPending] asks. */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as MomentsApplication).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, null)

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val context = applicationContext
        NotificationManagerCompat.from(context).cancel(RESULT_ID)
        // Asked while the app is on screen, as it is when publishing is tapped; a retry the
        // system starts in the background may not be let in, and runs as plain work instead.
        runCatching { setForeground(getForegroundInfo()) }
        val progress = MutableStateFlow<PublishProgress?>(null)
        // The system takes a few updates a second at most; one a second says as much.
        val shown = launch {
            progress.filterNotNull().sample(NOTIFICATION_EVERY_MS).collect { runCatching { setForeground(foregroundInfo(context, it)) } }
        }
        try {
            container.runPublish { progress.value = it }
            container.reportPublished()
            notifyResult(context, context.getString(R.string.notification_published), null)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isNetwork(error) && runAttemptCount + 1 < TRIES) {
                notifyResult(context, context.getString(R.string.notification_publish_waiting), null)
                Result.retry()
            } else {
                container.reportPublishFailed(error)
                notifyResult(context, context.getString(R.string.notification_publish_failed), failureReason(context, error))
                Result.failure()
            }
        } finally {
            shown.cancel()
        }
    }

    companion object {
        private const val NAME = "publish"
        private const val CHANNEL = "publishing"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2
        private const val NOTIFICATION_EVERY_MS = 1_000L
        private const val PROGRESS_MAX = 1000
        /** How many times a publish is tried over a link that keeps dropping before it gives up. */
        private const val TRIES = 5

        /**
         * Publishes the changes now, past the app being left or stopped; asked again while one
         * waits for the network, it starts at once.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PublishWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** A dropped or stalled link, which the next try may get through, not what the API said. */
        private fun isNetwork(error: Exception) = error is IOException && error !is HttpException && error !is NoTokenException

        private fun channel(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_publishing), NotificationManager.IMPORTANCE_LOW),
            )
            return CHANNEL
        }

        /** Opens the app, on whatever page it was left. */
        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun foregroundInfo(context: Context, progress: PublishProgress?): ForegroundInfo {
            val notification = NotificationCompat.Builder(context, channel(context))
                .setSmallIcon(R.drawable.ic_notification_upload)
                .setContentTitle(context.getString(R.string.notification_publishing))
                .setContentText(progress?.let { publishingText(context, it) })
                .setProgress(PROGRESS_MAX, ((progress?.fraction ?: 0f) * PROGRESS_MAX).toInt(), progress == null)
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(PROGRESS_ID, notification)
            }
        }

        /** How it went, left in the shade, when the app may post there. */
        private fun notifyResult(context: Context, title: String, text: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val notification = NotificationCompat.Builder(context, channel(context))
                .setSmallIcon(R.drawable.ic_notification_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        }
    }
}

/** What publishing is doing: how much of the originals has gone while they upload, then the write. */
fun publishingText(context: Context, progress: PublishProgress): String =
    if (progress.size > 0 && progress.sent < progress.size) {
        // In MB, the unit once, so it fits the button on one line.
        context.getString(R.string.message_uploading_progress, "%.1f".format(progress.sent / 1e6), "%.0f".format(progress.size / 1e6))
    } else {
        context.getString(R.string.message_publishing)
    }
