package cc.hyoban.moments.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.Place

/**
 * Asks a map app for the way to [place]. Amap first, told the place is in WGS-84 so it
 * shifts it onto its own map in China; else any app that takes a `geo:` link, whose
 * coordinates are WGS-84 by definition.
 */
fun navigateTo(context: Context, place: Place) {
    val amap = Uri.parse("amapuri://route/plan/").buildUpon()
        .appendQueryParameter("sourceApplication", context.getString(R.string.app_name))
        .appendQueryParameter("dlat", place.latitude.toString())
        .appendQueryParameter("dlon", place.longitude.toString())
        .appendQueryParameter("dname", place.name)
        .appendQueryParameter("dev", "1")
        .appendQueryParameter("t", "0")
        .build()
    val geo = Uri.parse("geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})")
    val intents = sequenceOf(
        Intent(Intent.ACTION_VIEW, amap).setPackage(AMAP),
        Intent(Intent.ACTION_VIEW, geo),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    Toast.makeText(context, R.string.no_map_app, Toast.LENGTH_SHORT).show()
}

private const val AMAP = "com.autonavi.minimap"
