package cc.hyoban.moments

import android.content.Intent
import android.os.Bundle
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.routeOf

/**
 * A page opened from a widget, over the app: it starts on that page, so back closes it
 * onto the app as it was, or its Moments when it wasn't open. The app's icon closes it too
 * and shows the app rather than the page left behind; the recent apps still bring it back.
 */
class LinkActivity : MainActivity() {
    /**
     * Opened with the app not running, it would be its task's first activity, which the
     * icon can't close. So it puts the app under itself first, on its Moments, and opens
     * again over it: the icon then closes the page onto the app, and back leads to it.
     */
    override fun handOff(savedInstanceState: Bundle?): Boolean {
        if (!isTaskRoot || savedInstanceState != null || intent.getBooleanExtra(EXTRA_OVER_APP, false)) return false
        startActivities(
            arrayOf(
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                // Into this task, over the app, without the widget's flags to start a task of its own.
                Intent(intent).setClass(this, LinkActivity::class.java).setFlags(0).putExtra(EXTRA_OVER_APP, true),
            ),
        )
        finish()
        return true
    }

    /** The link's page alone, over the app: back, the top bar's or the system's, closes it onto the app. */
    override fun startPages(restored: Boolean): List<Any> = listOf(intent.data?.let(::routeOf) ?: Feed)

    companion object {
        private const val EXTRA_OVER_APP = "over_app"
    }
}
