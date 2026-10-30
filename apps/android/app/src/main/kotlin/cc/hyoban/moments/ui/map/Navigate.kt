package cc.hyoban.moments.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.Place

/**
 * Asks a map app for the way to [place]. Amap first, told the place is in WGS-84 so it
 * shifts it onto its own map in China; else any app that takes a `geo:` link, whose
 * coordinates are WGS-84 by definition. False when no app took it, for the caller to say so.
 */
fun navigateTo(context: Context, place: Place): Boolean {
    val amap = "amapuri://route/plan/".toUri().buildUpon()
        .appendQueryParameter("sourceApplication", context.getString(R.string.app_name))
        .appendQueryParameter("dlat", place.latitude.toString())
        .appendQueryParameter("dlon", place.longitude.toString())
        .appendQueryParameter("dname", place.name)
        .appendQueryParameter("dev", "1")
        .appendQueryParameter("t", "0")
        .build()
    val geo = "geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})".toUri()
    val intents = sequenceOf(
        Intent(Intent.ACTION_VIEW, amap).setPackage(AMAP),
        Intent(Intent.ACTION_VIEW, geo),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return false
}

private const val AMAP = "com.autonavi.minimap"
