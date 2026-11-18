package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import cc.hyoban.moments.footprints.ActionRow
import cc.hyoban.moments.nas.NasKind
import cc.hyoban.moments.nas.NasOption
import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.nas.NasQuery
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.DateDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.feed.SelectedCheck
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatMonthOnly
import cc.hyoban.moments.ui.format.formatYearMonth
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.YearMonth

// 相册's and 选照片's filter: one for both, kept on the phone, narrowing whichever source is
// listed by what that source can tell of its photos. Its button sits beside the subtitle's count,
// which is what it changes; the conditions chosen line up under it, each taken off with its X.

/** What a photo is, for the filter: a still, a video, or a live photo. */
enum class PhotoKind { PHOTO, VIDEO, LIVE }

/** What a listing can narrow its photos by ([Listing.facets]). */
enum class Facet { KIND, TIME, PLACE, MOMENT, EXIF }

/** The NAS's EXIF conditions, by the name its API gives each, and what they are called. */
enum class ExifField(val key: String, val label: StringResource) {
    CAMERA("camera", Res.string.album_filter_camera),
    LENS("lens", Res.string.album_filter_lens),
    FOCAL("focal", Res.string.album_filter_focal),
    APERTURE("aperture", Res.string.album_filter_aperture),
    EXPOSURE("exposure", Res.string.album_filter_exposure),
    ISO("iso", Res.string.album_filter_iso),
}

/**
 * The filter: the [kinds] wanted (none for all); a time, a [year] and within it a [month], or a
 * range of days [from] [to] (ISO dates) chosen instead; whether a photo has a [place] or is in a
 * [moment]; and the NAS's [exif] conditions, each field's values chosen as the NAS gave them, with
 * their names ([exifNames]) to say them by.
 */
@Immutable
@Serializable
data class PhotoFilter(
    val kinds: Set<PhotoKind> = emptySet(),
    val year: Int? = null,
    val month: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val place: Boolean? = null,
    val moment: Boolean? = null,
    val exif: Map<String, List<String>> = emptyMap(),
    val exifNames: Map<String, String> = emptyMap(),
) {
    val active get() = this != NONE

    /** The days it keeps, both ends kept; null for any time. */
    val days: ClosedRange<LocalDate>? get() {
        val start = from?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = to?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when {
            start != null || end != null -> (start ?: LocalDate.MIN)..(end ?: LocalDate.MAX)
            year != null && month != null -> YearMonth.of(year, month).let { it.atDay(1)..it.atEndOfMonth() }
            year != null -> LocalDate.of(year, 1, 1)..LocalDate.of(year, 12, 31)
            else -> null
        }
    }

    /** Only what a listing of [facets] can tell: the rest is kept, but has no say there. */
    fun within(facets: Set<Facet>): PhotoFilter = PhotoFilter(
        kinds = if (Facet.KIND in facets) kinds else emptySet(),
        year = if (Facet.TIME in facets) year else null,
        month = if (Facet.TIME in facets) month else null,
        from = if (Facet.TIME in facets) from else null,
        to = if (Facet.TIME in facets) to else null,
        place = if (Facet.PLACE in facets) place else null,
        moment = if (Facet.MOMENT in facets) moment else null,
        exif = if (Facet.EXIF in facets) exif else emptyMap(),
        exifNames = if (Facet.EXIF in facets) exifNames else emptyMap(),
    )

    /** Whether [pick] passes what can be told of it on the device: its kind, its day, its place, its Moment. */
    fun matches(pick: Pick): Boolean {
        if (kinds.isNotEmpty() && pick.kind !in kinds) return false
        days?.let { range -> if (pick.day?.let { it in range } != true) return false }
        if (place != null && pick.hasPlace != place) return false
        if (moment != null && pick.inMoment != moment) return false
        return true
    }

    /** What the NAS narrows by itself: the kinds and the EXIF conditions. */
    val nasQuery: NasQuery get() = NasQuery(
        kinds.mapTo(HashSet()) { when (it) { PhotoKind.PHOTO -> NasKind.IMAGE; PhotoKind.VIDEO -> NasKind.VIDEO; PhotoKind.LIVE -> NasKind.LIVE } },
        exif.filterValues { it.isNotEmpty() },
    )

    /** Without the time, as the years and months to choose from are listed. */
    val anyTime get() = copy(year = null, month = null, from = null, to = null)

    fun encode(): String = if (active) json.encodeToString(serializer(), this) else ""

    companion object {
        val NONE = PhotoFilter()
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): PhotoFilter =
            if (text.isEmpty()) NONE else runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(NONE)
    }
}

