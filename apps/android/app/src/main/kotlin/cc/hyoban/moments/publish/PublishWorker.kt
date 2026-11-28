package cc.hyoban.moments.publish

import cc.hyoban.moments.plugins.HostChannels

import cc.hyoban.moments.res.*
import android.Manifest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

// Publishing runs as WorkManager's work rather than in the app's own scope, so leaving the
// app, or the system stopping it, doesn't stop it: it is a foreground service while it runs,
// its notification saying how far it has got, and a link that drops waits for the network
// and goes on, the originals already sent not sent again. Cancelling it drops the try still
// to come and stops the uploads under way.

/** Publishes every change kept on the phone, as [cc.hyoban.moments.PhoneContainer.publishPending] asks. */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as MomentsApplication).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, null, cancelIntent())

    /** Cancels this publish from its notification, as 取消发布 on the changes page does. */
    private fun cancelIntent(): PendingIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val context = applicationContext
        NotificationManagerCompat.from(context).cancel(RESULT_ID)
        // Asked while the app is on screen, as it is when publishing is tapped; a retry the
        // system starts in the background may not be let in, and runs as plain work instead.
        runCatching { setForeground(getForegroundInfo()) }
        // The same figures as the pages, from the one run that may say how far it has got.
        // The system takes a few updates a second at most; one a second says as much.
        val shown = launch {
            container.publishing.filterNotNull().sample(NOTIFICATION_EVERY_MS).collect { runCatching { setForeground(foregroundInfo(context, it, cancelIntent())) } }
        }
        val retried = { error: Exception -> isNetwork(error) && runAttemptCount + 1 < TRIES }
        try {
            container.runPublish(retried)
            container.reportPublished()
            notifyResult(context, textOf(Res.string.notification_published), null)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (retried(error)) {
                // The try to come can be stopped from here as well as from the changes' page.
                notifyResult(context, textOf(Res.string.notification_publish_waiting), null, cancelIntent())
                Result.retry()
            } else {
                container.reportPublishFailed(error)
                notifyResult(context, textOf(Res.string.notification_publish_failed), failureReason(error))
                Result.failure()
            }
        } finally {
            shown.cancel()
        }
    }

    companion object {
        private const val NAME = "publish"
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

        /**
         * Stops the publish running or waiting: what was uploaded stays uploaded, and the change
         * set, once it is on its way, still goes through.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            NotificationManagerCompat.from(context).cancel(RESULT_ID)
        }

        /** Whether the publish waits to run again, as after a dropped link, even once the app was stopped meanwhile. */
        fun waiting(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { works ->
                works.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            }

        /** A dropped or stalled link, which the next try may get through, not what the API said. */
        private fun isNetwork(error: Exception) = error is IOException && error !is HttpException && error !is NoTokenException

        private fun channel(context: Context): String {
            HostChannels.publishing.create(context)
            return HostChannels.publishing.id
        }

        /** Opens the app, on whatever page it was left. */
        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun foregroundInfo(context: Context, progress: PublishProgress?, cancel: PendingIntent): ForegroundInfo {
            val notification = NotificationCompat.Builder(context, channel(context))
                .setSmallIcon(R.drawable.ic_notification_upload)
                .setContentTitle(textOf(Res.string.notification_publishing))
                .setContentText(progress?.let { publishingText(it) })
                .setProgress(PROGRESS_MAX, ((progress?.fraction ?: 0f) * PROGRESS_MAX).toInt(), progress == null)
                .setContentIntent(openApp(context))
                .addAction(0, textOf(Res.string.action_cancel_publish), cancel)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
            return ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        /** How it went, left in the shade, when the app may post there. */
        private fun notifyResult(context: Context, title: String, text: String?, cancel: PendingIntent? = null) {
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
                .apply { if (cancel != null) addAction(0, textOf(Res.string.action_cancel_publish), cancel) }
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        }
    }
}
