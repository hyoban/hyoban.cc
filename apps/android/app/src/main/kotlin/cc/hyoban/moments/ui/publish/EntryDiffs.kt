package cc.hyoban.moments.ui.publish

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import cc.hyoban.moments.R
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

/** One field of an entry the phone changed: its name, what it was (null when it wasn't said) and what it is now. */
data class EntryDiff(val label: Int, val from: String?, val to: String, val icon: ImageVector)

/**
 * What a change to a check-in, an occasion, a device or a subscription changed, field by field,
 * in the order its sheet lists them; empty when nothing it shows differs.
 */
@Composable
fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
    val none = stringResource(R.string.change_none)
    val yes = stringResource(R.string.change_yes)
    val no = stringResource(R.string.change_no)
    val public = stringResource(R.string.visibility_public)
    val private = stringResource(R.string.visibility_private)
    val diffs = mutableListOf<EntryDiff>()
    fun add(label: Int, from: String?, to: String?, icon: ImageVector) {
        if (from != to) diffs += EntryDiff(label, from, to ?: none, icon)
    }
    val once = stringResource(R.string.occasion_once)
    val lunarly = stringResource(R.string.occasion_lunar)
    val yearly = stringResource(R.string.occasion_each_year)
    val counts = mapOf(
        Count.YEARS to stringResource(R.string.occasion_count_years),
        Count.AGE to stringResource(R.string.occasion_count_age),
        Count.NONE to stringResource(R.string.occasion_count_none),
    )
    val periods = mapOf(
        Period.YEAR to stringResource(R.string.subscription_period_year),
        Period.MONTH to stringResource(R.string.subscription_period_month),
        Period.ONCE to stringResource(R.string.subscription_period_once),
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
            add(R.string.change_place_name, was.venue, now.venue, AppIcons.Pen)
            add(R.string.change_time, span(was), span(now), AppIcons.Clock)
            add(R.string.change_place, was.place?.let { places[it]?.name ?: it }, now.place?.let { places[it]?.name ?: it }, AppIcons.Pin)
        }
        occasions.first != null && occasions.second != null -> {
            val (was, now) = occasions.first!! to occasions.second!!
            val repeat = { repeat: Boolean, lunar: Boolean -> if (!repeat) once else if (lunar) lunarly else yearly }
            val count = { count: Count -> counts.getValue(count) }
            add(R.string.occasion_name, was.name, now.name, AppIcons.Pen)
            add(R.string.occasion_date, day(was.first), day(now.first), AppIcons.Calendar)
            add(R.string.change_repeat, repeat(was.repeat, was.lunar), repeat(now.repeat, now.lunar), AppIcons.Refresh)
            add(R.string.change_count, count(was.count), count(now.count), AppIcons.Tag)
            add(R.string.occasion_first_label, was.firstLabel, now.firstLabel, AppIcons.Flag)
            add(R.string.occasion_note, was.note, now.note, AppIcons.Notes)
        }
        devices.first != null && devices.second != null -> {
            val (was, now) = devices.first!! to devices.second!!
            add(R.string.device_name, was.name, now.name, AppIcons.Pen)
            add(R.string.device_category, was.category, now.category, AppIcons.Category)
            add(R.string.device_bought, day(was.bought), day(now.bought), AppIcons.Calendar)
            add(R.string.device_price, yuan(was.price), yuan(now.price), AppIcons.Cash)
            add(R.string.device_sold_on, day(was.soldOn), day(now.soldOn), AppIcons.Calendar)
            add(R.string.device_sold_price, was.soldPrice?.let(::yuan), now.soldPrice?.let(::yuan), AppIcons.Cash)
            add(R.string.device_idle, flag(was.idle), flag(now.idle), AppIcons.Pause)
            add(R.string.device_note, was.note, now.note, AppIcons.Notes)
        }
        subscriptions.first != null && subscriptions.second != null -> {
            val (was, now) = subscriptions.first!! to subscriptions.second!!
            val period = { period: Period -> periods.getValue(period) }
            add(R.string.subscription_name, was.name, now.name, AppIcons.Pen)
            add(R.string.subscription_price, priceOf(was.price, was.currency) + " " + period(was.period), priceOf(now.price, now.currency) + " " + period(now.period), AppIcons.Cash)
            add(R.string.subscription_paid, day(was.paid), day(now.paid), AppIcons.Calendar)
            add(R.string.subscription_renews_on, day(was.renews), day(now.renews), AppIcons.Calendar)
            add(R.string.subscription_auto_renew, flag(was.autoRenew), flag(now.autoRenew), AppIcons.Refresh)
            add(R.string.subscription_paid_with, was.paidWith, now.paidWith, AppIcons.Card)
            add(R.string.subscription_ended, day(was.ended), day(now.ended), AppIcons.Calendar)
            add(R.string.subscription_note, was.note, now.note, AppIcons.Notes)
            add(R.string.change_link, was.link, now.link, AppIcons.Link)
        }
    }
    // Public or private, a change of its own: one made private leaves the site.
    val wasPrivate = Entries.isPrivate(before)
    val nowPrivate = Entries.isPrivate(after)
    if (wasPrivate != nowPrivate) {
        diffs += EntryDiff(R.string.section_visibility, if (wasPrivate) private else public, if (nowPrivate) private else public, if (nowPrivate) AppIcons.EyeOff else AppIcons.Eye)
    }
    return diffs
}
