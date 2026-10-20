package cc.hyoban.moments.ui.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

// A video playing in the viewer goes on in a small window of its own when the app is left,
// as a gallery's does: on Android 12 and later the system takes it there itself as the
// app goes to the background, before that the activity asks as the user leaves it. In that
// window the viewer shows only the video, and the system's own play and pause come from
// the video's media session.

/** Whether the app's window is a picture-in-picture one. */
val LocalInPictureInPicture = staticCompositionLocalOf { false }

/** Whether [LocalActivity]'s window is a picture-in-picture one, kept up as it goes in and out. */
@Composable
fun rememberInPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPip by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/** What the activity asks for as the user leaves it, before Android 12: the window of the video playing, if one is. */
object PictureInPicture {
    var params: PictureInPictureParams? = null
        private set

    /** Before Android 12, from the activity as the user leaves it: goes into the video's window if one is playing. */
    fun enterOnLeave(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        params?.let { runCatching { activity.enterPictureInPictureMode(it) } }
    }

    /**
     * Offers a window of [width] by [height] to the system while [playing]: the app's
     * window turns into it as the app is left, and stops doing so once the video stops.
     */
    @Composable
    fun Offer(playing: Boolean, width: Int, height: Int) {
        val activity = LocalActivity.current ?: return
        val supported = remember(activity) { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
        if (!supported || width <= 0 || height <= 0) return

        DisposableEffect(activity, playing, width, height) {
            val built = PictureInPictureParams.Builder()
                .setAspectRatio(aspectOf(width, height))
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(playing) }
                .build()
            params = built.takeIf { playing }
            runCatching { activity.setPictureInPictureParams(built) }
            onDispose {
                params = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
                }
            }
        }
    }

    /** The video's shape, held within what the system allows a window: between 1:2.39 and 2.39:1. */
    private fun aspectOf(width: Int, height: Int): Rational {
        val ratio = width.toDouble() / height
        return when {
            ratio > MAX_ASPECT -> Rational(239, 100)
            ratio < 1 / MAX_ASPECT -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    private const val MAX_ASPECT = 2.39
}
