package cc.hyoban.moments.ui.format

import androidx.compose.runtime.Composable
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.stringResource

/** [minutes] as they are read: `3 小时 25 分钟`, or `25 分钟`. */
@Composable
fun formatMinutes(minutes: Long): String =
    if (minutes >= 60) stringResource(Res.string.footprints_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    else stringResource(Res.string.footprints_minutes, minutes.toInt())
