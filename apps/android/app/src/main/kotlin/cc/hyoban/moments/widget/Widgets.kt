package cc.hyoban.moments.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ThemeMode
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.ui.DEEP_LINK_SCHEME
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.theme.seededColorScheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.time.LocalDate
import kotlinx.coroutines.flow.first

// The home screen's widgets: a Moment from this day in an earlier year, and the trip
// under way or the last one. Each is a photo with its words over a shade at the bottom,
// and opens its page in the app when tapped. They read the copy on the phone, so they
// draw without a network, and are drawn again after each sync and every few hours.

/** A Moment from this day in an earlier year, or any earlier one, with its photo. */
class MemoryWidget : GlanceAppWidget() {
    override val sizeMode = SIZES

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val card = memoryCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val card = memoryCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    private suspend fun memoryCard(context: Context): Card {
        val container = container(context)
        val memory = memoryFor(container.repository.moments.value, LocalDate.now()) { container.mediaUrls.still(it, PHOTO_WIDTH) != null }
            ?: return Card.Empty(context.getString(R.string.widget_memory_empty))
        val moment = memory.moment
        val places = container.repository.places.value
        val words = moment.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val where = moment.places.mapNotNull { places[it]?.name }.joinToString(" · ").ifEmpty { null }

        return Card.Photo(
            photo = photo(context, container, memory.media),
            eyebrow = memory.yearsAgo?.let { context.resources.getQuantityString(R.plurals.widget_years_ago, it, it) }
                // With its year always: a memory from this year still says which.
                ?: formatDate(moment.dateKey, today = LocalDate.MIN),
            title = words ?: where ?: formatDate(moment.dateKey),
            subtitle = if (words != null) where else null,
            link = "moment/${moment.uid}",
            text = moment.text.trim().ifEmpty { null },
        )
    }
}

/** The trip under way, else the last one, with one of its photos. */
class TripWidget : GlanceAppWidget() {
    override val sizeMode = SIZES

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val card = tripCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val card = tripCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    private suspend fun tripCard(context: Context): Card {
        val container = container(context)
        val today = LocalDate.now()
        val trips = container.repository.trips.value
        val moments = container.repository.moments.value
        val (trip, phase) = tripFor(trips, today) ?: return Card.Empty(context.getString(R.string.empty_trips))
        val total = daysBetween(trip.start, trip.end)
        val when_ = when (phase) {
            TripPhase.ONGOING -> context.getString(R.string.trip_day, daysBetween(trip.start, today.toString())) + " · " +
                context.resources.getQuantityString(R.plurals.count_days_total, total, total)
            TripPhase.UPCOMING -> (daysBetween(today.toString(), trip.start) - 1).let { context.resources.getQuantityString(R.plurals.widget_trip_in_days, it, it) }
            TripPhase.PAST -> formatRange(trip.start, trip.end, today)
        }
        val count = momentsOf(trip, moments, trips).size
        val cover = coversOf(trip, moments, trips, limit = 1).firstOrNull()

        return Card.Photo(
            photo = cover?.let { photo(context, container, it) },
            eyebrow = when_,
            title = trip.title,
            subtitle = if (count > 0) context.resources.getQuantityString(R.plurals.count_moments, count, count) else null,
            link = "trip/${trip.id}",
        )
    }
}

class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()
}

class TripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TripWidget()
}

object Widgets {
    private var previewsSet = false

    /**
     * Draws every widget on the home screen again from the copy on the phone, and, once a
     * run on Android 15 and later, gives the widget picker a preview of each as it is now.
     */
    suspend fun update(context: Context) {
        MemoryWidget().updateAll(context)
        TripWidget().updateAll(context)
        if (!previewsSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            previewsSet = true
            val manager = GlanceAppWidgetManager(context)
            // The system allows only a few a day; one turned down is tried next run.
            runCatching {
                manager.setWidgetPreviews(MemoryWidgetReceiver::class)
                manager.setWidgetPreviews(TripWidgetReceiver::class)
            }
        }
    }
}

/** What a widget shows: a photo with its words, or a line saying there is nothing yet. */
private sealed interface Card {
    /** [text], the Moment's words whole, is what a large widget shows in place of [title]. */
    data class Photo(val photo: Bitmap?, val eyebrow: String, val title: String, val subtitle: String?, val link: String, val text: String? = null) : Card
    data class Empty(val message: String) : Card
}

// The sizes a widget is laid out for, as ColorOS's cards come: a row (4×1), a square
// (2×2), a wide card (4×2) and a large one (4×4). The launcher takes the largest that fits.
private val STRIP = DpSize(160.dp, 50.dp)
private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(220.dp, 100.dp)
private val LARGE = DpSize(220.dp, 220.dp)
private val SIZES = SizeMode.Responsive(setOf(STRIP, SMALL, WIDE, LARGE))

@Composable
private fun WidgetCard(card: Card) {
    // The launcher's own corner on Android 12 and later, so the card rounds as the system's do.
    val base = GlanceModifier.fillMaxSize().appWidgetBackground().let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.cornerRadius(android.R.dimen.system_app_widget_background_radius) else it.cornerRadius(CORNER)
    }
    when (card) {
        is Card.Empty -> Box(
            base.background(GlanceTheme.colors.surface).padding(PADDING).clickable(open("moments")),
            contentAlignment = Alignment.Center,
        ) {
            Text(card.message, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
        }
        is Card.Photo -> when (LocalSize.current) {
            STRIP -> StripCard(card, base)
            WIDE -> if (card.photo != null) WideCard(card, base) else OverlayCard(card, base, large = false)
            LARGE -> OverlayCard(card, base, large = true)
            else -> OverlayCard(card, base, large = false)
        }
    }
}

