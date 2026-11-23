package cc.hyoban.moments.ui.feed

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.ui.LocalSettings

/** What a page reads its modules' records over: the timeline's days, a trip's span, or a place. */
sealed interface JoinSpan {
    /** The timeline's, its days narrowed to [filter]. */
    class Days(val filter: TimeFilter) : JoinSpan {
        fun keep(day: String) =
            (filter.year == null || day.take(4).toInt() == filter.year) && (filter.month == null || day.substring(5, 7).toInt() == filter.month)
    }

    /** A trip's page: what is in its span. */
    class Trip(val trip: TripRecord) : JoinSpan

    /** A place's page: what was there. */
    class Place(val id: String) : JoinSpan
}

/**
 * What the modules join over: the span, the Moments the page shows ([moments]), every Moment
 * ([allMoments]) and whether 演示模式 hides what is private.
 */
class JoinScope(val span: JoinSpan, val moments: List<MomentRecord>, val allMoments: List<MomentRecord> = moments, val demo: Boolean = false)

/**
 * The records a page shows beside its Moments, as the modules join them one after another
 * ([TimelineJoin.order]): what records go with first (the check-ins, stops of their own), then
 * what may take another's record to show it with its own (a charge the bill paid at its time).
 */
class JoinBuilder {
    private val loose = LinkedHashMap<String, MutableList<DayRecord>>()
    private val anchors = LinkedHashMap<String, MutableList<DayAnchor>>()
    private val attached = LinkedHashMap<String, MutableList<Pair<Int, DayRecord>>>()
    private val figures = mutableListOf<DayFigure>()
    private val totals = mutableListOf<SpanFigure>()
    private val marks = mutableListOf<DayMarks>()
    private val data = mutableMapOf<Any, Any>()

    /** [record] on its own on [day]. */
    fun loose(day: String, record: DayRecord) {
        loose.getOrPut(day) { mutableListOf() } += record
    }

    /** A stop of the module's own on [day]. */
    fun anchor(day: String, anchor: DayAnchor) {
        anchors.getOrPut(day) { mutableListOf() } += anchor
    }

    /** [record] with the Moment or stop [uid], at its foot by [rank], lowest first. */
    fun attach(uid: String, record: DayRecord, rank: Int) {
        attached.getOrPut(uid) { mutableListOf() } += rank to record
    }

    /** Takes the records [keys] out of where they were shown, as another module shows them with its own. */
    fun take(keys: Set<String>) {
        if (keys.isEmpty()) return
        loose.values.forEach { day -> day.removeAll { it.key in keys } }
        attached.values.forEach { foot -> foot.removeAll { it.second.key in keys } }
    }

    fun figure(figure: DayFigure) {
        figures += figure
    }

    fun total(total: SpanFigure) {
        totals += total
    }

    fun marks(marks: DayMarks) {
        this.marks += marks
    }

    /** What a module joined, by its [key] (its `Module`), as it and the modules after it read it. */
    fun put(key: Any, value: Any) {
        data[key] = value
    }

    fun <T> of(module: Any): T? {
        @Suppress("UNCHECKED_CAST")
        return data[module] as T?
    }

    fun build(records: Map<String, List<DayRecord>> = emptyMap()): TimelineRecords {
        val own = LinkedHashMap<String, List<DayRecord>>()
        loose.forEach { (day, list) -> if (list.isNotEmpty()) own[day] = list.toList() }
        records.forEach { (day, list) -> own[day] = own[day].orEmpty() + list }
        return TimelineRecords(
            own,
            anchors.mapValues { it.value.toList() },
            attached.mapValues { (_, foot) -> foot.sortedBy { it.first }.map { it.second } }.filterValues { it.isNotEmpty() },
            figures.toList(), totals.toList(), marks.toList(), data.toMap(),
        )
    }
}

/** How a module joins its records to a page's Moments ([cc.hyoban.moments.plugins.ModulePlugin.join]). */
interface TimelineJoin {
    /** Where it comes among the joins: what it goes with joins first. */
    val order: Int

    /** What it joins from, read in the composition; the join is worked out again when it changes. */
    @Composable fun input(container: AppContainer): Any?

    /** Adds its records to [into], read from its [input]. */
    fun join(scope: JoinScope, input: Any?, into: JoinBuilder)
}

/** The joins of the modules that are on, in their order, each with what it joins from. */
@Composable
fun rememberJoinInputs(container: AppContainer): List<Pair<TimelineJoin, Any?>> {
    val settings = LocalSettings.current
    // Every module's input is read, on or off, so turning one on or off keeps the composition's shape.
    val all = Plugins.joins.map { (plugin, join) -> Triple(plugin, join, join.input(container)) }
    return all.filter { (plugin) -> settings.isOn(plugin.module) }.map { (_, join, input) -> join to input }
}

/** The modules' records over [scope], joined from [inputs]. */
fun joinRecords(scope: JoinScope, inputs: List<Pair<TimelineJoin, Any?>>): JoinBuilder =
    JoinBuilder().apply { inputs.forEach { (join, input) -> join.join(scope, input, this) } }
