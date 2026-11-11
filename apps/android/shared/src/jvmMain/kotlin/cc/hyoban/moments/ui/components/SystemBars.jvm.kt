package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** A desktop's window has no system bars to put away. */
@Composable
actual fun DialogSystemBars(hidden: Boolean) {
}

actual fun fullWindowDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

// A window on a computer has no system bars to put away.
@Composable
actual fun ViewerSystemBars(hidden: Boolean) {
}
