package cc.hyoban.moments.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.PermissionSheet

/** What a [PermissionSheet] says before the system is asked for a permission. */
class PermissionWords(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val secondary: String,
    val facts: List<PermissionFact> = emptyList(),
)

fun Context.hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** The app's page in the system's settings, where a permission the system no longer asks for is given. */
fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

/**
 * Asks for [permissions] as the design does: only when something needs them, with a sheet first
 * saying what for. Given already, [onResult] is told at once; refused for good (the system no
 * longer asks), the sheet's button goes to the app's settings instead and [onResult] is told on
 * coming back. [isGranted] says when enough was given, any of them unless it says otherwise.
 * [onDecline] when the sheet is left without going on. Returns what starts it.
 */
@Composable
fun rememberPermissionAsk(
    permissions: Array<String>,
    words: PermissionWords,
    isGranted: (Context) -> Boolean = { context -> permissions.any { context.hasPermission(it) } },
    onDecline: () -> Unit = {},
    onResult: (Boolean) -> Unit,
): PermissionAsk {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val result by rememberUpdatedState(onResult)
    val decline by rememberUpdatedState(onDecline)
    val granted by rememberUpdatedState(isGranted)
    var showing by rememberSaveable { mutableStateOf(false) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    val key = remember(permissions.toList()) { "asked_" + permissions.sorted().joinToString(",") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        result(granted(context))
    }
    // Refused twice the system asks no more: what is left is its settings.
    val blocked = prefs.getBoolean(key, false) && activity != null &&
        permissions.none { activity.shouldShowRequestPermissionRationale(it) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (inSettings) {
            inSettings = false
            result(granted(context))
        }
    }
    if (showing) PermissionSheet(
        icon = words.icon,
        title = words.title,
        body = words.body,
        facts = words.facts,
        primary = stringResource(if (blocked) R.string.permission_open_settings else R.string.permission_continue),
        external = blocked,
        secondary = words.secondary,
        onPrimary = {
            showing = false
            if (blocked) {
                inSettings = true
                context.openAppSettings()
            } else {
                prefs.edit { putBoolean(key, true) }
                launcher.launch(permissions)
            }
        },
        onDismiss = { showing = false; decline() },
    )
    return remember(permissions.toList()) {
        PermissionAsk({ prefs.getBoolean(key, false) }) { if (granted(context)) result(true) else showing = true }
    }
}

/** Starts a [rememberPermissionAsk]; [asked] says whether the system has been asked before. */
class PermissionAsk(private val wasAsked: () -> Boolean, private val start: () -> Unit) {
    val asked get() = wasAsked()
    operator fun invoke() = start()
}
