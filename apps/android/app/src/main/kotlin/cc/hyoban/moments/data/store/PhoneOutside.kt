package cc.hyoban.moments.data.store

import cc.hyoban.moments.ledger.LedgerStore

/** The ledger's changes to publish. */
class PhoneOutside(private val ledger: LedgerStore) : OutsideChanges {
    override fun pending(): Outside = Outside(ledger.ledger.value.pendingBills())

    override suspend fun published(sent: Outside) {
        ledger.markPublished(sent.bills.associate { it.txn.id to it.part?.toString() })
    }
}
