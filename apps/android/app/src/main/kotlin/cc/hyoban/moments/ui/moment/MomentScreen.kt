package cc.hyoban.moments.ui.moment

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalSnackbarLift
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FLOATING_BAR
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.MediaTile
import cc.hyoban.moments.ui.components.READABLE_WIDTH
import cc.hyoban.moments.ui.components.RisingSheet
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.kindLabel
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.mediaKey
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary
import kotlinx.coroutines.launch

/** How far the page's sheet rises over the photos. */
private val RISE = 32.dp

/**
 * One Moment, as a page or, beside the Moments on a phone on its side, as their detail
 * [pane]: its media edge to edge, and over their lower edge a sheet with when, its
 * words first, a strip of its photos when there are several, and then where it was, the
 * trip it was on and the rest of its day, as one list of rows to open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MomentScreen(
    container: AppContainer,
    uid: String,
    onMedia: (String, Int) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    onDay: (String) -> Unit = {},
    pane: Boolean = false,
    onChanges: (() -> Unit)? = null,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val trip = remember(moment, trips) { tripOf(moment, trips) }
    val urls = container.mediaUrls
    var sharing by remember(uid) { mutableStateOf(false) }
    var shareTarget by remember(uid) { mutableStateOf<ShareTarget?>(null) }
    if (sharing) {
        ShareMenu(
            onSelect = { sharing = false; shareTarget = it },
            onDismiss = { sharing = false },
        )
    }
    shareTarget?.let { ShareMoment(moment, urls, it, onDismiss = { shareTarget = null }) }

    // The photos swiped above and the strip under them are one place in the Moment's media.
    val pager = rememberPagerState { moment.media.size }

    // The toolbar at the foot goes down out of the way as the page is read on, and comes back
    // as it is scrolled back, as Material 3 has a floating toolbar exit on scroll.
    val toolbarScroll = FloatingToolbarDefaults.exitAlwaysScrollBehavior(exitDirection = FloatingToolbarExitDirection.Bottom)

    // The whole page is the card it was opened from, grown to the screen.
    // As a pane beside the Moments, it neither grows out of a card nor flies its photos, the card still in sight.
    BoxWithConstraints(Modifier.fillMaxSize().then(if (pane) Modifier else Modifier.sharedCard(uid, page = true)).background(AppColors.page).nestedScroll(toolbarScroll)) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val scroll = rememberScrollState()
        val details: @Composable () -> Unit = {
            MomentDetails(
                moment, places, trip, moments, trips, urls, pager, onPlace, onTrip, onDay,
                changed = container.repository.isChanged(moment.uid),
                // The way to publish, only when the app can.
                onChanges = onChanges.takeIf { container.canPublish },
            )
        }
        if (landscape && !pane && moment.media.isNotEmpty()) {
            // On its side, the photos fill the left half, up under the status bar, and the
            // words scroll beside them, rather than a photo taller than the screen over them.
            Row(Modifier.fillMaxSize()) {
                MomentPhotos(moment, urls, pager, onMedia, Modifier.weight(1f).fillMaxHeight(), shared = !pane)
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().padding(top = FLOATING_BAR).verticalScroll(scroll).sideInsetsPadding(WindowInsetsSides.End)) { details() }
            }
        } else {
            // The photos are as tall as the tallest of them, but no taller than a 3:4 portrait
            // one, so a screenshot is cropped rather than filling the screen. A portrait one runs
            // under the status bar and the buttons; landscape ones, whole in sight, start below the
            // buttons, the page's colour above them, so the buttons hide none of them. The bar's
            // icons follow what is under them.
            val shape = moment.media.minOfOrNull(::shapeOf)?.coerceAtLeast(TALLEST)
            val bleeds = shape != null && shape < 1f

            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (moment.media.isNotEmpty()) {
                    // The tallest photo whole across the width, unless it would be taller than
                    // 3:4 or the screen, where it stops there.
                    val above = if (bleeds) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FLOATING_BAR
                    MomentPhotos(
                        moment,
                        urls,
                        pager,
                        onMedia,
                        Modifier.then(if (bleeds) Modifier else Modifier.statusBarsPadding().padding(top = FLOATING_BAR))
                            .fillMaxWidth().height(minOf(screenWidth / shape!!, screenHeight - above)),
                        whole = true,
                        shared = !pane,
                    )
                } else {
                    Spacer(Modifier.statusBarsPadding().height(FLOATING_BAR))
                }

                // The sheet rises over a portrait photo's foot; landscape ones it leaves whole below
                // as the buttons do above.
                RisingSheet(Modifier.offset(y = if (bleeds) -RISE else 0.dp)) {
                    // Centred, and clear of the sides, on a phone on its side.
                    Box(Modifier.fillMaxWidth().sideInsetsPadding().wrapContentWidth().widthIn(max = READABLE_WIDTH)) { details() }
                }
            }
        }
        // A pane beside the Moments has nothing to go back to: both are in sight.
        if (!pane) FloatingBack(onBack, Modifier.align(Alignment.TopStart))
        // What was written on the phone can be changed, and deleted from its edit page; the
        // site's Moments only with the API's token, and only on the phone until the changes are
        // published.
        val editable = container.repository.isLocal(uid) || container.canPublish
        val publishing by container.publishing.collectAsStateWithLifecycle()
        // The page's actions in Material 3's floating toolbar at the foot, as a page under the
        // tabs has a toolbar where they have the navigation bar: sharing, copying the words and
        // the photo shown at its full size; editing, when it can be, the FAB paired with it.
        // Only back stays over the photo.
        val context = LocalContext.current
        val show = rememberShowMessage()
        val copied = stringResource(R.string.message_text_copied)
        val tools: @Composable RowScope.() -> Unit = {
            ToolbarButton(Icons.Rounded.Share, stringResource(R.string.action_share), "share") { sharing = true }
            if (moment.text.isNotBlank()) {
                ToolbarButton(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_text), "copy_text") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", moment.text))
                    show(copied)
                }
            }
            if (moment.media.isNotEmpty()) {
                ToolbarButton(Icons.Rounded.Fullscreen, stringResource(R.string.action_view_photo), "view_photo") { onMedia(uid, pager.currentPage) }
            }
        }
        val toolbar = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.gutter).testTag("moment_toolbar")
        // Snackbars stand above the toolbar while the page is shown.
        val lift = LocalSnackbarLift.current
        DisposableEffect(lift) {
            lift.value = TOOLBAR_CLEARANCE
            onDispose { lift.value = 0.dp }
        }
        if (editable && publishing == null) {
            HorizontalFloatingToolbar(
                expanded = true,
                scrollBehavior = toolbarScroll,
                floatingActionButton = {
                    val edit = stringResource(R.string.action_edit_moment)
                    Tooltip(edit) {
                        FloatingToolbarDefaults.StandardFloatingActionButton(onClick = { onEdit(uid) }, modifier = Modifier.testTag("edit")) {
                            Icon(Icons.Rounded.Edit, contentDescription = edit)
                        }
                    }
                },
                modifier = toolbar,
                content = tools,
            )
        } else {
            HorizontalFloatingToolbar(expanded = true, modifier = toolbar, scrollBehavior = toolbarScroll, content = tools)
        }
    }
}

/** The tallest shape, width over height, the photos are shown at whole on a phone held upright. */
private const val TALLEST = 3f / 4f

