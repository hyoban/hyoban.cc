package cc.hyoban.moments.ui.occasions

import cc.hyoban.moments.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.occasions.Count
import cc.hyoban.moments.data.occasions.IcuLunar
import cc.hyoban.moments.data.occasions.Lunar
import cc.hyoban.moments.data.occasions.LunarDay
import cc.hyoban.moments.data.occasions.Occasion
import cc.hyoban.moments.data.occasions.OccasionEdit
import cc.hyoban.moments.data.occasions.Occasions
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.settings.PageCaption
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.things.APART
import cc.hyoban.moments.ui.things.PageColumn
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch
import java.time.LocalDate

// 纪念日, a page under 我: the days that matter, the one coming soonest first, each with how far
// off it is and what it will be (生日 27 岁, 提车 1 周年), then the days that have passed. Each
// is an entry; a change waits on the phone with the rest and goes out with 发布更改. Deeper than
// the page goes, an occasion opens as a sheet.

/** What an occasion is called on the day it comes round: its name with its age or years, or the first day's own name. */
@Composable
fun occasionLabel(occasion: Occasion, on: LocalDate): String {
    val years = occasion.years(on)
    return when {
        occasion.count == Count.AGE -> stringResource(Res.string.occasion_age, occasion.name, years)
        years == 0 -> occasion.firstLabel ?: occasion.name
        occasion.count == Count.YEARS -> stringResource(Res.string.occasion_years, occasion.name, years)
        else -> occasion.name
    }
}

/** How far off [day] is from [today]: today, in so many days, or so many days ago. */
@Composable
fun occasionDistance(today: LocalDate, day: LocalDate): String {
    val days = Occasions.daysUntil(today, day)
    return when {
        days == 0L -> stringResource(Res.string.occasion_today)
        days > 0 -> stringResource(Res.string.occasion_in_days, days)
        else -> stringResource(Res.string.occasion_days_ago, -days)
    }
}

/** A lunar day in words, 八月十六 or 闰四月初三. */
@Composable
fun lunarDayName(day: LunarDay): String {
    val months = stringArrayResource(Res.array.lunar_months)
    val days = stringArrayResource(Res.array.lunar_days)
    val leap = if (day.leap) stringResource(Res.string.lunar_leap) else ""
    return leap + months.getOrElse(day.month - 1) { day.month.toString() } + days.getOrElse(day.day - 1) { day.day.toString() }
}

/** The occasion coming soonest, as the row on 我 says it: what it will be and how far off. */
@Composable
fun nextOccasionSummary(container: PhoneContainer, lunar: Lunar = IcuLunar): String? {
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val next = remember(entries) { Occasions.of(entries.values, today, lunar).firstOrNull { it.next(today, lunar) != null } } ?: return null
    val day = next.next(today, lunar) ?: return null
    return occasionLabel(next, day) + APART + occasionDistance(today, day)
}

