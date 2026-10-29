package cc.hyoban.moments.ui.publish

import kotlinx.coroutines.launch

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.cardBorder
import androidx.compose.ui.graphics.StrokeCap
import cc.hyoban.moments.ui.design.LinearProgressIndicator
import cc.hyoban.moments.ui.components.restingElevation
import cc.hyoban.moments.ui.components.floatingElevation
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.ExtendedFloatingActionButton
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.HorizontalDivider
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.ProgressIndicatorDefaults
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.SwipeToDismissBox
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.data.spec.showcase
import cc.hyoban.moments.data.store.Change
import cc.hyoban.moments.data.store.PlaceChange
import cc.hyoban.moments.data.store.PublishProgress
import cc.hyoban.moments.publish.publishingText
import cc.hyoban.moments.ui.LocalSnackbarLift
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.compose.label
import cc.hyoban.moments.data.spec.MomentTopic
import cc.hyoban.moments.ui.format.topicLabel
import cc.hyoban.moments.ui.format.foodLine
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.Caption
import cc.hyoban.moments.ui.settings.Group
import cc.hyoban.moments.ui.theme.DISABLED_ALPHA
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.dividerColor
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
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { container.publishPending() }

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
        PageWidth(maxColumns = 1) { columns ->
            // A lazy list, so a change taken back folds away and one brought back by its
            // snackbar opens out again, the rest stepping aside, rather than jumping.
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = columns.margin, end = columns.margin, bottom = Space.xl + FAB_CLEARANCE),
            ) {
                if (pending.size == 0) {
                    item(key = "empty") { Column(Modifier.animateItem()) { Group(null, stringResource(R.string.empty_changes)) {} } }
                } else {
                    item(key = "private-note") {
                        Text(
                            stringResource(R.string.changes_private_note),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.xs),
                        )
                    }
                }
                section(R.string.section_added, pending.added, key = { it.uid }) { moment ->
                    ChangeCard(moment, onOpen = { onMoment(moment.uid) }.takeUnless { busy }) {
                        MomentWhole(moment, places, urls)
                    }
                }
                section(R.string.section_changed, pending.changed, key = { it.after.uid }) { change ->
                    val revert = { container.revert(change.after.uid) }.takeUnless { busy }
                    SwipeToRevert(revert) {
                        ChangeCard(
                            change.after,
                            identity = Identity(change.before ?: change.after, places, urls),
                            onOpen = { onMoment(change.after.uid) }.takeUnless { busy },
                            onRevert = revert,
                        ) {
                            ChangedFields(change, places, urls)
                        }
                    }
                }
                section(R.string.section_changed_places, pending.places, key = { "place-${it.id}" }) { change ->
                    val revert = { container.revertPlace(change.id) }.takeUnless { busy }
                    SwipeToRevert(revert) { PlaceChangeCard(change, revert) }
                }
                section(R.string.section_changed_trips, pending.trips, key = { "trip-${it.before.id}" }) { change ->
                    val revert = { container.scope.launch { container.repository.revertTrip(change.before.id) }; Unit }.takeUnless { busy }
                    val after = change.after
                    val title = (after["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: change.before.title
                    val span = after["span"] as? kotlinx.serialization.json.JsonObject
                    val start = (span?.get("start") as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(10) ?: change.before.start
                    val end = (span?.get("end") as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(10) ?: change.before.end
                    val private = cc.hyoban.moments.data.spec.Entries.isPrivate(after)
                    SwipeToRevert(revert) {
                        OutsideCard(
                            title,
                            formatDate(start) + " – " + formatDate(end),
                            null,
                            stringResource(if (private) R.string.visibility_private else R.string.visibility_public),
                            revert,
                        )
                    }
                }
                section(R.string.section_visibility, pending.outside.bills, key = { "bill-${it.uid}" }) { change ->
                    // Taken back by saying again what the site has: private for a bill going
                    // public, public for one going private; a public bill that changed has no undo.
                    val revert = when {
                        change.part == null -> "public"
                        change.txn.published == null -> "private"
                        else -> null
                    }?.let { visibility ->
                        { container.scope.launch { container.ledger.saveTxn(change.txn.copy(visibility = visibility)) }; Unit }
                    }?.takeUnless { busy }
                    SwipeToRevert(revert) { OutsideCard(change.label, formatDate(change.txn.dateKey), billAmount(change), stringResource(if (change.part == null) R.string.changes_bill_private else R.string.changes_bill_public_note), revert) }
                }
                section(R.string.changes_tracks, pending.outside.tracks, key = { "track-${it.share.uid}" }) { change ->
                    val span = listOf(change.share.start, change.share.end).map { formatDate(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) }.distinct().joinToString(" – ")
                    OutsideCard(
                        stringResource(R.string.changes_track_public, span),
                        null,
                        null,
                        if (change.line == null) stringResource(R.string.changes_track_removed) else stringResource(R.string.trip_track_public_note),
                        null,
                    )
                }
                section(R.string.section_removed, pending.removed, key = { it.uid }) { moment ->
                    val revert = { container.revert(moment.uid) }.takeUnless { busy }
                    SwipeToRevert(revert) {
                        ChangeCard(moment, onRevert = revert) {
                            MomentWhole(moment, places, urls, removed = true)
                        }
                    }
                }
                if (pending.size > 0) {
                    item(key = "foot") {
                        Column(
                            Modifier.animateItem().padding(start = Space.gutter, end = Space.gutter, top = Space.xl).navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            // Publishing takes a while and knows how far it has got, so a determinate
                            // indicator, as Material 3 has for a wait of more than a few seconds.
                            PublishingIndicator(publishing, Modifier.fillMaxWidth().padding(vertical = Space.xs).testTag("publish_progress"))
                            Text(
                                stringResource(if (container.canPublish) R.string.note_publish_all else R.string.note_publish_needs_token),
                                style = AppTheme.typography.bodySmall,
                                color = AppTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Space.l),
                            )
                        }
                    }
                }
            }
        }
        // Publishing, the page's one action, an ink capsule floating at the bottom end to keep it
        // in reach over a long list, saying how far it has got while it runs.
        // Shown only when the app can publish, as a FAB isn't greyed out but goes.
        val publishShown = container.canPublish && pending.size > 0
        // Snackbars stand above the button while it shows.
        val lift = LocalSnackbarLift.current
        DisposableEffect(lift, publishShown) {
            lift.value = if (publishShown) FAB_CLEARANCE else 0.dp
            onDispose { lift.value = 0.dp }
        }
        // It grows in from its middle as a FAB enters, and its label's width follows what it says.
        val motion = AppTheme.motionScheme
        var shownProgress by remember { mutableStateOf(publishing) }
        if (publishShown) shownProgress = publishing
        AnimatedVisibility(
            publishShown,
            enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Space.gutter),
        ) {
            val progress = shownProgress
            ExtendedFloatingActionButton(
                onClick = {
                    if (progress == null) {
                        // The notification that keeps publishing going in the background asks
                        // once, on Android 13 and later; it publishes whatever the answer.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            container.publishPending()
                        }
                    }
                },
                shape = Corners.full,
                containerColor = tokens.ink,
                contentColor = tokens.onInk,
                elevation = floatingElevation(),
                icon = { Icon(AppIcons.CloudUpload, contentDescription = null) },
                text = {
                    Text(
                        if (progress != null) {
                            publishingText(LocalContext.current, progress)
                        } else {
                            stringResource(R.string.label_publish_all)
                        },
                        modifier = Modifier.animateContentSize(motion.fastSpatialSpec()),
                    )
                },
                modifier = Modifier.testTag("publish_all"),
            )
        }
    }
}

