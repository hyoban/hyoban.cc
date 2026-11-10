package cc.hyoban.moments.data.store

import cc.hyoban.moments.footprints.FootprintStore
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.ledger.LedgerStore

/** The ledger's and the footprints' changes to publish. */
class PhoneOutside(private val ledger: LedgerStore, private val footprints: FootprintStore) : OutsideChanges {
    override fun pending(): Outside = Outside(ledger.ledger.value.pendingBills(), footprints.pendingTracks())

    override suspend fun published(sent: Outside) {
        ledger.markPublished(sent.bills.associate { it.txn.id to it.part?.toString() })
        footprints.markSharesPublished(sent.tracks.associate { it.share.uid to it.line?.toString() })
    }
}

/** Every public span whose line isn't what was last published, or taken back. */
fun FootprintStore.pendingTracks(): List<TrackChange> = shares().mapNotNull { share ->
    val line = if (share.removed) null else publicLine(share, points(TrackSelection(from = share.start, to = share.end)))
    if (line?.toString() == share.published || (line == null && share.published == null)) null else TrackChange(share, line)
}
