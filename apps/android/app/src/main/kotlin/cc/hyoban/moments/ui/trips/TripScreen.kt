package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.components.SectionHeader
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.stopsOf
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.feed.MomentFeed
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.TripMap
import cc.hyoban.moments.ui.theme.Space

/** One trip: what it covered, its route on a map card, then its Moments day by day, in order. */
@Composable
fun TripScreen(
    container: AppContainer,
    id: String,
    onMoment: (String) -> Unit,
    onMedia: (String, Int) -> Unit,
    onBack: () -> Unit,
) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val routes by container.routes.routes.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    val stops = remember(trip, all, places) { stopsOf(trip, all, trips, places) }
    val context = LocalContext.current

    // The page is the trip's card grown to the screen.
    Box(Modifier.fillMaxSize().sharedCard(tripCardKey(id), page = true).background(AppColors.page)) {
        MomentFeed(
            moments,
            places,
            trips,
            container.mediaUrls,
            onMoment,
            onMedia,
            modifier = Modifier.statusBarsPadding(),
            months = false,
            dayLabel = { day -> context.getString(R.string.trip_day, daysBetween(trip.start, day)) + " · " + formatDay(day) },
            showTrip = false,
        ) {
            // Room for the back button, which floats over the list.
            item(key = "back") { Spacer(Modifier.height(56.dp)) }
            item(key = "summary") {
                Column(Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(trip.title, style = MaterialTheme.typography.headlineMedium)
                    TripPills(trip, moments.size, places = stops.distinctBy { it.placeId }.size)
                }
            }
            item(key = "map") {
                // The route as a card of its own, headed like the photo panel's sections.
                val level = LocalSurfaceLevel.current.raised
                Surface(shape = MaterialTheme.shapes.large, color = level.fill(), modifier = Modifier.padding(horizontal = Space.gutter).fillMaxWidth()) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        SectionHeader(Icons.Rounded.Route, stringResource(R.string.section_route))
                        TripMap(stops, routes[trip.id].orEmpty(), Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium))
                    }
                }
            }
            item(key = "gap") { Spacer(Modifier.height(Space.s)) }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}
