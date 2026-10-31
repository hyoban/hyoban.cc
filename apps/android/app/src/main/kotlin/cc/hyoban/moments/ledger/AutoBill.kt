package cc.hyoban.moments.ledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动记账: whether payment pages are read, and the notification offering each payment read as
 * a bill. Nothing is written until the bill's form, opened from it, is saved.
 */
object AutoBill {
    private const val PREFS = "auto-bill"
    /** High importance, so a payment pops up over the payment app; a channel's can't be raised once made. */
    private const val CHANNEL = "ledger-payments-alert"
    /** The first, at default importance, only ever on a phone it was tried on. */
    private const val OLD_CHANNEL = "ledger-payments"
    private const val TAG = "AutoBill"
    private val enabledState = MutableStateFlow<Boolean?>(null)

    fun enabled(context: Context): Boolean = enabledState.value
        ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false).also { enabledState.value = it }

    /** The same as a flow, for the settings' switch; read from the preferences first. */
    fun enabledFlow(context: Context): StateFlow<Boolean?> {
        enabled(context)
        return enabledState.asStateFlow()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("enabled", enabled) }
        enabledState.value = enabled
        ShareAccessibilityService.listenIdle()
    }

    /** Opens a prefilled bill after an explicit capture; saving is still the user's decision. */
    fun open(context: Context, payment: Payment, seenAt: Long): Boolean {
        val ledger = (context.applicationContext as MomentsApplication).container.ledger.ledger.value
        val time = payment.time ?: seenAt
        if (ledger.hasPayment(payment, time)) return false
        context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(paymentLink(ledger, payment, time))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(payment))
        Log.i(TAG, "payment capture opened for confirmation")
        return true
    }

    private fun notificationId(payment: Payment): Int =
        "${payment.app}|${payment.amount}|${payment.time ?: payment.note}".hashCode()

    /** Offers [payment] as a bill, unless its notification is up or the ledger already has it. */
    fun offer(context: Context, payment: Payment, seenAt: Long) {
        // A bill's page gives its time, which names the payment better than its words.
        val ledger = (context.applicationContext as MomentsApplication).container.ledger.ledger.value
        val time = payment.time ?: seenAt
        // What became of a payment read, and nothing of what it was.
        if (ledger.hasPayment(payment, time)) {
            Log.i(TAG, "payment read, already in the ledger")
            return
        }
        // A page read again as it changes is the payment offered while its notification is up;
        // cleared without the bill written, it is offered again when the page is next opened.
        val id = notificationId(payment)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.any { it.id == id }) return
        Log.i(TAG, "payment read, offered")

        val link = paymentLink(ledger, payment, time)
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(link),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.deleteNotificationChannel(OLD_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.auto_bill_channel), NotificationManager.IMPORTANCE_HIGH))
        val amount = "¥" + formatCents(payment.amount)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_bill)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(context.getString(R.string.auto_bill_title, amount))
            .setContentText(listOfNotNull(payment.note, payment.method, context.getString(if (payment.app == PaymentApp.WECHAT) R.string.auto_bill_wechat else R.string.auto_bill_alipay)).joinToString(" "))
            .setWhen(time)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) manager.notify(id, notification)
    }

    private fun paymentLink(ledger: Ledger, payment: Payment, time: Long): Uri = Uri.Builder().scheme("moments").authority("payment")
            .appendQueryParameter("amount", payment.amount.toString())
            .appendQueryParameter("time", time.toString())
            .apply {
                payment.note?.let { appendQueryParameter("note", it) }
                ledger.accountFor(payment)?.let { appendQueryParameter("account", it) }
                ledger.categoryFor(payment.note)?.let { appendQueryParameter("category", it) }
            }.build()
}
