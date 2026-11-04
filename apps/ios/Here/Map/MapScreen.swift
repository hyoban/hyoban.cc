import MapKit
import SwiftUI

// 地图: the places in the colour of the year they were first visited, the trips' routes and the
// footprints, the fog of war over it behind a switch. The legend of years and the switches float
// in glass at the top; at the foot, the trips in a row of cards, or the place or trip tapped.

struct MapScreen: View {
    @Environment(AppStore.self) private var store
    @Environment(FootprintStore.self) private var footprints
    @Environment(Router.self) private var router

    @AppStorage("map-fog") private var fogOn = false
    @AppStorage("map-footprints") private var footprintsOn = false
    @State private var fog: FogOverlay?
    @State private var selectedPlace: String?
    @State private var selectedTrip: String?
    @State private var frame: MapFrame?

    var body: some View {
        HereMap(
            pins: pins,
            lines: lines,
            fog: fogOn ? fog : nil,
            frame: frame,
            showsUser: footprints.authorization == .authorizedWhenInUse || footprints.authorization == .authorizedAlways,
            selected: selectedPlace,
            onSelect: { id in
                withAnimation(Springs.spatialDefault) {
                    selectedPlace = id
                    if id != nil { selectedTrip = nil }
                }
            }
        )
        .ignoresSafeArea()
        .overlay(alignment: .top) { header }
        .safeAreaInset(edge: .bottom) { footer }
        .toolbarVisibility(.hidden, for: .navigationBar)
        .task(id: fogOn) { await buildFog() }
        .task(id: store.moments.count) { if fogOn { await buildFog() } }
        .onAppear { takeFocus() }
        .onChange(of: router.mapFocus) { takeFocus() }
        .accessibilityIdentifier("tab_map")
    }

    private func takeFocus() {
        guard let focus = router.mapFocus else {
            if frame == nil { frame = MapFrame(id: "all", coordinates: pins.map(\.coordinate), animated: false) }
            return
        }
        router.mapFocus = nil
        switch focus {
        case .place(let id):
            selectedPlace = id
            selectedTrip = nil
            if let place = store.places[id] { frame = MapFrame(id: "place-\(id)-\(Date().timeIntervalSince1970)", coordinates: [place.coordinate]) }
        case .trip(let id):
            selectTrip(id)
        }
    }

    private func selectTrip(_ id: String) {
        withAnimation(Springs.spatialDefault) {
            selectedTrip = id
            selectedPlace = nil
        }
        if let trip = store.trip(id) {
            frame = MapFrame(id: "trip-\(id)-\(Date().timeIntervalSince1970)", coordinates: trip.stops.map(\.place.coordinate))
        }
    }

    private func buildFog() async {
        guard fogOn else { return }
        let regions = await Fog.regions()
        let weights = Dictionary(grouping: store.moments.compactMap(\.place), by: { $0 }).compactMap { id, list in
            store.places[id].map { ($0.coordinate, list.count) }
        }
        fog = await Fog.overlay(regions: regions, places: weights)
    }

    // MARK: What is drawn

    private var pins: [MapPin] {
        let counts = Dictionary(grouping: store.moments.compactMap(\.place), by: { $0 }).mapValues(\.count)
        return counts.compactMap { id, count in
            guard let place = store.places[id] else { return nil }
            return MapPin(id: id, coordinate: place.coordinate, title: place.name, color: yearColor(store.firstVisits[id].flatMap { store.yearColor[$0] }), weight: count)
        }
    }

    private var lines: [MapLine] {
        var out: [MapLine] = []
        for trip in store.trips {
            let highlighted = selectedTrip == nil || selectedTrip == trip.id
            let color = highlighted ? Tokens.accent : Tokens.borderStrong
            if let stretches = store.routes[trip.id], !stretches.isEmpty {
                for (index, stretch) in stretches.enumerated() {
                    let coordinates = stretch.line.compactMap { point in point.count >= 2 ? CLLocationCoordinate2D(latitude: point[1], longitude: point[0]) : nil }
                    out.append(MapLine(id: "\(trip.id)-\(index)", coordinates: coordinates, style: stretch.straight ? .straight : .route, color: color))
                }
            } else if trip.stops.count >= 2 {
                out.append(MapLine(id: trip.id, coordinates: trip.stops.map(\.place.coordinate), style: .straight, color: color))
            }
        }
        for track in store.tracks.values {
            for (index, line) in track.lines.enumerated() {
                out.append(MapLine(id: "track-\(track.id)-\(index)", coordinates: line, style: .track, color: Tokens.tx))
            }
        }
        if footprintsOn {
            _ = footprints.version
            let today = FootprintStore.lines(footprints.samples(day: Times.day(Date())))
            for (index, line) in today.enumerated() {
                out.append(MapLine(id: "footprints-\(index)-\(line.count)", coordinates: line.map(\.coordinate), style: .footprints, color: Tokens.tx))
            }
        }
        return out
    }

    // MARK: Over the map

