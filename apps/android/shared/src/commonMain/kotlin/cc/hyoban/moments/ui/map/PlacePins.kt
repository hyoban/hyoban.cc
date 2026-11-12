package cc.hyoban.moments.ui.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.ui.components.readable
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import org.maplibre.compose.map.MissingImageRequest
import org.maplibre.compose.map.MissingImageResolver
import org.maplibre.compose.map.ResolvedStyleImage

// The places on the atlas, drawn as the site's map (packages/trip-map/src/map/place-markers.ts
// and styles.css) and the Mac's MapKit draw them: the newest photo taken at a place, framed in
// white with its year's dot at the corner, or for a place without one a balloon in its year's
// colour with its kind's icon. Only a thick crowd gathers into a stack of its busiest places'
// prints, counted. The pins are a symbol layer's icons, each image drawn here the first time
// the map asks for it.

/** The pins' sizes in dp, as the site's styles.css draws them. */
internal object PinSize {
    const val Photo = 34f
    const val PhotoBorder = 2f
    const val PhotoRadius = 10f
    const val YearDot = 10f
    const val Balloon = 28f
    const val BalloonBorder = 1.5f
    const val Icon = 15f

    /** The balloon's tail, a rotated square whose tip is this far below the balloon. */
    const val Tail = 6f
    const val TailSquare = 8f
    const val Stack = 40f
    const val Anchor = 3.5f

    /** How much larger an open place's pin is: a photo, and a balloon. */
    const val OpenPhoto = 1.8f
    const val OpenBalloon = 1.7f

    /** How far above its place an open pin's tip floats, lifted over the dot left there. */
    const val OpenPhotoTip = 7.8f
    const val OpenBalloonTip = 9f

    /**
     * Room around each image for its shadow, on every side but a pointed pin's foot, where its
     * tip is, so the room it takes on the map ends at its place.
     */
    const val Pad = 5f
}

private const val Prefix = "here-pin"

/** What a place's pin and its lifted, open pin are called in the style. */
internal fun pinImageIds(color: Color, photo: MomentMedia?, kind: String?): Pair<String, String> {
    val argb = color.toArgb().toUInt().toString(16)
    return if (photo != null) {
        "$Prefix:photo:0:$argb:${photo.uid}" to "$Prefix:photo:1:$argb:${photo.uid}"
    } else {
        val key = kind.orEmpty()
        "$Prefix:balloon:0:$argb:$key" to "$Prefix:balloon:1:$argb:$key"
    }
}

/** The start of a stack's image name, before its cluster's id; [key] names the places it's of. */
internal fun stackImagePrefix(key: String) = "$Prefix:stack:$key:"

/** Where the tip of a lifted photo's tail is below the photo's middle, as drawn unscaled. */
internal val PhotoTailTip = PinSize.Photo / 2 + PinSize.TailSquare * sqrt(2f) / 2

/** A painter that draws [block] at [size] dp, for MapLibre to render into a style image. */
private class PinPainter(private val block: DrawScope.() -> Unit) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() = block()
}

/** What the pins' images are drawn from, as the map's data and theme stand. */
internal class PinInputs(
    val photos: Map<String, MomentMedia>,
    val places: Map<String, MapPlace>,
    val colors: Map<Int, Color>,
    val unknown: Color,
    val leaves: suspend (clusterId: Long) -> List<String>?,
)

/**
 * Draws each pin image the style asks for and doesn't have: a photo's, a balloon's or a stack's,
 * by the name [pinImageIds] and [stackImagePrefix] gave it.
 */
