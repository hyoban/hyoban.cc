package cc.hyoban.moments.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.DayMark
import cc.hyoban.moments.ui.MapPoint

// What the modules put beside the Moments, on the timeline, a trip's page, a place's and a day's:
// records of their own (a bill, a charge, a device bought, a day's driving), loose on their day or
// at the foot of the Moment or stop they go with; stops of their own (a check-in), which records
// go with as they do with a Moment; each day's figures (what it spent); and what a day's map marks.
// Each module draws its own; the pages lay them out by their time.

/**
 * A record a module puts on the timeline, drawn by the module: as a row on the page, a line in a
 * records bubble, and at the foot of the Moment's or stop's bubble it goes with.
 */
interface DayRecord {
    val key: String

    /** Its time of day, `HH:mm:ss`, or empty for one known by its day alone, which comes first in its day. */
    val time: String

    /** When it was, in epoch milliseconds, parting the day's footprints; null for one known by its day alone. */
    val instant: Long?

    /** Whether it ends its day, after all of it, as a day's driving does, rather than coming by its time. */
    val endsDay: Boolean get() = false

    /** Whether, going with a Moment on the page, it is in the Moment's card rather than under it. */
    val inCard: Boolean get() = true

    /** What opens it, alone in its bubble; [onDay] opens its day. */
    @Composable fun open(onDay: (() -> Unit)?): (() -> Unit)?

    /** As a row on the page. */
    @Composable fun Row(modifier: Modifier)

    /** As a line in a records bubble, [large] for a large font and [timed] when the bubble has a column for times. */
    @Composable fun Line(large: Boolean, timed: Boolean)

    /** At the foot of the bubble it goes with, whose own time is [bubbleTime], `15:04`; it leaves out a time the same. */
    @Composable fun Attached(large: Boolean, bubbleTime: String?) {}

    /** Under the Moment or stop it goes with, on the page. */
    @Composable fun AttachedRow() {}
}

/** What a module's records on the timeline are read from: the entries, and which days are shown. */
class DayRecordsScope(val entries: Collection<kotlinx.serialization.json.JsonObject>, val inDay: (String) -> Boolean)

/**
 * A stop a module puts among the Moments (a check-in): sent from the right in the conversation as a
 * Moment is, the records that go with it ([attached]) at its foot.
 */
interface DayAnchor {
    /** Its key among the day's items, which a way to it is told by too. */
    val key: String

    /** What a record that goes with it names it by. */
    val uid: String
    val time: String
    val instant: Long?

    /** Its place, which a page of that place needn't name. */
    val place: String?

    /** As a bubble of the conversation. */
    @Composable fun Bubble(joinedAbove: Boolean, joinedBelow: Boolean, modifier: Modifier, attached: List<DayRecord>)

    /** As a row on the page, [hidePlace] the place the page is of. */
    @Composable fun Row(modifier: Modifier, hidePlace: String?, attached: List<DayRecord>)
}

/** What a module says of a day by its date: what it spent. */
fun interface DayFigure {
    @Composable fun of(day: String): String?
}

/** What a module says of the whole of what a page shows, at its head: what was spent at a place. */
fun interface SpanFigure {
    @Composable fun text(): String?
}

/** What a day's map has to mark with: the day, its Moments, a place's point, and the footprints' point at a time. */
class MarkScope(
    val day: String,
    val moments: List<MomentRecord>,
    val placed: (String?) -> MapPoint?,
    val tracked: (Long?) -> MapPoint?,
    /** The icon of a place's kind, as its rows show it. */
    val kindIcon: (String?) -> androidx.compose.ui.graphics.vector.ImageVector?,
    val records: TimelineRecords,
)

/** What a module marks on a day's map. */
fun interface DayMarks {
    fun marks(scope: MarkScope): List<DayMark>
}

/** What the modules put beside a page's Moments, by where it goes, joined by their plugins. */
class TimelineRecords(
    /** Records on their own, by day. */
    val loose: Map<String, List<DayRecord>> = emptyMap(),
    /** Stops of the modules' own, by day. */
    val anchors: Map<String, List<DayAnchor>> = emptyMap(),
    /** Records that go with a Moment or a stop, by its uid, in the order its foot lists them. */
    val attached: Map<String, List<DayRecord>> = emptyMap(),
    val figures: List<DayFigure> = emptyList(),
    val totals: List<SpanFigure> = emptyList(),
    val marks: List<DayMarks> = emptyList(),
    /** What each module joined, as its own pages (its search) read it. */
    private val data: Map<Any, Any> = emptyMap(),
) {
    /** What a module joined, by its key (its `Module`), as it keeps it; null while it is off. */
    fun <T> of(module: Any): T? {
        @Suppress("UNCHECKED_CAST")
        return data[module] as T?
    }

    /** What of it falls on [date] alone, as a day's page shows it; what goes with a Moment stays whole. */
    fun onDay(date: String) = TimelineRecords(
        loose.filterKeys { it == date }, anchors.filterKeys { it == date }, attached, figures, totals, marks, data,
    )

    /** How many stops of their own the modules put here. */
    val anchorCount: Int get() = anchors.values.sumOf { it.size }

    companion object {
        val EMPTY = TimelineRecords()
    }
}

/** What a day holds, in the order the timeline shows it: newest first. */
sealed interface DayItem {
    val key: String

    data class Moment(val moment: MomentRecord) : DayItem {
        override val key get() = moment.uid
    }

    /** A module's own record, as its plugin draws it. */
    data class Record(val record: DayRecord) : DayItem {
        override val key get() = record.key
    }

    /** A module's own stop, as its plugin draws it. */
    data class Anchor(val anchor: DayAnchor) : DayItem {
        override val key get() = anchor.key
    }

    /** A piece of the day's footprints, between the records it moved between. */
    data class Track(val day: String, val piece: TrackPiece) : DayItem {
        override val key get() = "track-$day-${piece.start}"
    }
}

/**
 * [moments], the modules' [records] and [anchors] of one day merged by the time of day, newest
 * first or, with [oldestFirst], from the morning. The day's footprints ([track]) are cut at every
 * record with a time, each piece between the two it moved between. A record that ends its day
 * comes after all of it.
 */
fun dayItems(
    moments: List<MomentRecord>,
    records: List<DayRecord> = emptyList(),
    anchors: List<DayAnchor> = emptyList(),
    oldestFirst: Boolean = false,
    day: String = "",
    track: DayTrack? = null,
): List<DayItem> {
    val (last, byTime) = records.partition { it.endsDay }
    // A record known by its day alone, a device or a subscription, comes first in the day.
    val timed = moments.map { Triple(it.occurredAt.drop(11).take(8), instantOf(it.occurredAt), DayItem.Moment(it) as DayItem) } +
        byTime.map { Triple(it.time, it.instant, DayItem.Record(it) as DayItem) } +
        anchors.map { Triple(it.time, it.instant, DayItem.Anchor(it) as DayItem) }
    // From the morning: each record with a time cuts the footprints, and the piece before it goes in ahead of it.
    val morning = timed.sortedByDescending { it.first }.asReversed()
    val cuts = morning.mapNotNull { it.second }
    val pieces = track?.pieces(cuts).orEmpty()
    val read = mutableListOf<DayItem>()
    var p = 0
    for ((_, at, item) in morning) {
        if (at != null) while (p < pieces.size && pieces[p].start < at) read += DayItem.Track(day, pieces[p++])
        read += item
    }
    while (p < pieces.size) read += DayItem.Track(day, pieces[p++])
    read += last.map { DayItem.Record(it) }
    return if (oldestFirst) read else read.asReversed()
}
