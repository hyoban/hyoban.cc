package cc.hyoban.moments.plugins

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ui.settings.ICostGroup
import cc.hyoban.moments.ui.settings.TrackGroup
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.footprints.ConnectionReminderService
import cc.hyoban.moments.footprints.RecordingService
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.screen
import androidx.navigation.toRoute
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.settings.MeRow
import cc.hyoban.moments.ui.things.APART
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/** Where a module's row on 我 goes: a page, a page gone back to if it is the one under, or the map's footprints. */
class MeNav(val navigate: (Any) -> Unit, val goTo: (Any) -> Unit, val showOverlay: () -> Unit)

/** What a module's own pages on the phone are opened with: going back, the ledger's ways and a day's page. */
class PhonePages(
    val container: PhoneContainer,
    val back: () -> Unit,
    val ledgerNav: cc.hyoban.moments.ui.ledger.LedgerNav,
    val openDay: (String) -> Unit,
)

/**
 * What a module brings to the phone alone, beside its [ModulePlugin]: its row on 我 and the
 * background work it starts and stops as it is turned on or off.
 */
interface PhonePlugin {
    val module: Module

    /** Its row among what is kept here on 我, drawn only while it is on, unless it says otherwise. */
    @Composable fun MeRow(container: PhoneContainer, nav: MeNav, shapes: ListItemShapes) {}

    /** Brings its background work in line with its switch, just turned on or off. */
    suspend fun applied(context: Context) {}

    /** The names of the sources 导入 offers for it on the phone alone. */
    val importSources: List<StringResource> get() = emptyList()

    /** Its group on 导入, drawn while it is on. */
    @Composable fun ImportGroup(container: PhoneContainer) {}

    /** The side keys' actions it brings, by their value, offered while it is on. */
    val sideKeyActions: Map<String, StringResource> get() = emptyMap()

    /** What the heatmap widget can show of it. */
    val heatSources: List<cc.hyoban.moments.widget.HeatSource> get() = emptyList()

    /** Does its side key [action], [on] whether its module is: what to say, and whether to say it in a toast. */
    fun sideKey(action: String, on: Boolean): Pair<String, Boolean> = "" to false

    /** Its own pages in the phone's navigation, each by its route. */
    fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {}

    /** Whether it draws over the atlas, as [MapOverlay] does. */
    val drawsOnMap: Boolean get() = false

    /** What it draws over the atlas and its panel, given to [content]; its settings and what it leads to by [actions]. */
    @Composable fun MapOverlay(
        container: PhoneContainer,
        actions: cc.hyoban.moments.ui.map.MapOverlayActions,
        content: @Composable (cc.hyoban.moments.ui.map.AtlasFootprintsOnMap?) -> Unit,
    ) = content(null)

    /** Whether it has a step of its own in 初次打开, shown while it is on. */
    val onboards: Boolean get() = false

    /** Its step in 初次打开, going [onBack] to where to start or on with [onDone]. */
    @Composable fun OnboardingStep(container: PhoneContainer, onBack: () -> Unit, onDone: () -> Unit) {}
}

/** The phone's plugins, in the order of their rows on 我. */
object PhonePlugins {
    val all: List<PhonePlugin> = listOf(LedgerPhone, FootprintsPhone, ThingsPhone, OccasionsPhone)

    fun of(module: Module): PhonePlugin? = all.firstOrNull { it.module == module }

    /** [module]'s background work, just turned on or off in 记录项与公开. */
    suspend fun applied(context: Context, module: Module) {
        of(module)?.applied(context)
    }

    /** The plugin whose side key action [action] is, or null for one of the side keys' own. */
    fun sideKeyOwner(action: String?): PhonePlugin? = all.firstOrNull { action != null && action in it.sideKeyActions }

    /** Whether 导入 has a source of the phone's own with these modules on. */
    fun imports(settings: Settings): Boolean = all.any { settings.isOn(it.module) && it.importSources.isNotEmpty() }
}

private object LedgerPhone : PhonePlugin {
    override val module = Module.LEDGER

    @Composable override fun MeRow(container: PhoneContainer, nav: MeNav, shapes: ListItemShapes) {
        if (!LocalSettings.current.isOn(module)) return
        val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
        // This month's spending in the shown books, in the book's currency, under the ledger's name.
        val month = remember(ledger) { monthExpense(ledger) }
        MeRow(
            LedgerPlugin.icon, stringResource(Res.string.me_ledger), "me_ledger", shapes,
            supporting = month?.let { (expense, currency) -> stringResource(Res.string.me_month_expense) + APART + money(expense, currency) },
        ) { nav.goTo(LedgerHome(0)) }
    }

    override val importSources = listOf(Res.string.import_source_icost)

    @Composable override fun ImportGroup(container: PhoneContainer) = ICostGroup(container)

