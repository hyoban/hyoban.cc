package cc.hyoban.moments.ui.things

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.data.things.Vehicle
import cc.hyoban.moments.data.things.VehicleMonth
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.settings.PageCaption
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.text.NumberFormat
import java.util.Locale

/** The car the 车 page shows: the one device the Zeekr statistics are imported for. */
const val CAR_ID = "zeekr-007gt"

/**
 * 车: a month of the car's report at a time, its days' distances, its charges and its assisted
 * driving, all from the Zeekr import. Nothing here is edited on the phone; a charge opens its
 * day.
 */
@Composable
fun CarScreen(container: PhoneContainer, onBack: () -> Unit, onDay: (String) -> Unit, titleMenu: TitleMenu? = null) {
    val records by container.repository.records.collectAsStateWithLifecycle()
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val car = remember(entries) { Things.devices(entries.values).firstOrNull { it.id == CAR_ID } }
    val months = remember(records) { Vehicle.months(records.vehicleMonths.values, CAR_ID) }
    val assisted = remember(records) { Vehicle.assisted(records.vehicleAssisted.values, CAR_ID) }
    val charges = remember(entries) { Vehicle.charges(entries.values, CAR_ID) }
    // The newest month with a report first: the current one may only have its days so far.
    var index by rememberSaveable { mutableIntStateOf(-1) }
    val shown = index.takeIf { it in months.indices } ?: months.indexOfFirst { it.distanceMeters != null }.coerceAtLeast(0)
    val month = months.getOrNull(shown)
    SubPage(
        car?.name ?: stringResource(Res.string.car_title),
        onBack,
        Modifier.background(AppColors.page),
        titleMenu = titleMenu,
        subtitle = assisted?.let { stats ->
            listOfNotNull(
                stats.daysOwned?.let { stringResource(Res.string.car_days_owned, it.toInt()) },
                stats.beatsPercent?.let { stringResource(Res.string.car_assisted_beats, it.toInt()) },
            ).joinToString(APART).ifEmpty { null }
        },
    ) {
        PageColumn(
            figures = month?.let { shownMonth -> {
                MonthSwitch(shownMonth, hasOlder = shown < months.lastIndex, hasNewer = shown > 0, onOlder = { index = shown + 1 }, onNewer = { index = shown - 1 })
                Report(shownMonth)
            } },
        ) {
                if (month == null) {
                    Text(stringResource(Res.string.car_empty), color = tokens.tx2, modifier = Modifier.padding(Space.gutter))
                    return@PageColumn
                }
                PageCaption(stringResource(Res.string.car_daily))
                DailyBars(month)
                val monthCharges = charges.filter { it.at.year == month.month.year && it.at.monthValue == month.month.monthValue }
                if (monthCharges.isNotEmpty()) {
                    PageCaption(
                        listOf(
                            stringResource(Res.string.car_charges),
                            monthCharges.size.toString(),
                            kwh(monthCharges.sumOf { it.energyWh ?: 0 }),
                        ).joinToString(APART),
                    )
                    monthCharges.forEach { charge ->
                        ListItem(
                            headlineContent = { Text(formatDate(charge.at.toLocalDate().toString()) + " " + "%02d:%02d".format(charge.at.hour, charge.at.minute)) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        stringResource(if (charge.type == "DC") Res.string.car_dc else Res.string.car_ac),
                                        charge.durationMinutes?.let { duration(it) },
                                    ).joinToString(APART),
                                )
                            },
                            trailingContent = { Text(charge.energyWh?.let(::kwh).orEmpty(), style = AppTheme.typography.body, color = tokens.tx) },
                            modifier = Modifier.fillMaxWidth().clickable { onDay(charge.at.toLocalDate().toString()) }.testTag("car_charge"),
                        )
                    }
                }
                month.assisted?.let { stats ->
                    PageCaption(listOfNotNull(stringResource(Res.string.car_assisted), stats.distanceMeters?.let(::km)).joinToString(APART))
                    Line(stringResource(Res.string.car_nzp), listOfNotNull(stats.nzpMeters?.let(::km), stats.nzpPercent?.let { "$it%" }).joinToString(APART))
                    Line(stringResource(Res.string.car_acc_lcc), listOfNotNull(stats.accLccMeters?.let(::km), stats.accLccPercent?.let { "$it%" }).joinToString(APART))
                    stats.parkingCount?.let { count ->
                        Line(stringResource(Res.string.car_parking), stringResource(Res.string.car_parking_value, count.toInt(), (stats.parkingAverageSeconds ?: 0).toInt()))
                    }
                }
                month.capturedAt?.let { captured ->
                    Text(
                        stringResource(Res.string.car_imported, formatDate(captured.toLocalDate().toString())),
                        style = AppTheme.typography.caption,
                        color = tokens.tx3,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
                    )
                }
        }
    }
}

