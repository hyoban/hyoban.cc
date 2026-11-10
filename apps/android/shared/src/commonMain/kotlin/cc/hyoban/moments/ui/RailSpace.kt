package cc.hyoban.moments.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the left and the right the tabs cover on a tab screen on a phone on its
 * side, one of them standing down one side or the other, to keep a page's content clear of.
 * A pane that has kept itself clear of the system's insets says so with [clearOfInsets].
 */
data class RailSpace(val left: Dp, val right: Dp, val clearOfInsets: Boolean = false)

val LocalRailSpace = staticCompositionLocalOf { RailSpace(0.dp, 0.dp) }
