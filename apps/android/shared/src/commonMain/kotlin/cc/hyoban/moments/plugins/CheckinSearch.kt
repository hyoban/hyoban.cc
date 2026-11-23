package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.ui.feed.LocalCheckinNav
import cc.hyoban.moments.ui.feed.marked
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
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
import org.jetbrains.compose.resources.stringResource

/** The check-ins the search lists: by their venue or their place's name. */
internal object CheckinsSearch : SearchKind {
    override val key = "checkins"
    override val label = Res.string.filter_checkins

    override fun matches(scope: SearchScope): List<SearchFound> =
        joinedCheckins { scope.records.of(it) }.values.flatten().filter { checkin ->
            scope.text.isEmpty() || checkin.venue.contains(scope.text, ignoreCase = true) ||
                checkin.place?.let { scope.places[it] }?.name?.contains(scope.text, ignoreCase = true) == true
        }.map(::CheckinFound)
}

private class CheckinFound(val checkin: cc.hyoban.moments.data.things.Checkin) : SearchFound {
    override val key get() = "checkin-${checkin.uid}"
    override val at get() = checkin.at

    @Composable override fun Row(scope: SearchScope, modifier: Modifier) = SearchCheckin(checkin, scope.text, scope.places, modifier)
}

/**
 * A check-in found: its venue, what was searched for marked, and under it its day and its place
 * when that is named otherwise, 私有 when kept off the site; a tap opens its sheet.
 */
@Composable
private fun SearchCheckin(checkin: cc.hyoban.moments.data.things.Checkin, text: String, places: Map<String, Place>, modifier: Modifier) {
    val open = LocalCheckinNav.current
    val mark = SpanStyle(background = tokens.accentSoft)
    SearchRow(
        lead = { Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx2) },
        onClick = open?.let { { it(checkin.uid) } },
        modifier = modifier.testTag("checkin_${checkin.uid}"),
        trailing = { checkinTime(checkin)?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) } },
    ) {
        Text(marked(checkin.venue, text, mark), style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                formatDate(checkin.at, ownYear(checkin.at)),
                checkin.place?.let { places[it]?.name }?.takeIf { it != checkin.venue },
                if (checkin.private) stringResource(Res.string.visibility_private) else null,
            ).joinToString(" "),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