/**
 * The Moment's photos, swiped through on [pager], each opening the viewer. Each fills the
 * space, or, when [whole], spans its width at its own shape, centred, cropped only where
 * it is taller than the space.
 */
@Composable
private fun MomentPhotos(moment: MomentRecord, urls: MediaUrls, pager: PagerState, onMedia: (String, Int) -> Unit, modifier: Modifier, whole: Boolean = false, shared: Boolean = true) {
    val uid = moment.uid
    HorizontalPager(pager, modifier, key = { moment.media[it].uid }) { page ->
        val media = moment.media[page]
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The top of the photo is under the status bar and the back button.
            MediaTile(
                media,
                urls,
                (if (whole) Modifier.fillMaxWidth().height(minOf(maxWidth / shapeOf(media), maxHeight)) else Modifier.fillMaxSize())
                    .testTag("media_${uid}_$page"),
                liveBadgeAlignment = Alignment.BottomStart,
                sharedKey = if (shared) mediaKey(uid, media.uid) else null,
                position = page to moment.media.size,
                onClick = { onMedia(uid, page) },
            )
        }
    }
}

/**
 * When, the Moment's words, its photos small, and then the rows that lead on from it: its
 * places, its trip and the rest of its day.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MomentDetails(
    moment: MomentRecord,
    places: Map<String, Place>,
    trip: TripRecord?,
    moments: List<MomentRecord>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    pager: PagerState,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onDay: (String) -> Unit,
    changed: Boolean = false,
    onChanges: (() -> Unit)? = null,
) {
    val sameDay = remember(moments, moment.dateKey) { moments.count { it.dateKey == moment.dateKey } }
    Column(Modifier.padding(top = Space.xl, bottom = Space.xxl)) {
        // The day emphasized, and its time beside it a shade lighter and plain: what the words are under.
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                buildAnnotatedString {
                    append(formatDay(moment.occurredAt))
                    formatTime(moment.occurredAt)?.let {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = MaterialTheme.typography.titleLarge.fontWeight)) { append("  $it") }
                    }
                },
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            if (moment.published == null || changed) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (moment.published == null) StateChip(stringResource(R.string.label_on_phone), stringResource(R.string.explain_on_phone), onChanges)
                    if (changed) StateChip(stringResource(R.string.label_unpublished_change), stringResource(R.string.explain_unpublished_change), onChanges)
                }
            }
        }
        if (moment.text.isNotBlank()) {
            SelectionContainer(Modifier.padding(start = Space.xl, end = Space.xl, top = Space.l)) {
                Text(moment.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 30.sp))
            }
        }
        if (moment.media.size > 1) {
            Filmstrip(moment, urls, pager, Modifier.padding(top = Space.xl))
        }

        // Where it was, its trip and its day as one expressive segmented list: each row filled on
        // its own, a small gap between, the group's outer corners round and its inner ones
        // tight, each row's corners rounding further under the finger.
        val rows = buildList<@Composable (ListItemShapes) -> Unit> {
            moment.places.forEach { id ->
                val place = places[id]
                add { shapes ->
                    SegmentedListItem(
                        onClick = { onPlace(id) },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { RowIcon(Icons.Rounded.Place) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = place?.note?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.testTag("place_$id"),
                    ) { Text(place?.name ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            trip?.let { trip ->
                add { shapes ->
                    val cover = remember(trip, moments) { coversOf(trip, moments, trips, limit = 1).firstOrNull() }
                    val count = remember(trip, moments) { momentsOf(trip, moments, trips).size }
                    SegmentedListItem(
                        onClick = { onTrip(trip.id) },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = {
                            if (cover != null) Thumbnail(cover, urls, size = ROW_LEAD) else RowIcon(Icons.Rounded.Luggage)
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(tripSummary(trip, count), maxLines = 2) },
                        modifier = Modifier.sharedCard(tripCardKey(trip.id)).testTag("trip_${trip.id}"),
                    ) { Text(trip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Only when the day has more than this one, the row grows into the day's page.
            if (sameDay > 1) {
                add { shapes ->
                    SegmentedListItem(
                        onClick = { onDay(moment.dateKey) },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { RowIcon(Icons.Rounded.CalendarToday) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(pluralStringResource(R.plurals.count_moments, sameDay, sameDay)) },
                        modifier = Modifier.sharedCard(dayCardKey(moment.dateKey)).testTag("day_${moment.dateKey}"),
                    ) { Text(stringResource(R.string.label_whole_day)) }
                }
            }
        }
        if (rows.isNotEmpty()) {
            Column(
                Modifier.padding(start = Space.l, end = Space.l, top = Space.xl),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
            }
        }
        // Clear of the floating toolbar at the foot.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        Spacer(Modifier.height(TOOLBAR_CLEARANCE))
    }
}

/** Room under the page's words for the floating toolbar over them: its height and margin. */
private val TOOLBAR_CLEARANCE = 88.dp

