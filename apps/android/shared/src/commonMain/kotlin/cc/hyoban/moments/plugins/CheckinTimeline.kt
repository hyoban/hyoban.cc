package cc.hyoban.moments.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.BubbleFoot
import cc.hyoban.moments.ui.feed.BubbleShape
import cc.hyoban.moments.ui.feed.DayAnchor
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.LocalCheckinNav
import cc.hyoban.moments.ui.feed.PageRow
import cc.hyoban.moments.ui.feed.RecordRow
import cc.hyoban.moments.ui.feed.TAIL
import cc.hyoban.moments.ui.feed.bubbleWidth
import cc.hyoban.moments.ui.feed.instantOf
import cc.hyoban.moments.ui.feed.timeSpan
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.billAmount
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnSupporting
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A check-in among the Moments: a stop of its own, the bills and charges paid there at its foot. */
class CheckinAnchor(val checkin: Checkin) : DayAnchor {
    override val key get() = "checkin-${checkin.uid}"
    override val uid get() = checkin.uid
    override val time get() = checkin.at.drop(11).take(8)
    override val instant get() = instantOf(checkin.at)
    override val place get() = checkin.place

    @Composable override fun Bubble(joinedAbove: Boolean, joinedBelow: Boolean, modifier: Modifier, attached: List<DayRecord>) =
        CheckinBubble(checkin, joinedAbove, joinedBelow, modifier, attached)

    @Composable override fun Row(modifier: Modifier, hidePlace: String?, attached: List<DayRecord>) {
        Column(modifier) {
            CheckinRow(checkin, hideVenue = hidePlace != null && checkin.place == hidePlace)
            attached.forEach { it.AttachedRow() }
        }
    }
}

/** A check-in as a row on the page: its time, and the venue it was at, or on a page of that place its day; a tap opens its sheet. */
@Composable
fun CheckinRow(checkin: Checkin, modifier: Modifier = Modifier, withDate: Boolean = false, hideVenue: Boolean = false) {
    val open = LocalCheckinNav.current
    PageRow(
        leading = { Icon(AppIcons.Pin, contentDescription = null) },
        // On its place's own page the venue is the page's title.
        title = if (hideVenue && !withDate) stringResource(Res.string.change_checkin) else checkinTitle(checkin, withDate),
        supporting = if (checkin.private) stringResource(Res.string.visibility_private) else null,
        onClick = open?.let { { it(checkin.uid) } },
        modifier = modifier.testTag("checkin_${checkin.uid}"),
        time = checkinTime(checkin).orEmpty(),
    )
}

/** When a check-in was, `15:04`, or the stay it was kept from, `15:04–16:30`. */
fun checkinTime(checkin: Checkin): String? = timeSpan(checkin.at, checkin.end)

/** `签到 面馆`, or on its place's own list `10月12日 签到`. */
@Composable
fun checkinTitle(checkin: Checkin, withDate: Boolean = false): String =
    if (withDate) stringResource(Res.string.timeline_checkin_on, formatDate(checkin.at.take(10))) else stringResource(Res.string.timeline_checkin, checkin.venue)

/**
 * A check-in as the conversation has it: sent from the right as a Moment is, on the pressed fill
 * and drawn with the Moments beside it, only as wide as it needs; the place's pin, its venue on
 * one line and, faint at the end, whether it is private and when. It sets the records around it
 * apart, and a tap opens its sheet.
 */
@Composable
internal fun CheckinBubble(
    checkin: Checkin,
    joinedAbove: Boolean,
    joinedBelow: Boolean,
    modifier: Modifier = Modifier,
    attached: List<DayRecord> = emptyList(),
) {
    val open = LocalCheckinNav.current
    val shape = androidx.compose.runtime.remember(joinedAbove, joinedBelow) { BubbleShape(sent = true, joinedAbove, joinedBelow) }
    val title = checkinTitle(checkin)
    val withBills = attached.isNotEmpty()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        // With its bills it is as wide as a records bubble, they at its foot.
        Column(
            Modifier
                .bubbleWidth(fill = withBills) { column -> minOf(column * 0.88f, 420.dp) }
                .clip(shape)
                .background(tokens.fill)
                .then(if (open != null) Modifier.clickable { open(checkin.uid) } else Modifier)
                .padding(end = TAIL)
                .padding(horizontal = Space.m, vertical = Space.s)
                .testTag("checkin_${checkin.uid}"),
        ) {
            // With bills under it, its time ends the line as theirs do, at the bubble's right edge.
            Row(
                (if (withBills) Modifier.fillMaxWidth() else Modifier).semantics(mergeDescendants = true) { contentDescription = title },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(18.dp))
                Text(checkin.venue, style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = withBills))
                val meta = listOfNotNull(stringResource(Res.string.visibility_private).takeIf { checkin.private }, checkinTime(checkin)).joinToString(" ")
                // A locked one says so before its time, with a lock.
                if (checkin.locked) Icon(AppIcons.Lock, contentDescription = stringResource(Res.string.plan_locked), tint = tokens.tx3, modifier = Modifier.size(12.dp))
                if (meta.isNotEmpty()) Text(meta, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1)
            }
            if (withBills) BubbleFoot(attached, Modifier.padding(top = Space.s), time = formatTime(checkin.at))
        }
    }
}

