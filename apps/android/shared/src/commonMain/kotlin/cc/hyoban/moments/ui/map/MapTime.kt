package cc.hyoban.moments.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.Settings
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.DateDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.SelectedCheck
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatMonthOnly
import cc.hyoban.moments.ui.format.formatYearMonth
import cc.hyoban.moments.footprints.ActionRow
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate
import org.jetbrains.compose.resources.stringResource
import java.time.YearMonth

/**
 * The time a map layer shows (09 地图的时间): every time, one that moves with today (today,
 * yesterday, the last 30 days, this year, last year), one fixed (a year, a month, a range of
 * days), or for the footprints one of the trips they recorded.
 */
sealed interface MapTime {
    data object All : MapTime
    data object Today : MapTime
    data object Yesterday : MapTime
    data object ThisYear : MapTime
    data object LastYear : MapTime
    data object Recent : MapTime
    data class Year(val year: Int) : MapTime
    data class Month(val year: Int, val month: Int) : MapTime
    data class Days(val from: LocalDate, val to: LocalDate) : MapTime

    /** A trip the footprints recorded (单次行程), by its session; the footprints' alone. */
    data class Trip(val id: String) : MapTime

    /** The days it keeps, both ends kept, as of [today]; null for every time. */
    fun days(today: LocalDate = LocalDate.now()): ClosedRange<LocalDate>? = when (this) {
        All, is Trip -> null
        Today -> today..today
        Yesterday -> today.minusDays(1)..today.minusDays(1)
        ThisYear -> LocalDate.of(today.year, 1, 1)..LocalDate.of(today.year, 12, 31)
        LastYear -> LocalDate.of(today.year - 1, 1, 1)..LocalDate.of(today.year - 1, 12, 31)
        Recent -> today.minusDays(29)..today
        is Year -> LocalDate.of(year, 1, 1)..LocalDate.of(year, 12, 31)
        is Month -> YearMonth.of(year, month).let { it.atDay(1)..it.atEndOfMonth() }
        is Days -> from..to
    }

    fun encode(): String = when (this) {
        All -> "all"
        Today -> "today"
        Yesterday -> "yesterday"
        is Trip -> "t:$id"
        ThisYear -> "this-year"
        LastYear -> "last-year"
        Recent -> "recent"
        is Year -> "y:$year"
        is Month -> "m:$year-$month"
        is Days -> "d:$from..$to"
    }

    companion object {
        fun decode(value: String?): MapTime? = runCatching {
            when {
                value == null -> null
                value == "all" -> All
                value == "today" -> Today
                value == "yesterday" -> Yesterday
                value.startsWith("t:") -> Trip(value.drop(2))
                value == "this-year" -> ThisYear
                value == "last-year" -> LastYear
                value == "recent" -> Recent
                value.startsWith("y:") -> Year(value.drop(2).toInt())
                value.startsWith("m:") -> value.drop(2).split("-").let { Month(it[0].toInt(), it[1].toInt()) }
                value.startsWith("d:") -> value.drop(2).split("..").let { Days(LocalDate.parse(it[0]), LocalDate.parse(it[1])) }
                else -> null
            }
        }.getOrNull()
    }
}

/** The layers that take a time of their own, each following every layer's until given one. */
enum class TimedLayer(val key: String) { PLACES("places"), CHECKINS("checkins"), ROUTES("routes"), PHOTOS("photos"), FOOTPRINTS("footprints"), FOG("fog") }

/** Every layer's time, as the settings keep it. */
class MapTimes(private val settings: Settings) {
    /** The time of every layer that has none of its own. */
    val shared: MapTime get() = MapTime.decode(settings.mapTime(SHARED)) ?: MapTime.All

    /** [layer]'s own time, or null while it follows [shared]. */
    fun own(layer: TimedLayer): MapTime? = MapTime.decode(settings.mapTime(layer.key))

    /** [layer]'s time back to following every layer's. */
    fun reset(layer: TimedLayer) = settings.changeMapTime(layer.key, null)

    /** What [layer] shows: its own time, else the shared one. */
    fun of(layer: TimedLayer): MapTime = own(layer) ?: shared

    fun setShared(time: MapTime) = settings.changeMapTime(SHARED, time.takeUnless { it == MapTime.All }?.encode())

    fun setOwn(layer: TimedLayer, time: MapTime?) = settings.changeMapTime(layer.key, time?.encode())

    private companion object {
        const val SHARED = "all"
    }
}