internal class PlacePinImages(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val context: PlatformContext,
    private val urls: MediaUrls,
    private val text: TextMeasurer,
    private val icons: Map<String, Painter>,
    private val fallbackIcon: Painter,
) : MissingImageResolver {
    var inputs: PinInputs? = null

    override suspend fun resolve(request: MissingImageRequest): ResolvedStyleImage? {
        // here-pin:photo|balloon:<open>:<colour>:<photo or kind>, or here-pin:stack:<key>:<cluster>
        val parts = request.id.split(":")
        if (parts.firstOrNull() != Prefix) return null
        val data = inputs ?: return null
        return when {
            parts.size == 5 && parts[1] == "photo" -> {
                val color = parseColor(parts[3]) ?: return null
                val media = data.photos[parts[4]] ?: return null
                photo(load(media), color, open = parts[2] == "1")
            }
            parts.size == 5 && parts[1] == "balloon" -> {
                val color = parseColor(parts[3]) ?: return null
                balloon(icons[parts[4]] ?: fallbackIcon, color, open = parts[2] == "1")
            }
            parts.size == 4 && parts[1] == "stack" -> {
                val id = parts[3].toLongOrNull() ?: return null
                val members = data.leaves(id)?.mapNotNull(data.places::get).orEmpty()
                    .sortedWith(compareByDescending<MapPlace> { it.count }.thenBy { it.place.name.length })
                if (members.isEmpty()) return null
                val color = data.colors[members.minOf { it.firstYear }] ?: data.unknown
                val prints = members.mapNotNull { it.photo }.take(3).map { load(it) }
                stack(prints + List(3 - prints.size) { null }, color, members.sumOf { it.count })
            }
            else -> null
        }
    }

    private fun parseColor(hex: String) = hex.toUIntOrNull(16)?.let { Color(it.toInt()) }

    /** The photo's still, loaded small; null when it can't be, the frame then in its year's colour. */
    private suspend fun load(media: MomentMedia): Painter? {
        val pixels = ceil(with(density) { (PinSize.Photo * PinSize.OpenPhoto).dp.toPx() }).toInt()
        val url = urls.still(media, pixels) ?: return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(pixels, pixels)
            .scale(Scale.FILL)
            .readable()
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return null
        return image.asPainter(context)
    }

    private suspend fun render(width: Float, height: Float, block: DrawScope.() -> Unit) =
        ResolvedStyleImage.fromPainter(
            PinPainter(block),
            density,
            layoutDirection,
            size = DpSize(width.dp, height.dp),
        )

    /**
     * A photo framed in white, its year's dot at the corner; or lifted, [PinSize.OpenPhoto] times
     * as large with no dot and a tail pointing down at its place, its tip at the image's foot.
     */
    private suspend fun photo(photo: Painter?, color: Color, open: Boolean): ResolvedStyleImage {
        val pad = PinSize.Pad
        val scale = if (open) PinSize.OpenPhoto else 1f
        val side = PinSize.Photo * scale
        val tail = if (open) (PhotoTailTip - PinSize.Photo / 2) * scale else 0f
        val width = side + pad * 2
        val height = if (open) side + tail + pad else side + pad * 2
        return render(width, height) {
            val origin = Offset(pad.dp.toPx(), pad.dp.toPx())
            val frame = Rect(origin, Size(side.dp.toPx(), side.dp.toPx()))
            val radius = (PinSize.PhotoRadius * scale).dp.toPx()
            val border = (PinSize.PhotoBorder * scale).dp.toPx()
            if (open) {
                // A corner of the frame, under it, so only its point shows below.
                val square = (PinSize.TailSquare * scale).dp.toPx()
                val center = Offset(frame.center.x, frame.bottom)
                rotate(45f, center) {
                    val tailRect = Rect(center - Offset(square / 2, square / 2), Size(square, square))
                    shadow(RoundRect(tailRect, CornerRadius(2.dp.toPx())), 2.dp.toPx(), 0.25f)
                    drawRoundRect(Color.White, tailRect.topLeft, tailRect.size, CornerRadius(2.dp.toPx()))
                }
            }
            shadow(RoundRect(frame, CornerRadius(radius)), 6.dp.toPx(), 0.3f, dy = 1.dp.toPx())
            drawRoundRect(Color.Black.copy(alpha = 0.12f), frame.topLeft - Offset(0.5f, 0.5f), Size(frame.width + 1f, frame.height + 1f), CornerRadius(radius + 0.5f))
            drawRoundRect(Color.White, frame.topLeft, frame.size, CornerRadius(radius))
            val inner = frame.deflate(border)
            drawPhoto(photo, inner, CornerRadius(radius - border), color)
            if (!open) {
                val dot = PinSize.YearDot.dp.toPx()
                val at = Offset(inner.right, inner.bottom)
                shadow(RoundRect(Rect(at - Offset(dot / 2, dot / 2), Size(dot, dot)), CornerRadius(dot / 2)), 2.dp.toPx(), 0.25f)
                drawCircle(Color.White, dot / 2, at)
                drawCircle(color, dot / 2 - PinSize.BalloonBorder.dp.toPx(), at)
            }
        }
    }

    /**
     * A balloon in its year's colour with its kind's icon, its tail's tip at the image's foot;
     * lifted, [PinSize.OpenBalloon] times as large.
     */
    private suspend fun balloon(icon: Painter, color: Color, open: Boolean): ResolvedStyleImage {
        val pad = PinSize.Pad
        val scale = if (open) PinSize.OpenBalloon else 1f
        val side = PinSize.Balloon * scale
        val width = side + pad * 2
        val height = (PinSize.Balloon + PinSize.Tail) * scale + pad
        return render(width, height) {
            val origin = Offset(pad.dp.toPx(), pad.dp.toPx())
            val diameter = side.dp.toPx()
            val center = origin + Offset(diameter / 2, diameter / 2)
            val border = (PinSize.BalloonBorder * scale).dp.toPx()
            val tip = Offset(center.x, origin.y + ((PinSize.Balloon + PinSize.Tail) * scale).dp.toPx())
            val square = (PinSize.TailSquare * scale).dp.toPx()
            val tailCenter = tip - Offset(0f, square * sqrt(2f) / 2)
            val tailRect = Rect(tailCenter - Offset(square / 2, square / 2), Size(square, square))
            shadow(RoundRect(Rect(center, diameter / 2), CornerRadius(diameter / 2)), 6.dp.toPx(), 0.3f, dy = 1.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.12f), diameter / 2 + 0.5f, center)
            rotate(45f, tailCenter) {
                drawRoundRect(Color.White, tailRect.topLeft, tailRect.size, CornerRadius(2.dp.toPx()))
                drawRoundRect(color, tailRect.topLeft, Size(square - border, square - border), CornerRadius(1.dp.toPx()))
            }
            drawCircle(Color.White, diameter / 2, center)
            drawCircle(color, diameter / 2 - border, center)
            val glyph = (PinSize.Icon * scale).dp.toPx()
            translate(center.x - glyph / 2, center.y - glyph / 2) {
                with(icon) { draw(Size(glyph, glyph), colorFilter = ColorFilter.tint(Color.White)) }
            }
        }
    }

    /**
     * A stack of prints: its busiest places' photos, the busiest one's on top and the next two
     * tilted out behind it, a card in the year's colour for a place without one, and how many
     * Moments it holds at its top right. Centred on the image.
     */
    private suspend fun stack(prints: List<Painter?>, color: Color, count: Int): ResolvedStyleImage {
        val reach = PinSize.Stack / 2 + 16f + PinSize.Pad
        val side = reach * 2
        return render(side, side) {
            val half = PinSize.Stack.dp.toPx() / 2
            val middle = Offset(size.width / 2, size.height / 2)
            val radius = PinSize.PhotoRadius.dp.toPx()
            val border = PinSize.PhotoBorder.dp.toPx()
            // The farthest first, so the busiest is drawn last, on top.
            val placings = listOf(
                Triple(Offset(9f, -3f), 10f, 0.86f),
                Triple(Offset(-8f, -2f), -9f, 0.9f),
                Triple(Offset.Zero, 0f, 1f),
            )
            prints.asReversed().forEachIndexed { index, print ->
                val (shift, turn, size) = placings[index]
                translate(shift.x.dp.toPx(), shift.y.dp.toPx()) {
                    rotate(turn, middle) {
                        scale(size, middle) {
                            val frame = Rect(middle, half)
                            shadow(RoundRect(frame, CornerRadius(radius)), 6.dp.toPx(), 0.3f, dy = 1.dp.toPx())
                            drawRoundRect(Color.White, frame.topLeft, frame.size, CornerRadius(radius))
                            drawPhoto(print, frame.deflate(border), CornerRadius(radius - border), color)
                        }
                    }
                }
            }
            val label = text.measure(
                count.toString(),
                TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            )
            val badgeHeight = 16.dp.toPx()
            val badgeWidth = max(badgeHeight, label.size.width + 8.dp.toPx() + 3.dp.toPx())
            val right = middle.x + half + 14.dp.toPx()
            val top = middle.y - half - 12.dp.toPx()
            val badge = Rect(Offset(right - badgeWidth, top), Size(badgeWidth, badgeHeight))
            shadow(RoundRect(badge, CornerRadius(badgeHeight / 2)), 4.dp.toPx(), 0.25f, dy = 1.dp.toPx())
            drawRoundRect(Color.White, badge.topLeft, badge.size, CornerRadius(badgeHeight / 2))
            val fill = badge.deflate(1.5.dp.toPx())
            drawRoundRect(color, fill.topLeft, fill.size, CornerRadius(fill.height / 2))
            drawText(label, topLeft = badge.center - Offset(label.size.width / 2f, label.size.height / 2f))
        }
    }
}