/**
 * A group of the changes under [title], each of [items] a card of its own that folds away
 * when taken back and opens out when brought back; the caption goes with the last of them.
 */
private fun <T> LazyListScope.section(title: Int, items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    item(key = "caption-$title") { Box(Modifier.animateItem()) { Caption(stringResource(title)) } }
    itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
        // Apart from the one above by the groups' gap; the first under its caption, as a group's card is.
        val gap by animateDpAsState(if (index == 0) 0.dp else Space.xl, AppTheme.motionScheme.defaultSpatialSpec(), label = "gap")
        Column(Modifier.animateItem().padding(top = gap)) {
            Column(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().clip(AppTheme.shapes.large).background(LocalSurfaceLevel.current.raised.fill()).border(cardBorder(), AppTheme.shapes.large)) {
                card(item)
            }
        }
    }
}

/** How far publishing has got, growing in under what it says while it runs and moving smoothly on as each change goes. */
@Composable
private fun PublishingIndicator(progress: PublishProgress?, modifier: Modifier = Modifier, color: Color = tokens.accent) {
    val motion = AppTheme.motionScheme
    var shown by remember { mutableStateOf(progress) }
    if (progress != null) shown = progress
    val fraction by animateFloatAsState(shown?.fraction ?: 0f, ProgressIndicatorDefaults.ProgressAnimationSpec, label = "published")
    AnimatedVisibility(
        progress != null,
        enter = expandVertically(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = color, trackColor = tokens.pressed, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/**
 * The changes kept on the phone, at the head of the timeline while there are any: a card
 * saying how many, how far publishing has got while it runs, opening them to look over.
 */
@Composable
fun PendingChangesRow(container: AppContainer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val publishing by container.publishing.collectAsStateWithLifecycle()
    Surface(
        onClick = onClick,
        shape = AppTheme.shapes.large,
        color = SurfaceLevel.Card.fill(),
        contentColor = AppTheme.colorScheme.onSurface,
        border = cardBorder(),
        modifier = modifier.fillMaxWidth().testTag("changes"),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.title_changes)) },
            supportingContent = {
                val progress = publishing
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        if (progress != null) {
                            publishingText(LocalContext.current, progress)
                        } else {
                            pluralStringResource(R.plurals.count_changes, pending.size, pending.size)
                        },
                    )
                    // The same linear indicator as the changes' page, as Material 3 has one
                    // process shown one way throughout.
                    PublishingIndicator(progress, Modifier.fillMaxWidth(), color = AppTheme.colorScheme.onSecondaryContainer)
                }
            },
            leadingContent = {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.CloudUpload, contentDescription = null)
                }
            },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = AppTheme.colorScheme.onSecondaryContainer,
                supportingColor = AppTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = AppTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = AppTheme.colorScheme.onSecondaryContainer,
            ),
        )
    }
}

