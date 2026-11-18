package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.BottomSheetScaffold
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.SheetValue
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.rememberBottomSheetScaffoldState
import cc.hyoban.moments.ui.design.rememberStandardBottomSheetState
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Several sources' places as one: their spots together, each marked with the source it is of, a
 * heap opened in each that has photos in it, and the photos of them all, newest first.
 */
class MergedPlaces(private val all: List<Places>) : Places {
    override val spots: List<Spot>?
        get() {
            val read = all.mapIndexedNotNull { i, places -> places.spots?.map { it.copy(from = i) } }
            return if (read.isEmpty()) null else read.flatten()
        }

    override val failed: (@Composable () -> Unit)? get() = null

    override suspend fun load() = coroutineScope { all.forEach { launch { it.load() } } }

    override suspend fun thumbnail(spot: Spot): ImageBitmap? = all.getOrNull(spot.from)?.thumbnail(spot.copy(from = 0))

    override val opened: List<Spot>?
        get() = all.flatMapIndexed { i, places -> places.opened?.map { it.copy(from = i) }.orEmpty() }.ifEmpty { null }

    override fun open(heap: List<Spot>?) {
        all.forEachIndexed { i, places -> places.open(heap?.filter { it.from == i }?.map { it.copy(from = 0) }?.ifEmpty { null }) }
    }

    @Composable
    override fun picks(): List<Pick> {
        val each = all.map { it.picks() }
        return remember(each) { each.flatten().sortedByDescending { it.day } }
    }

    override val reading get() = all.any { it.reading }

    override fun more() = all.forEach { it.more() }
}

/**
 * 地图's photos (09 地图的照片): the device's sources that know where their photos were taken (the
 * phone's gallery, once read) and the NAS's, as heaps over the map, and the photos of a heap
 * opened, looked at, chosen and written, shared, saved or deleted as in 相册.
 */
@Stable
class PhotoLayer internal constructor(val places: Places, val choice: PhotoChoice, val videos: PickerVideos)

@Composable
fun rememberPhotoLayer(container: AppContainer, onWrite: () -> Unit): PhotoLayer {
    val choice = rememberPhotoChoice(Int.MAX_VALUE, alwaysChoosing = false, onWrite = { refs -> container.offerPhotos(refs); onWrite() })
    val device = choice.device
    val own = device.places(choice.deletion)
    val nas = rememberNas(container, choice.deletion)
    val all = own + listOfNotNull(nas.places.takeIf { container.nas.configured })
    val places = remember(all) { MergedPlaces(all) }
    val videos = device.rememberVideos()
    return remember(places, videos) { PhotoLayer(places, choice, videos) }
}

/**
 * The opened heap's photos, risen from the foot over the map as 相册's grid, the map staying
 * above; dragged down, a tap on the map or back puts them away. Choosing, the bar of what to do
 * stands in place of the tabs, and an opened photo is the viewer over the whole window.
 */
@Composable
fun BoxScope.PhotoLayerSheet(container: AppContainer, layer: PhotoLayer, onMoment: (String) -> Unit) {
    val places = layer.places
    val choice = layer.choice
    val opened = places.opened
    BackHandler(enabled = opened != null && !choice.choosing) { places.open(null) }
    choice.TabsGiveWay()
    LaunchedEffect(opened) { if (opened == null) choice.stop() }
    if (opened != null) key(opened.first()) {
        val shown = places.picks()
        CompositionLocalProvider(LocalPickerVideos provides layer.videos) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false)
                LaunchedEffect(sheet) {
                    snapshotFlow { sheet.currentValue }.drop(1).collect { if (it == SheetValue.Hidden) places.open(null) }
                }
                BottomSheetScaffold(
                    scaffoldState = rememberBottomSheetScaffoldState(sheet),
                    modifier = Modifier.padding(top = LocalTopClearance.current).testTag("photo_heap_sheet"),
                    sheetPeekHeight = maxHeight * SHEET_SHARE,
                    sheetContainerColor = AppColors.sheet,
                    containerColor = Color.Transparent,
                    sheetContent = {
                        CompositionLocalProvider(LocalTopClearance provides 0.dp) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(Res.string.map_photos_here), style = AppTheme.typography.title, modifier = Modifier.padding(end = Space.s))
                                    Text(
                                        listOfNotNull(
                                            stringResource(Res.string.camera_photo_count, opened.size),
                                            choice.chosen.size.takeIf { it > 0 }?.let { stringResource(Res.string.picker_chosen_count, it) },
                                        ).joinToString(" · "),
                                        style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.weight(1f),
                                    )
                                    if (choice.choosing) IconButton(onClick = choice::stop, modifier = Modifier.testTag("album_select_done")) {
                                        Icon(AppIcons.Close, contentDescription = stringResource(Res.string.album_select_done))
                                    } else IconButton(onClick = choice::start, modifier = Modifier.testTag("album_select")) {
                                        Icon(AppIcons.Check, contentDescription = stringResource(Res.string.album_select))
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    Grid(
                                        shown, choice.order, choice.full, enabled = choice.idle, sideways = false,
                                        density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                                        limited = null, empty = false, onToggle = choice::toggle, onNearEnd = places::more,
                                        circles = choice.choosing, onHold = choice::hold, onHoldOver = choice::holdOver,
                                    ) { choice.viewing = it.key }
                                    if (places.reading) Spinner(Modifier.align(Alignment.TopCenter).padding(top = Space.xl))
                                }
                            }
                        }
                    },
                ) { }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            choice.sources = shown.map { it.source }.distinct()
            choice.Foot(choice.chosen.firstOrNull()?.source)
        }
        choice.ViewerOver(container, shown, layer.videos, overTabs = true, onMoment = onMoment)
        shown.map { it.source }.distinct().forEach { it.Dialogs() }
    }
}

/** How much of the window the opened heap's photos take at rest, the map keeping the rest. */
private const val SHEET_SHARE = 0.55f
