package cc.hyoban.moments.ui.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.Way
import cc.hyoban.moments.data.trips.inferWay
import cc.hyoban.moments.data.trips.isInTrip
import cc.hyoban.moments.footprints.TrackSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.HorizontalDivider
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.RecordLine
import cc.hyoban.moments.ui.feed.WayBubble
import cc.hyoban.moments.ui.feed.WayRow
import cc.hyoban.moments.ui.feed.trackTime
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.stringResource

// The ways between a timeline's stops, drawn over each as TripLegLine: on a trip's page and on the
// timeline alike, each as its trip notes it, else as its record says, else as the footprints kept
// on this device say, worked out only for the ways in sight and never written down.

/** What the footprints said of a way gone, how and how long and far, by leg and what was kept, once a run of the app. */
private object GoneWays {
    val known = mutableStateMapOf<String, Pair<Way?, cc.hyoban.moments.data.trips.Routers.Travel?>>()
}

/**
 * The ways between a timeline's stops, by the feed's key of the stop each leads to: told in the
 * footprints' lines just before that stop ([Track]), else as a bubble of their own ([Alone]).
 */
class LegLines internal constructor(private val legs: Map<String, TripLeg>, private val line: @Composable (TripLeg, RecordLine.Track?, (() -> Unit)?) -> Unit) {
    fun has(key: String): Boolean = key in legs

    /** [track], a footprints' line before the stop [key], as the way to it was gone; [openDay] from its menu. */
    @Composable
    fun Track(key: String, track: RecordLine.Track, openDay: (() -> Unit)?) {
        legs[key]?.let { line(it, track, openDay) }
    }

    /** The way to the stop [key] where no footprints' line tells it: a bubble of one line. */
    @Composable
    fun Alone(key: String) {
        legs[key]?.let { leg -> WayBubble { line(leg, null, null) } }
    }
}

/** A stop of a module's own a way can lead to (a check-in), as `ModulePlugin.legStops` gives it: its key among the day's items, its own uid. */
class LegStop(val key: String, val uid: String, val at: String, val place: String?, val reachedBy: String?)

/**
 * A stop of a module's own kept by its place alone (a check-in), as a trip's plan reads it: when,
 * the name it goes by, its place's id, whether its owner settled it, how it is reached, and the
 * word the planner is told its kind by.
 */
class KeptStop(val uid: String, val at: String, val name: String, val place: String?, val locked: Boolean, val reachedBy: String?, val kind: String)

/**
 * The ways between the stops of [moments] and the modules' [stops]; on the timeline only from a stop the same
 * day or the day before ([nextDayOnly]). A way in one of [trips] takes that trip's note of a leg not
 * driven, and changes it.
 */
@Composable
fun rememberLegLines(
    container: AppContainer,
    moments: List<MomentRecord>,
    stops: List<LegStop>,
    trips: List<TripRecord>,
    nextDayOnly: Boolean = false,
): LegLines {
    val places by container.repository.places.collectAsStateWithLifecycle()
    val legs = remember(moments, stops, places, nextDayOnly) {
        val placeOf = { id: String? -> id?.let(places::get)?.fix() }
        tripLegs(
            moments.map { PlanStop(it.uid, it.occurredAt, placeOf(it.places.firstOrNull()), it.reachedBy, place = it.places.firstOrNull(), planned = it.planned == true) } +
                stops.map { PlanStop(it.key, it.at, placeOf(it.place), it.reachedBy, uid = it.uid, place = it.place, stop = true) },
            nextDayOnly,
        )
    }
    val kept by container.footprints.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    return remember(legs, trips, kept) {
        LegLines(legs) { leg, track, openDay -> LegRow(container, leg, track, openDay, trips, kept, scope) }
    }
}

/**
 * One way's line: its trip's note, else its record's, else what the footprints say, else by road;
 * as [track] measured it where it is one, else, planned, as the routers work it out, else as the
 * footprints between its stops measure it.
 */
