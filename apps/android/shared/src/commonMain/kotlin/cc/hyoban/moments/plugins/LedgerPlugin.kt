package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.me_ledger
import cc.hyoban.moments.ui.LedgerAccount
import cc.hyoban.moments.ui.LedgerAccountEdit
import cc.hyoban.moments.ui.LedgerBill
import cc.hyoban.moments.ui.LedgerEntry
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.LedgerList
import cc.hyoban.moments.ui.LedgerSettings
import cc.hyoban.moments.ui.NewMoment
import cc.hyoban.moments.ui.design.AppIcons

/** 记账: the ledger's books, bills and accounts, kept on the phone. */
object LedgerPlugin : Plugin {
    /** The key of the bill 写动态 writes, alone when opened with [NewMoment.bill]. */
    const val BILL_PART = "bill"

    override val module = Module.LEDGER
    val icon = AppIcons.Wallet

    override fun Contributions.contribute() {
        add(Points.modules, ModuleCard(Module.LEDGER, Res.string.me_ledger, icon))
        add(Points.searchKinds, BillsSearch)
        add(Points.momentSections, LedgerSection)
        add(Points.composerParts, BillComposer)
        add(Points.tripFacets, SpendingFacet)
        add(Points.changes, LedgerChangeSection)
        add(Points.joins, LedgerJoin)
        add(Points.itemsGroups, object : ItemsGroup {
            @Composable override fun Group(container: AppContainer) = BooksGroup(container)
        })
        add(Points.pages, LedgerPages)
    }
}

private object LedgerPages : PageSet {
    override fun owns(route: Any) = route is LedgerHome || route is LedgerEntry || route is LedgerBill ||
        route is LedgerAccount || route is LedgerAccountEdit || route is LedgerSettings || route is LedgerList

    override fun routeOf(link: AppLink): Any? = when (link.host) {
        // The ledger's sums, as 我 opens them.
        "ledger" -> LedgerHome(1)
        "new-bill" -> NewMoment(bill = true)
        "payment" -> LedgerEntry(
            account = link.query("account"),
            amount = link.query("amount"),
            time = link.query("time"),
            note = link.query("note"),
            category = link.query("category"),
        )
        "bill" -> link.id?.let(::LedgerBill)
        "account" -> link.id?.let(::LedgerAccount)
        "ledger-settings" -> LedgerSettings()
        else -> null
    }

    /** A bill to write alone, with the ledger off, opens a Moment to write. */
    override fun whenOff(route: Any): Any? = if (route is NewMoment && route.bill) NewMoment() else super.whenOff(route)
}
