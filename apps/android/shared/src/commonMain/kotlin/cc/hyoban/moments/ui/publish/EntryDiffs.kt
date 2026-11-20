package cc.hyoban.moments.ui.publish

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.occasions.Count
import cc.hyoban.moments.data.occasions.Occasions
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.things.Period
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.data.things.checkinOf
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.things.priceOf
import cc.hyoban.moments.ui.things.yuan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * One field of an entry the phone changed: its name, what it was (null when it wasn't said) and
 * what it is now; [words] when it is writing, shown letter by letter rather than struck through whole.
 */
data class EntryDiff(val label: StringResource, val from: String?, val to: String, val icon: ImageVector, val words: Boolean = false)

/**
 * What a change to a check-in, an occasion, a device or a subscription changed, field by field,
 * in the order its sheet lists them; empty when nothing it shows differs.
 */
@Composable
fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
    val none = stringResource(Res.string.change_none)
    val yes = stringResource(Res.string.change_yes)
    val no = stringResource(Res.string.change_no)
    val public = stringResource(Res.string.visibility_public)
    val private = stringResource(Res.string.visibility_private)
    val diffs = mutableListOf<EntryDiff>()
    fun words(label: StringResource, from: String?, to: String?, icon: ImageVector) {
        if (from.orEmpty() != to.orEmpty()) diffs += EntryDiff(label, from.orEmpty(), to.orEmpty(), icon, words = true)
    }
    fun add(label: StringResource, from: String?, to: String?, icon: ImageVector) {
        if (from != to) diffs += EntryDiff(label, from, to ?: none, icon)
    }
    val once = stringResource(Res.string.occasion_once)
    val lunarly = stringResource(Res.string.occasion_lunar)
    val yearly = stringResource(Res.string.occasion_each_year)
    val counts = mapOf(
        Count.YEARS to stringResource(Res.string.occasion_count_years),
        Count.AGE to stringResource(Res.string.occasion_count_age),
        Count.NONE to stringResource(Res.string.occasion_count_none),
    )
    val periods = mapOf(
        Period.YEAR to stringResource(Res.string.subscription_period_year),
        Period.MONTH to stringResource(Res.string.subscription_period_month),
        Period.ONCE to stringResource(Res.string.subscription_period_once),
    )
    fun flag(value: Boolean) = if (value) yes else no
    fun day(value: Any?) = value?.toString()?.let(::formatDate)

    val checkins = checkinOf(before) to checkinOf(after)
    val occasions = Occasions.occasion(before) to Occasions.occasion(after)
    val devices = Things.device(before) to Things.device(after)
    val subscriptions = Things.subscription(before) to Things.subscription(after)
    when {
        checkins.first != null && checkins.second != null -> {
            val (was, now) = checkins.first!! to checkins.second!!
            val span = { c: cc.hyoban.moments.data.things.Checkin ->
                listOfNotNull(formatDate(c.at.take(10)), listOfNotNull(formatTime(c.at), c.end?.let(::formatTime)).joinToString("–").ifEmpty { null }).joinToString(" ")
            }
            add(Res.string.change_place_name, was.venue, now.venue, AppIcons.Pen)
            add(Res.string.change_time, span(was), span(now), AppIcons.Clock)
            add(Res.string.change_place, was.place?.let { places[it]?.name ?: it }, now.place?.let { places[it]?.name ?: it }, AppIcons.Pin)
        }
        occasions.first != null && occasions.second != null -> {
            val (was, now) = occasions.first!! to occasions.second!!
            val repeat = { repeat: Boolean, lunar: Boolean -> if (!repeat) once else if (lunar) lunarly else yearly }
            val count = { count: Count -> counts.getValue(count) }
            add(Res.string.occasion_name, was.name, now.name, AppIcons.Pen)
            add(Res.string.occasion_date, day(was.first), day(now.first), AppIcons.Calendar)
            add(Res.string.change_repeat, repeat(was.repeat, was.lunar), repeat(now.repeat, now.lunar), AppIcons.Refresh)
            add(Res.string.change_count, count(was.count), count(now.count), AppIcons.Tag)
            add(Res.string.occasion_first_label, was.firstLabel, now.firstLabel, AppIcons.Flag)
            words(Res.string.occasion_note, was.note, now.note, AppIcons.Notes)
        }
        devices.first != null && devices.second != null -> {
            val (was, now) = devices.first!! to devices.second!!
            add(Res.string.device_name, was.name, now.name, AppIcons.Pen)
            add(Res.string.device_category, was.category, now.category, AppIcons.Category)
            add(Res.string.device_bought, day(was.bought), day(now.bought), AppIcons.Calendar)
            add(Res.string.device_price, yuan(was.price), yuan(now.price), AppIcons.Cash)
            add(Res.string.device_sold_on, day(was.soldOn), day(now.soldOn), AppIcons.Calendar)
            add(Res.string.device_sold_price, was.soldPrice?.let(::yuan), now.soldPrice?.let(::yuan), AppIcons.Cash)
            add(Res.string.device_idle, flag(was.idle), flag(now.idle), AppIcons.Pause)
            words(Res.string.device_note, was.note, now.note, AppIcons.Notes)
            // Its photo, another or none.
            if (was.image != now.image) diffs += EntryDiff(Res.string.change_photos, null, if (now.image == null) none else stringResource(Res.string.change_replaced), AppIcons.Image)
        }
        subscriptions.first != null && subscriptions.second != null -> {
            val (was, now) = subscriptions.first!! to subscriptions.second!!
            val period = { period: Period -> periods.getValue(period) }
            add(Res.string.subscription_name, was.name, now.name, AppIcons.Pen)
            add(Res.string.subscription_price, priceOf(was.price, was.currency) + " " + period(was.period), priceOf(now.price, now.currency) + " " + period(now.period), AppIcons.Cash)
            add(Res.string.subscription_paid, day(was.paid), day(now.paid), AppIcons.Calendar)
            add(Res.string.subscription_renews_on, day(was.renews), day(now.renews), AppIcons.Calendar)
            add(Res.string.subscription_auto_renew, flag(was.autoRenew), flag(now.autoRenew), AppIcons.Refresh)
            add(Res.string.subscription_paid_with, was.paidWith, now.paidWith, AppIcons.Card)
            add(Res.string.subscription_ended, day(was.ended), day(now.ended), AppIcons.Calendar)
            words(Res.string.subscription_note, was.note, now.note, AppIcons.Notes)
            add(Res.string.change_link, was.link, now.link, AppIcons.Link)
        }
    }
    // Public or private, a change of its own: one made private leaves the site.
    val wasPrivate = Entries.isPrivate(before)
    val nowPrivate = Entries.isPrivate(after)
    if (wasPrivate != nowPrivate) {
        diffs += EntryDiff(Res.string.section_visibility, if (wasPrivate) private else public, if (nowPrivate) private else public, if (nowPrivate) AppIcons.EyeOff else AppIcons.Eye)
    }
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