    private var years: [(year: Int, color: Color)] {
        store.yearColor.keys.sorted().map { ($0, yearColor(store.yearColor[$0])) }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            HStack(spacing: Space.s) {
                Button { router.push(.search) } label: {
                    HStack(spacing: Space.s) {
                        Icon("search", size: 20).foregroundStyle(Tokens.tx2)
                        Text("map.search").textStyle(.body).foregroundStyle(Tokens.tx3)
                        Spacer()
                    }
                    .padding(.horizontal, Space.l)
                    .frame(height: 44)
                    .glassEffect(.regular.interactive(), in: .capsule)
                }
                .buttonStyle(.plain)
                FloatingButton(icon: "fog", label: "map.fog", on: fogOn) {
                    withAnimation(Springs.effectsDefault) { fogOn.toggle() }
                }
            }
            if !years.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(years, id: \.year) { year in
                            HStack(spacing: Space.xs) {
                                Circle().fill(year.color).frame(width: 8, height: 8)
                                Text(verbatim: String(year.year)).textStyle(.caption).foregroundStyle(Tokens.tx2)
                            }
                        }
                        if !store.trips.isEmpty {
                            Rectangle().fill(Tokens.border).frame(width: 1, height: 14)
                            HStack(spacing: Space.xs) {
                                Capsule().fill(Tokens.tx.opacity(0.4)).frame(width: 14, height: 3)
                                Text("map.trips").textStyle(.caption).foregroundStyle(Tokens.tx2)
                            }
                        }
                    }
                    .padding(.horizontal, Space.m)
                    .frame(height: 28)
                }
                .fixedSize(horizontal: true, vertical: false)
                .glassEffect(.regular, in: .capsule)
                .accessibilityElement(children: .combine)
            }
            if fogOn, let fog {
                Text("map.fogCount \(fog.cities) \(fog.provinces)")
                    .textStyle(.caption).foregroundStyle(Tokens.tx2)
                    .padding(.horizontal, Space.m)
                    .frame(height: 28)
                    .glassEffect(.regular, in: .capsule)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, Space.l)
        .padding(.top, Space.s)
        .overlay(alignment: .topTrailing) {
            VStack(spacing: Space.s) {
                FloatingButton(icon: "footprints", label: "map.footprints", on: footprintsOn, dot: footprints.recording) {
                    if !footprintsOn { footprints.requestWhenInUse() }
                    withAnimation(Springs.effectsDefault) { footprintsOn.toggle() }
                }
            }
            .padding(.trailing, Space.s)
            .padding(.top, 44 + Space.s * 2 + 28)
        }
    }

    @ViewBuilder
    private var footer: some View {
        Group {
            if let id = selectedPlace, let place = store.places[id] {
                PlaceCard(id: id, place: place) { withAnimation(Springs.spatialDefault) { selectedPlace = nil } }
                    .padding(.horizontal, Space.gutter)
            } else if let id = selectedTrip, let trip = store.trip(id) {
                TripMapCard(trip: trip) { withAnimation(Springs.spatialDefault) { selectedTrip = nil } }
                    .padding(.horizontal, Space.gutter)
            } else if !store.trips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s) {
                        ForEach(store.trips) { trip in
                            Button { selectTrip(trip.id) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: trip.title).textStyle(.label).lineLimit(1)
                                    Text(verbatim: Formats.span(trip.start, trip.end)).textStyle(.caption).foregroundStyle(Tokens.tx2)
                                }
                                .padding(.horizontal, Space.l)
                                .padding(.vertical, Space.m)
                                .frame(minWidth: 140, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            .glassEffect(.regular.interactive(), in: .rect(cornerRadius: Radius.large))
                            .accessibilityIdentifier("trip_\(trip.id)")
                        }
                    }
                    .padding(.horizontal, Space.gutter)
                }
                .scrollClipDisabled()
            }
        }
        .padding(.bottom, 72)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

/// A place tapped on the map: its name, its Moments, the way there and its page.
private struct PlaceCard: View {
    var id: String
    var place: Place
    var close: () -> Void
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        let moments = store.moments(at: id)
        VStack(alignment: .leading, spacing: Space.m) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: place.name).textStyle(.title, strong: true)
                    Text("place.moments \(moments.count)").textStyle(.caption).foregroundStyle(Tokens.tx2)
                }
                Spacer()
                Button(action: close) { Icon("close", size: 18).frame(width: 32, height: 32) }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel(Text("action.close"))
            }
            if let media = moments.lazy.flatMap(\.media).first(where: { !$0.utility }) {
                MediaImage(media: media, width: 360).frame(height: 120).clipShape(.mediumShape)
            }
            HStack(spacing: Space.s) {
                Button { router.push(.place(id)) } label: { Text("place.open").frame(maxWidth: .infinity) }
                    .buttonStyle(.glassProminent)
                Button { Navigate.open(place) } label: { Label("place.directions", icon: "directions").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass)
            }
            .controlSize(.large)
        }
        .padding(Space.l)
        .glassEffect(.regular, in: .rect(cornerRadius: Radius.large + 8))
    }
}

/// A trip chosen on the map: its days, and its page or its playback.
private struct TripMapCard: View {
    var trip: Trip
    var close: () -> Void
    @Environment(Router.self) private var router

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: trip.title).textStyle(.title, strong: true)
                    Text("trip.summary \(Formats.span(trip.start, trip.end)) \(trip.moments.count)").textStyle(.caption).foregroundStyle(Tokens.tx2)
                }
                Spacer()
                Button(action: close) { Icon("close", size: 18).frame(width: 32, height: 32) }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel(Text("action.close"))
            }
            HStack(spacing: Space.s) {
                Button { router.push(.trip(trip.id)) } label: { Text("trip.open").frame(maxWidth: .infinity) }
                    .buttonStyle(.glassProminent)
                Button { router.push(.play(trip.id)) } label: { Label("trip.play", icon: "play").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass)
            }
            .controlSize(.large)
        }
        .padding(Space.l)
        .glassEffect(.regular, in: .rect(cornerRadius: Radius.large + 8))
    }
}

/// The way to a place, in the system's Maps.
enum Navigate {
    static func open(_ place: Place) {
        let item = MKMapItem(location: CLLocation(latitude: place.latitude, longitude: place.longitude), address: nil)
        item.name = place.name
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
    }
}
