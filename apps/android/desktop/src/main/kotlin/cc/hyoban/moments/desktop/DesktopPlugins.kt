package cc.hyoban.moments.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.footprints_title
import cc.hyoban.moments.res.me_ledger
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.AppIcons
import org.jetbrains.compose.resources.StringResource

/**
 * What a module brings to the Mac alone, beside its `ModulePlugin`: its section in the sidebar, by
 * [key] (as `HERE_OPEN` and 启动时 name it), a destination among the others or, [mine], under 我的.
 * Listed while the module is on.
 */
internal class DesktopPlugin(
    val module: Module,
    val key: String,
    val label: StringResource,
    val icon: ImageVector,
    val route: Any,
    val mine: Boolean = false,
    /** Whether a route is one of its own pages, which [Page] draws. */
    val shows: (Any) -> Boolean = { false },
    val Page: @Composable (pages: DesktopPages, route: Any) -> Unit = { _, _ -> },
)

/** What a module's own pages on the Mac are opened with: going back, the ledger's ways, a day's page, and where what they show came from. */
internal class DesktopPages(
    val container: DesktopContainer,
    val back: () -> Unit,
    val ledgerNav: cc.hyoban.moments.ui.ledger.LedgerNav,
    val openDay: (String) -> Unit,
    /** Where the ledger and the footprints shown came from: the phone's backup, and when it was made. */
    val source: @Composable () -> String?,
)

/** The Mac's plugins, in the sidebar's order. */
internal object DesktopPlugins {
    val all = listOf(
        DesktopPlugin(
            Module.FOOTPRINTS, "footprints", Res.string.footprints_title, AppIcons.Footprints, FootprintDays,
            shows = { it == FootprintDays },
        ) { pages, _ -> cc.hyoban.moments.ui.footprints.FootprintDaysScreen(pages.container, pages.source(), onDay = pages.openDay, onBack = pages.back) },
        DesktopPlugin(
            Module.LEDGER, "ledger", Res.string.me_ledger, AppIcons.Wallet, LedgerHome(), mine = true,
            shows = { it is LedgerHome || it is cc.hyoban.moments.ui.LedgerBill || it is cc.hyoban.moments.ui.LedgerAccount || it is cc.hyoban.moments.ui.ledger.LedgerFilter },
        ) { pages, route ->
            when (route) {
                is LedgerHome -> cc.hyoban.moments.ui.ledger.LedgerScreen(pages.container, pages.ledgerNav, route.tab, source = pages.source())
                is cc.hyoban.moments.ui.LedgerBill -> cc.hyoban.moments.ui.ledger.BillScreen(pages.container, route.id, pages.ledgerNav)
                is cc.hyoban.moments.ui.LedgerAccount -> cc.hyoban.moments.ui.ledger.AccountScreen(pages.container, route.id, pages.ledgerNav)
                is cc.hyoban.moments.ui.ledger.LedgerFilter -> cc.hyoban.moments.ui.ledger.LedgerListScreen(pages.container, route, pages.ledgerNav)
            }
        },
        DesktopPlugin(Module.THINGS, "things", Res.string.me_things, AppIcons.Laptop, Things(), mine = true),
        DesktopPlugin(
            Module.OCCASIONS, "occasions", Res.string.occasions_title, AppIcons.Gift, Occasions(), mine = true,
            shows = { it is Occasions },
        ) { pages, route -> cc.hyoban.moments.ui.occasions.OccasionsScreen(pages.container, onBack = pages.back, open = (route as Occasions).open) },
    )
}