/** An action in the floating toolbar, named in a tooltip as an icon-only button is. */
@Composable
private fun ToolbarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Tooltip(label) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

/**
 * The Moment's photos small in a row, the one shown above turned round among the squares
 * and the rest dimmed; a tap turns the photos above to it, and a swipe above keeps the one
 * shown in sight here.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Filmstrip(moment: MomentRecord, urls: MediaUrls, pager: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val current = pager.currentPage
    LaunchedEffect(current) {
        val info = list.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == current }
        if (seen == null || seen.offset < 0 || seen.offset + seen.size > info.viewportEndOffset) {
            list.animateScrollToItem(current)
        }
    }
    LazyRow(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(moment.media, key = { _, media -> media.uid }) { index, media ->
            val selected = index == current
            val label = stringResource(R.string.media_position, stringResource(media.kindLabel()), index + 1, moment.media.size)
            // The one shown breaks from the others' shape, morphing on the motion scheme's
            // spatial spring; the others under a scrim rather than faded, so they don't turn the
            // page's grey.
            val corner by animateDpAsState(if (selected) 28.dp else 12.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "thumb shape")
            val dim by animateFloatAsState(if (selected) 0f else 0.45f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "thumb dim")
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(corner))
                    .clickable { scope.launch { pager.animateScrollToPage(index) } }
                    // Named as a carousel's item is, which of how many, and whether it is the one shown.
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                    }
                    .testTag("thumb_$index"),
            ) {
                Thumbnail(media, urls, size = 56)
                Box(Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black))
            }
        }
    }
}

/** A row of the segmented list, filled a level above the sheet as the app's cards are. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun segmentColors() = ListItemDefaults.segmentedColors(containerColor = LocalSurfaceLevel.current.raised.fill())

/** How wide what leads a row is, a trip's cover or an icon centred in the same room, so the rows' words line up. */
private const val ROW_LEAD = 40

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(Modifier.size(ROW_LEAD.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
}

/**
 * Where a Moment stands, only on the phone or changed there, as an assist chip that explains
 * itself: tapped, a persistent rich tooltip says what it means, with a way to the changes to
 * publish when [onChanges] is given, as Material 3 has a rich tooltip add context and an action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateChip(label: String, explanation: String, onChanges: (() -> Unit)?) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = { Text(label) },
                action = onChanges?.let { open ->
                    {
                        TextButton(
                            onClick = {
                                state.dismiss()
                                open()
                            },
                        ) { Text(stringResource(R.string.title_changes)) }
                    }
                },
            ) { Text(explanation) }
        },
        state = state,
    ) {
        AssistChip(
            onClick = { scope.launch { state.show() } },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            shape = Corners.full,
            border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = LocalSurfaceLevel.current.raised.fill(),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.testTag("state_chip"),
        )
    }
}
