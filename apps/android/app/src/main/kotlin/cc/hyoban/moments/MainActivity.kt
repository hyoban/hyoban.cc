package cc.hyoban.moments

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LedgerSettings
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.MomentsApp
import cc.hyoban.moments.ui.pagesOf
import cc.hyoban.moments.ui.routeOf
import cc.hyoban.moments.ui.theme.MomentsTheme
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
        val pages = startPages(restored = savedInstanceState != null)
        if (pages == listOf(Feed)) holdSplashUntilDrawn()
        setContent {
            MomentsTheme {
                MomentsApp(container = container, pages = pages)
            }
        }
        // Compose paints the whole window, so the window's own background would only be
        // painted over on every frame.
        window.setBackgroundDrawable(null)
    }

    /**
     * Keeps the system's splash, the app's icon, up until the timeline has read what is on the
     * phone and reported itself drawn, so the app opens on its Moments rather than a skeleton.
     * The page is composed and laid out behind it meanwhile, only not drawn; past
     * [SPLASH_LIMIT_MS] it shows whatever it has.
     */
    private fun holdSplashUntilDrawn() {
        var held = true
        fullyDrawnReporter.addOnReportDrawnListener { held = false }
        val content = findViewById<View>(android.R.id.content)
        content.postDelayed({ held = false }, SPLASH_LIMIT_MS)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (held) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    /** Whether this activity has handed its work to others and finished, so it draws nothing. */
    protected open fun handOff(savedInstanceState: Bundle?): Boolean = false

    /**
     * The pages the app opens on: the Moments tab from the launcher; a link's page over its tab;
     * a new Moment for a place shared into it. Made again, or opened again from the recent apps,
     * it goes back to where it was rather than to the link once more.
     */
    protected open fun startPages(restored: Boolean): List<Any> {
        if (restored || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return listOf(Feed)
        val route = when (intent.action) {
            ACTION_NEW_MOMENT -> NewMoment()
            ACTION_LEDGER_IMPORT -> LedgerSettings()
            else -> intent.data?.let(::routeOf)
        }
        return route?.let(::pagesOf) ?: listOf(Feed)
    }

    companion object {
        private const val SPLASH_LIMIT_MS = 2000L

        /** Opens a new Moment, or goes back to the one being written, for a place shared into the app. */
        const val ACTION_NEW_MOMENT = "cc.hyoban.moments.action.NEW_MOMENT"

        /** Opens the ledger's settings on the import of an iCost export shared into the app. */
        const val ACTION_LEDGER_IMPORT = "cc.hyoban.moments.action.LEDGER_IMPORT"
    }
}
