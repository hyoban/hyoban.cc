package cc.hyoban.moments.plugins

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.things.Period
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.timeline_bought
import cc.hyoban.moments.res.timeline_renewed
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.DayRecordsScope
import cc.hyoban.moments.ui.feed.LocalThingNav
import cc.hyoban.moments.ui.feed.PageRow
import cc.hyoban.moments.ui.feed.RecordRow
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.things.priceOf
import org.jetbrains.compose.resources.stringResource

/** The devices bought and the subscriptions paid, on the days shown. */
internal fun thingRecords(scope: DayRecordsScope): List<Pair<String, DayRecord>> = (
    Things.devices(scope.entries).map { ThingRecord(it.uid, it.bought.toString(), true, true, it.name, it.price, "CNY") } +
        Things.subscriptions(scope.entries).map {
            ThingRecord(it.uid, it.paid.toString(), false, it.period == Period.ONCE, it.name, it.price, it.currency)
        }
    ).filter { scope.inDay(it.day) }.map { it.day to it }

/**
 * A device bought or a subscription paid on the timeline: known by its day alone, so it has no
 * time; its name and price, and whether it was [bought] (a device, software bought once) or
 * renewed.
 */
data class ThingRecord(val uid: String, val day: String, val device: Boolean, val bought: Boolean, val name: String, val price: Double, val currency: String) : DayRecord {
    override val key get() = "thing-$uid"
    override val time get() = ""
    override val instant: Long? get() = null

    @Composable override fun open(onDay: (() -> Unit)?): (() -> Unit)? = LocalThingNav.current?.let { { it(uid) } }

    /** As a row on the page: no time, its name, its price at the end. */
    @Composable override fun Row(modifier: Modifier) {
        PageRow(
            leading = { Icon(if (device) AppIcons.Laptop else AppIcons.Subscription, contentDescription = null) },
            title = title(),
            supporting = priceOf(price, currency),
            onClick = open(null),
            modifier = modifier.testTag("thing_$uid"),
            time = "",
        )
    }

    @Composable override fun Line(large: Boolean, timed: Boolean) {
        RecordRow(
            icon = { Icon(if (device) AppIcons.Laptop else AppIcons.Subscription, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
            title = title(),
            // Known by its day alone: the time's column stays, empty.
            time = null,
            end = AnnotatedString(priceOf(price, currency)),
            endColor = tokens.tx,
            large = large,
            timed = timed,
            tag = "thing_$uid",
        )
    }

    /** `买了 OPPO Find X10`, `续费 ChatGPT Plus`. */
    @Composable private fun title(): String = stringResource(if (bought) Res.string.timeline_bought else Res.string.timeline_renewed, name)
}
