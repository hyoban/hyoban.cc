package cc.hyoban.moments.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun DialogSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(hidden, window, view) {
        if (!hidden) return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

actual fun fullWindowDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