@Composable
private fun LegRow(container: AppContainer, leg: TripLeg, track: RecordLine.Track?, openDay: (() -> Unit)?, trips: List<TripRecord>, kept: Long, scope: CoroutineScope) {
    val trip = trips.firstOrNull { leg.fromAt.isNotEmpty() && isInTrip(it, leg.fromAt) && isInTrip(it, leg.stop.at) }
    val noted = trip?.straightLegs?.firstOrNull { it.from == leg.fromPlace && it.to == leg.stop.place }?.let { Way.of(it.by.name.lowercase()) }
    val said = Way.of(leg.stop.reachedBy)
    val span = leg.span
    // A planned way, or one still to come, is worked out by the routers; one gone, by the footprints alone.
    val planned = leg.stop.planned || trip?.planned == true || isAhead(leg.stop.at)
    val asked = "${leg.stop.key}@${span?.first}-${span?.last}@$kept"
    val reading = !planned && span != null
    LaunchedEffect(asked, reading) {
        if (!reading || asked in GoneWays.known) return@LaunchedEffect
        GoneWays.known[asked] = withContext(Dispatchers.Default) {
            val points = container.footprints.points(TrackSelection(from = span.first, to = span.last))
            inferWay(points, container.footprints.activities(span.first, span.last), leg.from, leg.to) to cc.hyoban.moments.data.trips.measureWay(points)
        }
    }
    val read = GoneWays.known[asked]
    val guess = read?.first.takeIf { noted == null && said == null && !planned }
    val way = noted ?: said ?: guess ?: Way.DRIVE
    // How long and far: the footprints' line's own, else the routers' for a planned one, else the footprints' between the stops.
    val by = way.by
    if (planned && track == null && by != null) LaunchedEffect(leg.from, leg.to, by) { Legs.work(Triple(leg.from, leg.to, by), container.placeSearch) }
    val routed = if (planned && track == null && by != null) Legs.known[Triple(leg.from, leg.to, by)] else null
    // How far at the edge, as a footprints' line has it; how long faint after the way's name.
    val travel = when {
        track != null -> null
        planned -> (routed as? Legs.Leg.Done)?.travel
        else -> read?.second
    }
    val distance = cc.hyoban.moments.ui.compose.formatDistance(
        travel?.metres?.toDouble() ?: track?.piece?.metres ?: if (planned && by == null) cc.hyoban.moments.data.local.distance(leg.from, leg.to) else 0.0,
    )
    val end = when {
        track != null -> stringResource(Res.string.distance_kilometres, decimals(track.piece.metres / 1000, 1))
        planned && by == null -> stringResource(Res.string.leg_straight, distance)
        travel != null -> distance
        planned && routed is Legs.Leg.Done -> stringResource(Res.string.plan_lookup_no_way)
        planned && routed == Legs.Leg.Failed -> stringResource(Res.string.plan_leg_failed)
        else -> ""
    }
    val note = listOfNotNull(stringResource(Res.string.leg_inferred).takeIf { guess != null }, travel?.let { duration(it.seconds) }).joinToString(" ").ifEmpty { null }
    val busy = (planned && track == null && by != null && (routed == null || routed == Legs.Leg.Working)) || (reading && read == null && track == null)
    val editable = container.canPublish || container.repository.isLocal(leg.stop.uid)
    WayRow(
        icon = wayIcon(way),
        title = wayName(way),
        note = note,
        time = track?.let { trackTime(it.piece) },
        end = end,
        timed = track != null,
        tag = "leg_${leg.stop.key}",
        busy = busy,
        menu = if (!editable && openDay == null) null else { dismiss ->
            if (editable) Way.entries.forEach { choice ->
                val chosen = choice == way && guess == null
                DropdownMenuItem(
                    text = { Text(wayName(choice)) },
                    leadingIcon = { Icon(wayIcon(choice), contentDescription = null, tint = if (chosen) tokens.accent else tokens.tx2) },
                    modifier = if (chosen) Modifier.background(tokens.accentSoft) else Modifier,
                    trailingIcon = if (chosen) {
                        { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        dismiss()
                        scope.launch { chooseWay(container, trip, leg, choice, noted) }
                    },
                )
            }
            if (openDay != null) {
                if (editable) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.leg_open_day)) },
                    leadingIcon = { Icon(AppIcons.Calendar, contentDescription = null, tint = tokens.tx2) },
                    onClick = {
                        dismiss()
                        openDay()
                    },
                )
            }
        },
    )
}

/** A way's name, as the menu and the line say it. */
@Composable
internal fun wayName(way: Way): String = stringResource(
    when (way) {
        Way.DRIVE -> Res.string.plan_by_drive
        Way.WALK -> Res.string.plan_by_walk
        Way.BIKE -> Res.string.plan_by_bike
        Way.TRAIN -> Res.string.plan_by_train
        Way.PLANE -> Res.string.plan_by_plane
        Way.FERRY -> Res.string.plan_by_ferry
    },
)

/** A way's icon. */
internal fun wayIcon(way: Way): androidx.compose.ui.graphics.vector.ImageVector =
    way.by?.let { modeOf(it).first } ?: if (way == Way.PLANE) AppIcons.Plane else AppIcons.Boat

/**
 * Notes how [leg] was gone, as the owner chose: on the stop it leads to (`reachedBy`, by road unless
 * walked, cycled or by train), and, in [trip], a train, flight or crossing as its leg not driven, its
 * route drawn so; a way by road again takes that note away ([noted] being the trip's).
 */
private suspend fun chooseWay(container: AppContainer, trip: TripRecord?, leg: TripLeg, way: Way, noted: Way?) {
    val reachedBy = way.value.takeIf { way == Way.WALK || way == Way.BIKE || way == Way.TRAIN }
    if (reachedBy != leg.stop.reachedBy) {
        if (leg.stop.stop) {
            val opened = cc.hyoban.moments.plugins.Plugins.stopAsMoment(container, leg.stop.uid)
            if (opened != null) container.saveEntry(cc.hyoban.moments.ui.compose.keptEntry(opened.copy(reachedBy = reachedBy), null, placeChanged = false), null)
        } else {
            container.repository.moments.value.firstOrNull { it.uid == leg.stop.uid }?.let { container.updateLocal(it.copy(reachedBy = reachedBy), null) }
        }
    }
    val from = leg.fromPlace
    val to = leg.stop.place
    if (trip != null && from != null && to != null) {
        val straight = way.value.takeIf { way == Way.TRAIN || way == Way.PLANE || way == Way.FERRY }
        if (straight != noted?.value) container.repository.setStraightLeg(trip.id, from, to, straight)
    }
}
