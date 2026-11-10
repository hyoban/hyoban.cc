package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.ui.viewer.VideoState
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
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
 * Where 选照片's photos come from: the phone's gallery, the paired camera or the NAS. Each keeps
 * what it reads, says what the title's menu offers of it, fetches its chosen photos for the Moment
 * and deletes its own; the page only lays them out.
 */
internal interface PhotoSource {
    /** What it offers in the title's menu, in order; nothing while it can't be reached. */
    @Composable fun choices(): List<Choice>

    /** Whether its photos come down before they go in, counted in the bar as they come. */
    val fetches: Boolean

    /** [picks], its own, as Uris for the Moment, in their order; [progress] has the index and how far that one is. */
    suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<Uri>

    /** Deletes [picks], its own: chosen ones from the bar, or the one shown in the viewer. */
    fun delete(picks: List<Pick>, fromViewer: Boolean)

    /** The bar's delete, for tests. */
    val deleteTag: String

    /** What the viewer's delete says: to the bin, or deleted. */
    val deleteWords: Int

    /** What it asks before deleting, where the system doesn't. */
    @Composable fun Dialogs()
}

/** What the page lists, one of [source]'s: the whole gallery or an album, the camera's card, the NAS or one of its albums. */
internal interface Listing {
    val source: PhotoSource

    @Composable fun title(): String

    /** Its photos, newest first, as far as they are read. */
    @Composable fun picks(): List<Pick>

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
internal interface Places {
    /** Every photo with a place, once [load]ed; null until then. */
    val spots: List<Spot>?

    /** Why they couldn't be read, with trying again; null while they can. */
    val failed: (@Composable () -> Unit)?

    suspend fun load()

    /** The small thumbnail of the photo at [spot], a heap's cover. */
    suspend fun thumbnail(spot: Spot): Bitmap?

    /** The heap opened, by its photos; null when none is. */
    val opened: List<Spot>?

    fun open(heap: List<Spot>?)

    /** The opened heap's photos, as far as they are read, and whether more are coming. */
    @Composable fun picks(): List<Pick>
    val reading: Boolean

    fun more()
}

/** One line of the title's menu. */
internal class Choice(val label: String, val count: Int?, val icon: ImageVector, val tag: String, val listing: Listing)

/** A photo the picker shows, one of [source]'s: what the grid and the viewer ask of it. */
internal interface Pick {
    val key: String
    val day: LocalDate?
    val source: PhotoSource

    /** Whether it can be chosen: a camera's RAW is only looked at. */
    val choosable: Boolean get() = true

    /** Its thumbnail at [size] pixels, if it is at hand already. */
    fun cachedThumb(size: Int): Bitmap? = null

    /** Its thumbnail, at [size] pixels where its source keeps sizes. */
    suspend fun thumbnail(context: Context, size: Int): Bitmap?

    /** What its tile says in its corner: a video's length, 实况, or RAW. */
    @Composable fun badge(): String?

    /** What TalkBack says of its tile. */
    @Composable fun description(): String

    /** What the viewer says beside when it was taken. */
    @Composable fun place(): String?

    /** What it says of itself for the info panel: what the list knows, and the EXIF once it can be read. */
    @Composable fun rememberDetails(): PhotoDetails?

    /** A note for the info panel where [details] say too little. */
    fun infoNote(details: PhotoDetails?): Int? = null

    /** Once the viewer rests on it. */
    fun settled() {}

    /**
     * It full size in the viewer, [active] while it is the one shown: a video then plays over its
     * still through [video], which the viewer's controls work.
     */
    @Composable fun Page(active: Boolean, video: VideoState, onZoom: (Boolean) -> Unit)
}

/**
 * A delete going on, as the page shows it: a count in the bar for several, a veil over the viewer
 * for one. [gone] has the keys of what went, to leave the choice and the viewer.
 */
internal class Deletion(private val scope: CoroutineScope, val gone: (Set<String>) -> Unit) {
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
internal fun rememberThumb(pick: Pick, size: Int = 320): State<Bitmap?> {
    val context = LocalContext.current
    return produceState(pick.cachedThumb(size), pick.key, size) {
        if (value != null) return@produceState
        value = pick.thumbnail(context, size)
    }
}

/** A source that couldn't be reached: why, and trying again. */
@Composable
internal fun Failed(icon: ImageVector, reason: Int, note: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current + Space.xl * 3).padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(48.dp))
        Text(stringResource(reason), style = AppTheme.typography.body, textAlign = TextAlign.Center)
        Text(stringResource(note), style = AppTheme.typography.caption, color = tokens.tx3, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = Space.l)) { Button(onClick = onRetry, modifier = Modifier.testTag("camera_retry")) { Text(stringResource(R.string.action_retry)) } }
    }
}