/** How a time is said: 全部, 今年, 2024 年, 2024年5月, 2025.3.1 – 5.20. */
@Composable
fun timeWords(time: MapTime): String = when (time) {
    MapTime.All -> stringResource(Res.string.filter_all)
    MapTime.Today -> stringResource(Res.string.footprints_today)
    MapTime.Yesterday -> stringResource(Res.string.footprints_yesterday)
    is MapTime.Trip -> stringResource(Res.string.footprints_trip)
    MapTime.ThisYear -> stringResource(Res.string.map_time_this_year)
    MapTime.LastYear -> stringResource(Res.string.map_time_last_year)
    MapTime.Recent -> stringResource(Res.string.map_time_recent)
    is MapTime.Year -> stringResource(Res.string.filter_year, time.year)
    is MapTime.Month -> formatYearMonth(YearMonth.of(time.year, time.month))
    is MapTime.Days -> stringResource(Res.string.album_filter_range, formatDate(time.from.toString()), formatDate(time.to.toString()))
}

/**
 * 时间, a layer's or every layer's: the times to choose as chips, each taking effect at once, the
 * sheet staying up. A year chosen offers its months; 自定义 its two ends, each opening the calendar
 * with [days], the days with records, marked. A layer's own ([follow]) leads with 跟随全部图层, its
 * null.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapTimeSheet(
    title: String,
    time: MapTime?,
    follow: Boolean,
    days: Set<LocalDate>,
    onDismiss: () -> Unit,
    /** The footprints' recorded trips, each said as when it began, for theirs. */
    trips: List<Pair<String, String>> = emptyList(),
    onChange: (MapTime?) -> Unit,
) {
    val years = remember(days) { days.map { it.year }.distinct().sortedDescending() }
    var custom by rememberSaveable { mutableStateOf(time is MapTime.Days) }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val pickedYear = when (time) {
        is MapTime.Year -> time.year
        is MapTime.Month -> time.year
        else -> null
    }
    val months = remember(days, pickedYear) { pickedYear?.let { year -> days.filter { it.year == year }.map { it.monthValue }.distinct().sortedDescending() }.orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("map_time_sheet")) {
        Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTheme.typography.title, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(Res.string.action_close)) }
        }
        FlowRow(
            Modifier.padding(horizontal = Space.gutter).padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            val pick = { next: MapTime? -> custom = false; onChange(next) }
            if (follow) TimeChoice(stringResource(Res.string.map_time_follow), time == null && !custom, "time_follow") { pick(null) }
            TimeChoice(stringResource(Res.string.filter_all), time == MapTime.All && !custom, "time_all") { pick(MapTime.All) }
            TimeChoice(stringResource(Res.string.footprints_today), time == MapTime.Today, "time_today") { pick(MapTime.Today) }
            TimeChoice(stringResource(Res.string.footprints_yesterday), time == MapTime.Yesterday, "time_yesterday") { pick(MapTime.Yesterday) }
            TimeChoice(stringResource(Res.string.map_time_recent), time == MapTime.Recent, "time_recent") { pick(MapTime.Recent) }
            TimeChoice(stringResource(Res.string.map_time_this_year), time == MapTime.ThisYear, "time_this_year") { pick(MapTime.ThisYear) }
            TimeChoice(stringResource(Res.string.map_time_last_year), time == MapTime.LastYear, "time_last_year") { pick(MapTime.LastYear) }
            years.forEach { year ->
                TimeChoice(stringResource(Res.string.filter_year, year), pickedYear == year, "time_year_$year") { pick(MapTime.Year(year)) }
            }
            TimeChoice((time as? MapTime.Days)?.let { timeWords(it) } ?: stringResource(Res.string.album_filter_custom), custom || time is MapTime.Days, "time_custom") {
                custom = true
                if (time !is MapTime.Days) picking = true
            }
        }
        if (pickedYear != null && months.size > 1) FlowRow(
            Modifier.padding(horizontal = Space.gutter).padding(top = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            TimeChoice(stringResource(Res.string.filter_whole_year), time is MapTime.Year, "time_month_all") { onChange(MapTime.Year(pickedYear)) }
            months.forEach { month ->
                TimeChoice(formatMonthOnly(month), (time as? MapTime.Month)?.month == month, "time_month_$month") { onChange(MapTime.Month(pickedYear, month)) }
            }
        }
        if (trips.isNotEmpty()) FlowRow(
            Modifier.padding(horizontal = Space.gutter).padding(top = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            trips.forEach { (id, words) ->
                TimeChoice(words, (time as? MapTime.Trip)?.id == id, "time_trip_$id") { custom = false; onChange(MapTime.Trip(id)) }
            }
        }
        val range = time as? MapTime.Days
        if (custom || range != null) Box(Modifier.padding(top = Space.l)) {
            SegmentedGroup(null, null, listOf(
                { shapes -> ActionRow(AppIcons.Calendar, stringResource(Res.string.album_filter_from), shapes, supporting = range?.let { formatDate(it.from.toString()) }, tag = "time_from") { picking = true } },
                { shapes -> ActionRow(AppIcons.Calendar, stringResource(Res.string.album_filter_to), shapes, supporting = range?.let { formatDate(it.to.toString()) }, tag = "time_to") { picking = false } },
            ))
        }
        Box(Modifier.padding(bottom = Space.xl))
    }
    picking?.let { start ->
        val range = time as? MapTime.Days
        DateDialog(
            (if (start) range?.from else range?.to) ?: days.maxOrNull() ?: LocalDate.now(),
            stringResource(if (start) Res.string.album_filter_from else Res.string.album_filter_to),
            onDismiss = { picking = null },
            marked = days,
        ) { day ->
            picking = null
            // An end before the start, or a start after the end, moves the other with it.
            val next = when {
                start -> MapTime.Days(day, range?.to?.takeIf { it >= day } ?: day)
                else -> MapTime.Days(range?.from?.takeIf { it <= day } ?: day, day)
            }
            onChange(next)
        }
    }
}

