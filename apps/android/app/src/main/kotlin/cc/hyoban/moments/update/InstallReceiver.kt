package cc.hyoban.moments.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import cc.hyoban.moments.MomentsApplication

/**
 * How the system's installer is getting on with an update: it first asks to show its own
 * confirmation, which is opened; turned down or failed, 更新 says so. Once it installs, the app
 * is stopped and started again by the system.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as MomentsApplication).container.updates.installFailed()
        }
    }
}
