package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.SearchFound
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.ui.feed.SearchRow
import cc.hyoban.moments.ui.feed.SearchScope
import cc.hyoban.moments.ui.feed.ownYear
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The car's charges the search lists: all of them, with nothing typed, and none for words. */
internal object ChargesSearch : SearchKind {
    override val key = "charges"
    override val label = Res.string.part_charge

    override fun matches(scope: SearchScope): List<SearchFound> =
        if (scope.text.isEmpty()) scope.records.of<Map<String, List<Charge>>>(cc.hyoban.moments.data.spec.Module.CAR).orEmpty().values.flatten().map(::ChargeFound) else emptyList()
}

private class ChargeFound(val charge: Charge) : SearchFound {
    override val key get() = "charge-${charge.uid}"
    override val at get() = charge.at

    @Composable override fun Row(scope: SearchScope, modifier: Modifier) = SearchCharge(charge, modifier)
}

/** A charge found, as the timeline lists one, with its day, since the list spans days. */
@Composable
private fun SearchCharge(charge: Charge, modifier: Modifier) {
    SearchRow(
        lead = { Icon(AppIcons.Ev, contentDescription = null, tint = tokens.tx2) },
        onClick = null,
        modifier = modifier.testTag("charge_${charge.uid}"),
        trailing = { charge.at.drop(11).take(5).ifEmpty { null }?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) } },
    ) {
        Text(stringResource(Res.string.timeline_charge), style = AppTheme.typography.body)
        Text(
            listOfNotNull(
                formatDate(charge.at, ownYear(charge.at)),
                stringResource(if (charge.dc) Res.string.charge_dc else Res.string.charge_ac),
                charge.kwh?.let { "%.1f kWh".format(it) },
                charge.minutes?.let { pluralStringResource(Res.plurals.charge_minutes, it, it) },
            ).joinToString(" "),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
