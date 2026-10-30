package cc.hyoban.moments.ui.design

import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens

// The design system's own takes on what Material draws its own way: a switch, a slider, a
// sheet's handle, a snackbar and a floating bar of actions.

/**
 * A switch: a 44 by 26dp capsule, the accent with its thumb in on-accent at the end while on,
 * a ring in the control border with a thumb of the same at the start while off; the thumb
 * slides between, keeping its size. With [onCheckedChange] it is tapped itself; without, the
 * row it sits in is.
 */
@Composable
fun AppSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) tokens.accent else Color.Transparent, AppTheme.motionScheme.fastEffectsSpec(), label = "switch")
    val edge by animateColorAsState(if (checked) tokens.accent else tokens.borderStrong, AppTheme.motionScheme.fastEffectsSpec(), label = "switch edge")
    val thumb by animateColorAsState(if (checked) tokens.onAccent else tokens.borderStrong, AppTheme.motionScheme.fastEffectsSpec(), label = "switch thumb")
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, AppTheme.motionScheme.fastSpatialSpec(), label = "switch thumb x")
    val tap = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier
    Box(
        modifier
            .then(tap)
            .padding(vertical = 11.dp)
            .size(44.dp, 26.dp)
            .clip(Corners.full)
            .background(track)
            .border(1.5.dp, edge, Corners.full)
            .then(if (enabled) Modifier else Modifier.background(tokens.bg.copy(alpha = 0.5f))),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(20.dp).clip(CircleShape).background(thumb))
    }
}

/**
 * A slider: a thin line in [trackColor], [color] up to the value, and a small round thumb in
 * [color], tapped or dragged along, for scrubbing through a video, say. Its value is 0 to 1.
 */
@Composable
fun Scrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    color: Color = tokens.tx,
    trackColor: Color = tokens.fill,
) {
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    Canvas(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)); finish?.invoke() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { finish?.invoke() }, onDragCancel = { finish?.invoke() }) { drag, _ ->
                    change((drag.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); finish?.invoke(); true }
            },
    ) {
        val y = size.height / 2
        val r = 7.dp.toPx()
        val x = r + (size.width - 2 * r) * value.coerceIn(0f, 1f)
        val line = 3.dp.toPx()
        drawLine(trackColor, Offset(r, y), Offset(size.width - r, y), line, StrokeCap.Round)
        drawLine(color, Offset(r, y), Offset(x, y), line, StrokeCap.Round)
        drawCircle(color, r, Offset(x, y))
    }
}

/** Actions floating over a page: a capsule on the surface, with its hairline and a shadow. */
@Composable
fun FloatingBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier, shape = Corners.full, color = tokens.surface, contentColor = tokens.tx, border = cardBorder(), shadowElevation = Elevation.level2) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
