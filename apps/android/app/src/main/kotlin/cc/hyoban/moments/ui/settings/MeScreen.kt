package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.plugins.ledger
import cc.hyoban.moments.plugins.MeGroup
import cc.hyoban.moments.plugins.MeNav
import cc.hyoban.moments.plugins.PhonePoints
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points

import cc.hyoban.moments.res.*
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.R
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
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.ledger.DemoButton
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.platform.LocalContext
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.data.media.ShareAlbums
import cc.hyoban.moments.data.media.shareAlbums
import cc.hyoban.moments.ui.picker.GalleryAccess
import cc.hyoban.moments.ui.picker.LocalMedia
import kotlinx.coroutines.launch

/** Where a row of 我 leads: each a page of its own, the last one down. */
class MeRoutes(
    val changes: () -> Unit,
    /** The ledger on its tab: 0 the bills, 1 the sums, 2 the accounts. */
    /** Where the modules' rows go (`PhonePoints.meRows`). */
    val modules: MeNav,
    val items: () -> Unit,
    val homes: () -> Unit,
    val sync: () -> Unit,
    val imports: () -> Unit,
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
fun MeScreen(container: PhoneContainer, routes: MeRoutes) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val books = ledger.books.filter { !it.archived }
    // On its side, one scroll in two columns: what is waiting and what is kept here at the start,
    // the settings beside them (H15).
    val sideways = isPhoneLandscape()
    val first: @Composable () -> Unit = {
        // With no API to publish to, what is kept here is simply the phone's.
        if (pending.size > 0 && container.canPublish) PublishCard(pending.size, routes.changes)
        // A row for each module that is on (记录项与公开), as its plugin gives it.
        SettingsRowsOnPage(
            Plugins.registry.all(PhonePoints.meRows).filter { it.group == MeGroup.KEPT }.mapNotNull { it.row(container, routes.modules) },
            Modifier.padding(top = Space.s),
        )
    }
    val second: @Composable () -> Unit = {
        PageCaption(stringResource(Res.string.me_settings))
        // The sources of the modules that are on; with none, nothing to import into.
        val sources = importSources()
        val own = listOf(
            0 to SettingsRow.Page(
                AppIcons.Eye, stringResource(Res.string.me_items), "settings_items",
                supporting = stringResource(Res.string.me_items_public, books.count { it.visibility == "public" }),
                onClick = routes.items,
            ),
            10 to SettingsRow.Page(
                AppIcons.Home, stringResource(Res.string.homes_title), "settings_homes",
                supporting = homesSummary(container),
                onClick = routes.homes,
            ),
            20 to SettingsRow.Page(
                AppIcons.CloudSync, stringResource(Res.string.sync_title), "settings_sync",
                supporting = stringResource(
                    when {
                        !container.settings.apiUrl.isSet -> Res.string.me_sync_no_api
                        container.settings.apiToken.isSet -> Res.string.me_sync_token_set
                        else -> Res.string.me_sync_token_unset
                    },
                ),
                onClick = routes.sync,
            ),
            30 to if (sources.isNotEmpty()) SettingsRow.Page(
                AppIcons.FileOpen, stringResource(Res.string.import_title), "settings_import",
                supporting = sources.joinToString(stringResource(Res.string.separator_list)),
                onClick = routes.imports,
            ) else null,
        )
        // The plugins' rows among them by their order, each only where it applies.
        val brought = Plugins.registry.all(PhonePoints.meRows).filter { it.group == MeGroup.SETTINGS }.map { it.order to it.row(container, routes.modules) }
        val rows = (own + brought).sortedBy { it.first }.mapNotNull { it.second }
        SettingsRowsOnPage(rows)
        Credits()
    }
    // Demo mode at the top, where it is there with or without a ledger.
    TabPage(stringResource(Res.string.tab_me), tab = Preferences, actions = { DemoButton() }) {
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
            pluralStringResource(Res.plurals.count_changes, count, count),
            style = AppTheme.typography.body.strong,
            modifier = Modifier.semantics { heading() },
        )
        Button(onClick = onChanges, modifier = Modifier.padding(top = Space.m).testTag("review_publish")) {
            Text(stringResource(Res.string.action_review_publish))
        }
    }
}

/** What 家 says on 我: the places chosen as home, by name, else that none is. */
@Composable
private fun homesSummary(container: PhoneContainer): String {
    val homes = container.settings.homes
    val places by container.repository.places.collectAsStateWithLifecycle()
    val names = homes.mapNotNull { places[it]?.name }.sorted()
    return if (names.isEmpty()) stringResource(Res.string.setting_unset) else names.joinToString(stringResource(Res.string.separator_list))
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
            stringResource(Res.string.me_footer, androidx.compose.ui.res.stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
            style = AppTheme.typography.caption,
            color = tokens.tx3,
        )
    }
}

