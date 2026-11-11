package cc.hyoban.moments.ui.components

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import android.app.Activity
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

/**
 * White navigation icons over the black while the viewer is open, and the
 * bars themselves hidden when everything but the photo is; a swipe from the edge shows
 * them for a moment.
 */
@Composable
actual fun ViewerSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    // On its side the system's bars stay put away after the viewer closes, as the app has them there.
    val landscape by rememberUpdatedState(cc.hyoban.moments.ui.components.hidesSystemBars())

    DisposableEffect(controller) {
        // The status bar's icons follow what is under them, white over the black.
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.isAppearanceLightNavigationBars = navigation
            if (!landscape) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(hidden) {
        if (hidden || landscape) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
