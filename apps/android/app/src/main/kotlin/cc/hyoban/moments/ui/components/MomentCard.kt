package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/**
 * A Moment in a list: its media inset at the top of a white card, then pills for when,
 * where and the trip, then its words. Tapping anywhere, its photos too, opens the Moment.
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
) {
    // The card grows into the Moment's page when opened.
    PressableCard(onClick = onOpen, modifier = modifier.fillMaxWidth().sharedCard(moment.uid).testTag("moment_${moment.uid}")) {
        if (moment.media.isNotEmpty()) {
            // To the card's edges, its top corners rounding them.
            MediaMosaic(moment.uid, moment.media, urls, onOpen = { onOpen() })
        }
        Column(
            Modifier.padding(start = Space.l, end = Space.l, top = if (moment.media.isEmpty()) Space.l else Space.s, bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s + 2.dp),
        ) {
            MomentPills(moment, places, trip, showDate)
            if (moment.text.isNotBlank()) {
                Text(moment.text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** When, where and which trip, each a neutral pill, and whether it is only on this phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentPills(moment: MomentRecord, places: Map<String, Place>, trip: TripRecord?, showDate: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val time = listOfNotNull(if (showDate) formatDate(moment.occurredAt) else null, formatTime(moment.occurredAt)).joinToString(" ")
        if (time.isNotEmpty()) {
            Pill(time, icon = Icons.Rounded.Schedule)
        }
        moment.places.forEach { id -> Pill(places[id]?.name ?: id, icon = Icons.Rounded.Place) }
        trip?.let { Pill(it.title, icon = Icons.Rounded.Luggage) }
        if (moment.published == null) Pill(stringResource(R.string.label_on_phone), icon = Icons.Rounded.PhoneAndroid)
    }
}

/** A Moment in a sheet or a short list: its first media small, when, its first words. */
@Composable
fun CompactMomentRow(moment: MomentRecord, places: Map<String, Place>, urls: MediaUrls, onClick: () -> Unit) {
    // It grows into the Moment's page when opened, as a card on a list does.
    PressableCard(onClick = onClick, modifier = Modifier.fillMaxWidth().sharedCard(moment.uid).testTag("moment_${moment.uid}")) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            moment.media.firstOrNull()?.let { Thumbnail(it, urls, size = 76) }
            Column(Modifier.weight(1f).padding(top = Space.s, end = Space.s, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    moment.text.ifBlank { moment.places.joinToString(stringResource(R.string.separator_list)) { places[it]?.name ?: it } },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A back button that floats over a photo or a map, legible on either, narrow as M3 has a back button beside other actions. */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) =
    FloatingButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack, modifier, tag = "back", width = FloatingWidth.Narrow)

/** How wide a floating button is beside its height: narrow for back, wide for the page's main action. */
enum class FloatingWidth(val width: Dp) { Narrow(40.dp), Standard(48.dp), Wide(64.dp) }

/**
 * A button that floats over a photo or a map, legible on either, as the back button does.
 * The page's one main action may be [tonal], in the secondary container's colours, and
 * [width] wide; the others stay the floating level.
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
    width: FloatingWidth = FloatingWidth.Standard,
) {
    // Floating over a photo, a map or a card: a shadow, as the tabs have. Its touch target
    // stays 48dp however narrow it is drawn.
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = Corners.full,
        color = if (tonal) MaterialTheme.colorScheme.secondaryContainer else SurfaceLevel.floating(LocalDarkTheme.current).fill(),
        contentColor = if (tonal) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier.statusBarsPadding().sideInsetsPadding().padding(Space.s)
            .size(width.width, FLOATING_HEIGHT).semantics { contentDescription = label }.testTag(tag),
    ) {
        // Busy, it turns a spinner and takes no taps until what it started is done.
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, contentDescription = null)
        }
    }
}

private val FLOATING_HEIGHT = 48.dp

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
