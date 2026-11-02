import MapKit
import SwiftUI

// A trip's page: its covers and route, what was spent, its days of Moments, 回放旅程, and its
// edit (编辑旅程: name, days, public switch, Moments and covers), published as a collection.
// 公开这段旅程的轨迹 makes its footprints public, only their line, at the next publish.

struct TripView: View {
    var id: String

    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(FootprintStore.self) private var footprints
    @Environment(Router.self) private var router
    @State private var editing = false

    var body: some View {
        if let trip = store.trip(id) {
            content(trip)
        } else {
            Text("trip.gone").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity, maxHeight: .infinity).background(Tokens.bg)
        }
    }

    private func content(_ trip: Trip) -> some View {
        let spent = ledger.ledger.txns.filter { $0.trip == trip.id || ($0.day >= trip.start && $0.day <= trip.end && ledger.ledger.book($0.book)?.name.contains(trip.title) == true) }
        let total = ledger.ledger.sums(ledger.ledger.entries(of: spent)).spent
        let first = Times.date(trip.start) ?? Date()
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TripRouteMap(trip: trip)
                    .frame(height: 280)
                HStack(alignment: .bottom, spacing: Space.m) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: trip.title).textStyle(.titlePage, strong: true).foregroundStyle(Tokens.tx)
                        Text(verbatim: [Formats.span(trip.start, trip.end), String(localized: "trip.dayCount \(trip.dayCount)"), trip.isPrivate ? String(localized: "visibility.private") : nil].compactMap { $0 }.joined(separator: Formats.gap))
                            .textStyle(.caption).foregroundStyle(Tokens.tx2)
                    }
                    Spacer()
                    Button { router.push(.play(trip.id)) } label: { IconLabel(title: "trip.play", icon: "play") }
                        .buttonStyle(.ink)
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.l)
                StatRow(stats: [
                    ("\(trip.stops.count)", String(localized: "trip.stops")),
                    ("\(trip.moments.count)", String(localized: "trip.moments")),
                    (ledger.hideAmounts ? "••••" : Formats.amount(total), String(localized: "trip.spent")),
                ])
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.l)
                SwitchRow(icon: "route", title: String(localized: "trip.shareTrack"), detail: String(localized: "trip.shareTrackDetail"), isOn: Binding(
                    get: { footprints.share(of: trip.collection.uid) != nil },
                    set: { footprints.setShared($0, trip: trip) }
                ))
                .padding(.horizontal, Space.gutter - Space.l)
                .padding(.top, Space.l)
                ForEach(trip.days, id: \.day) { day in
                    let number = Int(((Times.date(day.day) ?? first).timeIntervalSince(first) / 86400).rounded()) + 1
                    ChatDay(day: day.day, trip: trip, tripDay: number)
                }
                .padding(.top, Space.s)
            }
            .padding(.bottom, Space.xl)
        }
        .ignoresSafeArea(edges: .top)
        .page(Text(verbatim: trip.title), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.showOnMap(.trip(trip.id)) } label: { Icon("map", size: 22) }
                    .accessibilityLabel(Text("place.onMap"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { editing = true } label: { Icon("pen", size: 22) }
                    .accessibilityLabel(Text("trip.edit"))
            }
        }
        .sheet(isPresented: $editing) { TripEditor(trip: trip) }
    }
}

/// A trip's stops and route on a map.
struct TripRouteMap: View {
    var trip: Trip
    var progress: Double? = nil
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        HereMap(
            pins: trip.stops.map { stop in
                MapPin(id: stop.placeId, coordinate: stop.place.coordinate, title: stop.place.name, color: yearColor(store.firstVisits[stop.placeId].flatMap { store.yearColor[$0] }))
            },
            lines: TripLines.of(trip, routes: store.routes),
            frame: MapFrame(id: trip.id, coordinates: trip.stops.map(\.place.coordinate), animated: false),
            onSelect: { id in if let id { router.push(.place(id)) } },
            insets: UIEdgeInsets(top: 110, left: 40, bottom: 40, right: 40)
        )
    }
}

enum TripLines {
    /// A trip's route as the API's routes draw it, else straight between its stops.
    static func of(_ trip: Trip, routes: [String: [RouteStretch]]) -> [MapLine] {
        if let stretches = routes[trip.id], !stretches.isEmpty {
            return stretches.enumerated().map { index, stretch in
                MapLine(id: "\(trip.id)-\(index)", coordinates: coordinates(stretch), style: stretch.straight ? .straight : .route)
            }
        }
        return [MapLine(id: trip.id, coordinates: trip.stops.map(\.place.coordinate), style: .straight)]
    }

    static func coordinates(_ stretch: RouteStretch) -> [CLLocationCoordinate2D] {
        stretch.line.compactMap { $0.count >= 2 ? CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) : nil }
    }

    /// The whole route as one line, for playback.
    static func path(_ trip: Trip, routes: [String: [RouteStretch]]) -> [CLLocationCoordinate2D] {
        if let stretches = routes[trip.id], !stretches.isEmpty { return stretches.flatMap(coordinates) }
        return trip.stops.map(\.place.coordinate)
    }
}

/// 编辑旅程: its name, its days, whether it is public, and its covers, kept as a change to its collection.
struct TripEditor: View {
    var trip: Trip

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var start = Date()
    @State private var end = Date()
    @State private var isPrivate = false
    @State private var covers: [String] = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("trip.name", text: $title)
                    DatePicker("trip.start", selection: $start, displayedComponents: .date)
                    DatePicker("trip.end", selection: $end, in: start..., displayedComponents: .date)
                    Toggle("visibility.private", isOn: $isPrivate)
                }
                Section("trip.covers") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: Space.s) {
                            ForEach(trip.moments.flatMap { entry in entry.media.filter { !$0.utility }.map { (entry, $0) } }, id: \.1.uid) { entry, media in
                                Button {
                                    let key = "\(entry.uid)/\(media.uid)"
                                    if let index = covers.firstIndex(of: key) { covers.remove(at: index) } else { covers.append(key) }
                                } label: {
                                    MediaImage(media: media, width: 80)
                                        .frame(width: 80, height: 80)
                                        .clipShape(.mediumShape)
                                        .overlay(alignment: .topTrailing) {
                                            if let index = covers.firstIndex(of: "\(entry.uid)/\(media.uid)") {
                                                Text(verbatim: "\(index + 1)")
                                                    .textStyle(.caption, strong: true)
                                                    .foregroundStyle(Tokens.onAccent)
                                                    .frame(width: 22, height: 22)
                                                    .background(Tokens.accent, in: .circle)
                                                    .padding(4)
                                            }
                                        }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("trip.edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Label("action.cancel", icon: "close") } }
                ToolbarItem(placement: .confirmationAction) { Button { save() } label: { Label("action.save", icon: "check") }.disabled(title.isEmpty) }
            }
            .onAppear {
                title = trip.title
                start = Times.date(trip.start) ?? Date()
                end = Times.date(trip.end) ?? Date()
                isPrivate = trip.isPrivate
                covers = trip.collection.covers.map { "\($0.entry)/\($0.media)" }
            }
        }
    }

    private func save() {
        var json = trip.collection.json
        json["title"] = JSON(title)
        json["span"] = ["start": JSON(Times.day(start)), "end": JSON(Times.day(end))]
        json["visibility"] = isPrivate ? "private" : nil
        json["covers"] = .array(covers.map { key in
            let parts = key.split(separator: "/")
            return ["entry": JSON(String(parts[0])), "media": JSON(String(parts[1]))]
        })
        store.saveCollection(Collection(json))
        dismiss()
    }
}
