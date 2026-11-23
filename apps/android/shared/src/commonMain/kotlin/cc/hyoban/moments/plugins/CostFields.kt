package cc.hyoban.moments.plugins

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ChipDefaults
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.LocalContentColor
import cc.hyoban.moments.ui.design.LocalTextStyle
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.FoodVerdict
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.ui.design.MultiChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.format.formatAmount
import cc.hyoban.moments.ui.format.verdictLabel
import cc.hyoban.moments.ui.format.worthLabel
import cc.hyoban.moments.data.money.COMMON_CURRENCIES
import cc.hyoban.moments.data.money.currencySymbol
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.settings.PageCaption
import cc.hyoban.moments.ui.settings.PageRows

/**
 * What it cost, rows on the page: what it went on, or for a [meal] what was eaten; how it was
 * (how a meal tasted, else whether it was worth it), one of three that a second tap clears; and
 * the amount in its currency, chosen at its symbol, and how many it was for, with each one's share.
 */
@Composable
internal fun CostGroup(
    meal: Boolean,
    items: String,
    onItems: (String) -> Unit,
    verdict: String?,
    onVerdict: (String?) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    currency: String,
    onCurrency: (String) -> Unit,
    people: String,
    onPeople: (String) -> Unit,
) {
    PageCaption(stringResource(if (meal) Res.string.section_food else Res.string.section_cost))
    PageRows { _ ->
        ListItem(
            leadingContent = { Icon(if (meal) AppIcons.Food else AppIcons.Cash, contentDescription = null) },
            headlineContent = {
                RowField(
                    items,
                    onItems,
                    stringResource(if (meal) Res.string.hint_food_dishes else Res.string.hint_cost_items),
                    AppTheme.typography.body,
                    KeyboardOptions(imeAction = ImeAction.Next),
                    Modifier.fillMaxWidth().testTag("cost_items"),
                )
            },
        )
        VerdictRow(verdict, onVerdict, "cost", if (meal) ::verdictLabel else ::worthLabel)
        // Each one's share once both are known, after how many.
        val share = amount.toDoubleOrNull()?.let { total -> people.toIntOrNull()?.takeIf { it > 1 }?.let { total / it } }
        var currencies by remember { mutableStateOf(false) }
        val symbol = currencySymbol(currency).trim()
        ListItem(
            leadingContent = { Icon(AppIcons.Receipt, contentDescription = null) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Text(
                            symbol,
                            style = AppTheme.typography.body,
                            modifier = Modifier.clickable { currencies = true }.testTag("cost_currency"),
                        )
                        DropdownMenu(currencies, onDismissRequest = { currencies = false }) {
                            COMMON_CURRENCIES.forEach { code ->
                                DropdownMenuItem(text = { Text("$code  ${currencySymbol(code).trim()}") }, onClick = { onCurrency(code); currencies = false })
                            }
                        }
                    }
                    RowField(
                        amount,
                        { typed -> onAmount(typed.filter { it.isDigit() || it == '.' }) },
                        stringResource(Res.string.label_food_amount),
                        AppTheme.typography.body,
                        KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        Modifier.weight(1f).padding(start = Space.xs).testTag("cost_amount"),
                    )
                }
            },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowField(
                        people,
                        { typed -> onPeople(typed.filter { it.isDigit() }.take(3)) },
                        stringResource(Res.string.label_food_people),
                        LocalTextStyle.current.copy(fontFamily = AppTheme.typography.body.fontFamily),
                        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        Modifier.testTag("cost_people"),
                        fit = true,
                    )
                    if (people.isNotEmpty()) Text(" " + stringResource(Res.string.unit_food_people))
                    if (share != null) Text(" " + stringResource(Res.string.cost_share, symbol + formatAmount(share)))
                }
            },
        )
    }
}

/** How it was, one of three in a segmented control that a second tap on the chosen one clears. */
@Composable
private fun VerdictRow(verdict: String?, onVerdict: (String?) -> Unit, tag: String, label: (FoodVerdict) -> StringResource) {
    ListItem(
        leadingContent = { Icon(AppIcons.FaceGood, contentDescription = null) },
        headlineContent = {
            MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(ChipDefaults.Height)) {
                FoodVerdict.entries.forEach { choice ->
                    SegmentedButton(
                        checked = choice.value == verdict,
                        onCheckedChange = { onVerdict(if (it) choice.value else null) },
                        modifier = Modifier.testTag("${tag}_${choice.value}"),
                    ) { Text(stringResource(label(choice)), maxLines = 1) }
                }
            }
        },
    )
}

/**
 * A field that is a row's words alone, its hint in the third grey while empty; one that [fit]s
 * is as wide as what it holds, with room for the cursor, so words can follow it on its line.
 */
@Composable
private fun RowField(value: String, onValueChange: (String) -> Unit, hint: String, style: TextStyle, keyboardOptions: KeyboardOptions, modifier: Modifier, fit: Boolean = false) {
    val color = LocalContentColor.current
    val measurer = rememberTextMeasurer()
    val width = if (fit) with(LocalDensity.current) { measurer.measure(value.ifEmpty { hint }, style).size.width.toDp() + CURSOR_ROOM } else null
    BasicTextField(
        value,
        onValueChange,
        if (width != null) modifier.width(width) else modifier,
        textStyle = style.merge(TextStyle(color = color)),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(tokens.tx),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = style, color = tokens.tx3, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * The cost as typed, kept with whatever else [original] said: a [meal] or not; what it went on
 * split at commas, enumeration commas and new lines; the amount only when it reads as one, with
 * how many it was for and its currency, yuan written as none; and nothing when nothing is said.
 */
fun costOf(meal: Boolean, items: String, verdict: String?, amount: String, currency: String, people: String, original: MomentCost?): MomentCost? {
    val names = items.split(DISH_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
    val total = amount.toDoubleOrNull()?.takeIf { it >= 0 }
    if (names.isEmpty() && verdict == null && total == null) return null
    // How many it was for, and its currency, only beside an amount.
    val fed = people.toIntOrNull()?.takeIf { it > 0 && total != null }
    val code = currency.takeIf { total != null && (it != "CNY" || original?.currency != null) }
    val kind = if (meal) MomentCost.FOOD else null
    return original?.copy(amount = total, items = names, verdict = verdict, currency = code, people = fed, kind = kind)
        ?: MomentCost(total, names, verdict, code, fed, kind)
}

private val DISH_SEPARATORS = Regex("[,，、;；\\n]")

private val CURSOR_ROOM = 2.dp
