package cc.hyoban.moments.widget

import cc.hyoban.moments.res.*
import cc.hyoban.moments.BuildConfig
import androidx.core.net.toUri
import cc.hyoban.moments.ui.theme.Tokens
import androidx.glance.color.colorProviders
import androidx.glance.color.ColorProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlin.reflect.KClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.LinkActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.store.Shown
import cc.hyoban.moments.data.store.shown
import cc.hyoban.moments.data.trips.countOf
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.ui.DEEP_LINK_SCHEME
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.appTokens
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.time.LocalDate
import kotlinx.coroutines.flow.first

// The home screen's widgets: a Moment from this day in an earlier year, and the trip
// under way or the last one (the heatmap is in HeatmapWidget.kt). Each is a photo with its words over a shade at the bottom,
// and opens its page in the app when tapped. They read the copy on the phone, so they
// draw without a network, and are drawn again after each sync and every few hours.

/** A Moment from this day in an earlier year, or any earlier one, with its photo. */
class MemoryWidget : GlanceAppWidget() {
    override val sizeMode = SIZES

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val card = memoryCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(redrawn(context, card, ::memoryCard)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val card = memoryCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    private suspend fun memoryCard(context: Context): Card {
        val container = container(context)
        val memory = memoryFor(shown(container).moments, LocalDate.now()) { container.mediaUrls.still(it, PHOTO_WIDTH) != null }
            ?: return Card.Empty(textOf(Res.string.widget_memory_empty))
        val moment = memory.moment
        val places = container.repository.places.value
        val words = moment.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val where = moment.places.mapNotNull { places[it]?.name }.joinToString(" ").ifEmpty { null }

        return Card.Photo(
            photo = photo(context, container, memory.media),
            eyebrow = memory.yearsAgo?.let { pluralTextOf(Res.plurals.widget_years_ago, it, it) }
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
        provideContent { WidgetTheme(context) { WidgetCard(redrawn(context, card, ::tripCard)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val card = tripCard(context)
        provideContent { WidgetTheme(context) { WidgetCard(card) } }
    }

    private suspend fun tripCard(context: Context): Card {
        val container = container(context)
        val today = LocalDate.now()
        val (moments, trips) = shown(container)
        val (trip, phase) = tripFor(trips, today) ?: return Card.Empty(textOf(Res.string.empty_trips))
        val total = daysBetween(trip.start, trip.end)
        val when_ = when (phase) {
            TripPhase.ONGOING -> textOf(Res.string.trip_day, daysBetween(trip.start, today.toString())) + " " +
                pluralTextOf(Res.plurals.count_days_total, total, total)
            TripPhase.UPCOMING -> (daysBetween(today.toString(), trip.start) - 1).let { pluralTextOf(Res.plurals.widget_trip_in_days, it, it) }
            TripPhase.PAST -> formatRange(trip.start, trip.end, today)
        }
        val count = countOf(trip, moments, trips)
        val cover = coversOf(trip, moments, trips, limit = 1).firstOrNull()

        return Card.Photo(
            photo = cover?.let { photo(context, container, it) },
            eyebrow = when_,
            title = trip.title,
            subtitle = if (count > 0) pluralTextOf(Res.plurals.count_moments, count, count) else null,
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
    /** The widgets whose picker preview the system has taken this run. */
    private val previewsSet = mutableSetOf<KClass<out GlanceAppWidgetReceiver>>()

    /**
     * Draws every widget on the home screen again from the copy on the phone, and, once a
     * run on Android 15 and later, gives the widget picker a preview of each as it is now.
     */
    suspend fun update(context: Context) {
        MemoryWidget().updateAll(context)
        TripWidget().updateAll(context)
        HeatmapWidget().updateAll(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val manager = GlanceAppWidgetManager(context)
            // The system allows only a few a day; one turned down is tried again at the next update.
            for (receiver in listOf(MemoryWidgetReceiver::class, TripWidgetReceiver::class, HeatmapWidgetReceiver::class)) {
                if (receiver in previewsSet) continue
                val result = runCatching { manager.setWidgetPreviews(receiver) }
                    .onFailure { Log.w("Moments", "Widget preview for ${receiver.simpleName} not set", it) }.getOrNull()
                if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) previewsSet += receiver
                Log.i("Moments", "Widget preview for ${receiver.simpleName}: $result")
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
internal val WIDE = DpSize(220.dp, 100.dp)
private val LARGE = DpSize(220.dp, 220.dp)
internal val SIZES = SizeMode.Responsive(setOf(STRIP, SMALL, WIDE, LARGE))

@Composable
private fun WidgetCard(card: Card) {
    // The launcher's own corner, so the card rounds as the system's do.
    val base = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
    when (card) {
        is Card.Empty -> Box(
            base.background(GlanceTheme.colors.surface).padding(PADDING).clickable(open("moments")),
            contentAlignment = Alignment.Center,
        ) {
            Text(card.message, style = WidgetType.BodyMedium.style(GlanceTheme.colors.onSurfaceVariant))
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
            // The words over it say what the card is, so a screen reader hears them once.
            Image(ImageProvider(card.photo), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // The shade the words sit on.
            Image(ImageProvider(R.drawable.widget_shade), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        val strong = if (onPhoto) ColorProvider(MediaOverlay.content) else GlanceTheme.colors.onPrimaryContainer
        val soft = if (onPhoto) ColorProvider(MediaOverlay.contentVariant) else GlanceTheme.colors.onPrimaryContainer
        Column(GlanceModifier.fillMaxSize().padding(if (large) PADDING_LARGE else PADDING), verticalAlignment = Alignment.Bottom) {
            Words(
                card,
                strong,
                soft,
                titleType = if (large) WidgetType.TitleMedium else WidgetType.TitleSmall,
                eyebrowType = if (large) WidgetType.LabelLarge else WidgetType.LabelMedium,
                subtitleType = if (large) WidgetType.BodyMedium else WidgetType.BodySmall,
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
            contentDescription = null,
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            contentScale = ContentScale.Crop,
        )
        Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(PADDING), verticalAlignment = Alignment.CenterVertically) {
            Words(card, GlanceTheme.colors.onSurface, GlanceTheme.colors.onSurfaceVariant, WidgetType.TitleSmall, WidgetType.LabelMedium, WidgetType.BodySmall, title = card.text ?: card.title, titleLines = 3)
        }
    }
}

/** One row: a square of the photo, then when and what on a single line each. */
@Composable
private fun StripCard(card: Card.Photo, base: GlanceModifier) {
    Row(base.background(GlanceTheme.colors.surface).clickable(open(card.link)), verticalAlignment = Alignment.CenterVertically) {
        if (card.photo != null) {
            Image(ImageProvider(card.photo), contentDescription = null, modifier = GlanceModifier.width(72.dp).fillMaxHeight(), contentScale = ContentScale.Crop)
        }
        Column(GlanceModifier.defaultWeight().padding(horizontal = PADDING), verticalAlignment = Alignment.CenterVertically) {
            Text(card.eyebrow, style = WidgetType.LabelMedium.style(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            Text(card.title, style = WidgetType.TitleSmall.style(GlanceTheme.colors.onSurface), maxLines = 1)
        }
    }
}

/** When, what and where, stacked. */
@Composable
private fun Words(
    card: Card.Photo,
    strong: ColorProvider,
    soft: ColorProvider,
    titleType: WidgetType,
    eyebrowType: WidgetType,
    subtitleType: WidgetType,
    title: String,
    titleLines: Int,
) {
    Text(card.eyebrow, style = eyebrowType.style(soft), maxLines = 1)
    Text(
        title,
        modifier = GlanceModifier.fillMaxWidth().padding(top = Space.xs),
        style = titleType.style(strong),
        maxLines = titleLines,
    )
    card.subtitle?.let {
        Text(it, modifier = GlanceModifier.padding(top = Space.xs), style = subtitleType.style(soft), maxLines = 1)
    }
}

/** The app's own colours, light or dark as the system is: each role the token that plays its part, by day and by night. */
@Composable
internal fun WidgetTheme(context: Context, content: @Composable () -> Unit) {
    val day = appTokens(false)
    val night = appTokens(true)
    fun role(pick: (Tokens) -> Color) = ColorProvider(day = pick(day), night = pick(night))
    val colors = colorProviders(
        primary = role { it.accent },
        onPrimary = role { it.onAccent },
        primaryContainer = role { it.accentSoft },
        onPrimaryContainer = role { it.tx },
        secondary = role { it.tx },
        onSecondary = role { it.bg },
        secondaryContainer = role { it.fill },
        onSecondaryContainer = role { it.tx },
        tertiary = role { it.income },
        onTertiary = role { it.bg },
        tertiaryContainer = role { it.fill },
        onTertiaryContainer = role { it.tx },
        error = role { it.danger },
        errorContainer = role { it.fill },
        onError = role { it.bg },
        onErrorContainer = role { it.tx },
        background = role { it.bg },
        onBackground = role { it.tx },
        surface = role { it.surface },
        onSurface = role { it.tx },
        surfaceVariant = role { it.fill },
        onSurfaceVariant = role { it.tx2 },
        outline = role { it.borderStrong },
        inverseOnSurface = role { it.bg },
        inverseSurface = role { it.tx },
        inversePrimary = role { it.accent },
        widgetBackground = role { it.bg },
    )
    GlanceTheme(colors = colors, content = content)
}

/** Opens a page of the app by its link, as `moments://moment/{uid}`. */
internal fun open(link: String) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, "$DEEP_LINK_SCHEME://$link".toUri()).setClassName(BuildConfig.APPLICATION_ID, LinkActivity::class.java.name),
)

/** The app's container, its copy of the records read before anything is picked from it. */
internal suspend fun container(context: Context): PhoneContainer =
    (context.applicationContext as MomentsApplication).container.also { it.repository.loaded.first { loaded -> loaded } }

/**
 * [first], made again by [card] when what the app shows changes while the widget is drawn:
 * demo mode turning arrives so, as an update to the session, not as a new provideGlance.
 */
@Composable
private fun redrawn(context: Context, first: Card, card: suspend (Context) -> Card): Card {
    val container = (context.applicationContext as MomentsApplication).container
    val changed by container.shown.collectAsState()
    var drawn by remember { mutableStateOf<Shown?>(null) }
    val made by produceState(first, changed) {
        val now = shown(container)
        if (drawn != null && now != drawn) value = card(context)
        drawn = now
    }
    return made
}

/** The Moments and trips the widgets may show: all of them, or in demo mode only the public's. */
internal fun shown(container: PhoneContainer): Shown {
    val repository = container.repository
    return shown(repository.moments.value, repository.trips.value, container.settings.demo, repository::isTripPrivate)
}

/** A media item's still, from Coil's cache when it has it, small enough for a widget to carry. */
private suspend fun photo(context: Context, container: PhoneContainer, media: MomentMedia): Bitmap? {
    val url = container.mediaUrls.still(media, PHOTO_WIDTH) ?: return null
    val request = ImageRequest.Builder(context).data(url).size(PHOTO_WIDTH).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}

private const val PHOTO_WIDTH = 720
private val PADDING = Space.l
private val PADDING_LARGE = Space.xl

/** Material 3's type scale for the widgets, which Glance has no theme typography for: its sizes and weights as they are. */
internal enum class WidgetType(val size: TextUnit, val weight: FontWeight) {
    TitleMedium(16.sp, FontWeight.Medium),
    TitleSmall(14.sp, FontWeight.Medium),
    LabelLarge(14.sp, FontWeight.Medium),
    LabelMedium(12.sp, FontWeight.Medium),
    BodyMedium(14.sp, FontWeight.Normal),
    BodySmall(12.sp, FontWeight.Normal);

    fun style(color: ColorProvider) = TextStyle(color = color, fontSize = size, fontWeight = weight)
}
