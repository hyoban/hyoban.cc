package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.data.things.Checkins
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.ui.trips.haversineKm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AtlasInputs(
    val moments: List<MomentRecord>,
    val places: Map<String, Place>,
    val checkins: Checkins,
    val trips: List<TripRecord>,
    val routes: Map<String, List<RouteSegment>>,
)

/** Everything the atlas can derive before its first composition. */
internal data class AtlasPrepared(
    private val inputs: AtlasInputs,
    val byPlace: Map<String, List<MomentRecord>>,
    val checkinsByPlace: Map<String, List<Checkin>>,
    val placesWithCheckins: List<MapPlace>,
    val placesWithoutCheckins: List<MapPlace>,
    val colorsWithCheckins: Map<Int, Color>,
    val colorsWithoutCheckins: Map<Int, Color>,
    val routes: List<MapRoute>,
    val kilometres: Map<String, Double>,
    val mapDataWithCheckins: AtlasMapData,
    val mapDataWithoutCheckins: AtlasMapData,
) {
    fun matches(other: AtlasInputs): Boolean =
        inputs.moments === other.moments &&
            inputs.places === other.places &&
            inputs.checkins === other.checkins &&
            inputs.trips === other.trips &&
            inputs.routes === other.routes
}

/**
 * Starts with the application container, before the atlas is visited, and keeps only the latest
 * complete snapshot. The composable checks source identities before using it, so a late result can
 * never show stale records while a newer snapshot is being prepared.
 */
internal class AtlasPreparedStore(container: PhoneContainer, scope: CoroutineScope) {
    private val _value = MutableStateFlow<AtlasPrepared?>(null)
    val value: StateFlow<AtlasPrepared?> = _value.asStateFlow()

    init {
        scope.launch {
            combine(
                container.shownMoments,
                container.repository.places,
                container.repository.checkins,
                container.shownTrips,
                container.tripRoutes,
                ::AtlasInputs,
            ).collectLatest { inputs ->
                _value.value = withContext(Dispatchers.Default) { prepareAtlas(inputs) }
            }
        }
    }
}

internal fun prepareAtlas(inputs: AtlasInputs): AtlasPrepared {
    val byPlace = inputs.moments
        .flatMap { moment -> moment.places.distinct().map { it to moment } }
        .groupBy({ it.first }, { it.second })
    val checkinsByPlace = inputs.checkins.byPlace
    val placesWithCheckins = mapPlaces(byPlace, checkinsByPlace, inputs.places)
    val placesWithoutCheckins = mapPlaces(byPlace, emptyMap(), inputs.places)
    val routes = inputs.trips
        .sortedByDescending { it.start }
        .mapNotNull { trip -> inputs.routes[trip.id]?.let { MapRoute(trip.id, it) } }
    val kilometres = routes.associate { route ->
        route.id to route.segments.sumOf { segment ->
            segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) ->
                haversineKm(lat1, lng1, lat2, lng2)
            }.sum()
        }
    }
    return AtlasPrepared(
        inputs = inputs,
        byPlace = byPlace,
        checkinsByPlace = checkinsByPlace,
        placesWithCheckins = placesWithCheckins,
        placesWithoutCheckins = placesWithoutCheckins,
        colorsWithCheckins = yearColors(placesWithCheckins.map(MapPlace::firstYear)),
        colorsWithoutCheckins = yearColors(placesWithoutCheckins.map(MapPlace::firstYear)),
        routes = routes,
        kilometres = kilometres,
        mapDataWithCheckins = prepareAtlasMapData(placesWithCheckins, routes),
        mapDataWithoutCheckins = prepareAtlasMapData(placesWithoutCheckins, routes),
    )
}

private fun mapPlaces(
    moments: Map<String, List<MomentRecord>>,
    checkins: Map<String, List<Checkin>>,
    places: Map<String, Place>,
): List<MapPlace> = (moments.keys + checkins.keys).mapNotNull { id ->
    val here = moments[id].orEmpty()
    val visits = checkins[id].orEmpty()
    val years = here.map { yearOf(it.occurredAt) } + visits.map { yearOf(it.at) }
    places[id]?.let { MapPlace(id, it, here.size + visits.size, years.min()) }
}
