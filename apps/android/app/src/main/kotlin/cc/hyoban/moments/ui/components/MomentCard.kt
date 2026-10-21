package cc.hyoban.moments.ui.components

import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.moment.MomentMenu
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/**
 * A Moment in a list: its media inset at the top of a white card, then a subhead of when,
 * where and the trip, then its words. Tapping anywhere, its photos too, opens the Moment;
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
) {
    // The card grows into the Moment's page when opened; shown in the detail pane beside the
    // list, it is [selected], outlined in the primary colour, as Material 3 marks the item a
    // two-pane layout's detail is of.
    val outline = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier
    // Held, or right clicked, its menu opens where it was pressed.
    var menu by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    PressableCard(
        onClick = onOpen,
        onLongClick = { at -> menu = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) } },
        onLongClickLabel = stringResource(R.string.action_more),
        modifier = modifier.fillMaxWidth().sharedCard(moment.uid).then(outline)
            .semantics { this.selected = selected }.testTag("moment_${moment.uid}"),
    ) {
        if (moment.media.isNotEmpty()) {
            // To the card's edges, its top corners rounding them; tapped as the rest of the card is.
            MediaMosaic(moment.uid, moment.media, urls, onOpen = null)
        }
        Column(
            Modifier.padding(start = Space.l, end = Space.l, top = if (moment.media.isEmpty()) Space.l else Space.s, bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            MomentSubhead(moment, places, trip, showDate)
            if (moment.text.isNotBlank()) {
                Text(moment.text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        MomentMenu(moment, urls, menu, onDismiss = { menu = null })
    }
}

/**
 * When, where and which trip, as a card's subhead in Material 3: one line of secondary text,
 * as none of it is tapped on its own. Whether it is only on this phone is state, so that
 * stays a label beside it.
 */
@Composable
fun MomentSubhead(moment: MomentRecord, places: Map<String, Place>, trip: TripRecord?, showDate: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val time = listOfNotNull(if (showDate) formatDate(moment.occurredAt) else null, formatTime(moment.occurredAt)).joinToString(" ")
        val parts = listOfNotNull(time.ifEmpty { null }) + moment.places.map { places[it]?.name ?: it } + listOfNotNull(trip?.title)
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (moment.published == null) Pill(stringResource(R.string.label_on_phone), icon = Icons.Rounded.PhoneAndroid)
    }
}

/**
 * A back button that floats over a photo or a map, legible on either, narrow as M3 has a back
 * button beside other actions; its middle where a top app bar's back button has its own.
 */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) =
    FloatingButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack, modifier, tag = "back", width = FloatingWidth.Narrow)

/**
 * How wide a floating button is, as Material 3's small icon button comes: narrow for back,
 * wide for the page's main action.
 */
enum class FloatingWidth(val width: Dp) { Narrow(32.dp), Standard(40.dp), Wide(52.dp) }

/**
 * A button that floats over a photo or a map, legible on either, as the back button does.
 * The page's one main action may be [tonal], in the secondary container's colours, and
 * [width] wide; the others stay the floating level.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    busy: Boolean = false,
    tonal: Boolean = false,
    width: FloatingWidth = FloatingWidth.Standard,
) {
    // Floating over a photo, a map or a card: a shadow. Drawn at Material
    // 3's small icon button size, its touch target still 48dp, as Compose widens a small one.
    Tooltip(label, below = true, modifier = modifier) {
        Surface(
            onClick = onClick,
            enabled = !busy,
            shape = Corners.full,
            color = if (tonal) MaterialTheme.colorScheme.secondaryContainer else SurfaceLevel.Floating.fill(),
            contentColor = if (tonal) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            shadowElevation = Elevation.level2,
            modifier = Modifier.statusBarsPadding().sideInsetsPadding().padding(horizontal = Space.m, vertical = Space.s)
                .size(width.width, FLOATING_HEIGHT).semantics { contentDescription = label }.testTag(tag),
        ) {
            // Busy, a short wait, it turns Material 3 Expressive's loading indicator and takes no taps until what it started is done.
            Box(contentAlignment = Alignment.Center) {
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current) else Icon(icon, contentDescription = null)
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
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        color = AppColors.page,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
