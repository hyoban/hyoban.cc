package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
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

data class AtlasInputs(
    val moments: List<MomentRecord>,
    val places: Map<String, Place>,
    /** The modules' stops at places (the check-ins), as [mapStops] gives them. */
    val stops: List<MapStop>,
    val trips: List<TripRecord>,
    val routes: Map<String, List<RouteSegment>>,
)

/** Everything the atlas can derive before its first composition. */
data class AtlasPrepared(
    private val inputs: AtlasInputs,
    val byPlace: Map<String, List<MomentRecord>>,
    val stopsByPlace: Map<String, List<MapStop>>,
    val placesWithStops: List<MapPlace>,
    val placesWithoutStops: List<MapPlace>,
    val colorsWithStops: Map<Int, Color>,
    val colorsWithoutStops: Map<Int, Color>,
    val routes: List<MapRoute>,
    val kilometres: Map<String, Double>,
    val mapDataWithStops: AtlasMapData,
    val mapDataWithoutStops: AtlasMapData,
) {
    fun matches(other: AtlasInputs): Boolean =
        inputs.moments === other.moments &&
            inputs.places === other.places &&
            inputs.stops === other.stops &&
            inputs.trips === other.trips &&
            inputs.routes === other.routes
}

/**
 * Starts with the application container, before the atlas is visited, and keeps only the latest
 * complete snapshot. The composable checks source identities before using it, so a late result can
 * never show stale records while a newer snapshot is being prepared.
 */
class AtlasPreparedStore(container: AppContainer, scope: CoroutineScope) {
    private val _value = MutableStateFlow<AtlasPrepared?>(null)
    val value: StateFlow<AtlasPrepared?> = _value.asStateFlow()

    init {
        scope.launch {
            combine(
                container.shownMoments,
                container.repository.places,
                mapStops(container),
                container.shownTrips,
                container.tripRoutes,
                ::AtlasInputs,
            ).collectLatest { inputs ->
                _value.value = withContext(Dispatchers.Default) { prepareAtlas(inputs) }
            }
        }
    }
}

fun prepareAtlas(inputs: AtlasInputs): AtlasPrepared {
    val byPlace = inputs.moments
        .flatMap { moment -> moment.places.distinct().map { it to moment } }
        .groupBy({ it.first }, { it.second })
    val stopsByPlace = inputs.stops.filter { it.place != null }.groupBy { it.place!! }
    val placesWithStops = mapPlaces(byPlace, stopsByPlace, inputs.places)
    val placesWithoutStops = mapPlaces(byPlace, emptyMap(), inputs.places)
    val routes = inputs.trips
        .sortedByDescending { it.start }
        .mapNotNull { trip -> inputs.routes[trip.id]?.let { MapRoute(trip.id, it, yearOf(trip.start), yearOf(trip.end)) } }
    val kilometres = routes.associate { route ->
        route.id to route.segments.sumOf { segment ->
            segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) ->
                haversineKm(lat1, lng1, lat2, lng2)
            }.sum()
        }
    }
    val colorsWithStops = yearColors(placesWithStops.map(MapPlace::firstYear))
    val colorsWithoutStops = yearColors(placesWithoutStops.map(MapPlace::firstYear))
    return AtlasPrepared(
        inputs = inputs,
        byPlace = byPlace,
        stopsByPlace = stopsByPlace,
        placesWithStops = placesWithStops,
        placesWithoutStops = placesWithoutStops,
        colorsWithStops = colorsWithStops,
        colorsWithoutStops = colorsWithoutStops,
        routes = routes,
        kilometres = kilometres,
        mapDataWithStops = prepareAtlasMapData(placesWithStops, routes, colorsWithStops),
        mapDataWithoutStops = prepareAtlasMapData(placesWithoutStops, routes, colorsWithoutStops),
    )
}

private fun mapPlaces(
    moments: Map<String, List<MomentRecord>>,
    stops: Map<String, List<MapStop>>,
    places: Map<String, Place>,
): List<MapPlace> = (moments.keys + stops.keys).mapNotNull { id ->
    val here = moments[id].orEmpty()
    val visits = stops[id].orEmpty()
    val years = here.map { yearOf(it.occurredAt) } + visits.map { yearOf(it.at) }
    places[id]?.let {
        MapPlace(
            id, it, here.size + visits.size, years.min(), newestPhoto(here), years.toSet(),
            momentDays = here.map { moment -> dayOf(moment.occurredAt) },
            stopDays = visits.map { visit -> dayOf(visit.at) },
        )
    }
}

/**
 * The newest still taken at a place, as the site draws it: the first photo or video of its
 * newest Moment that has one, leaving out those kept for the record rather than to be shown.
 */
private fun newestPhoto(moments: List<MomentRecord>): MomentMedia? = moments
    .sortedByDescending { it.occurredAt }
    .firstNotNullOfOrNull { moment -> moment.media.firstOrNull { it.utility != true } }