/** A photo filling [frame], cropped to it and rounded; the year's colour where there's none. */
private fun DrawScope.drawPhoto(photo: Painter?, frame: Rect, corner: CornerRadius, color: Color) {
    val path = Path().apply { addRoundRect(RoundRect(frame, corner)) }
    clipPath(path) {
        drawRect(color, frame.topLeft, frame.size)
        if (photo == null) return@clipPath
        val intrinsic = photo.intrinsicSize
        val fill = if (intrinsic.isSpecified() && intrinsic.width > 0f && intrinsic.height > 0f) {
            val ratio = max(frame.width / intrinsic.width, frame.height / intrinsic.height)
            Size(intrinsic.width * ratio, intrinsic.height * ratio)
        } else {
            frame.size
        }
        translate(frame.center.x - fill.width / 2, frame.center.y - fill.height / 2) {
            with(photo) { draw(fill) }
        }
    }
}

private fun Size.isSpecified() = this != Size.Unspecified

/**
 * A soft shadow under [shape], as CSS draws `0 dy blur rgba(0, 0, 0, alpha)`: half as dark as
 * [alpha] at the shape's edge, fading out as a gaussian does to nothing at the image's edge. Drawn as a ring a pixel wide at
 * a time, each as dark as makes the rings under it add up to that fade: the images are drawn in
 * software, where a blurred paint isn't to be had on every platform.
 */
