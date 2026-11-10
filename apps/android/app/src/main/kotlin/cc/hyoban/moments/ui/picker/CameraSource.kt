package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.ui.viewer.VideoState
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
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
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The paired camera's card, reached only once it is listed, and kept until the page closes, when
 * the phone leaves the camera's network. Refused its permissions, [onRefused] goes back to the gallery.
 */
@Composable
internal fun rememberCamera(deletion: Deletion, onRefused: () -> Unit): CameraSource {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = CameraStore.camera.collectAsStateWithLifecycle()
    val camera = remember {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && CameraLink.supported(context)
        CameraSource(context.applicationContext, scope, deletion, paired, supported)
    }
    val ask = rememberCameraPermissionAsk(
        emptyArray(),
        listOf(PermissionFact(AppIcons.Wifi, stringResource(R.string.permission_camera_wifi))),
        onDecline = onRefused,
    ) { granted -> if (granted) camera.connect() else onRefused() }
    SideEffect { camera.ask = { ask() } }
    DisposableEffect(camera) { onDispose { camera.close() } }
    return camera
}

internal class CameraSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deletion: Deletion,
    private val pairedState: State<PairedCamera?>,
    private val supported: Boolean,
) : PhotoSource {
    val paired get() = pairedState.value
    var transfer by mutableStateOf<CameraTransfer?>(null)
        private set
    var ask: () -> Unit = {}
    val listing = CameraListing(this)

    fun connect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            transfer?.close()
            transfer = CameraTransfer(context).also { scope.launch { it.connect() } }
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) transfer?.close()
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
    override val deleteWords = R.string.camera_delete

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
            title = { Text(if (one != null) stringResource(R.string.camera_delete_title) else stringResource(R.string.camera_delete_many_title, these.size)) },
            text = { Text(if (one != null) stringResource(R.string.camera_delete_body, one.name) else stringResource(R.string.camera_delete_many_body)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val camera = transfer ?: return@TextButton
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@TextButton
                    if (one != null && fromViewer) deletion.one {
                        val gone = withIt(one)
                        if (camera.delete(one)) deletion.gone(gone)
                        else Toast.makeText(context, R.string.camera_delete_failed, Toast.LENGTH_SHORT).show()
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
                            if (missed > 0) Toast.makeText(context, context.getString(R.string.camera_delete_missed, missed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.testTag("camera_delete_confirm")) { Text(stringResource(R.string.camera_delete), color = tokens.danger) }
            },
        )
    }
}

private fun cameraKey(photo: CameraPhoto) = "camera/${photo.key}"

/** The camera's card: how far joining it is, why it couldn't be, or its photos. */
internal data class CameraListing(val camera: CameraSource) : Listing {
    override val source get() = camera

    @Composable
    override fun title() = camera.paired?.name ?: stringResource(R.string.camera_model)

    @Composable
    override fun picks(): List<Pick> =
        (camera.state() as? CameraTransfer.State.Ready)?.photos?.let { photos -> remember(photos) { photos.map { CameraPick(it, camera) } } }.orEmpty()

    @Composable
    override fun count(shown: List<Pick>) = (camera.state() as? CameraTransfer.State.Ready)?.photos?.size

    override fun opened() {
        if (camera.transfer == null) camera.ask()
    }

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        when (val s = camera.state()) {
            null, is CameraTransfer.State.Connecting -> Steps((s as? CameraTransfer.State.Connecting)?.step ?: CameraTransfer.Step.WIFI_ON)
            is CameraTransfer.State.Failed -> Failed(AppIcons.CameraBody, s.reason, R.string.camera_failed_note) { camera.connect() }
            is CameraTransfer.State.Ready -> grid(null, null)
        }
    }
}

internal data class CameraPick(val photo: CameraPhoto, override val source: CameraSource) : Pick {
    override val key get() = cameraKey(photo)
    override val day get() = photo.captured?.toLocalDate()
    override val choosable get() = photo.photo

    /** As the camera sent it, whatever the size. */
    override suspend fun thumbnail(context: Context, size: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.thumbnail(photo) else null

    @Composable
    override fun badge() = if (photo.raw) "RAW" else null

    @Composable
    override fun description() =
        stringResource(if (photo.raw) R.string.camera_raw_description else R.string.camera_photo_description, photo.name)

    @Composable
    override fun place() = photo.name

    @Composable
    override fun rememberDetails(): PhotoDetails? {
        val cardName = stringResource(R.string.picker_camera_card)
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.originalOf(photo)?.file else null
        return produceState<PhotoDetails?>(null, key, original) {
            val base = PhotoDetails(
                takenAt = photo.captured?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
                name = photo.name, format = PhotoDetails.format(photo.mime, photo.name), bytes = photo.size, album = cardName,
            )
            value = if (original != null) PhotoDetails.read(base) { original.inputStream() } else base
        }.value
    }

    override fun infoNote(details: PhotoDetails?) =
        R.string.picker_info_camera_note.takeIf { details?.make == null && details?.focal == null }

    /** Its original, once the viewer is still on it, so swiping past doesn't queue downloads. */
    override fun settled() {
        if (photo.photo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.transfer?.loadOriginal(photo)
    }

    /** Its thumbnail enlarged until its original is down in the cache. */
    @Composable
    override fun Page(active: Boolean, video: VideoState, onZoom: (Boolean) -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val transfer = source.transfer
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || transfer == null) return@Box
            val thumb by rememberThumb(this@CameraPick)
            val original = transfer.originalOf(photo)
            val file = original?.file
            var shape by remember(key) { mutableFloatStateOf(0f) }
            ZoomablePhoto(shape.takeIf { it > 0f } ?: thumb?.let { it.width.toFloat() / it.height } ?: 1.5f, onZoom) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                file?.let {
                    AsyncImage(it, contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        onSuccess = { loaded -> loaded.painter.intrinsicSize.let { size -> if (size.height > 0f) shape = size.width / size.height } })
                }
            }
            if (photo.photo && file == null && original != null) Loading(original) { transfer.loadOriginal(photo) }
        }
    }
}

@Composable
private fun Steps(step: CameraTransfer.Step) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current).padding(horizontal = Space.gutter, vertical = Space.s)) {
        listOf(
            CameraTransfer.Step.WIFI_ON to R.string.camera_step_wifi_on,
            CameraTransfer.Step.JOINING to R.string.camera_step_joining,
            CameraTransfer.Step.READING to R.string.camera_step_reading,
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
        Text(stringResource(R.string.camera_join_note), style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.padding(top = Space.s))
    }
}

/** How far an original is: the spinner, how much is down of how much, and how fast; or that it failed, to try again. */
@Composable
private fun Loading(original: CameraTransfer.Original, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
        if (original.failed) {
            Text(stringResource(R.string.camera_download_failed), style = AppTheme.typography.body, color = MediaOverlay.content)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), color = MediaOverlay.content) }
            return@Column
        }
        Spinner(color = MediaOverlay.content)
        val seconds = (SystemClock.elapsedRealtime() - original.started) / 1000f
        val perSecond = if (seconds > 0.3f) (original.done / seconds).toLong() else 0L
        Text(
            stringResource(R.string.camera_loading, Formatter.formatShortFileSize(context, original.done), Formatter.formatShortFileSize(context, original.size),
                Formatter.formatShortFileSize(context, perSecond)),
            style = AppTheme.typography.caption, color = MediaOverlay.content,
        )
    }
}
