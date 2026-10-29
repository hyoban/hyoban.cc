package cc.hyoban.moments.ui.format

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.FoodBill
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentFood
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import kotlin.math.roundToLong

/** What a verdict is called on screen. */
fun verdictLabel(verdict: FoodVerdict) = when (verdict) {
    FoodVerdict.GOOD -> R.string.food_good
    FoodVerdict.OK -> R.string.food_ok
    FoodVerdict.BAD -> R.string.food_bad
}

/** Yuan as written: whole ones without a point, the rest as far as they go. */
fun formatAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toBigDecimal().stripTrailingZeros().toPlainString()

/** What a meal cost, `¥177.4`, how many it fed and, when more than one, each one's share to the yuan, as a review site gives it. */
@Composable
fun billLine(bill: FoodBill, each: Boolean = true): String = listOfNotNull(
    "¥" + formatAmount(bill.amount),
    bill.people?.let { pluralStringResource(R.plurals.count_food_people, it, it) },
    bill.people?.takeIf { each && it > 1 }?.let { stringResource(R.string.food_each, (bill.amount / it).roundToLong()) },
).joinToString(" ")

/** The dishes, how they were and what they cost in a line, as the site writes it under a Moment; null when it says nothing. */
@Composable
fun foodLine(food: MomentFood?): String? {
    food ?: return null
    return listOfNotNull(
        food.dishes.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.separator_list)),
        FoodVerdict.of(food.verdict)?.let { stringResource(verdictLabel(it)) },
        food.bill?.let { billLine(it, each = false) },
    ).joinToString(" ").ifEmpty { null }
}

/**
 * A verdict as a small badge, its face and word on a tint that says how it went: the accent's
 * soft one for good, the danger's for bad, the pressed grey for passable.
 */
@Composable
fun VerdictBadge(verdict: FoodVerdict, modifier: Modifier = Modifier) {
    val (container: Color, content: Color) = when (verdict) {
        FoodVerdict.GOOD -> tokens.accentSoft to tokens.tx
        FoodVerdict.OK -> tokens.pressed to tokens.tx2
        FoodVerdict.BAD -> tokens.dangerSoft to tokens.danger
    }
    val icon = when (verdict) {
        FoodVerdict.GOOD -> AppIcons.FaceGood
        FoodVerdict.OK -> AppIcons.FaceOk
        FoodVerdict.BAD -> AppIcons.FaceBad
    }
    Row(
        modifier.clip(Corners.full).background(container).heightIn(min = 28.dp).padding(start = Space.s, end = Space.m, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(stringResource(verdictLabel(verdict)), style = AppTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}
