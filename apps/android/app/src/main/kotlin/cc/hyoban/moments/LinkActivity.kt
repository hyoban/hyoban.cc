package cc.hyoban.moments

import android.content.Intent
import android.net.Uri
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.routeOf

/**
 * A page opened from a widget, over the app as it was or on its own: it starts on that
 * page, so back closes it and goes back to where it was opened from. The app's icon
 * closes it too and shows the app as it was, its Moments when it wasn't open, rather than
 * the page left behind; the recent apps still bring it back.
 */
class LinkActivity : MainActivity() {
    override fun startRoute(): Any {
        val link = intent.data ?: intent.getStringExtra(EXTRA_LINK)?.let(Uri::parse)
        // Kept aside rather than in the intent, so the navigation doesn't open the page a
        // second time over itself, and found again when the page is made again.
        intent = Intent(intent).setData(null).putExtra(EXTRA_LINK, link?.toString())
        return link?.let(::routeOf) ?: Feed
    }

    companion object {
        private const val EXTRA_LINK = "link"
    }
}
