package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.stopsOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.SectionHeader
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.feed.MomentFeed
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.TripMap
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

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

    val dayLabel = { day: String -> context.getString(R.string.trip_day, daysBetween(trip.start, day)) + " · " + formatDay(day) }
    val summary: @Composable () -> Unit = {
        Column(Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(trip.title, style = MaterialTheme.typography.headlineMedium)
            TripPills(trip, moments.size, places = stops.distinctBy { it.placeId }.size)
        }
    }
    // The route as a card of its own, headed like the photo panel's sections.
    val routeCard: @Composable (Modifier, Modifier) -> Unit = { modifier, mapModifier ->
        val level = LocalSurfaceLevel.current.raised
        Surface(shape = MaterialTheme.shapes.large, color = level.fill(), modifier = modifier.padding(horizontal = Space.gutter).fillMaxWidth()) {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionHeader(Icons.Rounded.Route, stringResource(R.string.section_route))
                TripMap(stops, routes[trip.id].orEmpty(), mapModifier.fillMaxWidth().clip(MaterialTheme.shapes.medium))
            }
        }
    }

    // The page is the trip's card grown to the screen.
    BoxWithConstraints(Modifier.fillMaxSize().sharedCard(tripCardKey(id), page = true).background(AppColors.page)) {
        if (landscape) {
            // On its side, the route stays on the start side, as tall as the screen, and
            // what the trip covered and its Moments scroll beside it.
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().sideInsetsPadding(WindowInsetsSides.Start)
                        .padding(top = 56.dp).navigationBarsPadding().padding(bottom = Space.l),
                ) {
                    routeCard(Modifier.weight(1f), Modifier.weight(1f))
                }
                MomentFeed(
                    moments,
                    places,
                    trips,
                    container.mediaUrls,
                    onMoment,
                    onMedia,
                    modifier = Modifier.weight(0.58f),
                    months = false,
                    dayLabel = dayLabel,
                    showTrip = false,
                ) {
                    item(key = "summary") { Box(Modifier.padding(top = Space.l)) { summary() } }
                }
            }
        } else {
            MomentFeed(
                moments,
                places,
                trips,
                container.mediaUrls,
                onMoment,
                onMedia,
                modifier = Modifier.statusBarsPadding(),
                months = false,
                dayLabel = dayLabel,
                showTrip = false,
            ) {
                // Room for the back button, which floats over the list.
                item(key = "back") { Spacer(Modifier.height(56.dp)) }
                item(key = "summary") { summary() }
                item(key = "map") { routeCard(Modifier, Modifier.height(240.dp)) }
                item(key = "gap") { Spacer(Modifier.height(Space.s)) }
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}
