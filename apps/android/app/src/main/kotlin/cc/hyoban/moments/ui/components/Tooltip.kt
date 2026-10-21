package cc.hyoban.moments.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Names an icon-only button on a long press, as Material 3's plain tooltip labels one:
 * above it, or [below] it for a button along the top of the screen. [modifier] places the
 * button among what is around it; an alignment in a box doesn't reach through it, so a
 * button aligned in one is put in a box of its own that is. Not [enabled] while the button
 * shows its name itself, as an extended FAB with its label out does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltip(label: String, below: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(if (below) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        enableUserInput = enabled,
        content = content,
    )
}
