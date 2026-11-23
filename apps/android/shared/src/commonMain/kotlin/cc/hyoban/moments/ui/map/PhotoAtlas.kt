package cc.hyoban.moments.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.picker.Places

/**
 * 选照片's map: the atlas as 地图 draws it, the places and the routes in their years' colours, with
 * [photos]' heaps over it. A year picked from the legend narrows both; a heap tapped opens its
 * photos, a tap on nothing puts them away. Its places and routes are switched in the top bar's layers menu. The places are only where things were: tapped, nothing
 * opens. Its map is its own, gone with the page, so 地图's kept atlas stays as it was.
 */
@Composable
fun PhotoAtlas(container: AppContainer, photos: Places, modifier: Modifier = Modifier) {
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val routes by container.tripRoutes.collectAsStateWithLifecycle()
    val stops by mapStops(container).collectAsStateWithLifecycle()
    val prepared by container.atlasPrepared.value.collectAsStateWithLifecycle()
    val inputs = remember(moments, places, stops, trips, routes) { AtlasInputs(moments, places, stops, trips, routes) }
    val atlas = remember(prepared, inputs) { prepared?.takeIf { it.matches(inputs) } ?: prepareAtlas(inputs) }
    val settings = LocalSettings.current
    val stopsOn = cc.hyoban.moments.plugins.Plugins.hasLayer(settings, TimedLayer.STOPS)
    val mapPlaces = if (stopsOn) atlas.placesWithStops else atlas.placesWithoutStops
    val colors = if (stopsOn) atlas.colorsWithStops else atlas.colorsWithoutStops
    val mapData = if (stopsOn) atlas.mapDataWithStops else atlas.mapDataWithoutStops
    val times = remember(settings) { MapTimes(settings) }
    val sharedTime = times.shared
    val kept = remember(mapPlaces, trips, stopsOn, sharedTime, TimedLayer.entries.map { times.own(it) }) { keptIn(times::of, mapPlaces, trips, stopsOn, moments = settings.mapPlaces) }
    Box(modifier.fillMaxSize().testTag("picker_map")) {
        SharedAtlasMap(
            layerPlaces(mapPlaces, settings.mapPlaces, stopsOn, null) ?: mapPlaces,
            colors,
            atlas.routes,
            selected = null,
            highlighted = null,
            onPlace = {},
            onRoute = {},
            onTrip = {},
            prepared = mapData.takeIf { settings.mapPlaces },
            mediaUrls = container.mediaUrls,
            modifier = Modifier.fillMaxSize(),
            onEmptyTap = { photos.open(null) },
            showRoutes = settings.mapRoutes,
            time = sharedTime.takeUnless { it == MapTime.All }?.encode(),
            keptPlaces = kept.places,
            keptTrips = kept.trips,
            photoDays = kept.photoDays,
            photos = photos,
            retained = false,
        )
        Box(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(top = LocalTopClearance.current, start = MAP_EDGE, end = MAP_EDGE),
        ) {
            TimePills(times, pickerLayers(settings))
        }
    }
}

