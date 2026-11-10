package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Whether the system's bars are put away: on a phone on its side, where the design (H00) gives
 * their height to the page; a swipe from the top or the bottom edge shows them for a moment.
 */
@Composable
fun hidesSystemBars(): Boolean = isLandscape() && isCompactHeight()

/**
 * In a dialog's window, which shows the system's bars again of its own accord, puts them away as
 * the app's window has them when [hidden], read from the app's window by [hidesSystemBars] before the dialog opens.
 */
@Composable
expect fun DialogSystemBars(hidden: Boolean)

/** A dialog that lays itself out over the whole window, under the system's bars where there are any. */
expect fun fullWindowDialogProperties(): DialogProperties
