package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.staticCompositionLocalOf
import cc.hyoban.moments.data.local.PhotoRef
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.PhotoDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Where 相册's photos come from: the phone's gallery, the paired camera, the NAS. Each keeps
 * what it reads, says what the title's menu offers of it, fetches its chosen photos for the Moment
 * and deletes its own; the page only lays them out.
 */
interface PhotoSource {
    /** What it offers in the title's menu, in order; nothing while it can't be reached. */
    @Composable fun choices(): List<Choice>

    /** What the page lists of it first: all its photos. */
    val home: Listing

    /** Whether its photos come down before they go in, counted in the bar as they come. */
    val fetches: Boolean

    /** [picks], its own, kept on the device for the Moment, in their order; [progress] has the index and how far that one is. */
    suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<PhotoRef>

    /** Deletes [picks], its own: chosen ones from the bar, or the one shown in the viewer. */
    fun delete(picks: List<Pick>, fromViewer: Boolean)

    /** The bar's delete, for tests. */
    val deleteTag: String

    /** What the viewer's delete says: to the bin, or deleted. */
    val deleteWords: org.jetbrains.compose.resources.StringResource

    /** What it asks before deleting, where the system doesn't. */
    @Composable fun Dialogs()

    /** What it lists for the menu's [tag] last picked, where its menu no longer offers it (a NAS album gone from it). */
    fun rememberedListing(tag: String): Listing? = null
}

/** What the page lists, one of [source]'s: the whole gallery or an album, the camera's card, the NAS or one of its albums. */
interface Listing {
    val source: PhotoSource

    @Composable fun title(): String

    /** Its photos, newest first, as far as they are read. */
    @Composable fun picks(): List<Pick>

    /** What the filter can narrow it by: any time, unless its photos say more of themselves. */
    val facets: Set<Facet> get() = setOf(Facet.TIME)

    /**
     * Its photos that [filter] (already [PhotoFilter.within] its [facets]) lets through, as far as they
     * are read: [picks] filtered on the device, unless its source filters them itself.
     */
    @Composable fun shown(filter: PhotoFilter): List<Pick> {
        val all = picks()
        return remember(all, filter) { if (filter.active) all.filter(filter::matches) else all }
    }

    /** The days it has photos on, whatever the time chosen, for the filter's years and months. */
    @Composable fun days(filter: PhotoFilter): List<LocalDate> {
        val all = picks()
        return remember(all) { all.mapNotNullTo(LinkedHashSet()) { it.day }.toList() }
    }

    /** The NAS's EXIF conditions' values among what [filter] leaves, where its source has them; null where not, or while they are read. */
    @Composable fun exifOptions(filter: PhotoFilter): Map<String, List<cc.hyoban.moments.nas.NasOption>>? = null

    /** How many it holds, where that is known. */
    @Composable fun count(shown: List<Pick>): Int?

    /** Where its photos were taken, for its map, where its source knows that of them all without reading each. */
    val places: Places? get() = null

    /** Once it is picked from the title's menu. */
    fun opened() {}

    /** The page under the bar: [grid] once there is something to show, else how far reaching it is or why it can't be. */
    @Composable fun Body(shown: List<Pick>, grid: @Composable (limited: (@Composable () -> Unit)?, onNearEnd: (() -> Unit)?) -> Unit)
}

/**
 * Where a listing's photos were taken, and the photos of the heap opened on its map: the map
 * shows [spots] in heaps, and one tapped is [open]ed, its photos read as the grid under it asks.
 */
interface Places {
    /** Every photo with a place, once [load]ed; null until then. */
    val spots: List<Spot>?

    /** Why they couldn't be read, with trying again; null while they can. */
    val failed: (@Composable () -> Unit)?

    suspend fun load()

    /**
     * When each photo read so far was taken, in milliseconds, by its id, those with a place and those
     * without, but not its videos: those without are placed by time ([TimePlaces]) and shown in
     * heaps like the rest, which [open] and [picks] take as they take [spots]. Null where it can't say.
     */
    val taken: Map<Long, Long>? get() = null

    /** The small thumbnail of the photo at [spot], a heap's cover. */
    suspend fun thumbnail(spot: Spot): ImageBitmap?

    /** The heap opened, by its photos; null when none is. */
    val opened: List<Spot>?

    fun open(heap: List<Spot>?)

    /** The opened heap's photos, as far as they are read, and whether more are coming. */
    @Composable fun picks(): List<Pick>
    val reading: Boolean

    fun more()
}

/** One line of the title's menu. */
class Choice(val label: String, val count: Int?, val icon: ImageVector, val tag: String, val listing: Listing)

/**
 * A photo the picker shows, one of [source]'s: what the grid asks of it, and the viewer as of any
 * photo ([cc.hyoban.moments.ui.viewer.ViewedMedia]).
 */
interface Pick : cc.hyoban.moments.ui.viewer.ViewedMedia {
    val day: LocalDate?
    val source: PhotoSource

    /** Whether it can be chosen: a camera's RAW is only looked at. */
    val choosable: Boolean get() = true

