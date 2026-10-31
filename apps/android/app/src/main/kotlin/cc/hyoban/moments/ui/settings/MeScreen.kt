package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.ui.design.strong
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.HorizontalDivider
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.Preferences
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate

/**
 * The 我 tab: what the app holds that the site doesn't yet, the changes waiting to be
 * published, on a card at the top; the ledger's month and the ways into its bills, sums and
 * accounts; then the settings, a row for each section opening its page. Nothing is switched
 * on this page itself.
 */
@Composable
fun MeScreen(
    container: AppContainer,
    onChanges: () -> Unit,
    /** Opens the ledger on its tab: 0 the bills, 1 the sums, 2 the accounts. */
    onLedger: (Int) -> Unit,
    onFootprints: () -> Unit,
    onItems: () -> Unit,
    onSettings: () -> Unit,
) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    TabPage(stringResource(R.string.tab_me), tab = Preferences) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = columns.margin)
                    .padding(bottom = LocalBottomClearance.current + Space.xl),
            ) {
                if (pending.size > 0) PublishCard(pending.size, onChanges)
                MonthSpending(container) { onLedger(1) }
                SegmentedGroup(
                    null,
                    null,
                    listOf(
                        { shapes -> SectionRow(AppIcons.Receipt, stringResource(R.string.me_ledger), "me_ledger", shapes) { onLedger(0) } },
                        { shapes -> SectionRow(AppIcons.Footprints, stringResource(R.string.footprints_title), "me_footprints", shapes, onClick = onFootprints) },
                        { shapes -> SectionRow(AppIcons.Eye, stringResource(R.string.me_items), "settings_items", shapes, onClick = onItems) },
                        { shapes -> SectionRow(AppIcons.Settings, stringResource(R.string.title_settings), "me_settings", shapes, onClick = onSettings) },
                    ),
                )
            }
        }
    }
}

/** The settings, a row for each section opening its page: what 我 held before its four rows. */
@Composable
fun SettingsHomeScreen(onBack: () -> Unit, onSection: (SettingsSection) -> Unit, onLedgerSettings: () -> Unit, onSideKeySettings: () -> Unit) {
    cc.hyoban.moments.ui.components.SubPage(stringResource(R.string.title_settings), onBack) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                SegmentedGroup(
                    null,
                    null,
                    listOf(
                        { shapes -> SectionRow(AppIcons.CloudSync, stringResource(R.string.settings_moments_data), "settings_moments", shapes) { onSection(SettingsSection.MOMENTS) } },
                        { shapes -> SectionRow(AppIcons.Map, stringResource(R.string.settings_map_footprints), "settings_map", shapes) { onSection(SettingsSection.MAP) } },
                        { shapes -> SectionRow(AppIcons.Receipt, stringResource(R.string.tab_ledger), "settings_ledger", shapes, onClick = onLedgerSettings) },
                        { shapes -> SectionRow(AppIcons.Sliders, stringResource(R.string.side_key_title), "settings_sidekey", shapes, onClick = onSideKeySettings) },
                        { shapes -> SectionRow(AppIcons.Info, stringResource(R.string.settings_about), "settings_about", shapes) { onSection(SettingsSection.ABOUT) } },
                    ),
                )
            }
        }
    }
}

/**
 * The changes waiting on the phone, the page's one block on the accent's soft tint, only while
 * there are any: how many, and the page's ink button to look them over and publish.
 */
@Composable
private fun PublishCard(count: Int, onChanges: () -> Unit) {
    val shape = AppTheme.shapes.large
    Column(
        Modifier
            .padding(horizontal = Space.gutter)
            .padding(top = Space.s)
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.accentSoft)
            .clickable(onClick = onChanges)
            .padding(Space.l)
            .testTag("settings_changes"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            pluralStringResource(R.plurals.count_changes, count, count),
            style = AppTheme.typography.body.strong,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.me_pending_note), style = AppTheme.typography.body, color = tokens.tx2)
        Button(onClick = onChanges, modifier = Modifier.padding(top = Space.m).testTag("review_publish")) {
            Text(stringResource(R.string.action_review_publish))
        }
    }
}

/**
 * This month's spending, straight on the page: the amount large in figures, and a thin bar for
 * each day, the days still to come in grey. Tapped, the ledger's sums.
 */
@Composable
private fun MonthSpending(container: AppContainer, onOpen: () -> Unit) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val book = ledger.book ?: return
    val currency = book.currency
    val today = LocalDate.now()
    val start = today.withDayOfMonth(1)
    val month = remember(ledger) {
        summarize(ledger.entries.filter { it.txn.book in ledger.shownBooks }, ledger.rates, currency, start, start.plusMonths(1))
    }
    val accent = tokens.accent
    val future = tokens.border
    Column(
        Modifier
            .padding(horizontal = Space.gutter)
            .padding(top = Space.xl, bottom = Space.s)
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.ledger_tab_stats), onClick = onOpen)
            .testTag("ledger_month"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(stringResource(R.string.me_month_expense), style = AppTheme.typography.caption, color = tokens.tx2)
        Text(money(month.expense, currency), style = AppTheme.typography.titlePage)
        val days = start.lengthOfMonth()
        val most = (month.expenseByDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(40.dp).padding(top = Space.s)) {
            val slot = size.width / days
            val bar = slot * 0.5f
            for (day in 1..days) {
                val date = start.withDayOfMonth(day)
                val spent = month.expenseByDay[date] ?: 0L
                val height = if (date > today) 2.dp.toPx() else maxOf(2.dp.toPx(), size.height * spent / most)
                drawRect(
                    if (date > today || spent == 0L) future else accent,
                    topLeft = androidx.compose.ui.geometry.Offset(slot * (day - 1) + (slot - bar) / 2, size.height - height),
                    size = androidx.compose.ui.geometry.Size(bar, height),
                )
            }
        }
    }
}

/** A row opening a page: its icon, its name, what it holds under it, an arrow. */
@Composable
private fun SectionRow(icon: ImageVector, title: String, tag: String, shapes: ListItemShapes, supporting: String? = null, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3) },
        modifier = Modifier.testTag(tag),
    ) { Text(title) }
}