/**
 * A change swiped from the end to take it back, as Material 3 has a list's item swiped for
 * its action: the undo icon on the accent's soft tint shows from under it as it goes, and
 * the card's own button does the same for whoever doesn't swipe. Its snackbar can undo it.
 */
@Composable
private fun SwipeToRevert(onRevert: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onRevert == null) return content()
    // Under a card as filled as the group it is in, so what is beneath shows only as it moves.
    val fill = LocalSurfaceLevel.current.raised.fill()
    SwipeToDismissBox(
        onDismiss = { onRevert() },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(AppTheme.colorScheme.secondaryContainer).padding(horizontal = Space.xl),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.action_revert), style = AppTheme.typography.label, color = AppTheme.colorScheme.onSecondaryContainer)
                Icon(AppIcons.Undo, contentDescription = null, tint = AppTheme.colorScheme.onSecondaryContainer)
            }
        },
    ) {
        Box(Modifier.background(fill)) { content() }
    }
}

/** Room under the list for the publishing button: a medium extended FAB and its margin. */
private val FAB_CLEARANCE = 96.dp

/** How much of the changes are published, for a determinate indicator. */

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
    // Not tapped as a whole: its actions are buttons of their own, as Material 3 keeps a card
    // either tapped or holding buttons, never both, at its foot, so what it is of has the
    // whole width at its head.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = if (onOpen != null || onRevert != null) Space.s else Space.l)
            .testTag("change_${moment.uid}"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (identity != null) {
            MomentIdentity(identity)
            HorizontalDivider(color = dividerColor())
        } else {
            Text(
                listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
                style = AppTheme.typography.label,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { content() }
        CardActions(
            onOpen?.let { open -> { TextButton(onClick = open, modifier = Modifier.testTag("open_${moment.uid}")) { Text(stringResource(R.string.action_view)) } } },
            onRevert?.let { revert -> { RevertButton(revert, Modifier.testTag("revert_${moment.uid}")) } },
        )
    }
}

/** A card's actions, at its foot on the end side, as Material 3 sets a card's buttons; nothing when it has none. */
@Composable
private fun CardActions(vararg actions: (@Composable () -> Unit)?) {
    val shown = actions.filterNotNull()
    if (shown.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { shown.forEach { it() } }
}

/** Takes back a change, its undo icon beside the words. */
@Composable
private fun RevertButton(onRevert: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onRevert, modifier = modifier) {
        Icon(AppIcons.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(Space.xs))
        Text(stringResource(R.string.action_revert))
    }
}

/** A bill's amount as it would publish, an income with its plus. */
private fun billAmount(change: cc.hyoban.moments.data.store.BillChange): String {
    val sign = if (change.txn.kind == cc.hyoban.moments.ledger.TxnKind.INCOME) "+" else ""
    return "$sign${change.txn.currency} ${"%.2f".format(change.txn.amount / 100.0)}"
}

/** A bill or a span of footprints going public or private: what it is, when, its figure, and what will happen. */
@Composable
private fun OutsideCard(title: String, date: String?, figure: String?, note: String, onRevert: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = if (onRevert != null) Space.s else Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTheme.typography.body, modifier = Modifier.weight(1f))
            figure?.let { Text(it, style = AppTheme.typography.numBody) }
        }
        Text(
            listOfNotNull(date, note).joinToString("\u2003"),
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        CardActions(onRevert?.let { revert -> { RevertButton(revert) } })
    }
}

