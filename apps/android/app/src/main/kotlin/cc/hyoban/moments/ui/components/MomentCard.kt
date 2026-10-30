package cc.hyoban.moments.ui.components

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.ui.draw.clip
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.feed.LinkedBills
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.Ledger
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.LocalContentColor
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.format.foodLine
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.moment.MomentMenu
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/**
 * A Moment in a list, on the page itself rather than in a card: its photos in rounded corners,
 * then a subhead of when, where and the trip, then its words, then the bills written with it. Tapping anywhere, its photos too, opens the Moment;
 * holding it offers sharing it, copying its words and editing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentCard(
    moment: MomentRecord,
    places: Map<String, Place>,
    trip: TripRecord?,
    urls: MediaUrls,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    selected: Boolean = false,
    /** A place the page is already of, left out of the subhead. */
    hidePlace: String? = null,
    /** The bills written with it, listed at its foot, each opening its bill. */
    bills: List<Txn> = emptyList(),
    ledger: Ledger? = null,
    onBill: (String) -> Unit = {},
    /** As a bubble sent from the right, as the timeline and a trip are a conversation; its bills then follow it apart. */
    bubble: Boolean = false,
) {
    if (bubble) return MomentBubble(moment, places, trip, urls, onOpen, modifier, selected, hidePlace)
    // The card grows into the Moment's page when opened; shown in the detail pane beside the
    // list, it is [selected], outlined in the accent, the one colour for state.
    val ring by animateColorAsState(if (selected) tokens.accent else Color.Transparent, AppTheme.motionScheme.fastEffectsSpec(), label = "selected")
    val outline = Modifier.border(2.dp, ring, AppTheme.shapes.large)
    // Held, or right clicked, its menu opens where it was pressed.
    var menu by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    PressableCard(
        onClick = onOpen,
        onLongClick = { at -> menu = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) } },
        onLongClickLabel = stringResource(R.string.action_more),
        modifier = modifier.fillMaxWidth().sharedCard(moment.uid).then(outline)
            .semantics { this.selected = selected }.testTag("moment_${moment.uid}"),
        flat = true,
    ) {
        if (moment.media.isNotEmpty()) {
            // Its photos in the large corners of their own; tapped as the rest of it is.
            MediaMosaic(moment.uid, moment.media, urls, onOpen = null, modifier = Modifier.clip(AppTheme.shapes.large))
        }
        Column(
            Modifier.padding(top = if (moment.media.isEmpty()) Space.xs else Space.m, bottom = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            MomentSubhead(moment, places, trip, showDate, hidePlace)
            if (moment.text.isNotBlank()) {
                Text(moment.text, style = AppTheme.typography.bodyReading, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            // What was eaten, under the words as the site has it: one line of secondary text.
            foodLine(moment.food)?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Icon(AppIcons.Food, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(18.dp))
                    Text(line, style = AppTheme.typography.body, color = tokens.tx2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (bills.isNotEmpty() && ledger != null) LinkedBills(bills, ledger, onBill)
        MomentMenu(moment, urls, menu, onDismiss = { menu = null })
    }
}

/**
 * A Moment as the conversation has it: a bubble from the right on the pressed fill, its photos
 * across its top, its words, and a faint line at its foot, to the right, of where, which trip
 * and when, and whether it is private or not yet published.
 */
@Composable
private fun MomentBubble(
    moment: MomentRecord,
    places: Map<String, Place>,
    trip: TripRecord?,
    urls: MediaUrls,
    onOpen: () -> Unit,
    modifier: Modifier,
    selected: Boolean,
    hidePlace: String?,
) {
    val ring by animateColorAsState(if (selected) tokens.accent else Color.Transparent, AppTheme.motionScheme.fastEffectsSpec(), label = "selected")
    var menu by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    val shape = cc.hyoban.moments.ui.feed.SentShape
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        // Most of the column, never wider than 420 on a wide screen; with photos always that
        // wide, words alone only as wide as they need.
        val max = minOf(maxWidth * 0.82f, 420.dp)
        val width = if (moment.media.isNotEmpty()) Modifier.width(max) else Modifier.widthIn(max = max)
        PressableCard(
            onClick = onOpen,
            onLongClick = { at -> menu = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) } },
            onLongClickLabel = stringResource(R.string.action_more),
            modifier = width.sharedCard(moment.uid).clip(shape).background(tokens.pressed).border(2.dp, ring, shape)
                .semantics { this.selected = selected }.testTag("moment_${moment.uid}"),
            flat = true,
        ) {
            if (moment.media.isNotEmpty()) MediaMosaic(moment.uid, moment.media, urls, onOpen = null)
            Column(
                (if (moment.media.isNotEmpty()) Modifier.fillMaxWidth() else Modifier).padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                if (moment.text.isNotBlank()) {
                    Text(moment.text, style = AppTheme.typography.body, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                foodLine(moment.food)?.let { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Icon(AppIcons.Food, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(16.dp))
                        Text(line, style = AppTheme.typography.bodySmall, color = tokens.tx2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val private = (moment.hidden || moment.topic == "tech").takeIf { it }?.let { stringResource(R.string.visibility_private) }
                val parts = moment.places.filter { it != hidePlace }.map { places[it]?.name ?: it } + listOfNotNull(trip?.title, private)
                // The time in the figures' monospace, as the records bubbles beside it have theirs.
                val time = formatTime(moment.occurredAt)
                val numFont = AppTheme.typography.numCaption.fontFamily
                val meta = androidx.compose.ui.text.buildAnnotatedString {
                    append(parts.joinToString("\u2003"))
                    if (time != null) {
                        if (length > 0) append("\u2003")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = numFont))
                        append(time)
                        pop()
                    }
                }
                Row(
                    Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(meta, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (moment.published == null) Pill(stringResource(R.string.label_on_phone), icon = AppIcons.Phone)
                }
            }
            MomentMenu(moment, urls, menu, onDismiss = { menu = null })
        }
    }
}

/**
 * When, where and which trip, as a card's subhead in Material 3: one line of secondary text,
 * as none of it is tapped on its own. Whether it is only on this phone is state, so that
 * stays a label beside it.
 */
@Composable
fun MomentSubhead(moment: MomentRecord, places: Map<String, Place>, trip: TripRecord?, showDate: Boolean = false, hidePlace: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val time = listOfNotNull(if (showDate) formatDate(moment.occurredAt) else null, formatTime(moment.occurredAt)).joinToString(" ")
        // Private (hidden, or about tech) is said in the faint line itself, as the design has it.
        val private = (moment.hidden || moment.topic == "tech").takeIf { it }?.let { stringResource(R.string.visibility_private) }
        val parts = listOfNotNull(time.ifEmpty { null }) + moment.places.filter { it != hidePlace }.map { places[it]?.name ?: it } + listOfNotNull(trip?.title, private)
        Text(
            parts.joinToString("\u2003"),
            style = AppTheme.typography.body,
            color = tokens.tx2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (moment.published == null) Pill(stringResource(R.string.label_on_phone), icon = AppIcons.Phone)
    }
}

/** A back button that floats over a photo or a map, legible on either, round as every floating button is. */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) =
    FloatingButton(AppIcons.ArrowLeft, stringResource(R.string.action_back), onBack, modifier, tag = "back")

/**
 * A button that floats over a photo or a map, legible on either, as the back button does.
 * A circle; the page's one main action may be [tonal], the ink, the others stay on the
 * floating surface with its hairline.
 */
@Composable
fun FloatingButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    busy: Boolean = false,
    tonal: Boolean = false,
) {
    // Floating over a photo, a map or a card: a shadow. Drawn at 40, its touch target still 48dp.
    Tooltip(label, below = true, modifier = modifier) {
        Surface(
            onClick = onClick,
            enabled = !busy,
            shape = Corners.full,
            color = if (tonal) tokens.ink else SurfaceLevel.Floating.fill(),
            contentColor = if (tonal) tokens.onInk else tokens.tx,
            border = if (tonal) null else cardBorder(),
            shadowElevation = Elevation.level2,
            modifier = Modifier.statusBarsPadding().sideInsetsPadding().padding(horizontal = Space.m, vertical = Space.s)
                .size(FLOATING_HEIGHT).semantics { contentDescription = label }.testTag(tag),
        ) {
            // Busy, a short wait, it turns a spinner and takes no taps until what it started is done.
            Box(contentAlignment = Alignment.Center) {
                if (busy) Spinner(Modifier.size(24.dp), color = LocalContentColor.current) else Icon(icon, contentDescription = null)
            }
        }
    }
}

private val FLOATING_HEIGHT = 40.dp

/** How tall a row of floating buttons stands under the status bar, for a page to keep what it shows clear of them. */
val FLOATING_BAR = FLOATING_HEIGHT + Space.s * 2

/** Card-coloured sheet that rises over a photo or a map at the top of a page. */
@Composable
fun RisingSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        color = AppColors.page,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
