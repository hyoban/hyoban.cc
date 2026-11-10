package cc.hyoban.moments.ui.moment

import cc.hyoban.moments.ui.compose.label
import cc.hyoban.moments.ui.ledger.momentBillRows
import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.spec.Module
import androidx.compose.ui.AbsoluteAlignment
import cc.hyoban.moments.data.store.isPublic

import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.components.floatingElevation
import cc.hyoban.moments.ui.design.FloatingActionButton
import cc.hyoban.moments.ui.design.FloatingBar
import androidx.compose.ui.graphics.Color
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.ui.ledger.LedgerEntryArgs
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.format.verdictLabel
import cc.hyoban.moments.ui.format.billLine
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.countOf
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalSnackbarLift
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FLOATING_BAR
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.MediaEdges
import cc.hyoban.moments.ui.components.MediaTile
import cc.hyoban.moments.ui.components.READABLE_WIDTH
import cc.hyoban.moments.ui.components.RisingSheet
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.kindLabel
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
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
    /** Opens one of its bills in the ledger, and writes one from its meal's bill. */
    onBill: (String) -> Unit = LocalLedgerNav.current?.bill ?: {},
    onNewBill: (String) -> Unit = LocalLedgerNav.current?.let { nav -> { uid -> nav.entry(LedgerEntryArgs(moment = uid)) } } ?: {},
) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val trip = remember(moment, trips) { tripOf(moment, trips) }
    val urls = container.mediaUrls
    var sharing by remember(uid) { mutableStateOf(false) }
    val device = cc.hyoban.moments.ui.LocalDevice.current
    if (sharing) device.ShareMoment(moment, urls, moment.places.firstOrNull()?.let { id -> places[id]?.name }, onDismiss = { sharing = false })

    // The photos swiped above and the strip under them are one place in the Moment's media.
    val pager = rememberPagerState { moment.media.size }

    // The whole page is the card it was opened from, grown to the screen.
    // As a pane beside the Moments, it doesn't grow out of a card, the card still in sight.
    BoxWithConstraints(Modifier.fillMaxSize().then(if (pane) Modifier else Modifier.sharedCard(uid, page = true)).background(AppColors.page)) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val scroll = rememberScrollState()
        val records by container.repository.records.collectAsStateWithLifecycle()
        val details: @Composable () -> Unit = {
            MomentDetails(
                moment, places, trip, moments, trips, urls, pager, onPlace, onTrip, onDay,
                changed = container.repository.isChanged(moment.uid),
                bills = momentBillRows(ledger, moment, onBill, onNewBill),
                parts = records.entries[moment.uid]?.get("parts") as? kotlinx.serialization.json.JsonObject,
                onPartPrivate = { plugin: String, private: Boolean ->
                    val own = (moment.extra[cc.hyoban.moments.data.spec.Entries.PART_VISIBILITY] as? kotlinx.serialization.json.JsonObject).orEmpty()
                    val next = if (private) own + (plugin to kotlinx.serialization.json.JsonPrimitive("private")) else own - plugin
                    val extra = if (next.isEmpty()) kotlinx.serialization.json.JsonObject(moment.extra - cc.hyoban.moments.data.spec.Entries.PART_VISIBILITY)
                    else kotlinx.serialization.json.JsonObject(moment.extra + (cc.hyoban.moments.data.spec.Entries.PART_VISIBILITY to kotlinx.serialization.json.JsonObject(next)))
                    container.scope.launch { container.updateLocal(moment.copy(extra = extra), null) }
                    Unit
                }.takeIf { container.canPublish },
                billPublic = ledger.txns.any { it.moment == moment.uid && ledger.isPublic(it) },
                // The way to publish, only when the app can.
                onChanges = onChanges.takeIf { container.canPublish },
            )
        }
        // On its side, the photos and the words side by side, each half the page.
        val split = landscape && !pane && moment.media.isNotEmpty()
        if (split) {
            // On its side, the photos fill the half on the camera's side, up under the status bar,
            // and the words scroll beside them, rather than a photo taller than the screen over them.
            cc.hyoban.moments.ui.components.MediaBeside(
                media = { half -> MomentPhotos(moment, urls, pager, onMedia, half) },
            ) { half -> Column(half.statusBarsPadding().padding(top = FLOATING_BAR).verticalScroll(scroll)) { details() } }
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
        // The page's actions float at its foot, as the tabs do under the timeline: sharing and
        // copying the words in a bar, editing, when it can be, the ink button beside it. The photo
        // opens at its full size with a tap on it. Only back stays over the photo.
        val show = rememberShowMessage()
        val copied = stringResource(Res.string.message_text_copied)
        val tools: @Composable RowScope.() -> Unit = {
            ToolbarButton(AppIcons.Share, stringResource(Res.string.action_share), "share") { sharing = true }
            if (moment.text.isNotBlank()) {
                ToolbarButton(AppIcons.Copy, stringResource(Res.string.action_copy_text), "copy_text") {
                    device.copyText(moment.text)
                    show(copied)
                }
            }
        }
        // Under the words it acts on: in the middle of the page, or of their half beside the photos.
        val mediaLeft = cc.hyoban.moments.ui.components.mediaOnLeft()
        val toolbarArea = if (split) {
            Modifier.align(if (mediaLeft) AbsoluteAlignment.BottomRight else AbsoluteAlignment.BottomLeft).fillMaxWidth(0.5f)
                .sideInsetsPadding(if (mediaLeft) WindowInsetsSides.Right else WindowInsetsSides.Left)
        } else {
            Modifier.align(Alignment.BottomCenter)
        }
        val toolbar = Modifier.testTag("moment_toolbar")
        // Snackbars stand above the toolbar while the page is shown.
        val lift = LocalSnackbarLift.current
        DisposableEffect(lift) {
            lift.value = TOOLBAR_CLEARANCE
            onDispose { lift.value = 0.dp }
        }
        Box(toolbarArea.navigationBarsPadding().padding(bottom = Space.gutter), contentAlignment = Alignment.BottomCenter) {
            // The edit button goes and comes back with publishing, the toolbar easing to its new width.
            val motion = AppTheme.motionScheme
            AnimatedContent(
                editable && publishing == null,
                transitionSpec = {
                    fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using SizeTransform { _, _ -> motion.fastSpatialSpec() }
                },
                contentAlignment = Alignment.BottomCenter,
                label = "toolbar",
            ) { canEdit ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FloatingBar(toolbar, content = tools)
                    // Editing, the page's one main action, an ink button beside the bar.
                    if (canEdit) {
                        val edit = stringResource(Res.string.action_edit_moment)
                        Tooltip(edit) {
                            FloatingActionButton(
                                onClick = { onEdit(uid) },
                                shape = Corners.full,
                                containerColor = tokens.tx,
                                contentColor = tokens.bg,
                                elevation = floatingElevation(),
                                modifier = Modifier.testTag("edit"),
                            ) {
                                Icon(AppIcons.Pen, contentDescription = edit)
                            }
                        }
                    }
                }
            }
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
private fun MomentPhotos(moment: MomentRecord, urls: MediaUrls, pager: PagerState, onMedia: (String, Int) -> Unit, modifier: Modifier, whole: Boolean = false) {
    val uid = moment.uid
    HorizontalPager(pager, modifier, key = { moment.media[it].uid }) { page ->
        val media = moment.media[page]
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // A photo wider than the space runs on above and below it in its edges' colours.
            if (whole) MediaEdges(media, urls, Modifier.fillMaxSize())
            // The top of the photo is under the status bar and the back button.
            MediaTile(
                media,
                urls,
                (if (whole) Modifier.fillMaxWidth().height(minOf(maxWidth / shapeOf(media), maxHeight)) else Modifier.fillMaxSize())
                    .testTag("media_${uid}_$page"),
                liveBadgeAlignment = Alignment.BottomStart,
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
    /** Its bills in the ledger, kept on the phone, as rows after what was eaten. */
    bills: List<@Composable (ListItemShapes) -> Unit> = emptyList(),
    /** Its parts the first shape doesn't hold, as the API has them: a charge. */
    parts: kotlinx.serialization.json.JsonObject? = null,
    /** Whether a bill of it would publish. */
    billPublic: Boolean = false,
    /** Makes one of its parts private, or public again; null where it can't be changed here. */
    onPartPrivate: ((String, Boolean) -> Unit)? = null,
) {
    val sameDay = remember(moments, moment.dateKey) { moments.count { it.dateKey == moment.dateKey } }
    // Its parts of modules turned off (记录项与公开) go unshown, kept as they are.
    val settings = LocalSettings.current
    val foodOn = settings.isOn(Module.FOOD)
    val ledgerOn = settings.isOn(Module.LEDGER)
    val carOn = settings.isOn(Module.CAR)
    Column(Modifier.padding(top = Space.xl, bottom = Space.xl)) {
        // When, in the second grey over the words, as the timeline's line over a Moment has it.
        Text(
            listOfNotNull(formatDay(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
            style = AppTheme.typography.body,
            color = tokens.tx2,
            modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() },
        )
        if (moment.text.isNotBlank()) {
            SelectionContainer(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m)) {
                Text(moment.text, style = AppTheme.typography.bodyReading)
            }
        }
        if (moment.media.size > 1) {
            Filmstrip(moment, urls, pager, Modifier.padding(top = Space.xl))
        }

        // Where it was, then its parts, a row each, and where it leads, the rows on the page
        // itself with space rather than lines between them.
        val rows = buildList<@Composable (ListItemShapes) -> Unit> {
            moment.places.forEach { id ->
                val place = places[id]
                add { shapes ->
                    SegmentedListItem(
                        onClick = { onPlace(id) },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Pin, contentDescription = null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                        // Its kind, then a note if it has one.
                        supportingContent = listOfNotNull(
                            PlaceKind.of(place?.kind)?.let { stringResource(it.label) },
                            place?.note?.takeIf { it.isNotBlank() },
                        ).joinToString(" ").takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.testTag("place_$id"),
                    ) { Text(place?.name ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // What was eaten: the dishes, how it went, what the meal cost and each one's share.
            // Not a link, so a row that doesn't answer a tap.
            moment.food?.takeIf { foodOn }?.let { food ->
                val verdict = FoodVerdict.of(food.verdict)
                add {
                    ListItem(
                        headlineContent = {
                            Text(
                                food.dishes.joinToString(stringResource(Res.string.separator_list)).ifEmpty { stringResource(Res.string.section_food) },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = listOfNotNull(verdict?.let { stringResource(verdictLabel(it)) }, food.bill?.let { billLine(it) })
                            .joinToString(" ").takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        leadingContent = { Icon(AppIcons.Food, contentDescription = null) },
                        modifier = Modifier.testTag("food"),
                    )
                }
            }
            if (ledgerOn) addAll(bills)
            (parts?.get("charge") as? kotlinx.serialization.json.JsonObject)?.takeIf { carOn }?.let { charge ->
                add {
                    ListItem(
                        headlineContent = { Text(chargeTitle(charge)) },
                        supportingContent = chargeLine(charge)?.let { line -> { Text(line, style = AppTheme.typography.caption) } },
                        leadingContent = { Icon(AppIcons.Ev, contentDescription = null) },
                        modifier = Modifier.testTag("charge"),
                    )
                }
            }
            trip?.let { trip ->
                add { shapes ->
                    val count = remember(trip, moments) { countOf(trip, moments, trips) }
                    SegmentedListItem(
                        onClick = { onTrip(trip.id) },
                        shapes = shapes,
                        colors = segmentColors(),
                        // The route, as a trip's row on the timeline leads, so its words line up with the others'.
                        leadingContent = { Icon(AppIcons.Route, contentDescription = null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                        supportingContent = { Text(tripSummary(trip, count), maxLines = 2) },
                        modifier = Modifier.testTag("trip_${trip.id}"),
                    ) { Text(trip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Only when the day has more than this one.
            if (sameDay > 1) {
                add { shapes ->
                    SegmentedListItem(
                        onClick = { onDay(moment.dateKey) },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Calendar, contentDescription = null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                        supportingContent = { Text(pluralStringResource(Res.plurals.count_moments, sameDay, sameDay)) },
                        modifier = Modifier.testTag("day_${moment.dateKey}"),
                    ) { Text(stringResource(Res.string.label_whole_day)) }
                }
            }
            // Then who sees it: private, or public with the parts that would publish.
            add {
                val private = moment.hidden
                val published = listOfNotNull(
                    stringResource(Res.string.part_text).takeIf { moment.text.isNotBlank() },
                    stringResource(Res.string.part_photos).takeIf { moment.media.isNotEmpty() },
                    stringResource(Res.string.section_food).takeIf { moment.food != null && foodOn },
                    stringResource(Res.string.part_bill).takeIf { billPublic && ledgerOn },
                    stringResource(Res.string.part_charge).takeIf { parts?.containsKey("charge") == true && carOn },
                )
                ListItem(
                    headlineContent = { Text(stringResource(if (private) Res.string.visibility_private else Res.string.visibility_public)) },
                    supportingContent = {
                        Text(
                            if (private) stringResource(Res.string.moment_private_note)
                            else stringResource(Res.string.moment_publishes, published.joinToString(stringResource(Res.string.separator_list))),
                        )
                    },
                    leadingContent = { Icon(if (private) AppIcons.EyeOff else AppIcons.Eye, contentDescription = null) },
                    modifier = Modifier.testTag("visibility"),
                )
            }
            // Each part said private on its own, while the record itself is public.
            val own = moment.extra[cc.hyoban.moments.data.spec.Entries.PART_VISIBILITY] as? kotlinx.serialization.json.JsonObject
            if (onPartPrivate != null && !moment.hidden) {
                listOfNotNull(
                    ("food" to Res.string.section_food).takeIf { moment.food != null && foodOn },
                    ("media" to Res.string.part_photos).takeIf { moment.media.isNotEmpty() },
                ).forEach { (plugin, label) ->
                    val private = (own?.get(plugin) as? kotlinx.serialization.json.JsonPrimitive)?.content == "private"
                    add { shapes ->
                        cc.hyoban.moments.ui.settings.SwitchRow(
                            private,
                            AppIcons.EyeOff,
                            stringResource(Res.string.part_private, stringResource(label)),
                            "private_$plugin",
                            shapes,
                        ) { onPartPrivate(plugin, !private) }
                    }
                }
            }
            // Where it stands, only on the phone or changed there, and the way to the changes to publish;
            // with no API there is nothing to publish to, so nothing is said.
            val state = when {
                !LocalSettings.current.connected -> null
                moment.published == null -> Triple(AppIcons.Phone, Res.string.label_on_phone, Res.string.explain_on_phone)
                changed -> Triple(AppIcons.Pen, Res.string.label_unpublished_change, Res.string.explain_unpublished_change)
                else -> null
            }
            state?.let { (icon, label, explanation) ->
                add { shapes ->
                    val headline: @Composable () -> Unit = { Text(stringResource(label)) }
                    val supporting: @Composable () -> Unit = { Text(stringResource(explanation), maxLines = 3) }
                    val leading: @Composable () -> Unit = { Icon(icon, contentDescription = null) }
                    if (onChanges != null) {
                        SegmentedListItem(
                            onClick = onChanges,
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = leading,
                            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                            supportingContent = supporting,
                            modifier = Modifier.testTag("state_notice"),
                            content = headline,
                        )
                    } else {
                        ListItem(headlineContent = headline, supportingContent = supporting, leadingContent = leading, modifier = Modifier.testTag("state_notice"))
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            // The rows' icons on the words' edge above, their own padding outside it, where a press shows.
            val shapes = ListItemShapes(AppTheme.shapes.medium)
            Column(Modifier.padding(start = Space.gutter - ROW_PADDING, end = Space.gutter - ROW_PADDING, top = Space.l)) {
                rows.forEach { row -> row(shapes) }
            }
        }
        // Clear of the floating toolbar at the foot.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        Spacer(Modifier.height(TOOLBAR_CLEARANCE))
    }
}

/** A charge as its row heads it: AC or DC, and how much went in. */
@Composable
private fun chargeTitle(charge: kotlinx.serialization.json.JsonObject): String {
    val type = (charge["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val kwh = (charge["energyWh"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.div(1000)
    return listOfNotNull(
        stringResource(if (type == "DC") Res.string.charge_dc else Res.string.charge_ac),
        kwh?.let { "%.1f kWh".format(it) },
    ).joinToString(" ")
}

/** How long it charged and what it cost, in figures. */
private fun chargeLine(charge: kotlinx.serialization.json.JsonObject): String? {
    val minutes = cc.hyoban.moments.data.spec.Entries.chargeMinutes(charge)
    val cost = (charge["cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    return listOfNotNull(minutes?.let { "${it.toInt()} min" }, cost?.let { "¥%.2f".format(it) }).joinToString(" ").ifEmpty { null }
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
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(moment.media, key = { _, media -> media.uid }) { index, media ->
            val selected = index == current
            val label = stringResource(Res.string.media_position, stringResource(media.kindLabel()), index + 1, moment.media.size)
            // The one shown rounder than the others, easing there on the motion scheme's spatial
            // spring; the others under a scrim rather than faded, so they don't turn the
            // page's grey.
            val corner by animateDpAsState(if (selected) Radius.large else Radius.medium, AppTheme.motionScheme.fastSpatialSpec(), label = "thumb shape")
            val dim by animateFloatAsState(if (selected) 0f else MediaOverlay.CONTROL_ALPHA, AppTheme.motionScheme.fastEffectsSpec(), label = "thumb dim")
            Box(
                Modifier.size(56.dp).clip(cc.hyoban.moments.ui.theme.continuousRoundedRectangle(corner))
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

/** A row that answers a tap: no fill of its own, on the page. */
@Composable
private fun segmentColors() = ListItemDefaults.segmentedColors(containerColor = Color.Transparent)

/** The side padding of a list row, outside the page's gutter so the rows line up with the words. */
private val ROW_PADDING = 16.dp
