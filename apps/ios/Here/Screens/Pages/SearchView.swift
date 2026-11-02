import SwiftUI

// 搜索: the words of Moments, places, trips and bills, filtered by kind and by year, what it finds
// listed under its years. With nothing to list it says why and what to try next.

struct SearchView: View {
    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router
    @State private var query = ""
    @State private var kind: Kind = .all
    @State private var year: String?

    enum Kind: String, CaseIterable, Identifiable {
        case all, moments, bills, charges
        var id: Self { self }
        var label: LocalizedStringKey { LocalizedStringKey("search.\(rawValue)") }
    }

    var body: some View {
        let results = self.results
        let byYear = Dictionary(grouping: results, by: { String($0.day.prefix(4)) })
        List {
            Section {
                Picker("search.kind", selection: $kind) {
                    ForEach(Kind.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s) {
                        ForEach(years, id: \.self) { value in
                            Button {
                                withAnimation(Springs.effectsDefault) { year = year == value ? nil : value }
                            } label: {
                                Text(verbatim: value)
                                    .textStyle(.label)
                                    .padding(.horizontal, Space.m)
                                    .padding(.vertical, Space.s)
                                    .background(year == value ? Tokens.accentSoft : Tokens.fill, in: .capsule)
                                    .foregroundStyle(year == value ? Tokens.accent : Tokens.tx)
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(year == value ? .isSelected : [])
                        }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: Space.s, leading: 0, bottom: 0, trailing: 0))
            }

            if !query.isEmpty, kind == .all || kind == .moments {
                let places = store.places.filter { $0.value.name.localizedCaseInsensitiveContains(query) }.prefix(5)
                let trips = store.trips.filter { $0.title.localizedCaseInsensitiveContains(query) }.prefix(5)
                if !places.isEmpty || !trips.isEmpty {
                    Section("search.placesTrips") {
                        ForEach(Array(trips)) { trip in
                            Button { router.push(.trip(trip.id)) } label: { Label(verbatim: trip.title, icon: "route") }
                        }
                        ForEach(Array(places), id: \.key) { id, place in
                            Button { router.push(.place(id)) } label: { Label(verbatim: place.name, icon: "pin") }
                        }
                    }
                }
            }

            ForEach(byYear.keys.sorted(by: >), id: \.self) { year in
                Section(year) {
                    ForEach(byYear[year] ?? []) { result in
                        Button { open(result) } label: { ResultRow(result: result) }
                            .buttonStyle(.plain)
                    }
                }
            }
        }
        .overlay {
            if results.isEmpty {
                if query.isEmpty && year == nil && kind == .all {
                    ContentUnavailableView("search.hint", image: "search", description: Text("search.hintDetail"))
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
        .navigationTitle(Text("tab.search"))
        .searchable(text: $query, prompt: Text("search.prompt"))
    }

    struct Result: Identifiable {
        var id: String
        var day: String
        var title: String
        var detail: String
        var icon: String
        var route: Route
    }

    private var years: [String] {
        Set(store.dayKeys.map { String($0.prefix(4)) }).sorted(by: >)
    }

    private var results: [Result] {
        guard !query.isEmpty || year != nil || kind != .all else { return [] }
        var out: [Result] = []
        let matches = { (text: String) in query.isEmpty || text.localizedCaseInsensitiveContains(query) }
        if kind == .all || kind == .moments {
            for entry in store.moments where (year == nil || entry.at.hasPrefix(year!)) {
                let place = entry.place.flatMap { store.places[$0]?.name } ?? ""
                if matches(entry.text) || matches(place) || (entry.food?["dishes"]?.strings ?? []).contains(where: matches) {
                    out.append(Result(id: entry.uid, day: entry.day, title: entry.text, detail: [Formats.day(entry.day), place].filter { !$0.isEmpty }.joined(separator: " · "), icon: entry.media.isEmpty ? "notes" : "photos", route: .moment(entry.uid)))
                }
            }
        }
        if kind == .all || kind == .bills {
            for txn in ledger.ledger.txns where year == nil || txn.day.hasPrefix(year!) {
                let category = ledger.ledger.categoryName(txn.category) ?? ""
                if matches(txn.note) || matches(category) {
                    out.append(Result(id: txn.id, day: txn.day, title: [category, txn.note].filter { !$0.isEmpty }.joined(separator: " · "), detail: Formats.day(txn.day) + " · " + (ledger.hideAmounts ? "••••" : Formats.money(txn.decimal, txn.currency)), icon: "bill", route: .bill(txn.id)))
                }
            }
        }
        if kind == .all || kind == .charges {
            for entry in store.entries.values where entry.charge != nil && (year == nil || entry.at.hasPrefix(year!)) {
                let station = entry.charge?["station"]?.string ?? ""
                if matches(station) || query.isEmpty {
                    out.append(Result(id: entry.uid, day: entry.day, title: station.isEmpty ? String(localized: "record.charge") : station, detail: Formats.day(entry.day), icon: "battery-charging", route: .day(entry.day)))
                }
            }
        }
        return out.sorted { $0.day > $1.day }
    }

    private func open(_ result: Result) { router.push(result.route) }
}

private struct ResultRow: View {
    var result: SearchView.Result

    var body: some View {
        HStack(alignment: .top, spacing: Space.m) {
            Icon(result.icon, size: 20).foregroundStyle(Tokens.tx2).padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: result.title).textStyle(.body).foregroundStyle(Tokens.tx).lineLimit(2)
                Text(verbatim: result.detail).textStyle(.caption).foregroundStyle(Tokens.tx2)
            }
        }
        .contentShape(.rect)
    }
}
