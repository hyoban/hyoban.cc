package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.exifTime
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.sheetSurface
import cc.hyoban.moments.ui.map.SharedPointMap
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.DarkTokens
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.LocalTokens
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a photo says of itself, for the info panel (PhotoInfo): when, with what, where, and
 * the file. Each is null when the photo doesn't say, and the panel leaves its row out.
 */
data class PhotoDetails(
    val takenAt: OffsetDateTime? = null,
    /** The offset the photo was taken at, as EXIF writes it (+08:00). */
    val offset: String? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    /** In millimetres, and as on a full frame. */
    val focal: Double? = null,
    val focal35: Int? = null,
    val aperture: Double? = null,
    /** The shutter, in seconds. */
    val exposure: Double? = null,
    val iso: Int? = null,
    val bias: Double? = null,
    val fix: Fix? = null,
    val name: String? = null,
    /** JPEG, HEIF, PNG, RAW… */
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val album: String? = null,
) {
    companion object {
        /** The format to show for a [mime] type. */
        fun format(mime: String?, name: String? = null): String? = when {
            name != null && Regex("\\.(arw|dng|cr3|nef|raf)$", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "RAW"
            mime == null -> null
            mime == "image/jpeg" -> "JPEG"
            mime == "image/heic" || mime == "image/heif" -> "HEIF"
            mime == "video/quicktime" -> "MOV"
            else -> mime.substringAfter('/').uppercase()
        }

        /** [base] with what the EXIF read from [open] says; [base] alone when it can't be read. */
        suspend fun read(base: PhotoDetails, open: () -> InputStream?): PhotoDetails = withContext(Dispatchers.IO) {
            runCatching { open()?.use { base.withExif(it) } }.getOrNull() ?: base
        }

        /** How much of a published original is fetched for its EXIF: it is at the start of the file. */
        private const val HEAD_BYTES = 256 * 1024

        /** The start of the file at [url], enough for its EXIF, or null. */
        suspend fun head(url: String): ByteArray? = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).header("Range", "bytes=0-${HEAD_BYTES - 1}").build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    // A server that ignores the range sends it all: read only the start.
                    body.byteStream().use { input ->
                        val out = ByteArray(HEAD_BYTES)
                        var read = 0
                        while (read < HEAD_BYTES) {
                            val n = input.read(out, read, HEAD_BYTES - read)
                            if (n < 0) break
                            read += n
                        }
                        out.copyOf(read)
                    }
                }
            }.getOrNull()
        }

        /** [base] with what the start of the file at [url] says. */
        suspend fun readRemote(base: PhotoDetails, url: String): PhotoDetails =
            head(url)?.let { bytes -> read(base) { ByteArrayInputStream(bytes) } } ?: base
    }
}

/**
 * The photo's info under it in a viewer (PhotoInfo): a dark surface whatever the theme, as the
 * viewer is black, with its time and zone, the camera's settings, where it was taken on a small
 * map, and the file; a row the photo has nothing for is left out. [note] says what isn't known yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoInfoPanel(container: AppContainer, details: PhotoDetails?, modifier: Modifier = Modifier, note: String? = null) {
    val place by produceState<String?>(null, details?.fix) {
        value = details?.fix?.let { runCatching { container.placeSearch.around(it).name }.getOrNull() }
    }
    CompositionLocalProvider(LocalTokens provides DarkTokens, LocalDarkTheme provides true) {
        Column(
            modifier.fillMaxWidth().sheetSurface(tokens.surface, AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.gutter, vertical = Space.m)
                .testTag("photo_info"),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = Space.s).size(36.dp, 4.dp).background(tokens.borderStrong.copy(alpha = 0.6f), Corners.full))
            if (details == null) return@Column
            details.takenAt?.let { at ->
                InfoRow(AppIcons.Clock, at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())),
                    details.offset?.let { "GMT$it" })
            }
            val camera = listOfNotNull(details.make, details.model).joinToString(" ").ifEmpty { null }
            val specs = specs(details)
            if (camera != null || details.lens != null || specs.isNotEmpty()) {
                InfoRow(AppIcons.Aperture, camera ?: details.lens ?: specs.first(), details.lens.takeIf { camera != null }) {
                    if (specs.isNotEmpty()) FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        specs.forEach { Text(it, style = AppTheme.typography.caption, color = tokens.tx, modifier = Modifier.background(tokens.fill, Corners.full).padding(horizontal = Space.s, vertical = 2.dp)) }
                    }
                }
            }
            details.fix?.let { fix ->
                InfoRow(AppIcons.Pin, place ?: stringResource(Res.string.photo_info_place), "%.4f, %.4f".format(Locale.ROOT, fix.latitude, fix.longitude)) {
                    SharedPointMap(fix, Modifier.padding(top = Space.s).fillMaxWidth().height(112.dp).background(tokens.fill, AppTheme.shapes.medium))
                }
            }
            val file = listOfNotNull(
                details.format,
                if (details.width != null && details.height != null) "${details.width} × ${details.height}" else null,
                details.bytes?.let(::fileSize),
                details.album,
            ).joinToString(" · ").ifEmpty { null }
            if (details.name != null || file != null) InfoRow(AppIcons.File, details.name ?: file!!, file.takeIf { details.name != null })
            note?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.padding(start = 36.dp)) }
        }
    }
}

/** These, with what the EXIF in [input] says filled in where it says it: the phone's reader, or the computer's. */
expect fun PhotoDetails.withExif(input: InputStream): PhotoDetails

/** [bytes] as a file's size is written: 512 KB, 3.4 MB. */
fun fileSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000 * 1000 -> "${(bytes / 1000.0).let { if (it < 10) "%.1f".format(Locale.ROOT, it) else "%.0f".format(Locale.ROOT, it) }} kB"
    bytes < 1000L * 1000 * 1000 -> "${"%.1f".format(Locale.ROOT, bytes / 1e6).removeSuffix(".0")} MB"
    else -> "${"%.2f".format(Locale.ROOT, bytes / 1e9)} GB"
}

/** The shooting settings, each one written as a camera does: 35 mm, ƒ/1.8, 1/250 s, ISO 100, +0.3 EV. */
internal fun specs(d: PhotoDetails): List<String> = listOfNotNull(
    d.focal?.let { f ->
        val mm = if (f % 1.0 == 0.0) "${f.toInt()}" else "%.1f".format(Locale.ROOT, f)
        if (d.focal35 != null && d.focal35 != f.roundToInt()) "$mm mm (${d.focal35} mm)" else "$mm mm"
    },
    d.aperture?.let { "ƒ/" + (if (it % 1.0 == 0.0) "${it.toInt()}" else "%.1f".format(Locale.ROOT, it)) },
    d.exposure?.let { s -> if (s >= 1.0) "${"%.1f".format(Locale.ROOT, s).removeSuffix(".0")} s" else "1/${(1 / s).roundToInt()} s" },
    d.iso?.let { "ISO $it" },
    d.bias?.takeIf { it != 0.0 }?.let { (if (it > 0) "+" else "−") + "%.1f".format(Locale.ROOT, kotlin.math.abs(it)) + " EV" },
)

@Composable
private fun InfoRow(icon: ImageVector, title: String, caption: String?, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(icon, contentDescription = null, tint = tokens.tx2, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.typography.body, color = tokens.tx, maxLines = 2, overflow = TextOverflow.Ellipsis)
            caption?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2) }
            extra()
        }
    }
}

