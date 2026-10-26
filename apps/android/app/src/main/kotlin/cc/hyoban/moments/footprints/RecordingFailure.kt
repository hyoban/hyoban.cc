package cc.hyoban.moments.footprints

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import cc.hyoban.moments.R

internal object RecordingFailure {
    fun reason(error: Exception): String = when {
        Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException -> "background_start_denied"
        error is SecurityException -> "permission_denied"
        else -> "service_error"
    }
    fun message(error: Exception): Int = when (reason(error)) {
        "background_start_denied" -> R.string.footprints_background_start_denied
        "permission_denied" -> R.string.footprints_permission_needed
        else -> R.string.footprints_error
    }
    fun isFailure(message: Int?) = message in listOf(R.string.footprints_background_start_denied,
        R.string.footprints_permission_needed, R.string.footprints_error)
}
