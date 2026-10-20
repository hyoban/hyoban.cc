package cc.hyoban.moments.ui.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.Change
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.Pill
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.Group
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/**
 * Everything kept on the phone that publishing would change on the site, to look over
 * first, each Moment a card: one written here whole, one of the site's changed as what
 * changed, its words marked where they were taken out and put in, its photos where they
 * went and came, its time and place from what they were, and one deleted faded and struck
 * through. A change or a deletion can be taken back; the button at the foot publishes all
 * of it, the only way the app writes to the site.
 */
@Composable
fun ChangesScreen(container: AppContainer, onMoment: (String) -> Unit, onBack: () -> Unit) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val publishing by container.publishing.collectAsStateWithLifecycle()
    val urls = container.mediaUrls
    val busy = publishing != null

    // Back to where it was opened from once everything is published.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(publishing, pending) {
        if (busy) started = true else if (started && pending.size == 0) onBack()
    }

    SubPage(
        stringResource(R.string.title_changes),
        onBack,
        Modifier.background(AppColors.page),
        subtitle = if (pending.size == 0) null else pluralStringResource(R.plurals.count_changes, pending.size, pending.size),
    ) {
        PageWidth { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                if (pending.size == 0) {
                    Group(null, stringResource(R.string.empty_changes)) {}
                    return@Column
                }
                pending.added.forEachIndexed { index, moment ->
                    Group(if (index == 0) stringResource(R.string.section_added) else null, null) {
                        ChangeCard(moment, onOpen = { onMoment(moment.uid) }.takeUnless { busy }) {
                            MomentWhole(moment, places, urls)
                        }
                    }
                }
                pending.changed.forEachIndexed { index, change ->
                    Group(if (index == 0) stringResource(R.string.section_changed) else null, null) {
                        ChangeCard(
                            change.after,
                            identity = Identity(change.before ?: change.after, places, urls),
                            onOpen = { onMoment(change.after.uid) }.takeUnless { busy },
                            onRevert = { container.revert(change.after.uid) }.takeUnless { busy },
                        ) {
                            ChangedFields(change, places, urls)
                        }
                    }
                }
                pending.removed.forEachIndexed { index, moment ->
                    Group(if (index == 0) stringResource(R.string.section_removed) else null, null) {
                        ChangeCard(moment, onRevert = { container.revert(moment.uid) }.takeUnless { busy }) {
                            MomentWhole(moment, places, urls, removed = true)
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(
                        onClick = container::publishPending,
                        enabled = container.canPublish && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("publish_all"),
                    ) {
                        val progress = publishing
                        if (progress != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                Text(stringResource(R.string.message_publishing_progress, progress.done, progress.total))
                            }
                        } else {
                            Text(stringResource(R.string.action_publish_all))
                        }
                    }
                    Text(
                        stringResource(if (container.canPublish) R.string.note_publish_all else R.string.note_publish_needs_token),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.l),
                    )
                }
            }
        }
    }
}

/**
 * The changes kept on the phone, at the head of the Moments while there are any: a tonal
 * row saying how many, a loading indicator while they publish, opening them to look over.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PendingChangesRow(container: AppContainer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val publishing by container.publishing.collectAsStateWithLifecycle()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("changes"),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.title_changes)) },
            supportingContent = {
                val progress = publishing
                Text(
                    if (progress != null) {
                        stringResource(R.string.message_publishing_progress, progress.done, progress.total)
                    } else {
                        pluralStringResource(R.plurals.count_changes, pending.size, pending.size)
                    },
                )
            },
            leadingContent = {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (publishing != null) LoadingIndicator(Modifier.size(32.dp), color = LocalContentColor.current) else Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                }
            },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )
    }
}

/** Which Moment a change is to, as the site has it: its first photo, its first words, when and where. */
private class Identity(val moment: MomentRecord, val places: Map<String, Place>, val urls: MediaUrls)

/**
 * One Moment's change on its card: at its head which Moment it is, by [identity] when the
 * change itself may not say, else when it is; under it [content]; and taking it back when
 * it can be.
 */
@Composable
private fun ChangeCard(
    moment: MomentRecord,
    identity: Identity? = null,
    onOpen: (() -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        (if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .fillMaxWidth()
            .padding(start = Space.l, end = Space.s, top = Space.s, bottom = Space.l)
            .testTag("change_${moment.uid}"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (identity != null) {
                MomentIdentity(identity, Modifier.weight(1f).padding(top = Space.s))
            } else {
                Text(
                    listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = Space.m),
                )
            }
            if (onRevert != null) {
                TextButton(onClick = onRevert, modifier = Modifier.testTag("revert_${moment.uid}")) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Space.xs))
                    Text(stringResource(R.string.action_revert))
                }
            }
        }
        if (identity != null) HorizontalDivider(Modifier.padding(end = Space.s), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(Modifier.padding(end = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) { content() }
    }
}

