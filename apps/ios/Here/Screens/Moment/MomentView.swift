import SwiftUI

// A Moment's page (the design's 02): its photo first, under the circles; then when, its words,
// a filmstrip of its photos, and plain rows of what it is tied to: its place, its meal, its bills
// and its trip, each leading to its page. It grows from its bubble.

struct MomentView: View {
    var uid: String

    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router
    @State private var index = 0

    var body: some View {
        if let entry = store.entry(uid) {
            content(entry)
        } else {
            Text("moment.gone").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity, maxHeight: .infinity).background(Tokens.bg)
        }
    }

    private func content(_ entry: Entry) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !entry.media.isEmpty {
                    hero(entry)
                }
                VStack(alignment: .leading, spacing: Space.m) {
                    HStack(spacing: Space.s) {
                        Text(verbatim: [Formats.day(entry.day), Formats.time(entry.at) ?? ""].filter { !$0.isEmpty }.joined(separator: Formats.gap))
                            .textStyle(.body).foregroundStyle(Tokens.tx2)
                        if store.isUnpublished(entry.uid) { StatusPill(title: "visibility.unpublished") }
                        if entry.isPrivate { Text("visibility.private").textStyle(.caption).foregroundStyle(Tokens.tx2) }
                    }
                    if !entry.text.isEmpty {
                        Text(verbatim: entry.text)
                            .textStyle(.bodyReading)
                            .foregroundStyle(Tokens.tx)
                            .textSelection(.enabled)
                    }
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.xl)
                if entry.media.count > 1 {
                    filmstrip(entry).padding(.top, Space.l)
                }
                facts(entry).padding(.top, Space.l)
            }
            .padding(.bottom, Space.xl)
        }
        .page(Text(verbatim: ""), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: ShareText.of(entry, store: store)) { Icon("share", size: 22) }
                    .accessibilityLabel(Text("menu.share"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu { MomentMenu(entry: entry) } label: { Icon("sliders", size: 22) }
                    .accessibilityLabel(Text("action.more"))
            }
        }
    }

    private func hero(_ entry: Entry) -> some View {
        let media = entry.media
        return TabView(selection: $index) {
            ForEach(Array(media.enumerated()), id: \.element.uid) { offset, item in
                Button { router.cover = .media(entry: entry.uid, index: offset) } label: {
                    MediaImage(media: item, width: 430)
                }
                .buttonStyle(.plain)
                .tag(offset)
                .accessibilityIdentifier("media_\(entry.uid)_\(offset)")
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: 320)
        .clipShape(.cardShape)
        .padding(.horizontal, Space.gutter)
    }

    /// The photos as a row of 56 thumbnails, the one shown round and bright.
    private func filmstrip(_ entry: Entry) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s) {
                ForEach(Array(entry.media.enumerated()), id: \.element.uid) { offset, item in
                    let on = offset == index
                    Button { withAnimation(Springs.spatialDefault) { index = offset } } label: {
                        MediaImage(media: item, width: 56, showsPlay: false)
                            .frame(width: 56, height: 56)
                            .overlay { if !on { Tokens.scrim } }
                            .clipShape(RoundedRectangle(cornerRadius: on ? Radius.large : Radius.medium, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Space.gutter)
        }
    }

    @ViewBuilder
    private func facts(_ entry: Entry) -> some View {
        let book = ledger.ledger
        VStack(spacing: 0) {
            if let id = entry.place, let place = store.places[id] {
                PlainRow(icon: "pin", title: place.name, detail: [place.kind.map { String(localized: String.LocalizationValue("placeKind.\($0)")) }, place.division].compactMap { $0 }.joined(separator: Formats.gap), trailing: .chevron) {
                    router.push(.place(id))
                }
            }
            if let food = entry.food {
                PlainRow(icon: "food", title: (food["dishes"]?.strings ?? []).joined(separator: "、").isEmpty ? String(localized: "food.title") : (food["dishes"]?.strings ?? []).joined(separator: "、"), detail: Moments.foodLine(food))
            }
            ForEach(book.txns.filter { $0.moment == entry.uid }) { txn in
                PlainRow(icon: BillLook.icon(txn, book), title: BillLook.title(txn, book), detail: [book.account(txn.account)?.name ?? "", book.isPublic(txn) ? "" : String(localized: "visibility.private")].filter { !$0.isEmpty }.joined(separator: Formats.gap), trailing: .amount(ledger.hideAmounts ? "••••" : BillLook.amount(txn), income: txn.isInflow)) {
                    router.push(.bill(txn.id))
                }
            }
            if entry.food != nil, !book.txns.contains(where: { $0.moment == entry.uid }) {
                PlainRow(icon: "plus", title: String(localized: "ledger.addBill")) { router.sheet = .newBill("moment:\(entry.uid)") }
            }
            if let trip = store.trip(of: entry) {
                PlainRow(icon: "route", title: trip.title, detail: [Formats.span(trip.start, trip.end), String(localized: "trip.dayCount \(trip.dayCount)"), String(localized: "trip.momentCount \(trip.moments.count)")].joined(separator: Formats.gap), trailing: .chevron) {
                    router.push(.trip(trip.id))
                }
            }
        }
    }
}

enum Moments {
    /// A meal's line: `好吃 ¥186 2 人 每人 ¥93`.
    static func foodLine(_ food: JSON) -> String {
        var parts: [String] = []
        if let verdict = food["verdict"]?.string { parts.append(String(localized: String.LocalizationValue("food.\(verdict)"))) }
        if let cost = food["cost"]?.double {
            parts.append(Formats.money(cost))
            let people = max(1, food["people"]?.int ?? 1)
            if people > 1 {
                parts.append(String(localized: "food.people \(people)"))
                parts.append(String(localized: "food.each \(Formats.money(cost / Double(people)))"))
            }
        }
        return parts.joined(separator: Formats.gap)
    }
}
