package cc.hyoban.moments.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.tokens

// The design's toolbar tooltip: after a pointer rests a moment, a small label in the ink, its words in the page's colour.
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Tooltip(label: String, modifier: Modifier, below: Boolean, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                label,
                style = AppTheme.typography.caption,
                color = tokens.bg,
                modifier = Modifier.background(tokens.tx, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        modifier = modifier,
        delayMillis = 600,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = if (below) Alignment.BottomCenter else Alignment.TopCenter,
            alignment = if (below) Alignment.BottomCenter else Alignment.TopCenter,
            offset = DpOffset(0.dp, if (below) 4.dp else (-4).dp),
        ),
    ) { content() }
}
