package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.AutoBill
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.sidekey.SideKeys
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.Preferences
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a row of 我 leads: each a page of its own, the last one down. */
class MeRoutes(
    val changes: () -> Unit,
    /** The ledger on its tab: 0 the bills, 1 the sums, 2 the accounts. */
    val ledger: (Int) -> Unit,
    val footprints: () -> Unit,
    val items: () -> Unit,
    val sync: () -> Unit,
    val ledgerSettings: () -> Unit,
    val footprintSettings: () -> Unit,
    val sideKey: () -> Unit,
)

/**
 * The 我 tab: the changes waiting to be published, on a card at the top; the ledger's month;
 * the bills and the footprints; then the settings in two parts, what is general and what stays
 * on this phone, each row a page with what it is set to under its name. Under 我 there is one
 * level of pages and no more: what goes deeper opens over them, as a sheet or a dialog.
 * Nothing is switched on this page itself.
 */
@Composable
fun MeScreen(container: AppContainer, routes: MeRoutes) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    val shares by produceState(0, revision) {
        value = withContext(Dispatchers.IO) { container.footprints.shares().count { !it.removed } }
    }
    val context = LocalContext.current
    val autoBill by AutoBill.enabledFlow(context).collectAsStateWithLifecycle()
    val sideKey by SideKeys.state.collectAsStateWithLifecycle()
    val books = ledger.books.filter { !it.archived }
    TabPage(stringResource(R.string.tab_me), tab = Preferences) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = columns.margin)
                    .padding(bottom = LocalBottomClearance.current + Space.xl),
            ) {
                if (pending.size > 0) PublishCard(pending.size, routes.changes)
                MonthSpending(container) { routes.ledger(1) }
                PageRows(Modifier.padding(top = Space.s)) { shapes ->
                    MeRow(AppIcons.Wallet, stringResource(R.string.me_ledger), "me_ledger", shapes) { routes.ledger(0) }
                    MeRow(AppIcons.Footprints, stringResource(R.string.footprints_title), "me_footprints", shapes, onClick = routes.footprints)
                }
                PageCaption(stringResource(R.string.me_general))
                PageRows { shapes ->
                    MeRow(
                        AppIcons.Eye, stringResource(R.string.me_items), "settings_items", shapes,
                        supporting = stringResource(R.string.me_items_public, books.count { it.visibility == "public" }, shares),
                        onClick = routes.items,
                    )
                    MeRow(
                        AppIcons.CloudSync, stringResource(R.string.sync_title), "settings_sync", shapes,
                        supporting = stringResource(if (container.settings.apiToken.isSet) R.string.me_sync_token_set else R.string.me_sync_token_unset),
                        onClick = routes.sync,
                    )
                }
                PageCaption(stringResource(R.string.me_on_phone))
                PageRows { shapes ->
                    MeRow(
                        AppIcons.Receipt, stringResource(R.string.ledger_settings_title), "settings_ledger", shapes,
                        supporting = listOfNotNull(
                            pluralStringResource(R.plurals.me_ledger_books, books.size, books.size),
                            stringResource(R.string.me_auto_bill_on).takeIf { autoBill == true },
                        ).joinToString(" "),
                        onClick = routes.ledgerSettings,
                    )
                    MeRow(AppIcons.Route, stringResource(R.string.footprint_settings_title), "settings_footprints", shapes, onClick = routes.footprintSettings)
                    MeRow(
                        AppIcons.Phone, stringResource(R.string.side_key_title), "settings_sidekey", shapes,
                        supporting = stringResource(sideKey.phase.label),
                        onClick = routes.sideKey,
                    )
                }
                Credits()
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
 * This month's spending, straight on the page under the ledger's name and the book's: the
 * amount large in figures, and a thin bar for each day, the days still to come in grey.
 * Tapped, the ledger's sums.
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
        Row(Modifier.padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.tab_ledger), style = AppTheme.typography.body.strong, modifier = Modifier.semantics { heading() })
            Text(
                if (ledger.allBooks) stringResource(R.string.ledger_all_books) else book.name,
                style = AppTheme.typography.caption,
                color = tokens.tx3,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3)
        }
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

/** A row of 我 opening a page: its icon on the gutter, its name, what it is set to under it, an arrow. */
@Composable
private fun MeRow(icon: ImageVector, title: String, tag: String, shapes: ListItemShapes, supporting: String? = null, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3) },
        modifier = Modifier.testTag(tag),
    ) { Text(title) }
}

/** The app and its version at the foot of the page, and 馒头, the cat the icon is drawn from. */
@Composable
private fun Credits() {
    Row(
        Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Image(painterResource(R.drawable.mantou), contentDescription = null, Modifier.size(20.dp).clip(CircleShape))
        Text(
            stringResource(R.string.me_footer, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
            style = AppTheme.typography.caption,
            color = tokens.tx3,
        )
    }
}
