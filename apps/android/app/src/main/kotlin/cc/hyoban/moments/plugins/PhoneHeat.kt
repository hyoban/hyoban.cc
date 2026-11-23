package cc.hyoban.moments.plugins

import android.content.Context
import androidx.compose.runtime.Composable
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.trackStats
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.widget.HeatSource
import java.text.NumberFormat
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private suspend fun phone(context: Context) =
    (context.applicationContext as MomentsApplication).container.also { it.repository.loaded.first { loaded -> loaded } }

/** What the ledger's bills spent each day, in the book's currency, drawn only as deep or light; none while the ledger is off. */
internal object SpendingHeat : HeatSource {
    override val key = "spending"
    override val link = "ledger"
    override val icon get() = AppIcons.Receipt

    override fun name(context: Context): String = textOf(Res.string.widget_heatmap_spending)

    @Composable override fun title(): String = stringResource(Res.string.widget_heatmap_spending)

    @Composable override fun note(): String = stringResource(Res.string.widget_heatmap_spending_note)

    override suspend fun days(context: Context, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val container = phone(context)
        if (!container.settings.isOn(Module.LEDGER)) return emptyMap()
        container.ledger.loaded.first { it }
        val ledger = container.ledger.ledger.value
        val currency = ledger.book?.currency ?: return emptyMap()
        val books = ledger.books.filter { !it.archived }.map { it.id }.toSet()
        return summarize(ledger.entries.filter { it.txn.book in books }, ledger.rates, currency, from, to.plusDays(1))
            .expenseByDay.mapValues { it.value.toDouble() }
    }
}

/** How far the footprints went each day, in metres; none while they are off. */
internal object FootprintsHeat : HeatSource {
    override val key = "footprints"
    override val link = "footprints"
    override val icon get() = AppIcons.Footprints

    override fun name(context: Context): String = textOf(Res.string.widget_heatmap_footprints)

    @Composable override fun title(): String = stringResource(Res.string.widget_heatmap_footprints)

    @Composable override fun note(): String = stringResource(Res.string.widget_heatmap_footprints_note)

    override suspend fun days(context: Context, from: LocalDate, to: LocalDate): Map<LocalDate, Double> = withContext(Dispatchers.IO) {
        val container = phone(context)
        if (!container.settings.isOn(Module.FOOTPRINTS)) return@withContext emptyMap<LocalDate, Double>()
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

    /** How far they went in all, in kilometres. */
    override fun amount(shown: Collection<Double>): String {
        val km = shown.sum() / 1000
        val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (km < 10) 1 else 0 }
        return textOf(Res.string.widget_heatmap_km, format.format(km))
    }
}

private val PastDistances = ConcurrentHashMap<String, Double>()
