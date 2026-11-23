package cc.hyoban.moments.ui.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cc.hyoban.moments.AppContainer

/** Bringing a ledger in from a file: [pick] chooses it, [Dialog] asks what to do with what was found. */
class LedgerImport(val pick: () -> Unit, val Dialog: @Composable () -> Unit)

/** What the ledger's pages have of the device they are on, where it keeps the ledger to write. */
interface LedgerDevice {
    /** Bringing a ledger in from another app, for an empty one. */
    @Composable fun rememberImport(container: AppContainer): LedgerImport? = null

    /** Where a bill with no place of its own was, from the device's check-ins and footprints; null where it can't tell. */
    @Composable fun billSpot(txn: cc.hyoban.moments.ledger.Txn): cc.hyoban.moments.footprints.BillSpot? = null
}

/** The device's, or null on one that keeps no ledger to write. */
val LocalLedgerDevice = staticCompositionLocalOf<LedgerDevice?> { null }