@Composable
private fun TimeChoice(words: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = chosen,
        onClick = onClick,
        label = { Text(words, style = AppTheme.typography.label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = tokens.tx) },
        leadingIcon = if (chosen) ({ SelectedCheck() }) else null,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * What each layer keeps in its time: the places (by their Moments in the places' time, or their
 * check-ins in the check-ins' while those are counted), the trips under way in the routes' time,
 * the days of the photos' and the places the fog lights. Null where a layer keeps everything.
 */
class AtlasKept(
    val places: Set<String>?,
    val trips: Set<String>?,
    /** The routes' days, which the possible trips are kept by too. */
    val routeDays: ClosedRange<LocalDate>?,
    val photoDays: ClosedRange<LocalDate>?,
    val fogPlaces: List<MapPlace>,
)

fun keptIn(
    timeOf: (TimedLayer) -> MapTime,
    places: List<MapPlace>,
    trips: List<cc.hyoban.moments.data.spec.TripRecord>,
    checkins: Boolean,
    today: LocalDate = LocalDate.now(),
    /** Whether 地点 is drawn: off, the places are kept by their check-ins alone. */
    moments: Boolean = true,
): AtlasKept {
    fun inside(days: List<LocalDate>, range: ClosedRange<LocalDate>?) = days.isNotEmpty() && (range == null || days.any { it in range })
    fun keep(byMoments: Boolean, momentDays: ClosedRange<LocalDate>?, visits: ClosedRange<LocalDate>?) =
        places.filter { (byMoments && inside(it.momentDays, momentDays)) || (checkins && inside(it.checkinDays, visits)) }
    val placeDays = timeOf(TimedLayer.PLACES).days(today)
    val checkinDays = timeOf(TimedLayer.CHECKINS).days(today)
    val routeDays = timeOf(TimedLayer.ROUTES).days(today)
    val fogDays = timeOf(TimedLayer.FOG).days(today)
    return AtlasKept(
        places = if ((placeDays == null || !moments) && (checkinDays == null || !checkins)) null else keep(moments, placeDays, checkinDays).mapTo(HashSet()) { it.id },
        trips = routeDays?.let { range -> trips.filter { dayOf(it.start) <= range.endInclusive && dayOf(it.end) >= range.start }.mapTo(HashSet()) { it.id } },
        routeDays = routeDays,
        photoDays = timeOf(TimedLayer.PHOTOS).days(today),
        fogPlaces = if (fogDays == null) places else keep(true, fogDays, fogDays),
    )
}

/** What the footprints' store reads for [time]: a trip's session, one day, a span of whole days, or every point. */
fun footprintSelection(time: MapTime, today: LocalDate = LocalDate.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): cc.hyoban.moments.footprints.TrackSelection {
    if (time is MapTime.Trip) return cc.hyoban.moments.footprints.TrackSelection(session = time.id)
    val days = time.days(today) ?: return cc.hyoban.moments.footprints.TrackSelection()
    if (days.start == days.endInclusive) return cc.hyoban.moments.footprints.TrackSelection(day = days.start.toString())
    return cc.hyoban.moments.footprints.TrackSelection(
        from = days.start.atStartOfDay(zone).toInstant().toEpochMilli(),
        to = days.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
    )
}

/**
 * The places of the layers drawn: every place while 地点 is ([moments]), else those with a
 * check-in while 签到 is ([checkins]), and the [open] one whatever is drawn. Null while it is
 * every place given, so the prepared data still serves.
 */
fun layerPlaces(places: List<MapPlace>, moments: Boolean, checkins: Boolean, open: String?): List<MapPlace>? =
    if (moments) null else places.filter { (checkins && it.checkinDays.isNotEmpty()) || it.id == open }
