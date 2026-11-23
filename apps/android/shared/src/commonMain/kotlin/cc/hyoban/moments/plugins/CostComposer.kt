package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.section_cost
import cc.hyoban.moments.res.section_food
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.compose.AddChip
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.compose.ComposerScope
import cc.hyoban.moments.ui.compose.PartDraft
import cc.hyoban.moments.ui.compose.Spent
import cc.hyoban.moments.ui.format.formatAmount
import cc.hyoban.moments.ui.design.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * What a Moment cost, written in 写动态: a meal (美食) or anything else (花费), one cost either
 * way, what it went on (a meal's dishes), how it was, the amount in its currency and how many.
 */
internal object CostComposer : ComposerPart {
    override val key = "cost"

    @Composable override fun draft(scope: ComposerScope): PartDraft {
        val originalCost = scope.original?.cost
        var meal by rememberSaveable { mutableStateOf(originalCost?.meal ?: false) }
        var costItems by rememberSaveable { mutableStateOf(originalCost?.items.orEmpty().joinToString("、")) }
        var verdict by rememberSaveable { mutableStateOf(originalCost?.verdict) }
        var amount by rememberSaveable { mutableStateOf(originalCost?.amount?.let(::formatAmount).orEmpty()) }
        var currency by rememberSaveable { mutableStateOf(originalCost?.currency ?: "CNY") }
        var people by rememberSaveable { mutableStateOf(originalCost?.people?.toString().orEmpty()) }
        /** Whether 美食 or 花费 was added from the 加一项 row: open for a Moment that has a cost. */
        var withCost by rememberSaveable { mutableStateOf(originalCost != null) }
        val cost = costOf(meal, costItems, verdict, amount, currency, people, originalCost)
        // Only while its module is on (记录项与公开), a meal already written staying as it is while meals are off.
        val settings = LocalSettings.current
        val foodOn = settings.isOn(Module.FOOD)
        val costOn = settings.isOn(Module.COST)
        return object : PartDraft {
            override val offered = foodOn || costOn
            override val holds = cost != null
            override val changed = cost != originalCost
            override val spent = Spent(amount, cost?.meal == true)

            @Composable override fun Chips() {
                // A meal and any other cost are one cost, so choosing one takes the other's place
                // with what was typed; choosing the chosen one again takes it away.
                val pick = { asMeal: Boolean ->
                    if (withCost && meal == asMeal) {
                        withCost = false
                        costItems = ""; verdict = null; amount = ""; people = ""
                    } else {
                        withCost = true
                        meal = asMeal
                    }
                }
                if (foodOn) AddChip(withCost && meal, AppIcons.Food, stringResource(Res.string.section_food), "add_food") { pick(true) }
                if (costOn) AddChip(withCost && !meal, AppIcons.Cash, stringResource(Res.string.section_cost), "add_cost") { pick(false) }
            }

            @Composable override fun Fields(keypad: Boolean) {
                if (scope.withMoment && withCost && (if (meal) foodOn else costOn)) CostGroup(
                    meal = meal,
                    items = costItems,
                    onItems = { costItems = it },
                    verdict = verdict,
                    onVerdict = { verdict = it },
                    amount = amount,
                    onAmount = { amount = it },
                    currency = currency,
                    onCurrency = { currency = it },
                    people = people,
                    onPeople = { people = it },
                )
            }

            override fun into(moment: MomentRecord): MomentRecord = moment.copy(cost = cost)
        }
    }
}
