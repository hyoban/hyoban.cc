package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.sidekey.SideKeys
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.Preferences
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.ledger.DemoButton
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.things.APART
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
    val things: () -> Unit,
    val items: () -> Unit,
    val homes: () -> Unit,
    val sync: () -> Unit,
    val imports: () -> Unit,
    val sideKey: () -> Unit,
)

/**
 * The 我 tab, one screen: the changes waiting to be published, on a card at the top; three rows
 * of what is kept here, the ledger with this month's spending under its name, the footprints and
 * the things; then one group of settings, each row a page with what it is set to under its name.
 * Under 我 there is one level of pages and no more: what goes deeper opens over them, as a sheet
 * or a dialog. The ledger's and the footprints' own settings are reached from their own pages,
 * not from here. Nothing is switched on this page itself.
 */
@Composable
fun MeScreen(container: AppContainer, routes: MeRoutes) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    // How many spans of the footprints are public, and whether they are being recorded now.
    val footprints by produceState(0 to false, revision) {
        value = withContext(Dispatchers.IO) {
            container.footprints.shares().count { !it.removed } to container.footprints.state().recording
        }
    }
    val sideKey by SideKeys.state.collectAsStateWithLifecycle()
    val connected = LocalSettings.current.connected
    val books = ledger.books.filter { !it.archived }
    // This month's spending in the shown books, in the book's currency, under the ledger's name.
    val month = remember(ledger) {
        ledger.book?.let { book ->
            val start = LocalDate.now().withDayOfMonth(1)
            summarize(ledger.entries.filter { it.txn.book in ledger.shownBooks }, ledger.rates, book.currency, start, start.plusMonths(1)).expense to book.currency
        }
    }
    // On its side, one scroll in two columns: what is waiting and what is kept here at the start,
    // the settings beside them (H15).
    val sideways = isPhoneLandscape()
    val first: @Composable () -> Unit = {
        // With no API to publish to, what is kept here is simply the phone's.
        if (pending.size > 0 && container.canPublish) PublishCard(pending.size, routes.changes)
        PageRows(Modifier.padding(top = Space.s)) { shapes ->
            MeRow(
                AppIcons.Wallet, stringResource(R.string.me_ledger), "me_ledger", shapes,
                supporting = month?.let { (expense, currency) -> stringResource(R.string.me_month_expense) + APART + money(expense, currency) },
            ) { routes.ledger(0) }
            MeRow(
                AppIcons.Footprints, stringResource(R.string.footprints_title), "me_footprints", shapes,
                supporting = stringResource(R.string.me_footprints_recording).takeIf { footprints.second },
                onClick = routes.footprints,
            )
            // The car's data comes only from an import into the API, so without one there is none.
            MeRow(
                AppIcons.Package, stringResource(R.string.me_things), "me_things", shapes,
                supporting = listOfNotNull(
                    stringResource(R.string.devices_title),
                    stringResource(R.string.subscriptions_title),
                    stringResource(R.string.car_title).takeIf { connected },
                ).joinToString(stringResource(R.string.separator_list)),
                onClick = routes.things,
            )
        }
    }
    val second: @Composable () -> Unit = {
        PageCaption(stringResource(R.string.me_settings))
        PageRows { shapes ->
            MeRow(
                AppIcons.Eye, stringResource(R.string.me_items), "settings_items", shapes,
                supporting = stringResource(R.string.me_items_public, books.count { it.visibility == "public" }, footprints.first),
                onClick = routes.items,
            )
            MeRow(
                AppIcons.Home, stringResource(R.string.homes_title), "settings_homes", shapes,
                supporting = homesSummary(container),
                onClick = routes.homes,
            )
            MeRow(
                AppIcons.CloudSync, stringResource(R.string.sync_title), "settings_sync", shapes,
                supporting = stringResource(
                    when {
                        !container.settings.apiUrl.isSet -> R.string.me_sync_no_api
                        container.settings.apiToken.isSet -> R.string.me_sync_token_set
                        else -> R.string.me_sync_token_unset
                    },
                ),
                onClick = routes.sync,
            )
            MeRow(
                AppIcons.FileOpen, stringResource(R.string.import_title), "settings_import", shapes,
                supporting = stringResource(R.string.me_import_sources),
                onClick = routes.imports,
            )
            MeRow(
                AppIcons.Phone, stringResource(R.string.side_key_title), "settings_sidekey", shapes,
                supporting = stringResource(sideKey.phase.label),
                onClick = routes.sideKey,
            )
        }
        Credits()
    }
    // Demo mode at the top, where it is there with or without a ledger.
    TabPage(stringResource(R.string.tab_me), tab = Preferences, actions = { DemoButton() }) {
        PageWidth(maxColumns = if (sideways) 2 else 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = columns.margin)
                    .padding(top = LocalTopClearance.current, bottom = LocalBottomClearance.current + Space.xl),
            ) {
                if (sideways) {
                    Row {
                        Column(Modifier.weight(1f)) { first() }
                        Column(Modifier.weight(1f)) { second() }
                    }
                } else {
                    first()
                    second()
                }
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
        Button(onClick = onChanges, modifier = Modifier.padding(top = Space.m).testTag("review_publish")) {
            Text(stringResource(R.string.action_review_publish))
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

/** What 家 says on 我: the places set by hand, by name, else that where one lives is found by itself. */
@Composable
private fun homesSummary(container: AppContainer): String {
    val homes = container.settings.homes
    val provinces = stringArrayResource(R.array.province_codes).zip(stringArrayResource(R.array.province_names)).toMap()
    val cities by produceState<List<cc.hyoban.moments.ui.map.FogRegion>?>(null, homes) { if (homes.any { it.length > 2 }) value = container.cityRegions() }
    if (homes.isEmpty()) return stringResource(R.string.homes_found_note)
    val names = homes.sorted().map { id ->
        if (id.length == 2) provinces[id] ?: id else cities?.firstOrNull { it.id == id }?.name?.let(cc.hyoban.moments.data.trips.TripDiscovery::shortCity) ?: ""
    }.filter { it.isNotEmpty() }
    return stringResource(R.string.me_homes_some, names.joinToString(stringResource(R.string.separator_list)))
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
