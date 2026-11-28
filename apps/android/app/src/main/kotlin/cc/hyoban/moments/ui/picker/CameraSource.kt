package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.ui.components.BadgeKind
import cc.hyoban.moments.ui.viewer.ReadAhead
import cc.hyoban.moments.ui.viewer.ViewedMedia
import cc.hyoban.moments.ui.viewer.ViewedStill

import cc.hyoban.moments.res.*
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.camera.CameraConnection
import cc.hyoban.moments.camera.CameraLink
import cc.hyoban.moments.camera.CameraPhoto
import cc.hyoban.moments.camera.CameraStore
import cc.hyoban.moments.camera.CameraTransfer
import cc.hyoban.moments.camera.PairedCamera
import cc.hyoban.moments.camera.rememberCameraPermissionAsk
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.PhotoDetails
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The paired camera's card, reached only once it is listed. It connects when shown, and the
 * connection outlives the page ([CameraConnection]) until Here goes to the background. Refused its
 * permissions, [onRefused] goes back to the gallery.
 */
@Composable
internal fun rememberCamera(deletion: Deletion, onRefused: () -> Unit): CameraSource {
    val context = LocalContext.current
    val paired = CameraStore.camera.collectAsStateWithLifecycle()
    val camera = remember {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && CameraLink.supported(context)
        CameraSource(context.applicationContext, deletion, paired, supported)
    }
    val ask = rememberCameraPermissionAsk(
        emptyArray(),
        listOf(PermissionFact(AppIcons.Wifi, stringResource(Res.string.permission_camera_wifi))),
        onDecline = onRefused,
    ) { granted -> if (granted) camera.connect(again = false) else onRefused() }
    SideEffect { camera.ask = { ask() } }
    return camera
}

internal class CameraSource(
    private val context: Context,
    private val deletion: Deletion,
    private val pairedState: State<PairedCamera?>,
    private val supported: Boolean,
) : PhotoSource {
    val paired get() = pairedState.value
    val transfer: CameraTransfer?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) CameraConnection.transfer else null
    var ask: () -> Unit = {}
    val listing = CameraListing(this)
    override val home get() = listing

    /** Connects unless connected or on the way; [again] starts over, as after a failure. */
    fun connect(again: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) CameraConnection.connect(context, again)
    }

    @Composable
    fun state(): CameraTransfer.State? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) transfer?.state?.collectAsStateWithLifecycle()?.value else null

    @Composable
    override fun choices(): List<Choice> =
        paired?.takeIf { supported }?.let { listOf(Choice(it.name, null, AppIcons.CameraBody, "picker_camera", listing)) }.orEmpty()

    override val fetches = true

    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<Uri> =
        if (picks.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) emptyList()
        else transfer!!.download(picks.map { (it as CameraPick).photo }, progress)

    // The chosen ones from the bar, or the one in the viewer: the dialog asks first.
    private var deleting by mutableStateOf<List<CameraPhoto>?>(null)
    private var fromViewer = false

    override fun delete(picks: List<Pick>, fromViewer: Boolean) {
        this.fromViewer = fromViewer
        deleting = picks.map { (it as CameraPick).photo }
    }

    override val deleteTag = "camera_delete_chosen"
    override val deleteWords = Res.string.camera_delete

    /** The keys of what goes with [photo]: the camera deletes its JPEG and its RAW together. */
    private fun withIt(photo: CameraPhoto): Set<String> {
        val listed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (transfer?.state?.value as? CameraTransfer.State.Ready)?.photos.orEmpty() else emptyList()
        return (listed.filter { it.content == photo.content } + photo).map { cameraKey(it) }.toSet()
    }

    @Composable
    override fun Dialogs() {
        val these = deleting ?: return
        val one = these.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (one != null) stringResource(Res.string.camera_delete_title) else stringResource(Res.string.camera_delete_many_title, these.size)) },
            text = { Text(if (one != null) stringResource(Res.string.camera_delete_body, one.name) else stringResource(Res.string.camera_delete_many_body)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val camera = transfer ?: return@TextButton
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@TextButton
                    if (one != null && fromViewer) deletion.one {
                        val gone = withIt(one)
                        if (camera.delete(one)) deletion.gone(gone)
                        else Toast.makeText(context, textOf(Res.string.camera_delete_failed), Toast.LENGTH_SHORT).show()
                    } else deletion.several(these.size) {
                        // One at a time, each leaving the choice once the camera says it's gone;
                        // those it couldn't delete stay chosen.
                        var missed = 0
                        try {
                            these.forEach { photo ->
                                val gone = withIt(photo)
                                if (camera.delete(photo)) deletion.gone(gone) else missed++
                                done++
                            }
                        } finally {
                            if (missed > 0) Toast.makeText(context, textOf(Res.string.camera_delete_missed, missed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.testTag("camera_delete_confirm")) { Text(stringResource(Res.string.camera_delete), color = tokens.danger) }
            },
        )
    }
}

private fun cameraKey(photo: CameraPhoto) = "camera/${photo.key}"

/** The camera's card: how far joining it is, why it couldn't be, or its photos. */
internal data class CameraListing(val camera: CameraSource) : Listing {
    override val source get() = camera

    @Composable
    override fun title() = camera.paired?.name ?: stringResource(Res.string.camera_model)

    @Composable
    override fun picks(): List<Pick> =
        (camera.state() as? CameraTransfer.State.Ready)?.photos?.let { photos -> remember(photos) { photos.map { CameraPick(it, camera) } } }.orEmpty()

    @Composable
    override fun count(shown: List<Pick>) = (camera.state() as? CameraTransfer.State.Ready)?.photos?.size


    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        when (val s = camera.state()) {
            null, is CameraTransfer.State.Connecting -> {
                // Shown unconnected, at first or back from the background: it connects.
                if (s == null) LaunchedEffect(Unit) { camera.ask() }
                Steps((s as? CameraTransfer.State.Connecting)?.step ?: CameraTransfer.Step.WIFI_ON)
            }
            is CameraTransfer.State.Failed -> Failed(AppIcons.CameraBody, s.reason, Res.string.camera_failed_note) { camera.connect() }
            is CameraTransfer.State.Ready -> {
                val saving = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) camera.transfer?.saving else null
                grid(saving?.let { { Saving(it.at, it.of) } }, null)
            }
        }
    }
}

