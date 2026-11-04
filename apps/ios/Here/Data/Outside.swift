import Foundation

// What publishing sends beside the Moments, from the stores that are the phone's own: the
// ledger's public bills and the footprints' public spans. Each is private until said public;
// what is public of it is worked out here, and only that leaves the phone. Each remembers what
// last went, so 发布更改 lists what changed and a bill or span made private again is taken off.

enum Outside {
    static func changes(ledger: LedgerStore, footprints: FootprintStore) -> OutsideChanges {
        let bills = ledger.pendingBills
        let tracks = footprints.pendingTracks
        var out = OutsideChanges()
        for (txn, part) in bills {
            if let part {
                var entry: JSON = [
                    "uid": JSON(txn.id),
                    "at": JSON(txn.at),
                    "parts": ["bill": part],
                    "createdAt": JSON(Times.stamp(Date(timeIntervalSince1970: Double(txn.createdAt) / 1000))),
                    "updatedAt": JSON(Times.stamp(Date(timeIntervalSince1970: Double(txn.updatedAt) / 1000))),
                ]
                if let place = txn.place { entry["place"] = JSON(place) }
                out.entries.append(entry)
            } else {
                out.deleted.append(txn.id)
            }
            let label = ledger.ledger.categoryName(txn.category) ?? String(localized: "record.bill")
            out.pending.append(PendingChange(
                subject: .outside("bill:\(txn.id)"), kind: part == nil ? .deleted : txn.published == nil ? .added : .changed,
                title: "\(label) \(Formats.money(txn.decimal, txn.currency))", detail: txn.day
            ))
        }
        for (share, line) in tracks {
            if let line { out.tracks.append(line) } else { out.deletedTracks.append(share.uid) }
            out.pending.append(PendingChange(
                subject: .outside("track:\(share.uid)"), kind: line == nil ? .deleted : share.published == nil ? .added : .changed,
                title: String(localized: "changes.track"), detail: Times.day(Date(timeIntervalSince1970: Double(share.start) / 1000))
            ))
        }
        out.published = { _ in
            ledger.markPublished(Dictionary(uniqueKeysWithValues: bills.map { ($0.txn.id, $0.part?.text) }))
            footprints.markSharesPublished(Dictionary(uniqueKeysWithValues: tracks.map { ($0.share.uid, $0.line?.text) }))
        }
        return out
    }
}
