package cc.hyoban.moments.ui.format

import cc.hyoban.moments.res.*
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.spec.FoodBill
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentFood
import kotlin.math.roundToLong

/** What a verdict is called on screen. */
fun verdictLabel(verdict: FoodVerdict) = when (verdict) {
    FoodVerdict.GOOD -> Res.string.food_good
    FoodVerdict.OK -> Res.string.food_ok
    FoodVerdict.BAD -> Res.string.food_bad
}

/** Yuan as written: whole ones without a point, the rest as far as they go. */
fun formatAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toBigDecimal().stripTrailingZeros().toPlainString()

/** What a meal cost, `¥177.4`, how many it fed and, when more than one, each one's share to the yuan, as a review site gives it. */
@Composable
fun billLine(bill: FoodBill, each: Boolean = true): String = listOfNotNull(
    "¥" + formatAmount(bill.amount),
    bill.people?.let { pluralStringResource(Res.plurals.count_food_people, it, it) },
    bill.people?.takeIf { each && it > 1 }?.let { stringResource(Res.string.food_each, (bill.amount / it).roundToLong()) },
).joinToString(" ")

/** The dishes, how they were and what they cost in a line, as the site writes it under a Moment; null when it says nothing. */
@Composable
fun foodLine(food: MomentFood?): String? {
    food ?: return null
    return listOfNotNull(
        food.dishes.takeIf { it.isNotEmpty() }?.joinToString(stringResource(Res.string.separator_list)),
        FoodVerdict.of(food.verdict)?.let { stringResource(verdictLabel(it)) },
        food.bill?.let { billLine(it, each = false) },
    ).joinToString(" ").ifEmpty { null }
}

/** What a meal's share ends with: where, each one's share and how it was, each behind an emoji; null when it says nothing. A bill without its people fed one. */
@Composable
fun foodShareLine(food: MomentFood?, place: String?): String? {
    food ?: return null
    return listOfNotNull(
        place?.takeIf { it.isNotBlank() }?.let { "📍 $it" },
        food.bill?.let { "💰 " + stringResource(Res.string.food_each, (it.amount / (it.people ?: 1).coerceAtLeast(1)).roundToLong()) },
        FoodVerdict.of(food.verdict)?.let { verdict ->
            val face = when (verdict) {
                FoodVerdict.GOOD -> "😋"
                FoodVerdict.OK -> "🙂"
                FoodVerdict.BAD -> "😖"
            }
            "$face " + stringResource(verdictLabel(verdict))
        },
    ).joinToString(" ").ifEmpty { null }
}