/** 纪念日: the days coming, soonest first, and the days that have passed. */
@Composable
fun OccasionsScreen(container: PhoneContainer, onBack: () -> Unit, open: String? = null, lunar: Lunar = IcuLunar) {
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val all = remember(entries) { Occasions.of(entries.values, today, lunar) }
    var editing by remember { mutableStateOf(all.firstOrNull { it.uid == open }) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val (coming, passed) = all.partition { it.next(today, lunar) != null }
    SubPage(
        stringResource(Res.string.occasions_title),
        onBack,
        Modifier.background(AppColors.page),
        actions = {
            IconButton(onClick = { adding = true }, modifier = Modifier.testTag("occasion_add")) {
                Icon(AppIcons.Plus, contentDescription = stringResource(Res.string.occasion_add))
            }
        },
    ) {
        PageColumn {
            if (all.isEmpty()) {
                Text(stringResource(Res.string.occasions_empty), color = tokens.tx2, modifier = Modifier.padding(Space.gutter))
                return@PageColumn
            }
            if (coming.isNotEmpty()) {
                PageCaption(stringResource(Res.string.occasions_coming))
                coming.forEach { occasion ->
                    val day = occasion.next(today, lunar)!!
                    OccasionRow(occasionLabel(occasion, day), occasionWhen(occasion, day, today, lunar), occasionDistance(today, day), occasion.uid) { editing = occasion }
                }
            }
            if (passed.isNotEmpty()) {
                PageCaption(stringResource(Res.string.occasions_passed))
                passed.forEach { occasion ->
                    OccasionRow(occasionLabel(occasion, occasion.first), formatDate(occasion.first.toString(), today), occasionDistance(today, occasion.first), occasion.uid, dim = true) { editing = occasion }
                }
            }
            Text(
                stringResource(Res.string.occasions_note),
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
            )
        }
    }
    if (adding || editing != null) {
        OccasionSheet(container, editing, lunar) {
            adding = false
            editing = null
        }
    }
}

/** When an occasion comes round: its day this time, and for one that repeats, by which calendar. */
@Composable
private fun occasionWhen(occasion: Occasion, day: LocalDate, today: LocalDate, lunar: Lunar): String {
    val date = formatDate(day.toString(), today)
    return when {
        !occasion.repeat -> date
        occasion.lunar -> date + APART + stringResource(Res.string.occasion_each_lunar, lunarDayName(occasion.lunarDay(lunar)))
        else -> date + APART + stringResource(Res.string.occasion_each_year)
    }
}

/** A row on the page: what the day will be, when, and how far off at its end; tapped, it opens. */
@Composable
private fun OccasionRow(title: String, supporting: String, distance: String, uid: String, dim: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = if (dim) tokens.tx2 else tokens.tx) },
        supportingContent = { Text(supporting, color = tokens.tx2) },
        trailingContent = { Text(distance, style = AppTheme.typography.body, color = if (dim) tokens.tx2 else tokens.tx) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("occasion_$uid"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OccasionSheet(container: PhoneContainer, occasion: Occasion?, lunar: Lunar, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(occasion?.name.orEmpty()) }
    var first by rememberSaveable { mutableStateOf((occasion?.first ?: today).toString()) }
    var repeat by rememberSaveable { mutableStateOf(occasion?.repeat ?: true) }
    var byLunar by rememberSaveable { mutableStateOf(occasion?.lunar == true) }
    var count by rememberSaveable { mutableStateOf(occasion?.count ?: Count.YEARS) }
    var firstLabel by rememberSaveable { mutableStateOf(occasion?.firstLabel.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(occasion?.note.orEmpty()) }
    var public by rememberSaveable { mutableStateOf(occasion?.private != true) }
    var picking by remember { mutableStateOf(false) }
    val valid = name.isNotBlank()
    val firstDay = LocalDate.parse(first)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Text(occasion?.name ?: stringResource(Res.string.occasion_add), style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.occasion_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("occasion_name"))
                Column(
                    Modifier.fillMaxWidth().clip(AppTheme.shapes.medium).clickable { picking = true }.padding(vertical = Space.xs).testTag("occasion_date"),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(stringResource(if (repeat) Res.string.occasion_first_day else Res.string.occasion_date), style = AppTheme.typography.caption, color = tokens.tx2)
                    Text(
                        formatDate(first, today) + if (repeat && byLunar) APART + lunarDayName(lunar.of(firstDay)) else "",
                        style = AppTheme.typography.body,
                    )
                }
                if (repeat) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        listOf(
                            Count.YEARS to Res.string.occasion_count_years,
                            Count.AGE to Res.string.occasion_count_age,
                            Count.NONE to Res.string.occasion_count_none,
                        ).forEach { (option, label) ->
                            FilterChip(selected = count == option, onClick = { count = option }, label = { Text(stringResource(label)) })
                        }
                    }
                }
            }
            SegmentedGroup(
                null,
                null,
                listOfNotNull(
                    { shapes -> SwitchRow(repeat, AppIcons.Refresh, stringResource(Res.string.occasion_repeat), "occasion_repeat", shapes) { repeat = !repeat } },
                    if (repeat) { shapes -> SwitchRow(byLunar, AppIcons.Calendar, stringResource(Res.string.occasion_lunar), "occasion_lunar", shapes) { byLunar = !byLunar } } else null,
                    { shapes -> SwitchRow(public, AppIcons.Eye, stringResource(Res.string.visibility_public), "occasion_public", shapes, supporting = stringResource(Res.string.occasion_public_note)) { public = !public } },
                ),
            )
            Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (repeat && count != Count.AGE) {
                    OutlinedTextField(
                        firstLabel, { firstLabel = it },
                        label = { Text(stringResource(Res.string.occasion_first_label)) },
                        placeholder = { Text(name.ifBlank { stringResource(Res.string.occasion_name) }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.occasion_note)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (occasion != null) {
                        TextButton(onClick = { scope.launch { container.repository.deleteEntry(occasion.uid); onDismiss() } }, modifier = Modifier.testTag("occasion_delete")) {
                            Text(stringResource(Res.string.action_delete), color = tokens.danger)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Button(
                        enabled = valid,
                        onClick = {
                            val edit = OccasionEdit(
                                name = name.trim(),
                                first = firstDay,
                                repeat = repeat,
                                lunar = byLunar,
                                count = count,
                                firstLabel = firstLabel.trim(),
                                note = note.trim(),
                                private = !public,
                            )
                            scope.launch {
                                container.repository.saveEntry(Occasions.occasionEntry(occasion?.entry, edit, recordTime()))
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("occasion_save"),
                    ) { Text(stringResource(Res.string.action_save)) }
                }
            }
        }
    }
    if (picking) {
        DateTimeDialog(firstDay, 0, 0, onDismiss = { picking = false }) { picked, _, _ ->
            first = picked.toString()
            picking = false
        }
    }
}
