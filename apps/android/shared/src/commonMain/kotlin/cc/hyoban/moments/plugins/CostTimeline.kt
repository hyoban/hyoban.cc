package cc.hyoban.moments.plugins

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.QuietLine
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.RecordRow
import cc.hyoban.moments.ui.format.costLine
import cc.hyoban.moments.ui.format.costMoney
import cc.hyoban.moments.ui.format.costTitle
import cc.hyoban.moments.ui.format.costVerdictLabel
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A Moment's cost, if it is a meal as [meal] says: 美食's, else 花费's. */
internal fun costOf(moment: MomentRecord, meal: Boolean): MomentCost? = moment.cost?.takeIf { it.meal == meal }

/**
 * What a Moment cost, under its words on its card as the site has it: a meal's dishes or what it
 * went on, how it was and what it came to, a line of secondary text.
 */
@Composable
internal fun CostCardLine(cost: MomentCost) {
    costLine(cost)?.let { line -> QuietLine(if (cost.meal) AppIcons.Food else AppIcons.Cash, line) }
}

/**
 * What a Moment cost, with its amount, leading the foot of its bubble, drawn as the bills under it
 * are, its amount at the edge; it opens the Moment as the rest of its bubble does.
 */
internal class CostFoot(val cost: MomentCost, val amount: Double) : DayRecord {
    override val key get() = "cost"
    override val time get() = ""
    override val instant: Long? get() = null

    @Composable override fun open(onDay: (() -> Unit)?): (() -> Unit)? = null

    @Composable override fun Row(modifier: Modifier) {}

    @Composable override fun Line(large: Boolean, timed: Boolean) {}

    @Composable override fun Attached(large: Boolean, bubbleTime: String?) = RecordRow(
        icon = { Icon(if (cost.meal) AppIcons.Food else AppIcons.Cash, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
        title = listOfNotNull(
            cost.items.takeIf { it.isNotEmpty() }?.joinToString(stringResource(Res.string.separator_list))
                ?: stringResource(costTitle(cost)),
            FoodVerdict.of(cost.verdict)?.let { stringResource(costVerdictLabel(cost, it)) },
            cost.people?.takeIf { it > 1 }?.let { pluralStringResource(Res.plurals.count_food_people, it, it) },
        ).joinToString(" "),
        time = null,
        end = androidx.compose.ui.text.AnnotatedString(costMoney(amount, cost.currency)),
        endColor = tokens.tx,
        large = large,
        timed = false,
        tag = "moment_cost",
        timeAtEnd = true,
    )
}

/** The foot of a Moment's bubble for its cost, if it has an amount and is a meal as [meal] says. */
internal fun costFoot(moment: MomentRecord, meal: Boolean): DayRecord? =
    costOf(moment, meal)?.let { cost -> cost.amount?.let { CostFoot(cost, it) } }
