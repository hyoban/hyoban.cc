package cc.hyoban.moments.footprints

import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.res.*
import android.app.ForegroundServiceStartNotAllowedException

internal object RecordingFailure {
    fun reason(error: Exception): String = when {
        error is ForegroundServiceStartNotAllowedException -> "background_start_denied"
        error is SecurityException -> "permission_denied"
        else -> "service_error"
    }
    fun message(error: Exception): StringResource = when (reason(error)) {
        "background_start_denied" -> Res.string.footprints_background_start_denied
        "permission_denied" -> Res.string.footprints_permission_needed
        else -> Res.string.footprints_error
    }
    fun isFailure(message: StringResource?) = message in listOf(Res.string.footprints_background_start_denied,
        Res.string.footprints_permission_needed, Res.string.footprints_error)
}
