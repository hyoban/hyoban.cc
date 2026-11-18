package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.spec.Module
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.Ledger
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
import cc.hyoban.moments.camera.CameraStore
import cc.hyoban.moments.camera.CameraLink
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
    val ledger: (Int) -> Unit,
    val footprints: () -> Unit,
    val things: () -> Unit,
    val occasions: () -> Unit,
    val items: () -> Unit,
    val homes: () -> Unit,
    val sync: () -> Unit,
    val imports: () -> Unit,
    val sideKey: () -> Unit,
    val camera: () -> Unit,
    val albums: () -> Unit,
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
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    // Whether the footprints are being recorded now.
    val recording by produceState(false, revision) {
        value = withContext(Dispatchers.IO) { container.footprints.state().recording }
    }
    val sideKey by SideKeys.state.collectAsStateWithLifecycle()
    val camera by CameraStore.camera.collectAsStateWithLifecycle()
    val cameraStatus by CameraStore.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraSupported = remember { CameraLink.supported(context) }
    val settings = LocalSettings.current
    val connected = settings.connected
    val books = ledger.books.filter { !it.archived }
    // This month's spending in the shown books, in the book's currency, under the ledger's name.
    val month = remember(ledger) { monthExpense(ledger) }
    // On its side, one scroll in two columns: what is waiting and what is kept here at the start,
    // the settings beside them (H15).
    val sideways = isPhoneLandscape()
    val first: @Composable () -> Unit = {
        // With no API to publish to, what is kept here is simply the phone's.
        if (pending.size > 0 && container.canPublish) PublishCard(pending.size, routes.changes)
        // A row for each module that is on (记录项与公开); the things' row names what of it is.
        val things = listOfNotNull(
            stringResource(Res.string.devices_title).takeIf { settings.isOn(Module.THINGS) },
            stringResource(Res.string.subscriptions_title).takeIf { settings.isOn(Module.THINGS) },
            // The car's data comes only from an import into the API, so without one there is none.
            stringResource(Res.string.car_title).takeIf { connected && settings.isOn(Module.CAR) },
        )
        PageRows(Modifier.padding(top = Space.s)) { shapes ->
            if (settings.isOn(Module.LEDGER)) MeRow(
                AppIcons.Wallet, stringResource(Res.string.me_ledger), "me_ledger", shapes,
                supporting = month?.let { (expense, currency) -> stringResource(Res.string.me_month_expense) + APART + money(expense, currency) },
            ) { routes.ledger(0) }
            if (settings.isOn(Module.FOOTPRINTS)) MeRow(
                AppIcons.Footprints, stringResource(Res.string.footprints_title), "me_footprints", shapes,
                supporting = stringResource(Res.string.me_footprints_recording).takeIf { recording },
                onClick = routes.footprints,
            )
            if (things.isNotEmpty()) MeRow(
                AppIcons.Package, stringResource(Res.string.me_things), "me_things", shapes,
                supporting = things.joinToString(stringResource(Res.string.separator_list)),
                onClick = routes.things,
            )
            if (settings.isOn(Module.OCCASIONS)) MeRow(
                AppIcons.Calendar, stringResource(Res.string.occasions_title), "me_occasions", shapes,
                supporting = cc.hyoban.moments.ui.occasions.nextOccasionSummary(container),
                onClick = routes.occasions,
            )
        }
    }
    val second: @Composable () -> Unit = {
        PageCaption(stringResource(Res.string.me_settings))
        PageRows { shapes ->
            MeRow(
                AppIcons.Eye, stringResource(Res.string.me_items), "settings_items", shapes,
                supporting = stringResource(Res.string.me_items_public, books.count { it.visibility == "public" }),
                onClick = routes.items,
            )
            MeRow(
                AppIcons.Home, stringResource(Res.string.homes_title), "settings_homes", shapes,
                supporting = homesSummary(container),
                onClick = routes.homes,
            )
            MeRow(
                AppIcons.CloudSync, stringResource(Res.string.sync_title), "settings_sync", shapes,
                supporting = stringResource(
                    when {
                        !container.settings.apiUrl.isSet -> Res.string.me_sync_no_api
                        container.settings.apiToken.isSet -> Res.string.me_sync_token_set
                        else -> Res.string.me_sync_token_unset
                    },
                ),
                onClick = routes.sync,
            )
            // The sources of the modules that are on; with none, nothing to import into.
            val sources = importSources()
            if (sources.isNotEmpty()) MeRow(
                AppIcons.FileOpen, stringResource(Res.string.import_title), "settings_import", shapes,
                supporting = sources.joinToString(stringResource(Res.string.separator_list)),
                onClick = routes.imports,
            )
            // While the gallery may be read.
            if (remember { LocalMedia.access(context) } != GalleryAccess.NONE) MeRow(
                AppIcons.Folder, stringResource(Res.string.albums_title), "settings_albums", shapes,
                supporting = galleryAlbumsSummary(),
                onClick = routes.albums,
            )
            // On the phones that can keep a camera's location going in the background.
            if (cameraSupported) MeRow(
                AppIcons.CameraBody, stringResource(Res.string.camera_title), "settings_camera", shapes,
                supporting = camera?.let { listOfNotNull(it.name, stringResource(Res.string.me_camera_sending).takeIf { cameraStatus.sending }).joinToString(" · ") }
                    ?: stringResource(Res.string.me_camera_unpaired),
                onClick = routes.camera,
            )
            // Only on the phone whose key it is.
            if (SideKeys.supported) MeRow(
                AppIcons.Phone, stringResource(Res.string.side_key_title), "settings_sidekey", shapes,
                supporting = stringResource(sideKey.phase.label),
                onClick = routes.sideKey,
            )
            ShareAlbumsRow(shapes)
        }
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

/** A row of 我 opening a page: its icon on the gutter, its name, what it is set to under it, an arrow. */
@Composable
private fun MeRow(icon: ImageVector, title: String, tag: String, shapes: ListItemShapes, supporting: String? = null, opens: Boolean = true, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        trailingContent = if (opens) ({ Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3) }) else null,
        modifier = Modifier.testTag(tag),
    ) { Text(title) }
}

