package cc.hyoban.moments

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Where what other apps share arrives, drawing nothing and leaving their task as it was.
 * A place shared from a map is handed to the app to find, and photos shared from the
 * gallery are copied in while this activity may still read them; either way the app comes
 * forward on a new Moment, or on the one being written, which takes them. Shared to
 * 保存为地点 ([SAVE_ALIAS]), a place is only kept, and the map stays in front.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MomentsApplication).container
        val photos = sharedStreams()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        when {
            photos.isNotEmpty() -> lifecycleScope.launch {
                // Read now: the permission to read them goes with this activity.
                container.receiveSharedPhotos(photos)
                openMoment()
                finish()
            }
            text != null && intent.component?.className == SAVE_ALIAS -> {
                container.saveSharedPlace(text)
                finish()
            }
            text != null -> {
                container.receiveSharedPlace(text)
                openMoment()
                finish()
            }
            else -> finish()
        }
    }

    /** The photos shared, one or several. */
    private fun sharedStreams(): List<Uri> {
        if (intent.type?.startsWith("image/") != true) return emptyList()
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
        } else {
            when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
        }
    }

    private fun openMoment() {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_MOMENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        /** The share target that only keeps the place, an alias of this activity. */
        const val SAVE_ALIAS = "cc.hyoban.moments.SavePlaceActivity"
    }
}
