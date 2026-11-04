import MapKit
import SwiftUI

// A day's page (the design's 05): that day's footprints on a map running under the circles, its
// date large under it with how far it went and what it spent, the trip it is in, and what happened
// read down by its times: bills and charges a line each, Moments with their photos and words.

struct DayView: View {
    var day: String

    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(FootprintStore.self) private var footprints
    @Environment(Router.self) private var router

    var body: some View {
        let entries = (store.days[day] ?? []).sorted { $0.date < $1.date }
        let site = Set(entries.map(\.uid))
        let bills = ledger.txns(on: day).filter { !site.contains($0.id) }
        let lines = { _ = footprints.version; return FootprintStore.lines(footprints.samples(day: day)) }()
        let pins = entries.filter(\.isMoment).compactMap { entry -> MapPin? in
            guard let id = entry.place, let place = store.places[id] else { return nil }
            return MapPin(id: id, coordinate: place.coordinate, title: place.name, color: yearColor(store.firstVisits[id].flatMap { store.yearColor[$0] }))
        }
        let spent = ledger.ledger.sums(ledger.ledger.entries(of: ledger.txns(on: day))).spent
        let trip = store.trips.first { day >= $0.start && day <= $0.end }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !lines.isEmpty || !pins.isEmpty {
                    HereMap(
                        pins: Array(Set(pins)),
                        lines: lines.enumerated().map { MapLine(id: "d\($0.offset)", coordinates: $0.element.map(\.coordinate), style: .footprints, color: Tokens.tx) },
                        frame: MapFrame(id: day, coordinates: lines.flatMap { $0.map(\.coordinate) } + pins.map(\.coordinate), animated: false),
                        onSelect: { id in if let id { router.push(.place(id)) } },
                        insets: UIEdgeInsets(top: 100, left: 30, bottom: 30, right: 30)
                    )
                    .frame(height: 320)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: Formats.day(day)).textStyle(.titlePage, strong: true).foregroundStyle(Tokens.tx)
                    let facts = [
                        lines.isEmpty ? nil : Formats.distance(FootprintStore.distance(lines)),
                        day == Times.day(Date()) && footprints.recording ? String(localized: "day.stillRecording") : nil,
                        spent > 0 ? String(localized: "day.spent \(ledger.hideAmounts ? "••••" : Formats.amount(spent))") : nil,
                    ].compactMap { $0 }
                    if !facts.isEmpty {
                        Text(verbatim: facts.joined(separator: Formats.gap)).textStyle(.caption).foregroundStyle(Tokens.tx2)
                    }
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.l)
                if let trip { TripBanner(trip: trip).padding(.top, Space.s) }
                VStack(alignment: .leading, spacing: Space.l) {
                    ForEach(DayLine.of(entries: entries, bills: bills, store: store, ledger: ledger)) { line in
                        line.view
                    }
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.l)
                if entries.isEmpty && bills.isEmpty && lines.isEmpty {
                    Text("day.empty").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity).padding(.top, 80)
                }
            }
            .padding(.bottom, Space.xl)
        }
        .ignoresSafeArea(edges: lines.isEmpty && pins.isEmpty ? [] : .top)
        .page(Text(verbatim: Formats.day(day)), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.cover = .newMoment(day) } label: { Icon("plus", size: 22) }
                    .accessibilityLabel(Text("action.newMoment"))
            }
        }
    }
}

/// One thing on a day's page, by its time.
struct DayLine: Identifiable {
    var id: String
    var date: Date
    var view: AnyView

    static func of(entries: [Entry], bills: [Txn], store: AppStore, ledger: LedgerStore) -> [DayLine] {
        var out: [DayLine] = []
        for entry in entries {
            if entry.isMoment {
                out.append(DayLine(id: entry.uid, date: entry.date, view: AnyView(DayMoment(entry: entry))))
            } else if let item = Chat.record(entry, store: store) {
                out.append(DayLine(id: entry.uid, date: entry.date, view: AnyView(DayRecord(item: item, place: entry.place.flatMap { store.places[$0]?.name }))))
            }
        }
        for txn in bills {
            let item = RecordItem(id: txn.id, time: Formats.time(txn.at), icon: BillLook.icon(txn, ledger.ledger), title: BillLook.title(txn, ledger.ledger), value: ledger.hideAmounts ? "••••" : BillLook.amount(txn), income: txn.isInflow, route: .bill(txn.id))
            out.append(DayLine(id: txn.id, date: txn.date, view: AnyView(DayRecord(item: item, place: txn.place.flatMap { store.places[$0]?.name } ?? (txn.note.isEmpty ? nil : txn.note)))))
        }
        return out.sorted { $0.date < $1.date }
    }
}

/// A bill or a charge on a day's page: its time, what it is and where, its amount.
private struct DayRecord: View {
    var item: RecordItem
    var place: String?
    @Environment(Router.self) private var router