/** A Moment named for the eye: its first photo small, its first words, and when and where under them. */
@Composable
private fun MomentIdentity(identity: Identity, modifier: Modifier = Modifier) {
    val moment = identity.moment
    val where = moment.places.joinToString(stringResource(R.string.separator_list)) { identity.places[it]?.name ?: it }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        moment.media.firstOrNull()?.let { Thumbnail(it, identity.urls, size = 48) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                moment.text.lineSequence().firstOrNull { it.isNotBlank() } ?: where.ifEmpty { stringResource(R.string.label_no_words) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt), where.takeIf { moment.text.isNotBlank() && it.isNotEmpty() })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A Moment as it would be published, or, [removed], as it would go: faded, its words struck through. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentWhole(moment: MomentRecord, places: Map<String, Place>, urls: MediaUrls, removed: Boolean = false) {
    Column(Modifier.alpha(if (removed) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (moment.text.isNotBlank()) {
            Text(
                moment.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (removed) TextDecoration.LineThrough else null,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (moment.media.isNotEmpty()) {
            PhotoRow(moment.media.map { it to null }, urls)
        }
        if (moment.places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                moment.places.forEach { Pill(places[it]?.name ?: it, icon = Icons.Rounded.Place) }
            }
        }
    }
}

/** What a change changed, each under its name; all of it as it is when the site no longer has the Moment. */
@Composable
private fun ChangedFields(change: Change, places: Map<String, Place>, urls: MediaUrls) {
    val after = change.after
    val before = change.before
    if (before == null) {
        Text(stringResource(R.string.note_change_recreates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MomentWhole(after, places, urls)
        return
    }
    var any = false
    if (before.text != after.text) {
        any = true
        Field(R.string.change_text) { TextChange(before.text, after.text) }
    }
    if (before.media.map { it.uid } != after.media.map { it.uid }) {
        any = true
        val kept = before.media.map { it.uid }.toSet()
        val now = after.media.map { it.uid }.toSet()
        val photos = after.media.map { it to (if (it.uid in kept) null else true) } +
            before.media.filter { it.uid !in now }.map { it to false }
        Field(R.string.change_photos) { PhotoRow(photos, urls) }
    }
    if (before.occurredAt != after.occurredAt) {
        any = true
        val whenOf = { moment: MomentRecord -> listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ") }
        Field(R.string.change_time) { FromTo(whenOf(before), whenOf(after), Icons.Rounded.Schedule) }
    }
    if (before.places != after.places) {
        any = true
        val separator = stringResource(R.string.separator_list)
        val none = stringResource(R.string.place_none)
        val names = { ids: List<String> -> ids.joinToString(separator) { places[it]?.name ?: it }.ifEmpty { none } }
        Field(R.string.change_place) { FromTo(names(before.places), names(after.places), Icons.Rounded.Place) }
    }
    if (!any) {
        Text(stringResource(R.string.note_change_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A field that changed, under its name. */
@Composable
private fun Field(name: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** The words as changed: what was taken out struck through in the error colour, what was put in on the primary's container. */
@Composable
private fun TextChange(before: String, after: String) {
    val removed = SpanStyle(
        color = MaterialTheme.colorScheme.error,
        background = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        textDecoration = TextDecoration.LineThrough,
    )
    val added = SpanStyle(color = MaterialTheme.colorScheme.onPrimaryContainer, background = MaterialTheme.colorScheme.primaryContainer)
    val parts = remember(before, after) { diffText(before, after) }
    Text(
        buildAnnotatedString {
            for (part in parts) {
                when (part.kind) {
                    DiffPart.Kind.SAME -> append(part.text)
                    DiffPart.Kind.REMOVED -> withStyle(removed) { append(part.text) }
                    DiffPart.Kind.ADDED -> withStyle(added) { append(part.text) }
                }
            }
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** What a field was, struck through, and what it is now. */
@Composable
private fun FromTo(from: String, to: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            from,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(to, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * Photos in a row, each marked by what became of it: null as it was, true put in, ringed
 * in primary with a plus, false taken out, faded, ringed in the error colour with a minus.
 */
@Composable
private fun PhotoRow(photos: List<Pair<MomentMedia, Boolean?>>, urls: MediaUrls) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for ((media, added) in photos) {
            val ring = when (added) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> Color.Transparent
            }
            Box(Modifier.testTag("change_photo_${media.uid}")) {
                Thumbnail(
                    media,
                    urls,
                    size = PHOTO_SIZE,
                    modifier = Modifier
                        .alpha(if (added == false) 0.4f else 1f)
                        .border(2.dp, ring, MaterialTheme.shapes.medium),
                )
                if (added != null) {
                    Icon(
                        if (added) Icons.Rounded.Add else Icons.Rounded.Remove,
                        contentDescription = stringResource(if (added) R.string.label_photo_added else R.string.label_photo_removed),
                        tint = if (added) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(ring).padding(2.dp),
                    )
                }
            }
        }
    }
}

private const val PHOTO_SIZE = 72