    /** The payment reader listens for 自动记账 only while the ledger is on. */
    override suspend fun applied(context: Context) = ShareAccessibilityService.listenIdle()

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        val container = pages.container
        val ledgerNav = pages.ledgerNav
        screen<LedgerHome> { entry ->
            cc.hyoban.moments.ui.ledger.LedgerScreen(container, ledgerNav, startTab = entry.toRoute<LedgerHome>().tab)
        }
        // A payment's from 自动记账's notification opening the app, with what it read.
        screen<cc.hyoban.moments.ui.LedgerEntry> { entry ->
            val route = entry.toRoute<cc.hyoban.moments.ui.LedgerEntry>()
            cc.hyoban.moments.ui.ledger.EntryScreen(
                container,
                cc.hyoban.moments.ui.ledger.LedgerEntryArgs(
                    route.edit, route.kind?.let(cc.hyoban.moments.ledger.TxnKind::of), route.related, route.moment, route.date, route.account,
                    route.amount?.toLongOrNull(), route.time?.toLongOrNull(), route.note, route.category,
                ),
                ledgerNav,
            )
        }
        screen<cc.hyoban.moments.ui.LedgerBill> { entry ->
            cc.hyoban.moments.ui.ledger.BillScreen(container, entry.toRoute<cc.hyoban.moments.ui.LedgerBill>().id, ledgerNav)
        }
        screen<cc.hyoban.moments.ui.LedgerAccount> { entry ->
            cc.hyoban.moments.ui.ledger.AccountScreen(container, entry.toRoute<cc.hyoban.moments.ui.LedgerAccount>().id, ledgerNav)
        }
        screen<cc.hyoban.moments.ui.LedgerAccountEdit> { entry ->
            cc.hyoban.moments.ui.ledger.AccountEditScreen(container, entry.toRoute<cc.hyoban.moments.ui.LedgerAccountEdit>().id, ledgerNav)
        }
        screen<cc.hyoban.moments.ui.LedgerSettings> { entry ->
            cc.hyoban.moments.ui.ledger.LedgerSettingsScreen(container, ledgerNav, startImport = entry.toRoute<cc.hyoban.moments.ui.LedgerSettings>().import)
        }
        screen<cc.hyoban.moments.ui.LedgerList> { entry ->
            val route = entry.toRoute<cc.hyoban.moments.ui.LedgerList>()
            cc.hyoban.moments.ui.ledger.LedgerListScreen(
                container,
                cc.hyoban.moments.ui.ledger.LedgerFilter(route.title, route.book, route.account, route.category, route.categoryName, route.trip, route.place, route.day, route.period, route.search),
                ledgerNav,
            )
        }
    }

    override val sideKeyActions = mapOf("ledger:capture" to Res.string.side_key_payment)

    override val heatSources = listOf<cc.hyoban.moments.widget.HeatSource>(SpendingHeat)

    /** Reads the payment on screen into a bill, or says the ledger is off. */
    override fun sideKey(action: String, on: Boolean): Pair<String, Boolean> {
        if (!on) return cc.hyoban.moments.res.textOf(Res.string.side_key_ledger_off) to true
        val result = ShareAccessibilityService.capturePayment()
        return cc.hyoban.moments.res.textOf(result.message) to (result != cc.hyoban.moments.accessibility.PaymentCapture.OPENED)
    }
}

/** The ledger's pages on the phone, which keeps the ledger to write: iCost's import, and where a bill was. */
object PhoneLedgerDevice : cc.hyoban.moments.ui.ledger.LedgerDevice {
    @Composable
    override fun rememberImport(container: cc.hyoban.moments.AppContainer): cc.hyoban.moments.ui.ledger.LedgerImport =
        cc.hyoban.moments.ui.ledger.rememberICostImport(container as PhoneContainer).let { cc.hyoban.moments.ui.ledger.LedgerImport(it.pick, it.Dialog) }

    @Composable
    override fun billSpot(txn: cc.hyoban.moments.ledger.Txn) = cc.hyoban.moments.footprints.billSpot(txn)
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

private object FootprintsPhone : PhonePlugin {
    override val module = Module.FOOTPRINTS

    @Composable override fun MeRow(container: PhoneContainer, nav: MeNav, shapes: ListItemShapes) {
        if (!LocalSettings.current.isOn(module)) return
        val revision by container.footprints.revision.collectAsStateWithLifecycle()
        // Whether the footprints are being recorded now.
        val recording by produceState(false, revision) {
            value = withContext(Dispatchers.IO) { container.footprints.state().recording }
        }
        MeRow(
            FootprintsPlugin.icon, stringResource(Res.string.footprints_title), "me_footprints", shapes,
            supporting = stringResource(Res.string.me_footprints_recording).takeIf { recording },
            onClick = nav.showOverlay,
        )
    }