private fun name(kind: PhotoKind) = when (kind) {
    PhotoKind.PHOTO -> Res.string.album_filter_photo
    PhotoKind.VIDEO -> Res.string.album_filter_video
    PhotoKind.LIVE -> Res.string.label_live
}

/** `2025.10.3 – 11.2`'s two ends, each as the dates are said elsewhere. */
@Composable
private fun rangeWords(filter: PhotoFilter): String? {
    if (filter.from == null && filter.to == null) return null
    return stringResource(Res.string.album_filter_range, filter.from?.let { formatDate(it) } ?: "…", filter.to?.let { formatDate(it) } ?: "…")
}

/** One condition chosen, in the row under the subtitle: what it is of, its words, and the filter without it. */
private class Condition(val facet: Facet, val key: String, val words: String, val without: PhotoFilter)

@Composable
private fun conditions(filter: PhotoFilter): List<Condition> = buildList {
    filter.kinds.sortedBy { it.ordinal }.forEach { kind ->
        add(Condition(Facet.KIND, "kind_$kind", stringResource(name(kind)), filter.copy(kinds = filter.kinds - kind)))
    }
    val range = rangeWords(filter)
    when {
        range != null -> add(Condition(Facet.TIME, "time", range, filter.anyTime))
        filter.year != null && filter.month != null ->
            add(Condition(Facet.TIME, "time", formatYearMonth(YearMonth.of(filter.year, filter.month)), filter.anyTime))
        filter.year != null -> add(Condition(Facet.TIME, "time", stringResource(Res.string.filter_year, filter.year), filter.anyTime))
    }
    filter.place?.let { add(Condition(Facet.PLACE, "place", stringResource(if (it) Res.string.album_filter_with_place else Res.string.album_filter_without_place), filter.copy(place = null))) }
    filter.moment?.let { add(Condition(Facet.MOMENT, "moment", stringResource(if (it) Res.string.album_filter_in_moment else Res.string.album_filter_not_in_moment), filter.copy(moment = null))) }
    ExifField.entries.forEach { field ->
        filter.exif[field.key].orEmpty().forEach { value ->
            val rest = filter.exif[field.key].orEmpty() - value
            add(Condition(Facet.EXIF, "exif_${field.key}_$value", filter.exifNames[value] ?: value,
                filter.copy(exif = if (rest.isEmpty()) filter.exif - field.key else filter.exif + (field.key to rest))))
        }
    }
}

/** The filter's button beside the subtitle's count, in the accent while it filters. */
@Composable
fun FilterButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("album_filter")) {
        Icon(
            AppIcons.Filter,
            contentDescription = stringResource(if (active) Res.string.album_filter_on else Res.string.album_filter),
            tint = if (active) tokens.accent else tokens.tx2,
        )
    }
}

/**
 * The conditions chosen that the listing's [facets] hold, a row under the subtitle that scrolls
 * sideways; each chip's X takes it off, the rest of [filter] kept.
 */
@Composable
fun FilterConditions(filter: PhotoFilter, facets: Set<Facet>, onChange: (PhotoFilter) -> Unit) {
    val shown = conditions(filter).filter { it.facet in facets }
    if (shown.isEmpty()) return
    LazyRow(
        Modifier.padding(bottom = Space.s).testTag("album_filter_conditions"),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        items(shown, key = { it.key }) { condition ->
            val remove = stringResource(Res.string.album_filter_remove, condition.words)
            FilterChip(
                selected = true,
                onClick = { onChange(condition.without) },
                label = { Text(condition.words, style = AppTheme.typography.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp)) },
                trailingIcon = { Icon(AppIcons.Close, contentDescription = remove, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.testTag("album_filter_off_${condition.key}"),
            )
        }
    }
}

/** Nothing passes: says so, and the one way out. */
@Composable
fun FilterNone(onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = Space.xl * 3).padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(stringResource(Res.string.album_filter_none), style = AppTheme.typography.body, color = tokens.tx2)
        TextButton(onClick = onClear, modifier = Modifier.testTag("album_filter_clear")) { Text(stringResource(Res.string.album_filter_clear)) }
    }
}