@Composable
private fun MonthSwitch(month: VehicleMonth, hasOlder: Boolean, hasNewer: Boolean, onOlder: () -> Unit, onNewer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.car_report, month.month.monthValue), style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.weight(1f))
        IconButton(onClick = onOlder, enabled = hasOlder, modifier = Modifier.testTag("car_older")) {
            Icon(AppIcons.ChevronLeft, contentDescription = stringResource(Res.string.car_previous_month))
        }
        Text("%d.%02d".format(month.month.year, month.month.monthValue), style = AppTheme.typography.caption, color = tokens.tx2)
        IconButton(onClick = onNewer, enabled = hasNewer, modifier = Modifier.testTag("car_newer")) {
            Icon(AppIcons.ChevronRight, contentDescription = stringResource(Res.string.car_next_month))
        }
    }
}

@Composable
private fun Report(month: VehicleMonth) {
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Figures(
            Triple(stringResource(Res.string.car_distance), month.distanceMeters?.let(::km) ?: "—", month.distanceBeatsPercent?.let { stringResource(Res.string.car_beats, it.toInt()) }),
            Triple(stringResource(Res.string.car_energy), month.energyWh?.let(::kwh) ?: "—", null),
            Triple(stringResource(Res.string.car_consumption), month.whPer100Km?.let { "%.1f".format(it / 1000.0) } ?: "—", "kWh/100 km".takeIf { month.whPer100Km != null }),
        )
        Figures(
            Triple(stringResource(Res.string.car_trips), month.tripCount?.toString() ?: "—", null),
            Triple(stringResource(Res.string.car_driving), month.durationSeconds?.let { duration(it / 60) } ?: "—", null),
            Triple(stringResource(Res.string.car_longest), month.longestTripMeters?.let(::km) ?: "—", month.longestTripSeconds?.let { duration(it / 60) }),
        )
    }
}

/** A bar for each day of the month by how far it went, the days not driven a stub in grey. */
@Composable
private fun DailyBars(month: VehicleMonth) {
    val accent = tokens.accent
    val stub = tokens.border
    val byDay = month.days.associate { it.date.dayOfMonth to it.distanceMeters }
    val most = (byDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val longest = month.days.maxByOrNull { it.distanceMeters }
    val label = longest?.let { "${formatDate(it.date.toString())} ${km(it.distanceMeters)}" }.orEmpty()
    Canvas(
        Modifier
            .padding(horizontal = Space.gutter)
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = label },
    ) {
        val days = month.month.lengthOfMonth()
        val slot = size.width / days
        val bar = slot * 0.7f
        for (day in 1..days) {
            val meters = byDay[day] ?: 0L
            val height = maxOf(2.dp.toPx(), size.height * meters / most)
            drawRect(
                if (meters > 0) accent else stub,
                topLeft = Offset(slot * (day - 1) + (slot - bar) / 2, size.height - height),
                size = Size(bar, height),
            )
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = { Text(value, style = AppTheme.typography.body, color = tokens.tx) },
    )
}

private val figures = NumberFormat.getIntegerInstance(Locale.ROOT).apply { isGroupingUsed = true }

private fun km(meters: Long): String = "${figures.format(meters / 1000)} km"

private fun kwh(wh: Long): String = "%.1f kWh".format(wh / 1000.0)

/** `3 小时 25 分钟`, or `25 分钟`. */
@Composable
private fun duration(minutes: Long): String =
    if (minutes >= 60) stringResource(Res.string.footprints_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    else stringResource(Res.string.footprints_minutes, minutes.toInt())