/**
 * 清理分享相册, while 准备图文 has left any Here Share album: the photos in them are copies, so one
 * tap hands them all to the system's delete prompt, which asks itself.
 */
@Composable
private fun ShareAlbumsRow(shapes: ListItemShapes) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<ShareAlbums?>(null) }
    val refresh: () -> Unit = { scope.launch { albums = runCatching { shareAlbums(context) }.getOrNull() } }
    // Back from 准备图文 or the gallery, the albums may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }
    val shown = albums?.takeIf { it.uris.isNotEmpty() } ?: return
    MeRow(
        AppIcons.Photos, stringResource(Res.string.share_albums_clean), "settings_share_albums", shapes,
        supporting = pluralStringResource(
            Res.plurals.share_albums_photos, shown.uris.size,
            pluralStringResource(Res.plurals.share_albums, shown.albums, shown.albums), shown.uris.size,
        ),
        opens = false,
    ) {
        runCatching {
            val request = MediaStore.createDeleteRequest(context.contentResolver, shown.uris)
            delete.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { Toast.makeText(context, textOf(Res.string.share_albums_clean_failed), Toast.LENGTH_SHORT).show() }
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

// This month's spending, kept with the ledger it was summed from: the tab's page is made again
// each time the tab is chosen, and summing every bill then held up the change of tab.
private var monthSpent: Triple<Ledger, LocalDate, Pair<Long, String>?>? = null

private fun monthExpense(ledger: Ledger): Pair<Long, String>? {
    val start = LocalDate.now().withDayOfMonth(1)
    monthSpent?.let { (from, month, spent) -> if (from === ledger && month == start) return spent }
    val spent = ledger.book?.let { book ->
        summarize(ledger.entries.filter { it.txn.book in ledger.shownBooks }, ledger.rates, book.currency, start, start.plusMonths(1)).expense to book.currency
    }
    monthSpent = Triple(ledger, start, spent)
    return spent
}
