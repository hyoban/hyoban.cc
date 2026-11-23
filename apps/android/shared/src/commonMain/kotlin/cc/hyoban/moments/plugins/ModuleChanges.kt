package cc.hyoban.moments.plugins

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cc.hyoban.moments.data.occasions.Count
import cc.hyoban.moments.data.occasions.Occasions
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.entryUid
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.EntryChange
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.data.things.Period
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.data.things.checkinOf
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.publish.ChangeSection
import cc.hyoban.moments.ui.publish.ChangesScope
import cc.hyoban.moments.ui.publish.EntryDiff
import cc.hyoban.moments.ui.publish.EntryFace
import cc.hyoban.moments.ui.publish.PartDiff
import cc.hyoban.moments.ui.publish.entryDiffsOf
import cc.hyoban.moments.ui.publish.entrySection
import cc.hyoban.moments.ui.publish.flagName
import cc.hyoban.moments.ui.things.priceOf
import cc.hyoban.moments.ui.things.yuan
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.stringResource

// What each module lists on 待发布: its check-ins, occasions, devices and subscriptions, and bills;
// and what changed of each of their parts, field by field ([PartDiff]).

/** The check-ins, Swarm's or the footprints' stays, and the check-in a Moment carries. */
internal object CheckinChanges : ChangeSection {
    override val order = 0

    override fun owns(entry: JsonObject) = checkinOf(entry) != null

    override fun LazyListScope.section(scope: ChangesScope, own: List<EntryChange>, state: Any?) =
        entrySection(Res.string.section_changed_checkins, own, scope) { entry ->
            val checkin = checkinOf(entry)!!
            val end = (entry["end"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::formatTime)
            EntryFace(
                stringResource(Res.string.timeline_checkin, checkin.venue),
                listOfNotNull(formatDate(checkin.at), listOfNotNull(formatTime(checkin.at), end).joinToString("–").ifEmpty { null }).joinToString(" "),
            )
        }

    override fun nameOf(entry: JsonObject): String? = checkinOf(entry)?.venue
}

/** What changed of a check-in, one alone or the one a Moment carries. */
internal object CheckinDiff : PartDiff {
    override val part = "checkin"

    @Composable
    override fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> = entryDiffsOf {
        val was = checkinOf(before) ?: return@entryDiffsOf
        val now = checkinOf(after) ?: return@entryDiffsOf
        val span = { c: Checkin ->
            listOfNotNull(formatDate(c.at.take(10)), listOfNotNull(formatTime(c.at), c.end?.let(::formatTime)).joinToString("–").ifEmpty { null }).joinToString(" ")
        }
        add(Res.string.change_place_name, was.venue, now.venue, AppIcons.Pen)
        add(Res.string.change_time, span(was), span(now), AppIcons.Clock)
        add(Res.string.change_place, was.place?.let { places[it]?.name ?: it }, now.place?.let { places[it]?.name ?: it }, AppIcons.Pin)
    }

    /** The check-in a Moment carries, renamed with its place. */
    @Composable
    override fun momentDiffs(before: MomentRecord, after: MomentRecord): List<EntryDiff> {
        val venueOf = { moment: MomentRecord ->
            ((moment.extra[Entries.OTHER_PARTS] as? JsonObject)?.get("checkin") as? JsonObject)?.let { Entries.checkinName(it) }
        }
        val was = venueOf(before)
        val now = venueOf(after)
        return if (was == now) emptyList() else listOf(EntryDiff(Res.string.change_checkin, was, now ?: stringResource(Res.string.change_none), AppIcons.Flag))
    }
}

/** The occasions written, changed or deleted. */
internal object OccasionChanges : ChangeSection {
    override val order = 1

    override fun owns(entry: JsonObject) = Occasions.occasion(entry) != null

    override fun LazyListScope.section(scope: ChangesScope, own: List<EntryChange>, state: Any?) =
        entrySection(Res.string.section_changed_occasions, own, scope) { entry ->
            val occasion = Occasions.occasion(entry)!!
            EntryFace(
                occasion.name,
                formatDate(occasion.first.toString()),
                stringResource(if (occasion.repeat) Res.string.occasion_each_year else Res.string.occasion_once),
            )
        }
}

/** What changed of an occasion. */
internal object OccasionDiff : PartDiff {
    override val part = "occasion"