/** The photo filling the card, the words over a shade at its foot; a large card gives the words more room. */
@Composable
private fun OverlayCard(card: Card.Photo, base: GlanceModifier, large: Boolean) {
    val onPhoto = card.photo != null
    Box(base.background(GlanceTheme.colors.primaryContainer).clickable(open(card.link))) {
        if (card.photo != null) {
            Image(ImageProvider(card.photo), contentDescription = card.title, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // The shade the words sit on.
            Image(ImageProvider(R.drawable.widget_shade), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        val strong = if (onPhoto) ColorProvider(Color.White) else GlanceTheme.colors.onPrimaryContainer
        val soft = if (onPhoto) ColorProvider(Color.White.copy(alpha = 0.82f)) else GlanceTheme.colors.onPrimaryContainer
        Column(GlanceModifier.fillMaxSize().padding(if (large) PADDING_LARGE else PADDING), verticalAlignment = Alignment.Bottom) {
            Words(
                card,
                strong,
                soft,
                titleSize = if (large) 18.sp else 15.sp,
                small = if (large) 13.sp else 12.sp,
                title = if (large) card.text ?: card.title else card.title,
                titleLines = if (large) 4 else 2,
            )
        }
    }
}

/** The photo down the start half, the words beside it on the card's surface. */
@Composable
private fun WideCard(card: Card.Photo, base: GlanceModifier) {
    Row(base.background(GlanceTheme.colors.surface).clickable(open(card.link))) {
        Image(
            ImageProvider(card.photo!!),
            contentDescription = card.title,
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            contentScale = ContentScale.Crop,
        )
        Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(PADDING), verticalAlignment = Alignment.CenterVertically) {
            Words(card, GlanceTheme.colors.onSurface, GlanceTheme.colors.onSurfaceVariant, titleSize = 15.sp, small = 12.sp, title = card.text ?: card.title, titleLines = 3)
        }
    }
}

/** One row: a square of the photo, then when and what on a single line each. */
@Composable
private fun StripCard(card: Card.Photo, base: GlanceModifier) {
    Row(base.background(GlanceTheme.colors.surface).clickable(open(card.link)), verticalAlignment = Alignment.CenterVertically) {
        if (card.photo != null) {
            Image(ImageProvider(card.photo), contentDescription = card.title, modifier = GlanceModifier.width(72.dp).fillMaxHeight(), contentScale = ContentScale.Crop)
        }
        Column(GlanceModifier.defaultWeight().padding(horizontal = PADDING), verticalAlignment = Alignment.CenterVertically) {
            Text(card.eyebrow, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(card.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

/** When, what and where, stacked. */
@Composable
private fun Words(card: Card.Photo, strong: ColorProvider, soft: ColorProvider, titleSize: TextUnit, small: TextUnit, title: String, titleLines: Int) {
    Text(card.eyebrow, style = TextStyle(color = soft, fontSize = small, fontWeight = FontWeight.Medium), maxLines = 1)
    Text(
        title,
        modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
        style = TextStyle(color = strong, fontSize = titleSize, fontWeight = FontWeight.Medium),
        maxLines = titleLines,
    )
    card.subtitle?.let {
        Text(it, modifier = GlanceModifier.padding(top = 2.dp), style = TextStyle(color = soft, fontSize = small), maxLines = 1)
    }
}

/** The app's own colours, from the seed and style its theme page sets, light or dark as it is set to follow. */
@Composable
private fun WidgetTheme(context: Context, content: @Composable () -> Unit) {
    val settings = (context.applicationContext as MomentsApplication).container.settings
    val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(android.R.color.system_accent1_500) else null
    val seed = Color(settings.themeColor ?: accent ?: Settings.DEFAULT_THEME_COLOR)
    val light = seededColorScheme(seed, dark = false, settings.colorStyle)
    val dark = seededColorScheme(seed, dark = true, settings.colorStyle, settings.pureBlack)
    val colors = when (settings.themeMode) {
        ThemeMode.SYSTEM -> ColorProviders(light = light, dark = dark)
        ThemeMode.LIGHT -> ColorProviders(light = light, dark = light)
        ThemeMode.DARK -> ColorProviders(light = dark, dark = dark)
    }
    GlanceTheme(colors = colors, content = content)
}

/** Opens a page of the app by its link, as `moments://moment/{uid}`. */
private fun open(link: String) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse("$DEEP_LINK_SCHEME://$link")).setClassName("cc.hyoban.moments", MainActivity::class.java.name),
)

/** The app's container, its copy of the records read before anything is picked from it. */
private suspend fun container(context: Context): AppContainer =
    (context.applicationContext as MomentsApplication).container.also { it.repository.loaded.first { loaded -> loaded } }

/** A media item's still, from Coil's cache when it has it, small enough for a widget to carry. */
private suspend fun photo(context: Context, container: AppContainer, media: MomentMedia): Bitmap? {
    val url = container.mediaUrls.still(media, PHOTO_WIDTH) ?: return null
    val request = ImageRequest.Builder(context).data(url).size(PHOTO_WIDTH).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}

private const val PHOTO_WIDTH = 720
private val CORNER = 24.dp
private val PADDING = 14.dp
private val PADDING_LARGE = 18.dp
