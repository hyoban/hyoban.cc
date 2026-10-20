package cc.hyoban.moments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.hyoban.moments.ui.MomentsApp
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.viewer.PictureInPicture

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MomentsApplication).container
        setContent {
            MomentsTheme(container.settings) {
                MomentsApp(container = container)
            }
        }
        // Compose paints the whole window, so the window's own background would only be
        // painted over on every frame.
        window.setBackgroundDrawable(null)
    }

    // Before Android 12 a playing video goes into its own window as the app is left; later the system takes it there itself.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPicture.enterOnLeave(this)
    }
}