    var body: some View {
        Button { if let route = item.route { router.push(route) } } label: {
            HStack(alignment: .firstTextBaseline, spacing: Space.m) {
                Text(verbatim: item.time ?? "").textStyle(.caption).foregroundStyle(Tokens.tx3).frame(width: 40, alignment: .leading)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: item.title).textStyle(.body).foregroundStyle(Tokens.tx)
                    if let place { Text(verbatim: place).textStyle(.caption).foregroundStyle(Tokens.tx2).lineLimit(1) }
                }
                Spacer(minLength: Space.m)
                Text(verbatim: item.value).textStyle(.body).foregroundStyle(item.income ? Tokens.income : Tokens.tx)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// A Moment on a day's page: its photos, its time and place, its words.
private struct DayMoment: View {
    var entry: Entry
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        Button { router.push(.moment(entry.uid)) } label: {
            VStack(alignment: .leading, spacing: Space.s) {
                if !entry.media.isEmpty {
                    MomentPhotos(media: entry.media).clipShape(.cardShape)
                }
                Text(verbatim: [Formats.time(entry.at), entry.place.flatMap { store.places[$0]?.name }].compactMap { $0 }.joined(separator: Formats.gap))
                    .textStyle(.body).foregroundStyle(Tokens.tx2)
                if !entry.text.isEmpty {
                    Text(verbatim: entry.text).textStyle(.bodyReading).foregroundStyle(Tokens.tx).multilineTextAlignment(.leading).lineLimit(10)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .zoomSource(entry.uid)
        .contextMenu { MomentMenu(entry: entry) }
    }
}

/// A place's page: its map running under the circles, its name large under it, the way there, and
/// its Moments in the same conversation as the timeline.
struct PlaceView: View {
    var id: String

    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        if let place = store.places[id] {
            let moments = store.moments(at: id).sorted { $0.date > $1.date }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HereMap(
                        pins: [MapPin(id: id, coordinate: place.coordinate, title: place.name, color: yearColor(store.firstVisits[id].flatMap { store.yearColor[$0] }), weight: 6)],
                        frame: MapFrame(id: id, coordinates: [place.coordinate], animated: false),
                        insets: UIEdgeInsets(top: 80, left: 0, bottom: 0, right: 0)
                    )
                    .frame(height: 320)
                    HStack(alignment: .bottom, spacing: Space.m) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: place.name).textStyle(.titlePage, strong: true).foregroundStyle(Tokens.tx)
                            Text(verbatim: [place.kind.map { String(localized: String.LocalizationValue("placeKind.\($0)")) }, place.division, String(localized: "place.moments \(moments.count)")].compactMap { $0 }.joined(separator: Formats.gap))
                                .textStyle(.caption).foregroundStyle(Tokens.tx2)
                        }
                        Spacer()
                        Button { Navigate.open(place) } label: { IconLabel(title: "place.directions", icon: "directions") }
                            .buttonStyle(.ink)
                    }
                    .padding(.horizontal, Space.gutter)
                    .padding(.top, Space.l)
                    if let note = place.note {
                        Text(verbatim: note).textStyle(.body).foregroundStyle(Tokens.tx).padding(.horizontal, Space.gutter).padding(.top, Space.m)
                    }
                    VStack(alignment: .trailing, spacing: Space.m) {
                        ForEach(moments) { entry in
                            VStack(alignment: .trailing, spacing: 7) {
                                Text(verbatim: Formats.day(entry.day)).textStyle(.label).foregroundStyle(Tokens.tx2)
                                    .padding(.horizontal, Space.s).padding(.vertical, 3).background(Tokens.fill, in: .capsule)
                                    .frame(maxWidth: .infinity)
                                MomentBubble(entry: entry, showPlace: false)
                                    .containerRelativeFrame(.horizontal, alignment: .trailing) { width, _ in width * 0.88 - 9 }
                            }
                        }
                    }
                    .padding(.trailing, Space.gutter + 9)
                    .padding(.top, Space.xl)
                }
                .padding(.bottom, Space.xl)
            }
            .ignoresSafeArea(edges: .top)
            .page(Text(verbatim: place.name), large: false)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker(selection: Binding(
                            get: { place.kind ?? "" },
                            set: { kind in
                                var json = place.json
                                json["kind"] = kind.isEmpty ? nil : JSON(kind)
                                store.savePlace(id: id, Place(json))
                            }
                        )) {
                            ForEach(Place.kinds, id: \.self) { Text(LocalizedStringKey("placeKind.\($0)")).tag($0) }
                        } label: {
                            Text("place.kind")
                        }
                        Button { router.showOnMap(.place(id)) } label: { Label("place.onMap", icon: "map") }
                    } label: {
                        Icon("sliders", size: 22)
                    }
                    .accessibilityLabel(Text("action.more"))
                }
            }
        } else {
            Text("place.gone").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity, maxHeight: .infinity).background(Tokens.bg)
        }
    }
}
