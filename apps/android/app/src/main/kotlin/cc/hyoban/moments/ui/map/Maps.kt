package cc.hyoban.moments.ui.map

import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.spec.Place
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.plugin.locationcomponent.location
import java.util.Locale
import androidx.compose.foundation.layout.offset
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.style.MapStyle

// The site's basemap, light or dark with the system, as Mapbox draws it.

@Composable
private fun basemap(): String = if (isSystemInDarkTheme()) Style.DARK else Style.LIGHT

/** The places on the map, each a dot; the dots are the site's year colours one day. */
@Composable
fun PlacesMap(places: Map<String, Place>, center: Pair<Double, Double>?, modifier: Modifier = Modifier) {
    val start = center ?: places.values.firstOrNull()?.let { it.latitude to it.longitude } ?: (32.0603 to 118.7969)
    val viewport = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(start.second, start.first))
            zoom(if (center == null && places.size > 1) 4.0 else 11.0)
        }
    }
    val dot = MaterialTheme.colorScheme.primary

    // The fix arrives after the map is up: move there once, at a neighbourhood zoom.
    LaunchedEffect(center) {
        center?.let { (latitude, longitude) ->
            viewport.setCameraOptions {
                center(Point.fromLngLat(longitude, latitude))
                zoom(11.0)
            }
        }
    }

    // The map runs under the system bars; its logo, attribution and compass stay clear of them,
    // and stay put under a keyboard, which only ever comes up over the map in a sheet.
    val bars = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    MapboxMap(
        modifier,
        mapViewportState = viewport,
        style = { MapStyle(style = basemap()) },
        scaleBar = {},
        logo = { Logo(bars) },
        attribution = { Attribution(bars) },
        compass = { Compass(bars) },
    ) {
        ChineseLabels()
        MapEffect(Unit) { mapView ->
            mapView.location.updateSettings {
                enabled = true
                pulsingEnabled = true
            }
        }
        places.forEach { (id, place) ->
            key(id) {
                CircleAnnotation(point = Point.fromLngLat(place.longitude, place.latitude)) {
                    circleRadius = 6.0
                    circleColor = dot
                    circleStrokeWidth = 2.0
                    circleStrokeColor = Color.White
                }
            }
        }
    }
}

/**
 * Picks a point by moving the map under a pin: the map's centre is the place. Reports
 * the centre whenever the camera settles.
 */
@Composable
fun PointPicker(initial: Pair<Double, Double>, onMove: (Double, Double) -> Unit, modifier: Modifier = Modifier) {
    val viewport = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(initial.second, initial.first))
            zoom(15.0)
        }
    }
    val center = viewport.cameraState?.center

    LaunchedEffect(center) {
        center?.let { onMove(it.latitude(), it.longitude()) }
    }

    Box(modifier) {
        MapboxMap(Modifier.fillMaxSize(), mapViewportState = viewport, style = { MapStyle(style = basemap()) }) {
            ChineseLabels()
            // The map keeps its drags: a touch on it tells what holds it (the sheet) to keep off,
            // as a scrolling view would, so dragging the map doesn't drag the sheet down too.
            MapEffect(Unit) { mapView ->
                mapView.setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        }
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            // The pin's tip, not its middle, sits on the centre.
            modifier = Modifier.align(Alignment.Center).size(40.dp).offset(y = (-17).dp),
        )
    }
}

/** The basemap's names in Chinese, as on the site. */
@Composable
private fun ChineseLabels() {
    MapEffect(Unit) { mapView ->
        mapView.mapboxMap.getStyle { style -> style.localizeLabels(Locale.SIMPLIFIED_CHINESE) }
    }
}

@Composable
private inline fun key(key: Any, block: @Composable () -> Unit) = androidx.compose.runtime.key(key) { block() }
