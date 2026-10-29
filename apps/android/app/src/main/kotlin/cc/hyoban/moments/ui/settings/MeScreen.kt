package cc.hyoban.moments.ui.settings

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
    onSection: (SettingsSection) -> Unit,
    onLedgerSettings: () -> Unit,
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
                PublishCard(pending.size, onChanges)
                LedgerGroup(container, onLedger)
                SegmentedGroup(
                    stringResource(R.string.title_settings),
                    null,
                    listOf(
                        { shapes -> SectionRow(AppIcons.Palette, stringResource(R.string.section_theme_mode), "settings_appearance", shapes) { onSection(SettingsSection.APPEARANCE) } },
                        { shapes -> SectionRow(AppIcons.CloudSync, stringResource(R.string.settings_moments_data), "settings_moments", shapes) { onSection(SettingsSection.MOMENTS) } },
                        { shapes -> SectionRow(AppIcons.Map, stringResource(R.string.settings_map_footprints), "settings_map", shapes) { onSection(SettingsSection.MAP) } },
                        { shapes -> SectionRow(AppIcons.Receipt, stringResource(R.string.tab_ledger), "settings_ledger", shapes, onClick = onLedgerSettings) },
                        { shapes -> SectionRow(AppIcons.Info, stringResource(R.string.settings_about), "settings_about", shapes) { onSection(SettingsSection.ABOUT) } },
                    ),
                )
            }
        }
    }
}

/**
 * The changes waiting on the phone, on the surface with its hairline: how many, and the
 * page's one ink button to look them over and publish; with none, a line saying so.
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
            .background(SurfaceLevel.Card.fill())
            .border(cardBorder(), shape)
            .clickable(onClick = onChanges)
            .padding(Space.l)
            .testTag("settings_changes"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            if (count > 0) pluralStringResource(R.plurals.count_changes, count, count) else stringResource(R.string.me_pending_none),
            style = AppTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (count > 0) R.string.me_pending_note else R.string.me_synced_note),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            Button(
                onClick = onChanges,
                modifier = Modifier.padding(top = Space.m).testTag("review_publish"),
            ) { Text(stringResource(R.string.action_review_publish)) }
        }
    }
}

/**
 * The ledger's month at the head of its group, what the chosen book spent this month large and
 * what it earned under it, then the rows into its bills, sums and accounts; with no book yet,
 * one row to start it.
 */
@Composable
private fun LedgerGroup(container: AppContainer, onLedger: (Int) -> Unit) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val book = ledger.book
    val currency = book?.currency ?: "CNY"
    val month = remember(ledger) {
        val start = LocalDate.now().withDayOfMonth(1)
        book?.let { summarize(ledger.entries.filter { it.txn.book in ledger.shownBooks }, ledger.rates, currency, start, start.plusMonths(1)) }
    }
    val rows = if (month == null) {
        listOf<Segment>({ shapes -> SectionRow(AppIcons.Receipt, stringResource(R.string.me_start_ledger), "ledger_start", shapes, stringResource(R.string.me_start_ledger_note)) { onLedger(0) } })
    } else {
        listOf(
            boxed {
                Column(
                    Modifier.fillMaxWidth().clickable { onLedger(1) }.padding(Space.l).testTag("ledger_month"),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(stringResource(R.string.me_month_expense), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
                    Text(money(month.expense, currency), style = AppTheme.typography.displayMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        Text(stringResource(R.string.ledger_income_short, money(month.income, currency)), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            { shapes -> SectionRow(AppIcons.Receipt, stringResource(R.string.ledger_tab_bills), "ledger_bills_row", shapes) { onLedger(0) } },
            { shapes -> SectionRow(AppIcons.Chart, stringResource(R.string.ledger_tab_stats), "ledger_stats_row", shapes) { onLedger(1) } },
            { shapes -> SectionRow(AppIcons.Bank, stringResource(R.string.me_assets), "ledger_assets_row", shapes) { onLedger(2) } },
        )
    }
    SegmentedGroup(stringResource(R.string.tab_ledger), null, rows)
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
