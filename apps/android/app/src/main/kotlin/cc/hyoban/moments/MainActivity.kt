package cc.hyoban.moments

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.MomentsApp
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.viewer.PictureInPicture

open class MainActivity : ComponentActivity() {
    // Before Android 13 the app keeps its own language and speaks it here; later the system does.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (handOff(savedInstanceState)) return
        val container = (application as MomentsApplication).container
        val start = startRoute()
        setContent {
            MomentsTheme(container.settings) {
                MomentsApp(container = container, start = start)
            }
        }
        // Compose paints the whole window, so the window's own background would only be
        // painted over on every frame.
        window.setBackgroundDrawable(null)
    }

    /** Whether this activity has handed its work to others and finished, so it draws nothing. */
    protected open fun handOff(savedInstanceState: Bundle?): Boolean = false

    /** The page the app opens on: the Moments tab, from the launcher or a link. */
    protected open fun startRoute(): Any = Feed

    // Before Android 12 a playing video goes into its own window as the app is left; later the system takes it there itself.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPicture.enterOnLeave(this)
    }
}