    override val importSources = listOf(Res.string.import_source_gpx, Res.string.import_source_footprint_csv)

    override val heatSources = listOf<cc.hyoban.moments.widget.HeatSource>(FootprintsHeat)

    @Composable override fun ImportGroup(container: PhoneContainer) = TrackGroup(container)

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        screen<cc.hyoban.moments.ui.FootprintSettings> { cc.hyoban.moments.footprints.FootprintSettingsScreen(pages.container, onBack = pages.back) }
    }

    // Its own store and panel, drawn on the atlas as the shared map takes them.
    override val drawsOnMap = true

    @Composable
    override fun MapOverlay(
        container: PhoneContainer,
        actions: cc.hyoban.moments.ui.map.MapOverlayActions,
        content: @Composable (cc.hyoban.moments.ui.map.AtlasFootprintsOnMap?) -> Unit,
    ) = cc.hyoban.moments.footprints.FootprintsOnMap(container, { actions.navigate(cc.hyoban.moments.ui.FootprintSettings) }, actions.writeAt, actions.openStop) { state ->
        val drawing = remember(state.drawing) {
            cc.hyoban.moments.ui.map.AtlasFootprints(
                lines = state.drawing.lines.map { (mode, points) ->
                    cc.hyoban.moments.ui.map.AtlasFootprintLine(
                        mode = when (mode) {
                            cc.hyoban.moments.footprints.TravelMode.WALK -> cc.hyoban.moments.ui.map.AtlasFootprintMode.WALK
                            cc.hyoban.moments.footprints.TravelMode.CYCLE -> cc.hyoban.moments.ui.map.AtlasFootprintMode.CYCLE
                            cc.hyoban.moments.footprints.TravelMode.DRIVE -> cc.hyoban.moments.ui.map.AtlasFootprintMode.DRIVE
                        },
                        points = points,
                    )
                },
                ends = state.drawing.ends,
                stays = state.drawing.stays,
                tail = state.drawing.tail,
            )
        }
        content(cc.hyoban.moments.ui.map.AtlasFootprintsOnMap(
            drawing.copy(focus = state.focus), state.frame, state.frameRequested, state.onFramed, state.open, state.recording, state.openStay,
            looking = state.looking, stopLooking = state.stopLooking, days = state.days, trips = state.trips,
        ))
    }

    /** Recording them, asked for in 初次打开 with what it asks of the system. */
    override val onboards = true

    @Composable override fun OnboardingStep(container: PhoneContainer, onBack: () -> Unit, onDone: () -> Unit) =
        cc.hyoban.moments.ui.onboarding.PhoneOnboardingFootprints(container, onBack, onDone)

    /**
     * The footprints' recording and connection reminders stop while their module is off and come
     * back as they were set when it is on again.
     */
    override suspend fun applied(context: Context) {
        try {
            RecordingService.control(context)
            ConnectionReminderService.sync(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Footprints", "Could not apply the footprints module", e)
        }
    }
}

/** The things' row, which the car's page shares: it names what of it is on. */
private object ThingsPhone : PhonePlugin {
    override val module = Module.THINGS

    @Composable override fun MeRow(container: PhoneContainer, nav: MeNav, shapes: ListItemShapes) {
        val settings = LocalSettings.current
        val things = listOfNotNull(
            stringResource(Res.string.devices_title).takeIf { settings.isOn(Module.THINGS) },
            stringResource(Res.string.subscriptions_title).takeIf { settings.isOn(Module.THINGS) },
            // The car's data comes only from an import into the API, so without one there is none.
            stringResource(Res.string.car_title).takeIf { settings.connected && settings.isOn(Module.CAR) },
        )
        if (things.isNotEmpty()) MeRow(
            ThingsPlugin.icon, stringResource(Res.string.me_things), "me_things", shapes,
            supporting = things.joinToString(stringResource(Res.string.separator_list)),
        ) { nav.navigate(Things()) }
    }
}

private object OccasionsPhone : PhonePlugin {
    override val module = Module.OCCASIONS

    override fun androidx.navigation.NavGraphBuilder.pages(pages: PhonePages) {
        screen<Occasions> { entry ->
            cc.hyoban.moments.ui.occasions.OccasionsScreen(pages.container, onBack = pages.back, open = entry.toRoute<Occasions>().open)
        }
    }

    @Composable override fun MeRow(container: PhoneContainer, nav: MeNav, shapes: ListItemShapes) {
        if (!LocalSettings.current.isOn(module)) return
        MeRow(
            OccasionsPlugin.icon, stringResource(Res.string.occasions_title), "me_occasions", shapes,
            supporting = cc.hyoban.moments.ui.occasions.nextOccasionSummary(container),
        ) { nav.navigate(Occasions()) }
    }
}
