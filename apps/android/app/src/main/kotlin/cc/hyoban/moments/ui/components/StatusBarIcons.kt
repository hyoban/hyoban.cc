package cc.hyoban.moments.ui.components

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import cc.hyoban.moments.ui.theme.LocalDarkTheme

/** How long after the last frame is drawn the strip under the status bar is looked at again. */
private const val SAMPLE_DELAY_MS = 120L

/** The longest the strip goes unlooked at while frames keep coming, a scroll or what plays. */
private const val SAMPLE_MAX_WAIT_MS = 1000L

// Dark icons over what is lighter than this, light icons over what is darker than that;
// between the two they stay as they are, so a middling photo doesn't flip them back and forth.
private const val DARK_ICONS_ABOVE = 0.6f
private const val LIGHT_ICONS_BELOW = 0.45f

// What shows through where the window is clear, as over the full-screen map, drawn into a surface
// of its own that the copy can't see: its land, pale by day and dark by night.
private const val CLEAR_LIGHT = 0.93
private const val CLEAR_DARK = 0.12

/** How many of the strip's 48 columns at each end the icons take: the clock's side and the battery's. */
private const val ICON_END = 14

/**
 * The status bar's icons dark over a light page and light over a dark photo, whatever is
 * under them: a moment after drawing stops, the strip under the bar is copied small and the
 * brightness at its ends, under the icons, picks them. Nothing is copied while nothing is
 * drawn, nor while the bar is hidden; while frames keep coming, once a second at most, as
 * each copy holds up the frame being drawn. Where the window is clear, over a surface drawn
 * apart (the map's), the copy holds nothing, so that part counts as the map's land in the theme.
 */
@Composable
fun AdaptiveStatusBarIcons() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val dark = rememberUpdatedState(LocalDarkTheme.current)

    DisposableEffect(view, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val handler = Handler(Looper.getMainLooper())
        // Copied down to a few pixels, which PixelCopy scales to as it copies.
        val strip = createBitmap(48, 2)
        val pixels = IntArray(strip.width * strip.height)
        var scheduled = false
        // When the last frame was drawn, and the first since the strip was last looked at.
        var lastDrawn = 0L
        var firstDrawn = 0L
        var copying = false
        var stopped = false

        fun apply() {
            strip.getPixels(pixels, 0, strip.width, 0, 0, strip.width, strip.height)
            // Only the ends, where the clock and the battery are, not the middle between them.
            val ends = pixels.filterIndexed { index, _ -> (index % strip.width).let { it < ICON_END || it >= strip.width - ICON_END } }
            val clear = if (dark.value) CLEAR_DARK else CLEAR_LIGHT
            val brightness = ends.sumOf { pixel ->
                val shown = Color.alpha(pixel) / 255.0
                val own = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) / 255
                // Laid over what shows through, as much as the window leaves clear.
                own * shown + clear * (1 - shown)
            } / ends.size
            when {
                brightness > DARK_ICONS_ABOVE -> controller.isAppearanceLightStatusBars = true
                brightness < LIGHT_ICONS_BELOW -> controller.isAppearanceLightStatusBars = false
            }
        }

        lateinit var sample: Runnable
        sample = Runnable {
            val now = SystemClock.uptimeMillis()
            val settled = now - lastDrawn >= SAMPLE_DELAY_MS
            if (!settled && now - firstDrawn < SAMPLE_MAX_WAIT_MS) {
                handler.postDelayed(sample, minOf(lastDrawn + SAMPLE_DELAY_MS, firstDrawn + SAMPLE_MAX_WAIT_MS) - now)
                return@Runnable
            }
            scheduled = false
            val bar = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            if (stopped || copying || bar <= 0 || view.width <= 0) return@Runnable
            copying = true
            try {
                PixelCopy.request(window, Rect(0, 0, view.width, bar), strip, { result ->
                    copying = false
                    if (!stopped && result == PixelCopy.SUCCESS) apply()
                }, handler)
            } catch (_: IllegalArgumentException) {
                // The window has no surface yet, or no longer.
                copying = false
            }
        }
        val drawn = ViewTreeObserver.OnDrawListener {
            lastDrawn = SystemClock.uptimeMillis()
            if (!scheduled) {
                scheduled = true
                firstDrawn = lastDrawn
                handler.postDelayed(sample, SAMPLE_DELAY_MS)
            }
        }
        view.viewTreeObserver.addOnDrawListener(drawn)
        onDispose {
            stopped = true
            handler.removeCallbacks(sample)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(drawn)
        }
    }
}

/** Puts the system's bars away while [hidesSystemBars], and brings them back once the phone is upright again. */
@Composable
fun LandscapeSystemBars() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val hidden = hidesSystemBars()
    LaunchedEffect(hidden, window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