internal data class CameraPick(val photo: CameraPhoto, override val source: CameraSource) : Pick {
    override val key get() = cameraKey(photo)
    override val day get() = photo.captured?.toLocalDate()
    override val choosable get() = photo.photo

    /** As the camera sent it, whatever the size. */
    override suspend fun thumbnail(size: Int): ImageBitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.thumbnail(photo)?.asImageBitmap() else null

    override val type get() = MediaType.IMAGE

    @Composable
    override fun badge() = if (photo.raw) BadgeKind.Raw else null

    @Composable
    override fun description(): String {
        val what = stringResource(if (photo.raw) Res.string.camera_raw_description else Res.string.camera_photo_description, photo.name)
        return when (saveState()) {
            SaveState.SAVING -> stringResource(Res.string.camera_tile_saving, what)
            SaveState.SAVED -> stringResource(Res.string.camera_tile_saved, what)
            null -> what
        }
    }

    private enum class SaveState { SAVING, SAVED }

    /** Whether it is being saved to the camera's album, or is there. */
    private fun saveState(): SaveState? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val transfer = source.transfer ?: return null
        return when {
            transfer.savingNow?.key == photo.key -> SaveState.SAVING
            transfer.saved(photo) -> SaveState.SAVED
            else -> null
        }
    }

    /** Being saved to the camera's album, a ring filling as it comes down; saved, a check. */
    @Composable
    override fun Corner(modifier: Modifier) {
        val state = saveState() ?: return
        Box(modifier.size(20.dp).background(MediaOverlay.control, CircleShape), contentAlignment = Alignment.Center) {
            if (state == SaveState.SAVED) {
                Icon(AppIcons.Check, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(14.dp))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val done = source.transfer?.savingDone ?: 0L
                val color = MediaOverlay.content
                Canvas(Modifier.size(14.dp)) {
                    val line = 2.dp.toPx()
                    val inset = Offset(line / 2, line / 2)
                    val arc = Size(size.width - line, size.height - line)
                    drawArc(color.copy(alpha = 0.3f), 0f, 360f, useCenter = false, topLeft = inset, size = arc, style = Stroke(line))
                    val part = if (photo.size > 0) (done.toFloat() / photo.size).coerceIn(0f, 1f) else 0f
                    drawArc(color, -90f, 360f * part, useCenter = false, topLeft = inset, size = arc, style = Stroke(line, cap = StrokeCap.Round))
                }
            }
        }
    }

    @Composable
    override fun place() = photo.name

    @Composable
    override fun rememberDetails(): PhotoDetails? {
        val cardName = stringResource(Res.string.picker_camera_card)
        val context = LocalContext.current
        val saved = albumCopy()
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.originalOf(photo)?.file else null
        return produceState<PhotoDetails?>(null, key, saved, original) {
            val base = PhotoDetails(
                takenAt = photo.captured?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
                name = photo.name, format = PhotoDetails.format(photo.mime, photo.name), bytes = photo.size, album = cardName,
            )
            value = when {
                saved != null -> PhotoDetails.read(base) { context.contentResolver.openInputStream(saved)!! }
                original != null -> PhotoDetails.read(base) { original.inputStream() }
                else -> base
            }
        }.value
    }

    override fun infoNote(details: PhotoDetails?) =
        Res.string.picker_info_camera_note.takeIf { details?.make == null && details?.focal == null }

    /** Its copy in the camera's album on the phone, once saved there. */
    private fun albumCopy(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.inAlbum(photo) else null

    /** Its original, once the viewer is still on it, so swiping past doesn't queue downloads; none while the album has it. */
    override fun settled(reading: ReadAhead) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val transfer = source.transfer ?: return
        if (photo.photo && albumCopy() == null) transfer.loadOriginal(photo)
    }

    /** Then those either side of the one rested on. */
    override fun ahead(from: ViewedMedia, reading: ReadAhead) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val transfer = source.transfer ?: return
        if (albumCopy() == null) (from as? CameraPick)?.let { transfer.prefetch(photo, after = it.photo) }
    }

    /**
     * Its copy in the album on the phone, read as a photo there is; else its thumbnail enlarged until
     * its original is down in the cache, then the original in tiles.
     */
    @Composable
    override fun rememberStill(active: Boolean, deep: Boolean): ViewedStill {
        val thumb by rememberThumb(this)
        val file = albumCopy() ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.originalOf(photo)?.file else null
        return ViewedStill(thumb?.let { it.width.toFloat() / it.height } ?: 1.5f, thumb = thumb, model = file)
    }

    /** Its original coming down, over it. */
    @Composable
    override fun Decor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val transfer = source.transfer ?: return
        val original = transfer.originalOf(photo)
        if (photo.photo && albumCopy() == null && original != null && original.file == null) Loading(original) { transfer.loadOriginal(photo) }
    }
}

