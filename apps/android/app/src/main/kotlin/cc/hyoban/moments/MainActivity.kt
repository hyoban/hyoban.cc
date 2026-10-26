package cc.hyoban.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.MomentsApp
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.viewer.PictureInPicture
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import cc.hyoban.moments.footprints.RecordingService
import cc.hyoban.moments.footprints.ConnectionReminderService

open class MainActivity : ComponentActivity() {
    /**
     * Intents given to the activity after it was made, a link opened while the app is open,
     * kept until the app's navigation takes them. Caught from [onCreate], as one can come
     * before the first frame: an activity made again from the recent apps for a link has it
     * delivered straight after it is made, before Compose is listening.
     */
    val newIntents = Channel<Intent>(Channel.UNLIMITED)
    // Before Android 13 the app keeps its own language and speaks it here; later the system does.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (handOff(savedInstanceState)) return
        addOnNewIntentListener { newIntents.trySend(it) }
        val container = (application as MomentsApplication).container
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    RecordingService.control(this@MainActivity)
                    ConnectionReminderService.sync(this@MainActivity)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Footprints", "Could not resume local recording", e)
                }
            }
        }
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

    /** The page the app opens on: the Moments tab, from the launcher or a link; a new Moment, for a place shared into it. */
    protected open fun startRoute(): Any = if (intent.action == ACTION_NEW_MOMENT) NewMoment else Feed

    // Before Android 12 a playing video goes into its own window as the app is left; later the system takes it there itself.
    companion object {
        /** Opens a new Moment, or goes back to the one being written, for a place shared into the app. */
        const val ACTION_NEW_MOMENT = "cc.hyoban.moments.action.NEW_MOMENT"
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPicture.enterOnLeave(this)
    }
}