/** One of the site's places with its kind changed: its name, and the kind from what it was. */
@Composable
private fun PlaceChangeCard(change: PlaceChange, onRevert: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = if (onRevert != null) Space.s else Space.l)
            .testTag("place_change_${change.id}"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(change.after.name, style = AppTheme.typography.titleSmall)
        HorizontalDivider(color = dividerColor())
        val kindOf = @Composable { place: Place -> PlaceKind.of(place.kind)?.let { stringResource(it.label) } ?: place.kind }
        Field(R.string.change_place_kind) { FromTo(kindOf(change.before), kindOf(change.after).orEmpty(), AppIcons.Category) }
        CardActions(onRevert?.let { revert -> { RevertButton(revert, Modifier.testTag("revert_place_${change.id}")) } })
    }
}

/** A Moment named for the eye: its first photo small, its first words, and when and where under them. */
@Composable
private fun MomentIdentity(identity: Identity, modifier: Modifier = Modifier) {
    val moment = identity.moment
    val where = moment.places.joinToString(stringResource(R.string.separator_list)) { identity.places[it]?.name ?: it }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        moment.media.firstOrNull()?.let { Thumbnail(it, identity.urls, size = 48) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(
                moment.text.lineSequence().firstOrNull { it.isNotBlank() } ?: where.ifEmpty { stringResource(R.string.label_no_words) },
                style = AppTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt), where.takeIf { moment.text.isNotBlank() && it.isNotEmpty() })
                    .joinToString("\u2003"),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
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
                style = AppTheme.typography.bodyReading,
                textDecoration = if (removed) TextDecoration.LineThrough else null,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (moment.media.isNotEmpty()) {
            PhotoRow(moment.media.map { it to null }, urls)
        }
        if (moment.places.isNotEmpty()) {
            // Where, as a subhead rather than labels, none of them tapped on its own.
            Text(
                moment.places.joinToString("\u2003") { places[it]?.name ?: it },
                style = AppTheme.typography.body,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What a change changed, each under its name; all of it as it is when the site no longer has the Moment. */
@Composable
private fun ChangedFields(change: Change, places: Map<String, Place>, urls: MediaUrls) {
    val after = change.after
    val before = change.before
    if (before == null) {
        Text(stringResource(R.string.note_change_recreates), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
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
    // The photos kept, marked out of covers or back into them, each set under what became of it.
    val was = before.media.associate { it.uid to it.showcase }
    val marked = after.media.filter { !it.showcase && was[it.uid] == true }
    val unmarked = after.media.filter { it.showcase && was[it.uid] == false }
    if (marked.isNotEmpty() || unmarked.isNotEmpty()) {
        any = true
        Field(R.string.change_cover) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (marked.isNotEmpty()) CoverChange(AppIcons.PhotoOff, R.string.change_cover_off, marked, urls)
                if (unmarked.isNotEmpty()) CoverChange(AppIcons.Image, R.string.change_cover_on, unmarked, urls)
            }
        }
    }
    if (before.occurredAt != after.occurredAt) {
        any = true
        val whenOf = { moment: MomentRecord -> listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ") }
        Field(R.string.change_time) { FromTo(whenOf(before), whenOf(after), AppIcons.Clock) }
    }
    if (before.places != after.places) {
        any = true
        val separator = stringResource(R.string.separator_list)
        val none = stringResource(R.string.place_none)
        val names = { ids: List<String> -> ids.joinToString(separator) { places[it]?.name ?: it }.ifEmpty { none } }
        Field(R.string.change_place) { FromTo(names(before.places), names(after.places), AppIcons.Pin) }
    }
    if (before.food != after.food) {
        any = true
        val none = stringResource(R.string.food_none)
        Field(R.string.change_food) {
            val after = foodLine(after.food)
            if (after == null) FromTo(foodLine(before.food), none, AppIcons.Food) else FromTo(foodLine(before.food), after, AppIcons.Food)
        }
    }
    if (before.topic != after.topic) {
        any = true
        val none = stringResource(R.string.topic_none)
        val from = MomentTopic.of(before.topic)?.let { stringResource(topicLabel(it)) } ?: none
        val to = MomentTopic.of(after.topic)?.let { stringResource(topicLabel(it)) } ?: none
        Field(R.string.change_topic) { FromTo(from, to, AppIcons.Category) }
    }
    // Public or private, a change of its own: one made private leaves the site.
    if (before.hidden != after.hidden) {
        any = true
        val public = stringResource(R.string.visibility_public)
        val private = stringResource(R.string.visibility_private)
        Field(R.string.section_visibility) {
            FromTo(if (before.hidden) private else public, if (after.hidden) private else public, if (after.hidden) AppIcons.EyeOff else AppIcons.Eye)
        }
    }
    if (!any) {
        Text(stringResource(R.string.note_change_nothing), style = AppTheme.typography.body, color = AppTheme.colorScheme.onSurfaceVariant)
    }
}

/** Photos whose use as covers changed, under what it changed to. */
@Composable
private fun CoverChange(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, photos: List<MomentMedia>, urls: MediaUrls) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(icon, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(stringResource(label), style = AppTheme.typography.body)
        }
        PhotoRow(photos.map { it to null }, urls, badges = false)
    }
}

/** A field that changed, under its name. */
@Composable
private fun Field(name: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(name), style = AppTheme.typography.caption, color = AppTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** The words as changed: what was taken out struck through on the error's container, what was put in on the primary's. */
@Composable
private fun TextChange(before: String, after: String) {
    val removed = SpanStyle(
        color = AppTheme.colorScheme.onErrorContainer,
        background = AppTheme.colorScheme.errorContainer,
        textDecoration = TextDecoration.LineThrough,
    )
    val added = SpanStyle(color = AppTheme.colorScheme.onPrimaryContainer, background = AppTheme.colorScheme.primaryContainer)
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
        style = AppTheme.typography.bodyReading,
    )
}

/** What a field was, struck through, and what it is now. */
@Composable
private fun FromTo(from: String?, to: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        // Something that wasn't said before is just what it is now, rather than from nothing.
        if (from != null) {
            Text(
                from,
                style = AppTheme.typography.body,
                color = AppTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(AppIcons.ArrowRight, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Text(to, style = AppTheme.typography.body, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * Photos in a row, each marked by what became of it: null as it was, true put in, ringed
 * in primary with a plus, false taken out, faded, ringed in the error colour with a minus.
 * A photo not used as a cover says so in its corner, unless [badges] is false.
 */
@Composable
private fun PhotoRow(photos: List<Pair<MomentMedia, Boolean?>>, urls: MediaUrls, badges: Boolean = true) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for ((media, added) in photos) {
            val ring = when (added) {
                true -> AppTheme.colorScheme.primary
                false -> AppTheme.colorScheme.error
                null -> Color.Transparent
            }
            Box(Modifier.testTag("change_photo_${media.uid}")) {
                Thumbnail(
                    media,
                    urls,
                    size = PHOTO_SIZE,
                    modifier = Modifier
                        .alpha(if (added == false) DISABLED_ALPHA else 1f)
                        .border(2.dp, ring, AppTheme.shapes.medium),
                )
                if (badges && !media.showcase) {
                    Icon(
                        AppIcons.PhotoOff,
                        contentDescription = stringResource(R.string.label_photo_utility),
                        tint = MediaOverlay.content,
                        modifier = Modifier.align(Alignment.BottomStart).padding(Space.xs).size(20.dp).clip(CircleShape)
                            .background(MediaOverlay.control).padding(Space.xxs),
                    )
                }
                if (added != null) {
                    Icon(
                        if (added) AppIcons.Plus else AppIcons.Minus,
                        contentDescription = stringResource(if (added) R.string.label_photo_added else R.string.label_photo_removed),
                        tint = if (added) AppTheme.colorScheme.onPrimary else AppTheme.colorScheme.onError,
                        modifier = Modifier.align(Alignment.TopEnd).padding(Space.xs).size(20.dp).clip(CircleShape).background(ring).padding(Space.xxs),
                    )
                }
            }
        }
    }
}

private const val PHOTO_SIZE = 72