    /** Whether it opens in the viewer: a photo not read yet doesn't. */
    val openable: Boolean get() = true

    /** Whether it can be deleted from here: a published one can't. */
    val deletable: Boolean get() = true

    /** The Moment it is of, a published photo's, opened from the viewer. */
    val moment: String? get() = null

    /** The Moment it went into, for a NAS photo published as one's ([cc.hyoban.moments.data.spec.NasLink]): marked in the grid, opened from the viewer. */
    val linkedMoment: String? get() = null

    /** What it is, for the filter: a still, a video or a live photo; null where that isn't known. */
    val kind: PhotoKind? get() = null

    /** Whether it says where it was taken, for the filter; null where that isn't known. */
    val hasPlace: Boolean? get() = null

    /** Whether it went into a Moment, for the filter; null where that isn't known. */
    val inMoment: Boolean? get() = null

    /** The same photo on the NAS, a published one's that is linked to it, its original kept from there. */
    val nasLink: cc.hyoban.moments.data.spec.NasLink? get() = null

    /** Shares it as the Moment's viewer does, a published photo's own way; nothing for the rest. */
    suspend fun shareOwn() {}

    /** Its thumbnail at [size] pixels, if it is at hand already. */
    fun cachedThumb(size: Int): ImageBitmap? = null

    /** Its thumbnail, at [size] pixels where its source keeps sizes. */
    suspend fun thumbnail(size: Int): ImageBitmap?

    /** What its tile's badge says it holds: a video and its length, a live photo, or RAW. */
    @Composable fun badge(): cc.hyoban.moments.ui.components.BadgeKind?

    /** Drawn in its tile's bottom corner when it isn't in a Moment: a camera photo being saved, or saved. */
    @Composable fun Corner(modifier: Modifier) {}

    /** What TalkBack says of its tile, and of the photo in the viewer. */
    @Composable override fun description(): String

    /** Small in the viewer's row, as its tile has it. */
    @Composable
    override fun Thumb(modifier: Modifier) {
        val thumb by rememberThumb(this)
        thumb?.let { androidx.compose.foundation.Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = modifier) }
    }

    /** What the viewer says beside when it was taken. */
    @Composable fun place(): String?

    /** What it says of itself for the info panel: what the list knows, and the EXIF once it can be read. */
    @Composable override fun rememberDetails(): PhotoDetails?
}

/** The device's own for 相册: sharing, and where it keeps photos. */
interface PickerDevice {
    /** Hands [refs] to another app, where the device has a way to; null where it has none. */
    val share: ((List<PhotoRef>) -> Unit)?

    /** What saving to the device is called: 存到相册 on the phone, the Pictures folder on a computer. */
    val saveWords: StringResource
}

/** A computer's: the NAS alone and no share sheet, its photos kept in the Pictures folder's Here. */
object NasOnly : PickerDevice {
    override val share: ((List<PhotoRef>) -> Unit)? = null
    override val saveWords = Res.string.album_save_pictures
}

/**
 * A delete going on, as the page shows it: a count in the bar for several, a veil over the viewer
 * for one. [gone] has the keys of what went, to leave the choice and the viewer.
 */
class Deletion(private val scope: CoroutineScope, val gone: (Set<String>) -> Unit) {
    var job by mutableStateOf<Job?>(null)
        private set
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set

    fun several(count: Int, work: suspend Deletion.() -> Unit) {
        done = 0
        total = count
        job = scope.launch { try { work() } finally { job = null } }
    }

    fun one(work: suspend () -> Unit) {
        busy = true
        scope.launch { try { work() } finally { busy = false } }
    }
}

/** [pick]'s thumbnail at [size] pixels, shared by the grid and the viewer. */
@Composable
fun rememberThumb(pick: Pick, size: Int = 320): State<ImageBitmap?> {
    return produceState(pick.cachedThumb(size), pick.key, size) {
        if (value != null) return@produceState
        value = pick.thumbnail(size)
    }
}

/** A source that couldn't be reached: why, and trying again. */
@Composable
fun Failed(icon: ImageVector, reason: StringResource, note: StringResource, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current + Space.xl * 3).padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(48.dp))
        Text(stringResource(reason), style = AppTheme.typography.body, textAlign = TextAlign.Center)
        Text(stringResource(note), style = AppTheme.typography.caption, color = tokens.tx3, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = Space.l)) { Button(onClick = onRetry, modifier = Modifier.testTag("camera_retry")) { Text(stringResource(Res.string.action_retry)) } }
    }
}

/** The NAS not set up yet, where it is all there is to list: what is missing and where to set it. */
@Composable
fun Unset() {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current + Space.xl * 3).padding(horizontal = Space.xl).testTag("album_no_nas"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(cc.hyoban.moments.ui.design.AppIcons.Nas, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(48.dp))
        Text(stringResource(Res.string.album_no_nas), style = AppTheme.typography.body, textAlign = TextAlign.Center)
        Text(stringResource(Res.string.album_no_nas_note), style = AppTheme.typography.caption, color = tokens.tx3, textAlign = TextAlign.Center)
    }
}
