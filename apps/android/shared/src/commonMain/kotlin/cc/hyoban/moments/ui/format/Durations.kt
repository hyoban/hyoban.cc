package cc.hyoban.moments.ui.format

import androidx.compose.runtime.Composable
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.stringResource

/** [minutes] as they are read: `3 小时 25 分钟`, or `25 分钟`. */
@Composable
fun formatMinutes(minutes: Long): String =
    if (minutes >= 60) stringResource(Res.string.footprints_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    else stringResource(Res.string.footprints_minutes, minutes.toInt())

/** A video's length as a player shows it: `0:21`, or `1:02:05` past an hour. */
fun formatClock(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A length written `HH:MM:SS` or `MM:SS`, as the NAS lists a video's, in milliseconds; null when it isn't one. */
fun parseClock(text: String): Long? {
    val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1000
}
