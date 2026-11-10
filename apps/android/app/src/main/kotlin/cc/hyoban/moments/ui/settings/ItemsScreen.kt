package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import android.util.Log
import android.content.Context
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.footprints.ConnectionReminderService
import cc.hyoban.moments.footprints.RecordingService
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记录项与公开: what is recorded, and who sees it by default. First the modules the spec defines
 * (`Module`), each on or off as one: off, its rows, pages and records are put away everywhere,
 * its background work stops ([applyModule]) and its data stays. Then each book says whether its
 * new bills are public, a public one publishing its amount, category and note alone; the
 * footprints are private, with the spans made public counted here and made so on a trip's
 * page. Every bill and span can still say otherwise. Nothing here opens a page further down.
 */
@Composable
fun ItemsScreen(container: AppContainer, onBack: () -> Unit) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    val shares by produceState(0, revision) {
        value = withContext(Dispatchers.IO) { container.footprints.shares().count { !it.removed } }
    }
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val context = LocalContext.current
    SubPage(stringResource(Res.string.items_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                SegmentedGroup(
                    stringResource(Res.string.items_modules),
                    stringResource(Res.string.items_modules_note),
                    Module.entries.map { module ->
                        { shapes ->
                            val on = settings.isOn(module)
                            SwitchRow(on, moduleIcon(module), stringResource(moduleTitle(module)), "module_${module.key}", shapes) {
                                settings.changeModule(module, !on)
                                scope.launch { applyModule(context, module) }
                            }
                        }
                    },
                )
                val books = ledger.books.filter { !it.archived }
                if (books.isNotEmpty() && settings.isOn(Module.LEDGER)) {
                    SegmentedGroup(
                        stringResource(Res.string.items_books),
                        stringResource(Res.string.items_book_note),
                        books.map { book ->
                            { shapes ->
                                SwitchRow(
                                    book.visibility == "public",
                                    AppIcons.Receipt,
                                    book.name,
                                    "book_public_${book.id}",
                                    shapes,
                                    supporting = stringResource(Res.string.items_book_public),
                                ) {
                                    scope.launch {
                                        container.ledger.saveBook(book.copy(visibility = if (book.visibility == "public") "private" else "public"))
                                    }
                                }
                            }
                        },
                    )
                }
                if (settings.isOn(Module.FOOTPRINTS)) SegmentedGroup(
                    stringResource(Res.string.items_tracks),
                    stringResource(Res.string.items_tracks_note),
                    listOf { _ ->
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.footprints_title)) },
                            leadingContent = { Icon(AppIcons.Footprints, contentDescription = null) },
                            trailingContent = { Text(stringResource(Res.string.items_track_shares, shares)) },
                            modifier = Modifier.testTag("items_tracks"),
                        )
                    },
                )
            }
        }
    }
}

/** A module's name on the page: the app's own word for each. */
fun moduleTitle(module: Module): StringResource = when (module) {
    Module.FOOTPRINTS -> Res.string.footprints_title
    Module.LEDGER -> Res.string.me_ledger
    Module.THINGS -> Res.string.me_things
    Module.CAR -> Res.string.car_title
    Module.FOOD -> Res.string.section_food
    Module.CHECKIN -> Res.string.module_checkin
    Module.OCCASIONS -> Res.string.occasions_title
}

/** A module's icon, the one its row on 我 or its part has. */
fun moduleIcon(module: Module): ImageVector = when (module) {
    Module.FOOTPRINTS -> AppIcons.Footprints
    Module.LEDGER -> AppIcons.Wallet
    Module.THINGS -> AppIcons.Package
    Module.CAR -> AppIcons.Car
    Module.FOOD -> AppIcons.Food
    Module.CHECKIN -> AppIcons.Pin
    Module.OCCASIONS -> AppIcons.Calendar
}

/**
 * Brings the phone's background work in line with [module] just turned on or off: the footprints'
 * recording and connection reminders stop while their module is off and come back as they were
 * set when it is on again; the payment reader listens for 自动记账 only while the ledger is on.
 */
suspend fun applyModule(context: Context, module: Module) {
    when (module) {
        Module.FOOTPRINTS -> try {
            RecordingService.control(context)
            ConnectionReminderService.sync(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Footprints", "Could not apply the footprints module", e)
        }
        Module.LEDGER -> ShareAccessibilityService.listenIdle()
        else -> Unit
    }
}
