package cc.hyoban.moments

import android.content.Intent
import android.net.Uri
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

    override fun startRoute(): Any {
        val link = intent.data ?: intent.getStringExtra(EXTRA_LINK)?.let(Uri::parse)
        // Kept aside rather than in the intent, so the navigation doesn't open the page a
        // second time over itself, and found again when the page is made again.
        intent = Intent(intent).setData(null).putExtra(EXTRA_LINK, link?.toString())
        return link?.let(::routeOf) ?: Feed
    }

    companion object {
        private const val EXTRA_LINK = "link"
        private const val EXTRA_OVER_APP = "over_app"
    }
}
