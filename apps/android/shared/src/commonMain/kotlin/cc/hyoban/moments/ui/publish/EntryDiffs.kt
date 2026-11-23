package cc.hyoban.moments.ui.publish

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * One field of an entry the phone changed: its name, what it was (null when it wasn't said) and
 * what it is now; [words] when it is writing, shown letter by letter rather than struck through whole.
 */
data class EntryDiff(val label: StringResource, val from: String?, val to: String, val icon: ImageVector, val words: Boolean = false)

/** A diff builder for an entry's fields: [add] a value changed, [words] writing changed letter by letter. */
class EntryDiffs internal constructor(private val none: String) {
    internal val diffs = mutableListOf<EntryDiff>()

    fun words(label: StringResource, from: String?, to: String?, icon: ImageVector) {
        if (from.orEmpty() != to.orEmpty()) diffs += EntryDiff(label, from.orEmpty(), to.orEmpty(), icon, words = true)
    }

    fun add(label: StringResource, from: String?, to: String?, icon: ImageVector) {
        if (from != to) diffs += EntryDiff(label, from, to ?: none, icon)
    }

    operator fun plusAssign(diff: EntryDiff) {
        diffs += diff
    }
}

/** The fields an entry's change changed, as [build] adds them. */
@Composable
fun entryDiffsOf(build: @Composable EntryDiffs.() -> Unit): List<EntryDiff> {
    val diffs = EntryDiffs(stringResource(Res.string.change_none))
    diffs.build()
    return diffs.diffs
}

/** A yes or no, as a change says it. */
@Composable
fun flagName(value: Boolean): String = stringResource(if (value) Res.string.change_yes else Res.string.change_no)

/** What any entry's change changes beside its own fields: who sees it, how it is reached, planned or locked. */
@Composable
internal fun commonEntryDiffs(before: JsonObject, after: JsonObject): List<EntryDiff> {
    val public = stringResource(Res.string.visibility_public)
    val private = stringResource(Res.string.visibility_private)
    val yes = flagName(true)
    val no = flagName(false)
    fun flag(value: Boolean) = if (value) yes else no
    val diffs = mutableListOf<EntryDiff>()
    // Public or private, a change of its own: one made private leaves the site.
    val wasPrivate = Entries.isPrivate(before)
    val nowPrivate = Entries.isPrivate(after)
    if (wasPrivate != nowPrivate) {
        diffs += EntryDiff(Res.string.section_visibility, if (wasPrivate) private else public, if (nowPrivate) private else public, if (nowPrivate) AppIcons.EyeOff else AppIcons.Eye)
    }
    // How it is reached from the place before it, as a Moment's.
    val reached = { entry: JsonObject -> (entry["reachedBy"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
    if ((reached(before) ?: "drive") != (reached(after) ?: "drive")) diffs += EntryDiff(Res.string.change_reached_by, reachedByName(reached(before)), reachedByName(reached(after)), AppIcons.Route)
    // Planned or lived, and locked or not, as a Moment's are.
    val flagOf = { entry: JsonObject, key: String -> (entry[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
    if (flagOf(before, "planned") != flagOf(after, "planned")) {
        diffs += EntryDiff(Res.string.trip_planned, flag(flagOf(before, "planned")), flag(flagOf(after, "planned")), AppIcons.Calendar)
    }
    if (flagOf(before, "locked") != flagOf(after, "locked")) {
        diffs += EntryDiff(Res.string.moment_lock, flag(flagOf(before, "locked")), flag(flagOf(after, "locked")), AppIcons.Lock)
    }
    return diffs
}


/** How a planned record is reached, by name: driving when it doesn't say. */
@Composable
fun reachedByName(by: String?): String = stringResource(
    when (cc.hyoban.moments.data.ai.PlanAssistant.byOf(by)) {
        cc.hyoban.moments.data.trips.Routers.By.WALK -> Res.string.plan_by_walk
        cc.hyoban.moments.data.trips.Routers.By.BIKE -> Res.string.plan_by_bike
        cc.hyoban.moments.data.trips.Routers.By.TRAIN -> Res.string.plan_by_train
        else -> Res.string.plan_by_drive
    },
)

/**
 * What a change to a trip changed, against the site's [before] collection, field by field: its
 * name, days, who sees it, whether it is a plan, the records it takes in or leaves out, its covers,
 * the check-ins it runs between (named by [venueOf]), its route's hints, its music and its note.
 */
@Composable
fun tripDiffs(before: JsonObject, after: JsonObject, venueOf: (String) -> String?): List<EntryDiff> {
    val diffs = mutableListOf<EntryDiff>()
    val none = stringResource(Res.string.change_none)
    val yes = stringResource(Res.string.change_yes)
    val no = stringResource(Res.string.change_no)
    fun text(o: JsonObject?, key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun obj(o: JsonObject, key: String) = o[key] as? JsonObject
    fun count(o: JsonObject?, key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonArray)?.size ?: 0
    fun add(label: StringResource, from: String?, to: String?, icon: ImageVector, words: Boolean = false) {
        if (from != to) diffs += EntryDiff(label, from ?: none, to ?: none, icon, words)
    }
    add(Res.string.change_place_name, text(before, "title"), text(after, "title"), AppIcons.Pen, words = true)
    val span = { o: JsonObject ->
        val at = { key: String -> text(obj(o, "span"), key)?.let { listOfNotNull(formatDate(it.take(10)), formatTime(it)).joinToString(" ") } }
        listOfNotNull(at("start"), at("end")).joinToString(" – ")
    }
    add(Res.string.change_span, span(before), span(after), AppIcons.Calendar)
    val seen = @Composable { o: JsonObject -> stringResource(if (Entries.isPrivate(o)) Res.string.visibility_private else Res.string.visibility_public) }
    val (wasSeen, nowSeen) = seen(before) to seen(after)
    if (wasSeen != nowSeen) diffs += EntryDiff(Res.string.section_visibility, wasSeen, nowSeen, if (Entries.isPrivate(after)) AppIcons.EyeOff else AppIcons.Eye)
    add(Res.string.trip_planned, if (Entries.isPlanned(before)) yes else no, if (Entries.isPlanned(after)) yes else no, AppIcons.Calendar)
    // The records it takes in from outside its days, or leaves out of them: what is in now and wasn't, and what went.
    val inOf = { o: JsonObject -> Entries.strings(o, "include").toSet() }
    val outOf = { o: JsonObject -> Entries.strings(o, "exclude").toSet() }
    val taken = (inOf(after) - inOf(before)) + (outOf(before) - outOf(after))
    val left = (outOf(after) - outOf(before)) + (inOf(before) - inOf(after))
    if (taken.isNotEmpty() || left.isNotEmpty()) {
        val said = listOfNotNull(
            stringResource(Res.string.change_records_in, taken.size).takeIf { taken.isNotEmpty() },
            stringResource(Res.string.change_records_out, left.size).takeIf { left.isNotEmpty() },
        ).joinToString("\u2003")
        diffs += EntryDiff(Res.string.change_records, null, said, AppIcons.Notes)
    }
    val covers = { o: JsonObject -> Entries.coversOf(o) }
    if (covers(before) != covers(after)) {
        val (was, now) = covers(before).size to covers(after).size
        diffs += if (was == now) EntryDiff(Res.string.change_cover, null, stringResource(Res.string.change_replaced), AppIcons.Image)
        else EntryDiff(Res.string.change_cover, stringResource(Res.string.change_photo_count, was), stringResource(Res.string.change_photo_count, now), AppIcons.Image)
    }
    val bounds = { o: JsonObject -> listOfNotNull(text(obj(o, "bounds"), "from"), text(obj(o, "bounds"), "to")).map { venueOf(it) ?: it }.joinToString(" – ").ifEmpty { null } }
    add(Res.string.change_bounds, bounds(before), bounds(after), AppIcons.Flag)
    val legs = @Composable { o: JsonObject, key: String -> count(obj(o, "route"), key).let { if (it == 0) null else stringResource(Res.string.change_legs_count, it) } }
    add(Res.string.change_straight_legs, legs(before, "straightLegs"), legs(after, "straightLegs"), AppIcons.Route)
    add(Res.string.change_same_way, legs(before, "sameWayBack"), legs(after, "sameWayBack"), AppIcons.Refresh)
    add(Res.string.change_music, text(before, "music"), text(after, "music"), AppIcons.Volume)
    if (text(before, "note").orEmpty() != text(after, "note").orEmpty()) diffs += EntryDiff(Res.string.change_note, text(before, "note").orEmpty(), text(after, "note").orEmpty(), AppIcons.Notes, words = true)
    return diffs
}
