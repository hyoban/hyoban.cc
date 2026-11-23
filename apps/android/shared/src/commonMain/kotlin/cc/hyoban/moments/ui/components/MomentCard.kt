package cc.hyoban.moments.ui.components

import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.ui.draw.clip
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.design.Spinner
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.format.costLine
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.feed.bubbleWidth
import cc.hyoban.moments.ui.feed.dashedOutline
import cc.hyoban.moments.ui.design.sheetSurface

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
    /**
     * What the modules have go with it (the bills linked to it, the charges at its time), each
     * drawn by its module: in its card at its foot, or as a bubble at its foot inside it.
     */
    attached: List<cc.hyoban.moments.ui.feed.DayRecord> = emptyList(),
    /** As a bubble sent from the right, as the timeline and a trip are a conversation; its bills then at its foot inside it. */
    bubble: Boolean = false,
    /** As a bubble, whether the one above or below it is a Moment too, a run of them drawn together. */
    joinedAbove: Boolean = false,
    joinedBelow: Boolean = false,
) {
    if (bubble) return MomentBubble(moment, places, trip, urls, onOpen, modifier, selected, hidePlace, joinedAbove, joinedBelow, attached)
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
        onLongClickLabel = stringResource(Res.string.action_more),
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
            // What the modules that are on add under the words, as the site has it: what it cost.
            val settings = LocalSettings.current
            cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.cardLines, settings::isOn).forEach { it.Line(moment) }
        }
        // What goes with it in its card, at its foot, each opening itself.
        val inCard = attached.filter { it.inCard }
        if (inCard.isNotEmpty()) Column(Modifier.padding(top = Space.s)) { inCard.forEach { it.AttachedRow() } }
        cc.hyoban.moments.ui.moment.MomentMenu(moment, urls, menu, onDismiss = { menu = null })
    }
}

/**
 * A Moment as the conversation has it: a bubble from the right on the pressed fill, its photos
 * across its top, its words (how a meal was left to its page), and after them, faint, where,
 * which trip and when, and whether it is private or not yet published; at its foot, what it
 * cost and the bills linked to it.
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
    joinedAbove: Boolean,
    joinedBelow: Boolean,
    attached: List<cc.hyoban.moments.ui.feed.DayRecord> = emptyList(),
) {
    // What of its own the modules that are on put at its foot (what it cost, with its amount)
    // leads what goes with it there.
    val settings = LocalSettings.current
    val footRecords = cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.feet, settings::isOn).mapNotNull { it.footOf(moment) } + attached
    val withBills = footRecords.isNotEmpty()
    val ring by animateColorAsState(if (selected) tokens.accent else Color.Transparent, AppTheme.motionScheme.fastEffectsSpec(), label = "selected")
    var menu by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    // Written into a plan, not lived: no fill and no tail, a dashed hairline in their place.
    val planned = moment.planned == true
    val plannedLabel = stringResource(Res.string.trip_planned)
    // Settled by its owner: the dashed line drawn whole.
    val locked = planned && moment.locked == true
    val shape = remember(joinedAbove, joinedBelow, planned) {
        if (planned) cc.hyoban.moments.ui.feed.PlannedBubbleShape(joinedAbove, joinedBelow)
        else cc.hyoban.moments.ui.feed.BubbleShape(sent = true, joinedAbove, joinedBelow)
    }
    val tail = cc.hyoban.moments.ui.feed.TAIL
    val surface = when {
        locked -> Modifier.clip(shape).border(1.dp, tokens.borderStrong, shape)
        planned -> Modifier.clip(shape).dashedOutline(shape, tokens.borderStrong)
        else -> Modifier.clip(shape).background(tokens.fill)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        // Most of the column, never wider than 420 on a wide screen; with photos always that
        // wide, words alone only as wide as they need. The tail's strip comes on top, at the right.
        val width = Modifier.bubbleWidth(fill = moment.media.isNotEmpty() || withBills) { column -> minOf(column * 0.88f, 420.dp) }
        PressableCard(
            onClick = onOpen,
            onLongClick = { at -> menu = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) } },
            onLongClickLabel = stringResource(Res.string.action_more),
            modifier = width.sharedCard(moment.uid).then(surface).border(2.dp, ring, shape).padding(end = tail)
                // The dashed outline shows a planned one; TalkBack says it.
                .semantics {
                    this.selected = selected
                    if (planned) stateDescription = plannedLabel
                }.testTag("moment_${moment.uid}"),
            flat = true,
            shape = remember(joinedAbove, joinedBelow) { cc.hyoban.moments.ui.feed.sentBodyShape(joinedAbove, joinedBelow) },
            // Words alone wrap the bubble to them, so where and when end at its edge.
            fillWidth = moment.media.isNotEmpty() || withBills,
        ) {
            if (moment.media.isNotEmpty()) MediaMosaic(moment.uid, moment.media, urls, onOpen = null)
            Column(
                (if (moment.media.isNotEmpty() || withBills) Modifier.fillMaxWidth() else Modifier).padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                val private = moment.hidden.takeIf { it }?.let { stringResource(Res.string.visibility_private) }
                val parts = moment.places.filter { it != hidePlace }.map { places[it]?.name ?: it } + listOfNotNull(trip?.title, private)
                // The time in the figures' monospace, as the records bubbles beside it have theirs.
                val time = cc.hyoban.moments.ui.feed.momentTime(moment)
                val numFont = AppTheme.typography.caption.fontFamily
                val meta = androidx.compose.ui.text.buildAnnotatedString {
                    append(parts.joinToString(" "))
                    if (time != null) {
                        if (length > 0) append(" ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = numFont))
                        append(time)
                        pop()
                    }
                }
                val foot: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            // A locked one starts with a lock.
                            if (locked) Icon(AppIcons.Lock, contentDescription = stringResource(Res.string.plan_locked), tint = tokens.tx3, modifier = Modifier.size(12.dp))
                            Text(meta, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (moment.published == null && LocalSettings.current.connected) Pill(stringResource(Res.string.label_on_phone), icon = AppIcons.Phone)
                    }
                }
                // How a meal was stays on the Moment's page: the conversation shows the words, and
                // what it cost at the foot, among the bills.
                if (moment.text.isNotBlank()) {
                    // As Telegram has it, where and when follow the last line of the words, on
                    // a line of their own only when they don't fit there.
                    TrailingFoot(if (moment.media.isNotEmpty() || withBills) Modifier.fillMaxWidth() else Modifier, foot) {
                        Text(moment.text, style = AppTheme.typography.body, color = if (planned) tokens.tx2 else androidx.compose.ui.graphics.Color.Unspecified, maxLines = 8, overflow = TextOverflow.Ellipsis, onTextLayout = it)
                    }
                } else {
                    Box(Modifier.align(Alignment.End)) { foot() }
                }
                // What the meal cost and its linked bills at its foot, each bill opening its bill.
                if (withBills) cc.hyoban.moments.ui.feed.BubbleFoot(footRecords, Modifier.padding(top = Space.xs), time = time)
            }
            cc.hyoban.moments.ui.moment.MomentMenu(moment, urls, menu, onDismiss = { menu = null })
        }
    }
}

/**
 * [text], with [foot] after the end of its last line when it fits there beside it, [Space.m]
 * apart and on the same baseline, else on a line of its own at the right, as Telegram sets a
 * message's time. [text] passes on its layout so the last line can be measured.
 */