@Composable
private fun Steps(step: CameraTransfer.Step) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current).padding(horizontal = Space.gutter, vertical = Space.s)) {
        listOf(
            CameraTransfer.Step.WIFI_ON to Res.string.camera_step_wifi_on,
            CameraTransfer.Step.JOINING to Res.string.camera_step_joining,
            CameraTransfer.Step.READING to Res.string.camera_step_reading,
        ).forEach { (s, label) ->
            val past = s.ordinal < step.ordinal
            val now = s == step
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    when {
                        now -> Spinner()
                        past -> Icon(AppIcons.Check, contentDescription = null, tint = tokens.tx2)
                        else -> Icon(AppIcons.Photos, contentDescription = null, tint = tokens.tx3)
                    }
                }
                Text(stringResource(label), style = AppTheme.typography.body, color = if (now) tokens.tx else if (past) tokens.tx2 else tokens.tx3)
            }
        }
        Text(stringResource(Res.string.camera_join_note), style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.padding(top = Space.s))
    }
}

/** How far saving the card to the camera's album is, over the photos until it is done. */
@Composable
private fun Saving(at: Int, of: Int) {
    Text(
        stringResource(Res.string.camera_saving, CameraTransfer.SAVED_NAME, at, of),
        style = AppTheme.typography.caption, color = tokens.tx2,
        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
    )
}

/** How far an original is: the spinner, how much is down of how much, and how fast; or that it failed, to try again. */
@Composable
private fun Loading(original: CameraTransfer.Original, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
        if (original.failed) {
            Text(stringResource(Res.string.camera_download_failed), style = AppTheme.typography.body, color = MediaOverlay.content)
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry), color = MediaOverlay.content) }
            return@Column
        }
        Spinner(color = MediaOverlay.content)
        val seconds = (SystemClock.elapsedRealtime() - original.started) / 1000f
        val perSecond = if (seconds > 0.3f) (original.done / seconds).toLong() else 0L
        Text(
            stringResource(Res.string.camera_loading, Formatter.formatShortFileSize(context, original.done), Formatter.formatShortFileSize(context, original.size),
                Formatter.formatShortFileSize(context, perSecond)),
            style = AppTheme.typography.caption, color = MediaOverlay.content,
        )
    }
}
