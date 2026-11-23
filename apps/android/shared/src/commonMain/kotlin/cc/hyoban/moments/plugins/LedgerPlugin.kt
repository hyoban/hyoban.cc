package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.trips.TripFacet
import cc.hyoban.moments.ui.compose.ComposerPart
import cc.hyoban.moments.ui.moment.MomentSection
import cc.hyoban.moments.ui.feed.SearchKind
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
object LedgerPlugin : ModulePlugin {
    /** The key of the bill 写动态 writes, alone when opened with [NewMoment.bill]. */
    const val BILL_PART = "bill"

    override val module = Module.LEDGER
    override val title = Res.string.me_ledger
    override val searchKinds: List<SearchKind> = listOf(BillsSearch)
    override val momentSection: MomentSection = LedgerSection
    override val composerPart: ComposerPart = BillComposer
    override val tripFacets: List<TripFacet> = listOf(SpendingFacet)
    override val icon = AppIcons.Wallet
    override val changeSection: cc.hyoban.moments.ui.publish.ChangeSection = LedgerChangeSection
    override val join: cc.hyoban.moments.ui.feed.TimelineJoin = LedgerJoin

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

    @Composable override fun ItemsGroup(container: AppContainer) = BooksGroup(container)
}