/**
 * 过滤, the sheet: each group of conditions [facets] holds, the years and months from [days] (the
 * days the listing has photos on, whatever their time), and the NAS's EXIF conditions from [exif]
 * (null while they are read or where there are none). Every change is the filter at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: PhotoFilter,
    facets: Set<Facet>,
    days: List<LocalDate>,
    exif: Map<String, List<NasOption>>?,
    onChange: (PhotoFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true for the start, false for the end
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("album_filter_sheet")) {
        // As tall with 重置 as without, and the groups scroll under it from below its line.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Space.gutter, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.album_filter), style = AppTheme.typography.title, modifier = Modifier.weight(1f).semantics { heading() })
            if (filter.active) TextButton(onClick = { onChange(PhotoFilter.NONE) }, modifier = Modifier.testTag("album_filter_reset")) {
                Text(stringResource(Res.string.album_filter_reset))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xl)) {
            if (Facet.KIND in facets) Section(Res.string.album_filter_kind) {
                PhotoKind.entries.forEach { kind ->
                    Choice(stringResource(name(kind)), kind in filter.kinds, "album_filter_kind_$kind") {
                        onChange(filter.copy(kinds = if (kind in filter.kinds) filter.kinds - kind else filter.kinds + kind))
                    }
                }
            }
            if (Facet.TIME in facets) TimeSection(filter, days, onChange, onPick = { picking = it })
            if (Facet.PLACE in facets) Section(Res.string.album_filter_place) {
                Choice(stringResource(Res.string.album_filter_with_place), filter.place == true, "album_filter_place_yes") {
                    onChange(filter.copy(place = true.takeUnless { filter.place == true }))
                }
                Choice(stringResource(Res.string.album_filter_without_place), filter.place == false, "album_filter_place_no") {
                    onChange(filter.copy(place = false.takeUnless { filter.place == false }))
                }
            }
            if (Facet.MOMENT in facets) Section(Res.string.album_filter_moment) {
                Choice(stringResource(Res.string.album_filter_in_moment), filter.moment == true, "album_filter_moment_yes") {
                    onChange(filter.copy(moment = true.takeUnless { filter.moment == true }))
                }
                Choice(stringResource(Res.string.album_filter_not_in_moment), filter.moment == false, "album_filter_moment_no") {
                    onChange(filter.copy(moment = false.takeUnless { filter.moment == false }))
                }
            }
            if (Facet.EXIF in facets && exif != null) ExifField.entries.forEach { field ->
                val chosen = filter.exif[field.key].orEmpty()
                // What is chosen stays offered, though the other conditions now leave none of it.
                val offered = exif[field.key].orEmpty()
                val options = offered + chosen.filter { value -> offered.none { it.value == value } }.map { NasOption(it, filter.exifNames[it] ?: it) }
                if (options.isNotEmpty()) ExifSection(field, options, chosen) { option ->
                    val next = if (option.value in chosen) chosen - option.value else chosen + option.value
                    onChange(filter.copy(
                        exif = if (next.isEmpty()) filter.exif - field.key else filter.exif + (field.key to next),
                        exifNames = filter.exifNames + (option.value to option.name),
                    ))
                }
            }
        }
    }
    picking?.let { start ->
        val current = (if (start) filter.from else filter.to)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        DateDialog(
            current ?: days.firstOrNull() ?: LocalDate.now(),
            stringResource(if (start) Res.string.album_filter_from else Res.string.album_filter_to),
            onDismiss = { picking = null },
            marked = remember(days) { days.toSet() },
        ) { day ->
            picking = null
            val other = (if (start) filter.to else filter.from)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // An end before the start, or a start after the end, moves the other with it.
            val (from, to) = when {
                start -> day to (other?.takeIf { it >= day } ?: day)
                else -> (other?.takeIf { it <= day } ?: day) to day
            }
            onChange(filter.copy(year = null, month = null, from = from.toString(), to = to.toString()))
        }
    }
}

/** A group's heading and its chips, wrapping onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: StringResource, trailing: (@Composable () -> Unit)? = null, chips: @Composable () -> Unit) {
    // Every heading as tall as one with 展开, so the groups stand evenly apart.
    Row(Modifier.fillMaxWidth().padding(top = Space.s).heightIn(min = 48.dp).padding(start = Space.gutter, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = AppTheme.typography.label, color = tokens.tx2, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
    FlowRow(
        Modifier.padding(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) { chips() }
}

/** One choice, on the accent's soft tint with a check when chosen. */
@Composable
private fun Choice(words: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = chosen,
        onClick = onClick,
        // One line, a long lens's name cut short, as the chip keeps its height.
        label = { Text(words, style = AppTheme.typography.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (chosen) ({ SelectedCheck() }) else null,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * 时间: the years with photos, and once one is chosen its months, as the search has them; then
 * 自定义, a range of days, its two ends a row each that opens the calendar ([onPick]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeSection(filter: PhotoFilter, days: List<LocalDate>, onChange: (PhotoFilter) -> Unit, onPick: (Boolean) -> Unit) {
    val years = remember(days) { days.map { it.year }.distinct().sortedDescending() }
    val months = remember(days, filter.year) { filter.year?.let { year -> days.filter { it.year == year }.map { it.monthValue }.distinct().sortedDescending() }.orEmpty() }
    val ranged = filter.from != null || filter.to != null
    var custom by rememberSaveable { mutableStateOf(false) }
    Section(Res.string.album_filter_time) {
        Choice(stringResource(Res.string.filter_all), !ranged && !custom && filter.year == null, "album_filter_year_all") {
            custom = false
            onChange(filter.anyTime)
        }
        years.forEach { year ->
            Choice(stringResource(Res.string.filter_year, year), !ranged && filter.year == year, "album_filter_year_$year") {
                custom = false
                onChange(filter.anyTime.copy(year = year))
            }
        }
        Choice(rangeWords(filter) ?: stringResource(Res.string.album_filter_custom), ranged || custom, "album_filter_custom") {
            custom = true
            if (!ranged) onPick(true)
        }
    }
    if (!ranged && filter.year != null && months.size > 1) FlowRow(
        Modifier.padding(horizontal = Space.gutter).padding(top = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Choice(stringResource(Res.string.filter_whole_year), filter.month == null, "album_filter_month_all") { onChange(filter.copy(month = null)) }
        months.forEach { month ->
            Choice(formatMonthOnly(month), filter.month == month, "album_filter_month_$month") { onChange(filter.copy(month = month)) }
        }
    }
    if (ranged || custom) Box(Modifier.padding(top = Space.s)) {
        SegmentedGroup(null, null, listOf(
            { shapes -> ActionRow(AppIcons.Calendar, stringResource(Res.string.album_filter_from), shapes, supporting = filter.from?.let { formatDate(it) }, tag = "album_filter_from") { onPick(true) } },
            { shapes -> ActionRow(AppIcons.Calendar, stringResource(Res.string.album_filter_to), shapes, supporting = filter.to?.let { formatDate(it) }, tag = "album_filter_to") { onPick(false) } },
        ))
    }
}

/** One of the NAS's EXIF conditions: its values, the first two lines of them until 展开. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExifSection(field: ExifField, options: List<NasOption>, chosen: List<String>, onToggle: (NasOption) -> Unit) {
    var open by rememberSaveable(field) { mutableStateOf(false) }
    val many = options.size > FOLDED
    Section(field.label, trailing = if (many) ({
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag("album_filter_more_${field.key}")) {
            Text(stringResource(if (open) Res.string.album_filter_less else Res.string.album_filter_more))
        }
    }) else null) {
        // Folded, what is chosen stays in sight.
        val shown = if (open || !many) options else options.take(FOLDED) + options.drop(FOLDED).filter { it.value in chosen }
        shown.forEach { option -> Choice(option.name, option.value in chosen, "album_filter_${field.key}_${option.name}") { onToggle(option) } }
    }
}

/** How many of a condition's values show before 展开: about two lines of chips. */
private const val FOLDED = 6