@Composable
private fun TrailingFoot(
    modifier: Modifier,
    foot: @Composable () -> Unit,
    text: @Composable ((androidx.compose.ui.text.TextLayoutResult) -> Unit) -> Unit,
) {
    val layout = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    val gap = Space.m
    androidx.compose.ui.layout.Layout(
        contents = listOf({ text { layout[0] = it } }, foot),
        modifier = modifier,
    ) { (words, feet), constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val w = words.first().measure(free)
        val f = feet.first().measure(free)
        val result = layout[0]
        val gapPx = gap.roundToPx()
        val last = result?.let { it.lineCount - 1 }
        val lastRight = if (result != null && last != null) kotlin.math.ceil(result.getLineRight(last)).toInt() else w.width
        val inline = result != null && last != null && lastRight + gapPx + f.width <= constraints.maxWidth
        val width = maxOf(constraints.minWidth, w.width, if (inline) lastRight + gapPx + f.width else f.width).coerceAtMost(constraints.maxWidth)
        // On the same line, the foot's baseline on the last line's; else just under the words.
        val footBaseline = f[androidx.compose.ui.layout.FirstBaseline]
        val footY = if (inline && result != null && last != null) {
            if (footBaseline != androidx.compose.ui.layout.AlignmentLine.Unspecified) result.getLineBaseline(last).toInt() - footBaseline
            else result.getLineBottom(last).toInt() - f.height
        } else w.height + Space.xs.roundToPx()
        val height = maxOf(w.height, footY + f.height)
        layout(width, height) {
            w.place(0, 0)
            f.place(width - f.width, footY)
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
        val time = listOfNotNull(if (showDate) formatDate(moment.occurredAt) else null, cc.hyoban.moments.ui.feed.momentTime(moment)).joinToString(" ")
        // Private is said in the faint line itself, as the design has it.
        val private = moment.hidden.takeIf { it }?.let { stringResource(Res.string.visibility_private) }
        val parts = listOfNotNull(time.ifEmpty { null }) + moment.places.filter { it != hidePlace }.map { places[it]?.name ?: it } + listOfNotNull(trip?.title, private)
        Text(
            parts.joinToString(" "),
            style = AppTheme.typography.body,
            color = tokens.tx2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (moment.published == null && LocalSettings.current.connected) Pill(stringResource(Res.string.label_on_phone), icon = AppIcons.Phone)
    }
}

/** A back button that floats over a photo or a map, legible on either, round as every floating button is. */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) =
    FloatingButton(AppIcons.ArrowLeft, stringResource(Res.string.action_back), onBack, modifier, tag = "back")

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
            color = if (tonal) tokens.tx else SurfaceLevel.Floating.fill(),
            contentColor = if (tonal) tokens.bg else tokens.tx,
            border = if (tonal) null else cardBorder(),
            shadowElevation = ShadowElevation,
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
    // What it holds keeps clear of its corners, below the page's top padding.
    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().sheetSurface(AppColors.page, AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)),
        content = content,
    )
}

/** A line of secondary text under a Moment's words, behind its icon. */
@Composable
internal fun QuietLine(icon: androidx.compose.ui.graphics.vector.ImageVector, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(18.dp))
        Text(line, style = AppTheme.typography.body, color = tokens.tx2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