private fun DrawScope.shadow(shape: RoundRect, blur: Float, alpha: Float, dy: Float = 0f) {
    val sigma = blur / 2
    // No further than the room around the image, where it comes down to nothing rather than
    // being cut off.
    val reach = minOf(sigma * 3, PinSize.Pad.dp.toPx() - dy)
    val steps = ceil(reach).toInt().coerceIn(4, 32)
    fun fall(distance: Float) = kotlin.math.exp(-(distance * distance) / (2 * sigma * sigma))
    val floor = fall(reach)
    fun target(distance: Float) = alpha / 2 * (fall(distance) - floor) / (1 - floor)
    var outside = 0f
    for (step in steps downTo 0) {
        val distance = reach * step / steps
        val wanted = target(distance)
        // Over what the wider rings already laid down, only what's still missing.
        val each = (1f - (1f - wanted) / (1f - outside)).coerceAtLeast(0f)
        outside = wanted
        if (each <= 0f) continue
        val rect = Rect(shape.left - distance, shape.top - distance + dy, shape.right + distance, shape.bottom + distance + dy)
        drawRoundRect(
            Color.Black.copy(alpha = each),
            rect.topLeft,
            rect.size,
            CornerRadius(shape.topLeftCornerRadius.x + distance),
        )
    }
}
