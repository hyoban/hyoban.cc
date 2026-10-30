package cc.hyoban.moments.ui.components

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** How long after a frame is drawn the strip under the status bar is looked at again. */
private const val SAMPLE_DELAY_MS = 120L

// Dark icons over what is lighter than this, light icons over what is darker than that;
// between the two they stay as they are, so a middling photo doesn't flip them back and forth.
private const val DARK_ICONS_ABOVE = 0.6f
private const val LIGHT_ICONS_BELOW = 0.45f

/** How many of the strip's 48 columns at each end the icons take: the clock's side and the battery's. */
private const val ICON_END = 14

/**
 * The status bar's icons dark over a light page and light over a dark photo, whatever is
 * under them: a moment after anything is drawn, the strip under the bar is copied small
 * and the brightness at its ends, under the icons, picks them. Nothing is copied while
 * nothing is drawn, nor while the bar is hidden.
 */
@Composable
fun AdaptiveStatusBarIcons() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    DisposableEffect(view, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val handler = Handler(Looper.getMainLooper())
        // Copied down to a few pixels, which PixelCopy scales to as it copies.
        val strip = createBitmap(48, 2)
        val pixels = IntArray(strip.width * strip.height)
        var scheduled = false
        var copying = false
        var stopped = false

        fun apply() {
            strip.getPixels(pixels, 0, strip.width, 0, 0, strip.width, strip.height)
            // Only the ends, where the clock and the battery are, not the middle between them.
            val ends = pixels.filterIndexed { index, _ -> (index % strip.width).let { it < ICON_END || it >= strip.width - ICON_END } }
            val brightness = ends.sumOf { pixel ->
                (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) / 255
            } / ends.size
            when {
                brightness > DARK_ICONS_ABOVE -> controller.isAppearanceLightStatusBars = true
                brightness < LIGHT_ICONS_BELOW -> controller.isAppearanceLightStatusBars = false
            }
        }

        val sample = Runnable {
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
            if (!scheduled) {
                scheduled = true
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
