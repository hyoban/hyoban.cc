package cc.hyoban.moments.ui.format

import cc.hyoban.moments.res.*
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ledger.currencySymbol
import cc.hyoban.moments.data.spec.FoodVerdict
import kotlin.math.roundToLong

/** What a verdict is called on screen. */
fun verdictLabel(verdict: FoodVerdict) = when (verdict) {
    FoodVerdict.GOOD -> Res.string.food_good
    FoodVerdict.OK -> Res.string.food_ok
    FoodVerdict.BAD -> Res.string.food_bad
}

/** An amount as written: whole ones without a point, the rest as far as they go. */
fun formatAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toBigDecimal().stripTrailingZeros().toPlainString()

/** Whether a cost was worth it, as the screen says it. */
fun worthLabel(verdict: FoodVerdict) = when (verdict) {
    FoodVerdict.GOOD -> Res.string.cost_good
    FoodVerdict.OK -> Res.string.cost_ok
    FoodVerdict.BAD -> Res.string.cost_bad
}

/** How a cost was, as the screen says it: how a meal tasted, else whether it was worth it. */
fun costVerdictLabel(cost: MomentCost, verdict: FoodVerdict) = if (cost.meal) verdictLabel(verdict) else worthLabel(verdict)

/** A cost's name when it says nothing of what it went on: 美食 or 花费. */
fun costTitle(cost: MomentCost) = if (cost.meal) Res.string.section_food else Res.string.section_cost

/** An amount of a cost with its currency's symbol, `¥177.4`, `HK$80`: yuan without one. */
fun costMoney(amount: Double, currency: String?): String = currencySymbol(currency ?: "CNY").trim() + formatAmount(amount)

/** What a cost came to, `¥177.4`, how many it was for and, when more than one, each one's share, as a review site gives it; null without an amount. */
@Composable
fun costAmountLine(cost: MomentCost, each: Boolean = true): String? {
    val amount = cost.amount ?: return null
    return listOfNotNull(
        costMoney(amount, cost.currency),
        cost.people?.let { pluralStringResource(Res.plurals.count_food_people, it, it) },
        cost.people?.takeIf { each && it > 1 }?.let { stringResource(Res.string.cost_share, costMoney(amount / it, cost.currency)) },
    ).joinToString(" ")
}

/** What it went on (a meal's dishes), how it was and what it came to in a line, as the site writes it under a Moment; null when it says nothing. */
@Composable
fun costLine(cost: MomentCost?): String? {
    cost ?: return null
    return listOfNotNull(
        cost.items.takeIf { it.isNotEmpty() }?.joinToString(stringResource(Res.string.separator_list)),
        FoodVerdict.of(cost.verdict)?.let { stringResource(costVerdictLabel(cost, it)) },
        costAmountLine(cost, each = false),
    ).joinToString(" ").ifEmpty { null }
}

/**
 * What a share ends with: when it was, where, each one's share of what it cost and how it was
 * (a meal's taste, else whether it was worth it), each behind an emoji, as the Telegram channel
 * posts it too. A cost without its people was one's.
 */
@Composable
fun shareLine(moment: MomentRecord, place: String?): String {
    val cost = moment.cost
    val amount = cost?.amount
    return listOfNotNull(
        "🕐 " + listOfNotNull(formatDate(moment.dateKey), formatTime(moment.occurredAt)).joinToString(" "),
        place?.takeIf { it.isNotBlank() }?.let { "📍 $it" },
        amount?.let { "💰 " + stringResource(Res.string.cost_each, costMoney((it / (cost.people ?: 1).coerceAtLeast(1)).roundToLong().toDouble(), cost.currency)) },
        FoodVerdict.of(cost?.verdict)?.let { verdict ->
            val face = when (verdict) {
                FoodVerdict.GOOD -> if (cost!!.meal) "😋" else "👍"
                FoodVerdict.OK -> if (cost!!.meal) "😌" else "👌"
                FoodVerdict.BAD -> if (cost!!.meal) "😖" else "👎"
            }
            "$face " + stringResource(costVerdictLabel(cost!!, verdict))
        },
    ).joinToString(" ")
}

/** A Moment's words as they go out: its own words exactly, then [shareLine]. */
@Composable
fun shareText(moment: MomentRecord, place: String?): String =
    listOfNotNull(moment.text.takeIf { it.isNotBlank() }, shareLine(moment, place)).joinToString("\n\n")

/** The module a cost goes with: 美食 for a meal, else 花费. */
val MomentCost.module: Module get() = if (meal) Module.FOOD else Module.COST
