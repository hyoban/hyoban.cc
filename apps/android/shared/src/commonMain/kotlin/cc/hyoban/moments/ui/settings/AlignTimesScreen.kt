package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.TimeFix
import cc.hyoban.moments.data.local.checkinFix
import cc.hyoban.moments.data.local.momentFix
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.TrackStay
import cc.hyoban.moments.footprints.trackParts
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.shownCheckins
import cc.hyoban.moments.plugins.nas
import cc.hyoban.moments.nas.NasMatch
import cc.hyoban.moments.nas.takenTimes
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.groupedShapes
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.ZoneId

/**
 * 对齐时间: every record whose time its photos or the footprints say otherwise, a Moment by its
 * photos and a check-in by the stay it was at, each with its time now and what it would be. Those
 * left on are changed together as the phone's changes, published only through 全部发布.
 */
@Composable
fun AlignTimesScreen(container: AppContainer, onBack: () -> Unit) {
    var fixes by remember { mutableStateOf<List<TimeFix>?>(null) }
    var skipped by remember { mutableStateOf(emptySet<String>()) }
    var applying by remember { mutableStateOf(false) }
    // How far reading the NAS has got: its stage, done of total.
    var reading by remember { mutableStateOf<Triple<NasMatch.Stage, Int, Int>?>(null) }
    val scope = rememberCoroutineScope()
    val find: suspend () -> List<TimeFix> = { withContext(Dispatchers.Default) { timeFixes(container) { stage, done, total -> reading = Triple(stage, done, total) } } }
    LaunchedEffect(Unit) { fixes = find() }

    SubPage(stringResource(Res.string.align_times_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            LazyColumn(
                Modifier.fillMaxSize().testTag("align_times"),
                contentPadding = PaddingValues(
                    start = columns.margin,
                    end = columns.margin,
                    top = LocalTopClearance.current,
                    bottom = Space.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                val found = fixes
                when {
                    found == null -> item(key = "finding") {
                        val supporting = reading?.let { (stage, done, total) ->
                            when {
                                total == 0 -> stringResource(Res.string.nas_match_starting)
                                stage == NasMatch.Stage.DAYS -> stringResource(Res.string.nas_match_reading, done + 1, total)
                                else -> stringResource(Res.string.nas_match_comparing, done, total)
                            }
                        }
                        SettingsGroupView(SettingsGroupSpec(null, null, listOf(SettingsRow.Status(AppIcons.Clock, stringResource(Res.string.align_times_finding), "align_times_finding", supporting = supporting, busy = true))))
                    }
                    found.isEmpty() -> item(key = "none") {
                        SettingsGroupView(SettingsGroupSpec(null, null, listOf(SettingsRow.Status(AppIcons.Check, stringResource(Res.string.align_times_none), "align_times_none"))))
                    }
                    else -> {
                        val toggle = { uid: String -> skipped = if (uid in skipped) skipped - uid else skipped + uid }
                        fixRows("moments", Res.string.align_times_moments, found.filter { !it.checkin }, skipped, toggle)
                        fixRows("checkins", Res.string.align_times_checkins, found.filter { it.checkin }, skipped, toggle)
                        item(key = "apply") {
                            val chosen = found.filter { it.uid !in skipped }
                            Button(
                                onClick = {
                                    applying = true
                                    scope.launch {
                                        applyTimeFixes(container, chosen)
                                        // What is left, those left off, to look at again.
                                        fixes = find()
                                        applying = false
                                    }
                                },
                                enabled = chosen.isNotEmpty() && !applying,
                                modifier = Modifier.padding(horizontal = Space.gutter).padding(top = Space.xl).fillMaxWidth().testTag("align_times_apply"),
                            ) { Text(pluralStringResource(Res.plurals.align_times_apply, chosen.size, chosen.size)) }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.fixRows(key: String, title: org.jetbrains.compose.resources.StringResource, rows: List<TimeFix>, skipped: Set<String>, toggle: (String) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "$key-caption") { Caption(stringResource(title)) }
    itemsIndexed(rows, key = { _, fix -> fix.uid }) { index, fix ->
        val row = SettingsRow.Switch(
            if (fix.checkin) AppIcons.Pin else AppIcons.Image,
            fix.name.ifBlank { formatDate(fix.at) },
            "align_${fix.uid}",
            checked = fix.uid !in skipped,
            supporting = stringResource(Res.string.align_times_change, when_(fix.at, fix.end), when_(fix.newAt, fix.newEnd)),
        ) { toggle(fix.uid) }
        SettingsRowView(row, groupedShapes(index, rows.size))
    }
}

/** `10月6日 14:05–16:30`, the end's day too when it is another. */
private fun when_(at: String, end: String?): String {
    val start = listOfNotNull(formatDate(at), formatTime(at)).joinToString(" ")
    end ?: return start
    val until = if (end.take(10) == at.take(10)) formatTime(end) else listOfNotNull(formatDate(end), formatTime(end)).joinToString(" ")
    return "$start–$until"
}

/**
 * Every record shown whose time its photos or, for a check-in, the footprints' stays say otherwise;
 * photos that say no time of their own take the NAS's, once it is set, [progress] saying how far
 * reading it has got.
 */
suspend fun timeFixes(container: AppContainer, progress: (NasMatch.Stage, Int, Int) -> Unit = { _, _, _ -> }): List<TimeFix> {
    val settings = container.settings
    val shown = container.shownMoments.value.filter { it.planned != true }
    val nas = container.nas.takeIf { it.configured }?.let { nas ->
        runCatching { nas.takenTimes(shown, container::smallStill, progress) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; cc.hyoban.moments.logWarning("Moments", "nas: photo times not read", it) }
            .getOrNull()
    }.orEmpty()
    val moments = shown.mapNotNull { momentFix(it, nas[it.uid].orEmpty()) }
    val footprints = Plugins.dayTracks(container) { settings.isOn(it) }
    if (footprints == null || !settings.isOn(Module.CHECKIN)) return moments.sortedByDescending { it.at }
    val entries = container.repository.entries.value
    val places = container.repository.places.value
    val zone = ZoneId.systemDefault()
    val recorded = footprints.days().toSet()
    val checkins = container.shownCheckins.value.all.filter { it.at.length > 10 && entries[it.uid]?.get("planned") == null }
    val fixes = checkins.groupBy { it.at.take(10) }.flatMap { (day, ofDay) ->
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@flatMap emptyList()
        // A stay may start the day before, a night at a hotel, or run into the next.
        if (listOf(date.minusDays(1), date, date.plusDays(1)).none { it.toString() in recorded }) return@flatMap emptyList()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli() - HALF_DAY_MS
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + HALF_DAY_MS
        val stays = withContext(Dispatchers.IO) { footprints.points(TrackSelection(from = from, to = to)) }
            .let(::trackParts).filterIsInstance<TrackStay>()
        if (stays.isEmpty()) return@flatMap emptyList()
        ofDay.mapNotNull { checkin ->
            val point = entries[checkin.uid]?.let(::pointOf) ?: checkin.place?.let(places::get)?.let { it.latitude to it.longitude }
            checkinFix(checkin, point, stays, zone)
        }
    }
    return (moments + fixes).sortedByDescending { it.at }
}

private fun pointOf(entry: JsonObject): Pair<Double, Double>? {
    val point = entry["point"] as? JsonObject ?: return null
    val lat = (point["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val lng = (point["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null
    return lat to lng
}

/** Keeps [fixes] as the phone's changes, each at its new time. */
suspend fun applyTimeFixes(container: AppContainer, fixes: List<TimeFix>) {
    val repository = container.repository
    val now = recordTime()
    val entries = repository.entries.value
    val changed = fixes.mapNotNull { fix ->
        val entry = if (fix.checkin) entries[fix.uid] else repository.moment(fix.uid)?.let(Entries::fromMoment)
        entry ?: return@mapNotNull null
        val timed = entry - "end" + ("at" to JsonPrimitive(fix.newAt)) + ("updatedAt" to JsonPrimitive(now))
        JsonObject(if (fix.newEnd != null) timed + ("end" to JsonPrimitive(fix.newEnd)) else timed)
    }
    container.saveEntries(changed)
}

private const val HALF_DAY_MS = 12 * 60 * 60_000L
