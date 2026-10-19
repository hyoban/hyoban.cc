package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space

/**
 * A Moment in a list: its media inset at the top of a white card, then pills for when,
 * where and the trip, then its words. Tapping a photo opens it; the rest opens the Moment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentCard(
    moment: MomentRecord,
    places: Map<String, Place>,
    trip: TripRecord?,
    urls: MediaUrls,
    onOpen: () -> Unit,
    onOpenMedia: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    PressableCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        if (moment.media.isNotEmpty()) {
            MediaMosaic(
                moment.uid,
                moment.media,
                urls,
                onOpen = onOpenMedia,
                modifier = Modifier.padding(6.dp).clip(Corners.inset),
            )
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

/** When, where and which trip, each a pill: the time in the primary colour, places neutral, the trip in the tertiary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentPills(moment: MomentRecord, places: Map<String, Place>, trip: TripRecord?, showDate: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val time = listOfNotNull(if (showDate) formatDate(moment.occurredAt) else null, formatTime(moment.occurredAt)).joinToString(" ")
        if (time.isNotEmpty()) {
            Pill(
                time,
                icon = Icons.Rounded.Schedule,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        moment.places.forEach { id -> Pill(places[id]?.name ?: id, icon = Icons.Rounded.Place) }
        trip?.let {
            Pill(
                it.title,
                icon = Icons.Rounded.Luggage,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** A Moment in a sheet or a short list: its first media small, when, its first words. */
@Composable
fun CompactMomentRow(moment: MomentRecord, places: Map<String, Place>, urls: MediaUrls, onClick: () -> Unit) {
    PressableCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            moment.media.firstOrNull()?.let { Thumbnail(it, urls, size = 76) }
            Column(Modifier.weight(1f).padding(top = Space.s, end = Space.s, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    moment.text.ifBlank { moment.places.joinToString("、") { places[it]?.name ?: it } },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A back button that floats over a photo or a map, legible on either. */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onBack,
        modifier = modifier.statusBarsPadding().padding(Space.s),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        ),
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

/** Card-coloured sheet that rises over a photo or a map at the top of a page. */
@Composable
fun RisingSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = AppColors.page,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
