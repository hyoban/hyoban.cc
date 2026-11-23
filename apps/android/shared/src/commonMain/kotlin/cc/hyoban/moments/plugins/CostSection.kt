package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.food_none
import cc.hyoban.moments.res.separator_list
import cc.hyoban.moments.ui.publish.EntryDiff
import cc.hyoban.moments.ui.publish.PartDiff
import cc.hyoban.moments.ui.format.costLine
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.costAmountLine
import cc.hyoban.moments.ui.format.costTitle
import cc.hyoban.moments.ui.format.costVerdictLabel
import cc.hyoban.moments.ui.moment.MomentPageScope
import cc.hyoban.moments.ui.moment.MomentSection
import org.jetbrains.compose.resources.stringResource

/**
 * A Moment's cost on its page, a meal's ([meal]) for 美食 and any other for 花费: what it went on,
 * how it was, the amount, how many and each one's share. Not a link, so a row that doesn't
 * answer a tap. It publishes with the Moment and may be made private on its own.
 */
internal class CostSection(private val meal: Boolean) : MomentSection {
    private fun MomentPageScope.cost(): MomentCost? = moment.cost?.takeIf { it.meal == meal }

    @Composable override fun rows(scope: MomentPageScope): List<@Composable (ListItemShapes) -> Unit> {
        val cost = scope.cost() ?: return emptyList()
        val verdict = FoodVerdict.of(cost.verdict)
        return listOf {
            ListItem(
                headlineContent = {
                    Text(
                        cost.items.joinToString(stringResource(Res.string.separator_list)).ifEmpty { stringResource(costTitle(cost)) },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = listOfNotNull(verdict?.let { stringResource(costVerdictLabel(cost, it)) }, costAmountLine(cost))
                    .joinToString(" ").takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                leadingContent = { Icon(if (cost.meal) AppIcons.Food else AppIcons.Cash, contentDescription = null) },
                modifier = Modifier.testTag("cost"),
            )
        }
    }

    @Composable override fun publishes(scope: MomentPageScope): String? = scope.cost()?.let { stringResource(costTitle(it)) }

    override fun privatePart(scope: MomentPageScope) = scope.cost()?.let { "cost" to costTitle(it) }
}

/**
 * What 发布更改 lists of a Moment's cost that changed, a meal's ([meal], the `food` part, 美食's)
 * or any other (the `cost` part, 花费's): one line, from what it said to what it says, named for
 * what it is now, or was when it is gone.
 */
internal class CostDiff(private val meal: Boolean) : PartDiff {
    override val part = if (meal) "food" else "cost"

    @Composable
    override fun momentDiffs(before: MomentRecord, after: MomentRecord): List<EntryDiff> {
        if (before.cost == after.cost) return emptyList()
        val cost = after.cost ?: before.cost!!
        if (cost.meal != meal) return emptyList()
        val none = stringResource(Res.string.food_none)
        return listOf(EntryDiff(costTitle(cost), costLine(before.cost), costLine(after.cost) ?: none, if (cost.meal) AppIcons.Food else AppIcons.Cash))
    }
}