    @Composable
    override fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
        val once = stringResource(Res.string.occasion_once)
        val lunarly = stringResource(Res.string.occasion_lunar)
        val yearly = stringResource(Res.string.occasion_each_year)
        val counts = mapOf(
            Count.YEARS to stringResource(Res.string.occasion_count_years),
            Count.AGE to stringResource(Res.string.occasion_count_age),
            Count.NONE to stringResource(Res.string.occasion_count_none),
        )
        return entryDiffsOf {
            val was = Occasions.occasion(before) ?: return@entryDiffsOf
            val now = Occasions.occasion(after) ?: return@entryDiffsOf
            val repeat = { repeat: Boolean, lunar: Boolean -> if (!repeat) once else if (lunar) lunarly else yearly }
            val day = { value: Any? -> value?.toString()?.let(::formatDate) }
            add(Res.string.occasion_name, was.name, now.name, AppIcons.Pen)
            add(Res.string.occasion_date, day(was.first), day(now.first), AppIcons.Calendar)
            add(Res.string.change_repeat, repeat(was.repeat, was.lunar), repeat(now.repeat, now.lunar), AppIcons.Refresh)
            add(Res.string.change_count, counts.getValue(was.count), counts.getValue(now.count), AppIcons.Tag)
            add(Res.string.occasion_first_label, was.firstLabel, now.firstLabel, AppIcons.Flag)
            words(Res.string.occasion_note, was.note, now.note, AppIcons.Notes)
        }
    }
}

/** The devices and subscriptions written, changed or deleted. */
internal object ThingChanges : ChangeSection {
    override val order = 2

    override fun owns(entry: JsonObject) = Things.device(entry) != null || Things.subscription(entry) != null

    override fun LazyListScope.section(scope: ChangesScope, own: List<EntryChange>, state: Any?) =
        entrySection(Res.string.section_changed_things, own, scope) { entry ->
            val device = Things.device(entry)
            val subscription = Things.subscription(entry)
            EntryFace(
                device?.name ?: subscription?.name ?: entry.entryUid,
                null,
                device?.let { yuan(it.price) } ?: subscription?.let { priceOf(it.price, it.currency) },
            )
        }

}

/** What changed of a device. */
internal object DeviceDiff : PartDiff {
    override val part = "device"

    @Composable
    override fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
        val none = stringResource(Res.string.change_none)
        val replaced = stringResource(Res.string.change_replaced)
        val yes = flagName(true)
        val no = flagName(false)
        return entryDiffsOf {
            val flag = { value: Boolean -> if (value) yes else no }
            val day = { value: Any? -> value?.toString()?.let(::formatDate) }
            val was = Things.device(before) ?: return@entryDiffsOf
            val now = Things.device(after) ?: return@entryDiffsOf
            add(Res.string.device_name, was.name, now.name, AppIcons.Pen)
            add(Res.string.device_category, was.category, now.category, AppIcons.Category)
            add(Res.string.device_bought, day(was.bought), day(now.bought), AppIcons.Calendar)
            add(Res.string.device_price, yuan(was.price), yuan(now.price), AppIcons.Cash)
            add(Res.string.device_sold_on, day(was.soldOn), day(now.soldOn), AppIcons.Calendar)
            add(Res.string.device_sold_price, was.soldPrice?.let(::yuan), now.soldPrice?.let(::yuan), AppIcons.Cash)
            add(Res.string.device_idle, flag(was.idle), flag(now.idle), AppIcons.Pause)
            words(Res.string.device_note, was.note, now.note, AppIcons.Notes)
            // Its photo, another or none.
            if (was.image != now.image) this += EntryDiff(Res.string.change_photos, null, if (now.image == null) none else replaced, AppIcons.Image)
        }
    }
}

/** What changed of a subscription, on an entry that isn't a device's. */
internal object SubscriptionDiff : PartDiff {
    override val part = "subscription"

    @Composable
    override fun entryDiffs(before: JsonObject, after: JsonObject, places: Map<String, Place>): List<EntryDiff> {
        val periods = mapOf(
            Period.YEAR to stringResource(Res.string.subscription_period_year),
            Period.MONTH to stringResource(Res.string.subscription_period_month),
            Period.ONCE to stringResource(Res.string.subscription_period_once),
        )
        val yes = flagName(true)
        val no = flagName(false)
        return entryDiffsOf {
            // A device's entry says what changed of the device alone, as its sheet does.
            if (Things.device(before) != null && Things.device(after) != null) return@entryDiffsOf
            val flag = { value: Boolean -> if (value) yes else no }
            val day = { value: Any? -> value?.toString()?.let(::formatDate) }
            val was = Things.subscription(before) ?: return@entryDiffsOf
            val now = Things.subscription(after) ?: return@entryDiffsOf
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
}

/** The bills going public, changed there, or going off, grouped, a group of many folded. */
internal object LedgerChangeSection : ChangeSection {
    override val order = 3

    private class State(val open: MutableState<Set<BillGroup>>, val onOpen: (String) -> Unit)

    @Composable
    override fun state(): Any {
        val nav = LocalLedgerNav.current
        val open = remember { mutableStateOf(emptySet<BillGroup>()) }
        return State(open) { nav?.bill?.invoke(it) }
    }

    override fun LazyListScope.section(scope: ChangesScope, own: List<EntryChange>, state: Any?) {
        val bills = state as State
        billSection(
            scope.pending.outside.bills,
            bills.open.value,
            onFold = { group -> bills.open.value = if (group in bills.open.value) bills.open.value - group else bills.open.value + group },
            onRevert = scope.container::revertBill.takeUnless { scope.busy },
            onOpen = bills.onOpen,
            placeName = { id -> scope.places[id]?.name },
        )
    }
}
