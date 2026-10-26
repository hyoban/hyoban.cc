package cc.hyoban.moments.footprints

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Boot resumes only an explicitly enabled recorder, and only with background location access. */
class FootprintBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                ConnectionReminderService.sync(context)
                if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    RecordingService.control(context)
                }
            } catch (e: Exception) {
                android.util.Log.w("Footprints", "Could not resume recording after boot", e)
            } finally { pending.finish() }
        }
    }
}
