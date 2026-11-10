package cc.hyoban.moments.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The ledger as the pages read it; the phone's store keeps it, and a device with none has an empty one. */
interface LedgerSource {
    val ledger: StateFlow<Ledger>
}

/** No ledger: a device that keeps none. */
object NoLedger : LedgerSource {
    override val ledger: StateFlow<Ledger> = MutableStateFlow(Ledger())
}
