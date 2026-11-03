package cc.hyoban.moments.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.glance.currentState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.size
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.core.net.toUri
import cc.hyoban.moments.ui.DEEP_LINK_SCHEME
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.lifecycle.lifecycleScope
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.trackStats
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text as AppText
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.theme.MomentsTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.text.NumberFormat
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The heatmap widget: a square a day, as deep as that day's Moments, spending or footprints,
// the weeks across as wide as the widget is, a year by months when it is large and a row of
// days when it is a strip. The squares are drawn as masks the theme's colours tint, so they
// turn light or dark with the system as the words do.

/** Days counted by what each widget shows, the chosen [HeatSource] kept in the widget's own state. */
class HeatmapWidget : GlanceAppWidget() {
    // Laid out at the size it is, as many weeks as fit, rather than at the nearest of a few.
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition
    // The picker shows the widget at the size it is added at, four cells by two.
    override val previewSizeMode = SizeMode.Responsive(setOf(WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = HeatSource.of(getAppWidgetState<Preferences>(context, id)[SOURCE])
        val today = LocalDate.now()
        // Read only as far back as the widget's sizes reach, the footprints being the slow ones.
        val sizes = runCatching { GlanceAppWidgetManager(context).getAppWidgetSizes(id) }.getOrNull().orEmpty().ifEmpty { listOf(DpSize(220.dp, 220.dp)) }
        val from = sizes.minOf { layoutFor(it, today).grid.start }
        val firstDays = heatDays(context, first, from, today)
        val choose = chooseSource(context, GlanceAppWidgetManager(context).getAppWidgetId(id))
        provideContent {
            // A source chosen while the widget is drawn arrives as its state changing, not a new
            // provideGlance: its days are read then, the widget keeping the last ones until they are.
            val chosen = HeatSource.of(currentState(SOURCE))
            // The Moments, which demo mode narrows, are counted again as they change.
            val moments by (context.applicationContext as MomentsApplication).container.shownMoments.collectAsState()
            val shown by produceState(first to firstDays, chosen, moments) {
                if (value.first != chosen || chosen == HeatSource.MOMENTS) value = chosen to heatDays(context, chosen, from, today)
            }
            WidgetTheme(context) { HeatCard(shown.first, shown.second, today, choose = choose) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val today = LocalDate.now()
        val days = heatDays(context, HeatSource.MOMENTS, layoutFor(WIDE, today).grid.start, today)
        provideContent { WidgetTheme(context) { HeatCard(HeatSource.MOMENTS, days, today) } }
    }

    companion object {
        val SOURCE = stringPreferencesKey("source")
    }
}

class HeatmapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeatmapWidget()
}

/** What each day from [from] through [to] had of [source]: Moments, money in the book's currency, or metres. */
private suspend fun heatDays(context: Context, source: HeatSource, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
    val container = container(context)
    return when (source) {
        HeatSource.MOMENTS -> {
            shown(container).moments
                .mapNotNull { runCatching { LocalDate.parse(it.dateKey) }.getOrNull() }
                .filter { it in from..to }
                .groupingBy { it }.eachCount()
                .mapValues { it.value.toDouble() }
        }
        HeatSource.SPENDING -> {
            container.ledger.loaded.first { it }
            val ledger = container.ledger.ledger.value
            val currency = ledger.book?.currency ?: return emptyMap()
            val books = ledger.books.filter { !it.archived }.map { it.id }.toSet()
            summarize(ledger.entries.filter { it.txn.book in books }, ledger.rates, currency, from, to.plusDays(1))
                .expenseByDay.mapValues { it.value.toDouble() }
        }
        HeatSource.FOOTPRINTS -> withContext(Dispatchers.IO) {
            val store = container.footprints
            store.days().mapNotNull { day -> runCatching { LocalDate.parse(day) }.getOrNull()?.takeIf { it in from..to }?.let { it to day } }
                .associate { (date, day) ->
                    // A past day's distance stays as it was, so it is read once while the app runs.
                    val metres = PastDistances[day]?.takeIf { date < to }
                        ?: trackStats(store.points(TrackSelection(day = day))).metres.also { if (date < to) PastDistances[day] = it }
                    // A day the phone only lay still went nowhere, as the timeline counts it.
                    date to if (metres < 100) 0.0 else metres
                }
        }
    }
}

private val PastDistances = ConcurrentHashMap<String, Double>()

/** Where the widget puts its squares at [size]: a row of days, the weeks, or a year by months. */
private data class HeatLayout(val shape: Shape, val grid: HeatGrid, val padding: Dp, val gridWidth: Dp, val gridHeight: Dp) {
    enum class Shape { STRIP, WEEKS, YEAR }
}

private fun layoutFor(size: DpSize, today: LocalDate): HeatLayout {
    val (width, height) = size
    return when {
        height < 100.dp -> {
            val padding = Space.l
            val gridWidth = width - padding * 2 - STRIP_WORDS - Space.l
            val weeks = (((gridWidth + GAP) / (STRIP_CELL + GAP)).toInt() / 7).coerceIn(1, 5)
            HeatLayout(HeatLayout.Shape.STRIP, dayRow(today, weeks * 7), padding, gridWidth, height - padding * 2)
        }
        width >= 200.dp && height >= 200.dp -> {
            val padding = Space.xl
            HeatLayout(HeatLayout.Shape.YEAR, monthGrid(today), padding, width - padding * 2, height - padding * 2 - HEADER - Space.m)
        }
        else -> {
            val padding = Space.l
            val gridWidth = width - padding * 2
            val gridHeight = height - padding * 2 - HEADER - Space.s
            val cell = ((gridHeight - GAP * 6) / 7).coerceAtLeast(4.dp)
            val weeks = ((gridWidth + GAP) / (cell + GAP)).toInt().coerceIn(4, 53)
            HeatLayout(HeatLayout.Shape.WEEKS, weekGrid(today, weeks), padding, gridWidth, gridHeight)
        }
    }
}

@Composable
private fun HeatCard(source: HeatSource, days: Map<LocalDate, Double>, today: LocalDate, choose: Action? = null) {
    val context = LocalContext.current
    val layout = layoutFor(LocalSize.current, today)
    val grid = layout.grid
    val shown = grid.cells.associate { it.date to (days[it.date] ?: 0.0) }
    val level = heatLevels(shown.values)
    val pictures = drawHeat(context, layout, level, shown)
    val name = context.getString(
        when (source) {
            HeatSource.MOMENTS -> R.string.widget_heatmap_moments
            HeatSource.SPENDING -> R.string.widget_heatmap_spending
            HeatSource.FOOTPRINTS -> R.string.widget_heatmap_footprints
        },
    )
    val range = when (layout.shape) {
        HeatLayout.Shape.YEAR -> context.getString(R.string.widget_heatmap_year)
        else -> ((grid.end.toEpochDay() - grid.start.toEpochDay()) / 7 + 1).toInt().let { context.resources.getQuantityString(R.plurals.widget_heatmap_weeks, it, it) }
    }
    val amount = when (source) {
        HeatSource.FOOTPRINTS -> {
            val km = shown.values.sum() / 1000
            val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (km < 10) 1 else 0 }
            context.getString(R.string.widget_heatmap_km, format.format(km))
        }
        else -> shown.values.count { it > 0 }.let { context.resources.getQuantityString(R.plurals.widget_heatmap_days, it, it) }
    }
    val summary = context.getString(R.string.widget_heatmap_summary, range, amount)

    val base = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(GlanceTheme.colors.surface).clickable(open(source.link))
    if (layout.shape == HeatLayout.Shape.STRIP) {
        Row(base.padding(horizontal = layout.padding), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.width(STRIP_WORDS)) {
                Name(name, choose)
                Text(summary, style = WidgetType.LabelMedium.style(GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
            }
            Box(GlanceModifier.defaultWeight().padding(start = Space.l)) { Squares(pictures) }
        }
    } else {
        Column(base.padding(layout.padding)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight()) { Name(name, choose) }
                Text(summary, style = WidgetType.LabelMedium.style(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
            Box(
                GlanceModifier.fillMaxWidth().defaultWeight().padding(top = if (layout.shape == HeatLayout.Shape.YEAR) Space.m else Space.s),
                contentAlignment = Alignment.Center,
            ) { Squares(pictures) }
        }
    }
}

/**
 * What the widget shows, a caret after it: tapped, the page that chooses what it shows, as
 * not every launcher sets a widget up again from its long press.
 */
@Composable
private fun Name(name: String, choose: Action?) {
    val ink = GlanceTheme.colors.onSurface
    Row(if (choose != null) GlanceModifier.clickable(choose) else GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = WidgetType.LabelLarge.style(ink), maxLines = 1)
        if (choose != null) {
            Image(ImageProvider(R.drawable.widget_caret_down), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(ink))
        }
    }
}

/** Opens the page that chooses what widget [widgetId] shows, each widget's its own. */
private fun chooseSource(context: Context, widgetId: Int): Action = actionStartActivity(
    Intent(context, HeatmapSettingsActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Told apart by its data, so each widget's tap carries its own id.
        .setData("$DEEP_LINK_SCHEME://heatmap-widget/$widgetId".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
)

/** The empty days in the fill, the others in the accent as deep as each is, and the months' numbers in the secondary ink. */
@Composable
private fun Squares(pictures: HeatPictures) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The words beside them say what they show, so a screen reader hears that alone.
        Image(ImageProvider(pictures.empty), null, GlanceModifier.fillMaxSize(), ContentScale.Fit, ColorFilter.tint(GlanceTheme.colors.surfaceVariant))
        Image(ImageProvider(pictures.filled), null, GlanceModifier.fillMaxSize(), ContentScale.Fit, ColorFilter.tint(GlanceTheme.colors.primary))
        pictures.labels?.let {
            Image(ImageProvider(it), null, GlanceModifier.fillMaxSize(), ContentScale.Fit, ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

private class HeatPictures(val empty: Bitmap, val filled: Bitmap, val labels: Bitmap?)

/**
 * The squares as three masks of one size, white at the opacity each is to be drawn at: the
 * grid laid into [HeatLayout.gridWidth] by [HeatLayout.gridHeight], square but for a year's,
 * whose months are as tall as the space allows, up to half again as wide.
 */
private fun drawHeat(context: Context, layout: HeatLayout, level: (Double) -> Int, shown: Map<LocalDate, Double>): HeatPictures {
    val density = context.resources.displayMetrics.density
    val grid = layout.grid
    val gap = GAP.value * density
    val gutter = if (layout.shape == HeatLayout.Shape.YEAR) YEAR_GUTTER.value * density else 0f
    val areaWidth = layout.gridWidth.value.coerceAtLeast(1f) * density
    val areaHeight = layout.gridHeight.value.coerceAtLeast(1f) * density
    var cellWidth = (areaWidth - gutter - gap * (grid.columns - 1)) / grid.columns
    var cellHeight = (areaHeight - gap * (grid.rows - 1)) / grid.rows
    if (layout.shape == HeatLayout.Shape.YEAR) cellHeight = cellHeight.coerceAtMost(cellWidth * 1.5f)
    else cellWidth.coerceAtMost(cellHeight).let { cellWidth = it; cellHeight = it }
    cellWidth = cellWidth.coerceAtLeast(1f)
    cellHeight = cellHeight.coerceAtLeast(1f)
    val width = (gutter + cellWidth * grid.columns + gap * (grid.columns - 1)).toInt().coerceAtLeast(1)
    val height = (cellHeight * grid.rows + gap * (grid.rows - 1)).toInt().coerceAtLeast(1)
    val empty = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val filled = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val radius = minOf(2 * density, cellWidth / 3, cellHeight / 3)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val emptyCanvas = Canvas(empty)
    val filledCanvas = Canvas(filled)
    for (cell in grid.cells) {
        val left = gutter + cell.column * (cellWidth + gap)
        val top = cell.row * (cellHeight + gap)
        val rect = RectF(left, top, left + cellWidth, top + cellHeight)
        val step = level(shown[cell.date] ?: 0.0)
        paint.alpha = if (step == 0) 255 else (HEAT_ALPHA[step] * 255).toInt()
        (if (step == 0) emptyCanvas else filledCanvas).drawRoundRect(rect, radius, radius, paint)
    }
    val labels = if (layout.shape == HeatLayout.Shape.YEAR) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also { bitmap ->
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * density * context.resources.configuration.fontScale }
            val canvas = Canvas(bitmap)
            val first = grid.start
            for (row in 0 until grid.rows) {
                val month = first.plusMonths(row.toLong()).monthValue
                // Every third month named, the first row always.
                if (row % 3 != 0) continue
                val baseline = row * (cellHeight + gap) + (cellHeight - text.ascent() - text.descent()) / 2
                canvas.drawText("$month", 0f, baseline, text)
            }
        }
    } else null
    return HeatPictures(empty, filled, labels)
}

private val GAP = 3.dp
private val HEADER = 20.dp
private val STRIP_WORDS = 72.dp
private val STRIP_CELL = 10.dp
private val YEAR_GUTTER = 18.dp

/**
 * Chooses what a heatmap widget shows, when it is added and when its name is tapped (or the
 * launcher sets it up again): a tap on one shows it and closes the page; closed without one,
 * it keeps what it had.
 */
class HeatmapSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Closed without choosing, a widget being added is added all the same, showing Moments.
        setResult(RESULT_OK, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val manager = GlanceAppWidgetManager(this)
        val glanceId = runCatching { manager.getGlanceIdBy(widgetId) }.getOrNull() ?: return finish()
        setContent {
            MomentsTheme {
                var chosen by remember { mutableStateOf<HeatSource?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    chosen = HeatSource.of(getAppWidgetState(this@HeatmapSettingsActivity, PreferencesGlanceStateDefinition, glanceId)[HeatmapWidget.SOURCE])
                }
                val scope = rememberCoroutineScope()
                HeatmapSettings(chosen, onBack = ::finish) { source ->
                    chosen = source
                    scope.launch {
                        updateAppWidgetState(this@HeatmapSettingsActivity, glanceId) { it[HeatmapWidget.SOURCE] = source.key }
                        HeatmapWidget().update(this@HeatmapSettingsActivity, glanceId)
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapSettings(chosen: HeatSource?, onBack: () -> Unit, onChoose: (HeatSource) -> Unit) {
    SubPage(
        stringResource(R.string.widget_heatmap_name),
        onBack,
        Modifier.background(AppColors.page),
        subtitle = stringResource(R.string.widget_heatmap_settings_note),
        close = true,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTopClearance.current)
                .navigationBarsPadding()
                .padding(bottom = Space.xl),
        ) {
            SegmentedGroup(
                stringResource(R.string.widget_heatmap_show),
                stringResource(R.string.widget_heatmap_settings_hint),
                HeatSource.entries.map { source ->
                    { shapes ->
                        val (icon, title, supporting) = when (source) {
                            HeatSource.MOMENTS -> Triple(AppIcons.Pen, R.string.widget_heatmap_moments, R.string.widget_heatmap_moments_note)
                            HeatSource.SPENDING -> Triple(AppIcons.Receipt, R.string.widget_heatmap_spending, R.string.widget_heatmap_spending_note)
                            HeatSource.FOOTPRINTS -> Triple(AppIcons.Footprints, R.string.widget_heatmap_footprints, R.string.widget_heatmap_footprints_note)
                        }
                        val selected = source == chosen
                        SegmentedListItem(
                            onClick = { onChoose(source) },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(icon, contentDescription = null) },
                            supportingContent = { AppText(stringResource(supporting)) },
                            trailingContent = if (selected) ({ Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }) else null,
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = selected
                            }.testTag("heatmap_${source.key}"),
                        ) { AppText(stringResource(title)) }
                    }
                },
            )
        }
    }
}
