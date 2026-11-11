import CoreLocation
import HereKit
import MapKit
import SwiftUI

/// What the panel shows under its search field.
enum Chosen: Hashable {
    case place(String)
    case trip(String)

    /// As the recents keep it.
    var key: String {
        switch self {
        case let .place(id): "p:\(id)"
        case let .trip(uid): "t:\(uid)"
        }
    }

    init?(key: String) {
        if key.hasPrefix("p:") { self = .place(String(key.dropFirst(2))) } else if key.hasPrefix("t:") { self = .trip(String(key.dropFirst(2))) } else { return nil }
    }
}

/// Map (M09), the whole of the Mac app, used as Maps on the Mac is: the map fills the window with
/// every place been to as a MapKit balloon or a photo, each trip's route and the fog. A floating
/// panel at the top left (the toolbar's sidebar button or ⌃⌘S shows and hides it) holds the search
/// over places and trips, the recent ones while it is empty, and the card of what is chosen: a
/// place clicked on the map (M10) or a place or trip picked. Down the top right, as in Maps, are a
/// glass capsule with the fog and My Location, MapKit's zoom stepper and its compass.
struct MapPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var scheme
    @AppStorage("map.panel") private var panelShown = true
    @State private var atlas = Atlas()
    @State private var position: MapCameraPosition = .automatic
    @State private var fitted = false
    @State private var year: Int?
    @State private var selection: String?
    @State private var chosen: Chosen?
    @State private var query = ""
    @State private var fog: FogShape?
    @State private var fogOn = false
    @State private var visible: MKMapRect?
    @State private var titlebarHovered = false
    @Namespace private var scope
    @FocusState private var searching: Bool

    var body: some View {
        map
            .overlay(alignment: .topLeading) {
                if panelShown {
                    MapPanel(
                        atlas: atlas,
                        query: $query,
                        chosen: chosen,
                        recents: recents,
                        searching: $searching,
                        choose: choose,
                        close: close
                    )
                    .padding(.leading, 12)
                    .padding(.top, 8)
                    .transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
            .animation(.snappy, value: panelShown)
            .navigationTitle(L.t("map.title"))
            .toolbar { toolbar }
            // As in Maps, the map runs up under the toolbar with no bar of its own: a thin blurred
            // band fades in only while the pointer is over the top of the window,
            // where it can be dragged; the toolbar's own items always float on glass.
            .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
            .overlay(alignment: .top) {
                // Maps' band: a thin blur the map shows through, fading out at its lower edge, no line.
                Rectangle()
                    .fill(.ultraThinMaterial)
                    .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.7), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                    .frame(height: 56)
                    .opacity(titlebarHovered ? 1 : 0)
                    .allowsHitTesting(false)
                    .ignoresSafeArea(.container, edges: .top)
            }
            .modifier(TitlebarHover(hovering: $titlebarHovered))
            .task(id: app.store.version) {
                atlas = Atlas.build(app)
                if !fitted { fitAll() }
            }
            .modifier(FogLoader(on: fogOn, shape: $fog))
            .onAppear { fogOn = app.fogOn }
            .onChange(of: fogOn) { app.fogOn = fogOn }
            .onChange(of: selection) {
                if let selection, chosen != .place(selection) { choose(.place(selection)) }
            }
            .onChange(of: app.findRequest) {
                panelShown = true
                searching = true
            }
            .onChange(of: app.panelRequest) { panelShown.toggle() }
            .onExitCommand {
                if !query.isEmpty { query = "" } else if chosen != nil { close() }
            }
    }

    // MARK: Choosing

    private func choose(_ what: Chosen) {
        chosen = what
        query = ""
        searching = false
        panelShown = true
        remember(what)
        switch what {
        case let .place(id):
            selection = id
            focus(on: id)
        case let .trip(uid):
            selection = nil
            if let trip = atlas.trips.first(where: { $0.id == uid }) { focus(on: trip) }
        }
    }

    private func close() {
        chosen = nil
        selection = nil
    }

    /// The places and trips chosen lately, the latest first, as Maps keeps its recents.
    private var recents: [Chosen] {
        _ = chosen
        let keys = app.preference("map.recents") as? [String] ?? []
        return keys.compactMap(Chosen.init(key:)).filter { chosen in
            switch chosen {
            case let .place(id): atlas.places.contains { $0.id == id }
            case let .trip(uid): atlas.trips.contains { $0.id == uid }
            }
        }
    }

    private func remember(_ what: Chosen) {
        var keys = app.preference("map.recents") as? [String] ?? []
        keys.removeAll { $0 == what.key }
        keys.insert(what.key, at: 0)
        app.setPreference("map.recents", Array(keys.prefix(8)))
    }

    private var chosenTrip: String? {
        if case let .trip(uid) = chosen { return uid }
        return nil
    }

    private var trips: [TripSummary] {
        guard let year else { return atlas.trips }
        return atlas.trips.filter { $0.trip.year == year }
    }

    /// The places in sight, the most been to first, as many as the map draws quickly.
    private var places: [PlaceSummary] {
        var shown = atlas.places
        if let year { shown = shown.filter { $0.years.contains(year) } }
        if let visible {
            let around = visible.insetBy(dx: -visible.width * 0.25, dy: -visible.height * 0.25)
            shown = shown.filter { around.contains(MKMapPoint($0.place.coordinate)) }
        }
        shown.sort { $0.count > $1.count }
        var kept = Array(shown.prefix(400))
        if let id = selection, !kept.contains(where: { $0.id == id }), let place = atlas.places.first(where: { $0.id == id }) {
            kept.append(place)
        }
        return kept
    }

    // MARK: The map

    private var map: some View {
        let places = places
        let photos = Set(places.filter { $0.photo != nil }.prefix(80).map(\.id))
        let highlighted = chosenTrip
        return Map(position: $position, selection: $selection, scope: scope) {
            if fogOn, let fog { FogContent(shape: fog, ink: scheme.ink) }
            ForEach(trips) { trip in
                let color = atlas.color(trip.trip.year)
                let faded = highlighted != nil && highlighted != trip.id
                let width: CGFloat = highlighted == trip.id ? 6 : 3
                ForEach(Array(trip.segments.enumerated()), id: \.offset) { _, segment in
                    MapPolyline(coordinates: segment.line)
                        .stroke(
                            color.opacity(faded ? 0.22 : 0.9),
                            style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round, dash: segment.straight ? [2, 7] : [])
                        )
                }
            }
            ForEach(places) { summary in
                let color = atlas.color(summary.firstYear)
                if photos.contains(summary.id), let photo = summary.photo {
                    Annotation(summary.place.name, coordinate: summary.place.coordinate, anchor: .center) {
                        PhotoPin(item: photo, color: color, selected: selection == summary.id)
                            .contextMenu { PlaceMenu(place: summary.place) }
                    }
                    .annotationTitles(selection == summary.id ? .visible : .hidden)
                    .tag(summary.id)
                } else {
                    Marker(summary.place.name, systemImage: PlaceKinds.symbol(summary.place.kind), coordinate: summary.place.coordinate)
                        .tint(color)
                        .tag(summary.id)
                }
            }
            UserAnnotation()
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
        .mapControls {}
        .onMapCameraChange(frequency: .onEnd) { context in
            visible = context.rect
        }
        .overlay(alignment: .bottomLeading) {
            if atlas.years.count > 1 {
                YearLegend(years: atlas.years, color: atlas.color, selected: $year)
            }
        }
        .overlay(alignment: .topTrailing) {
            MapButtons(fogOn: $fogOn, scope: scope) { here in
                withAnimation { position = .camera(MapCamera(centerCoordinate: here, distance: 6000)) }
            }
        }
        .mapScope(scope)
        .ignoresSafeArea(.container, edges: .top)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            Button {
                panelShown.toggle()
            } label: {
                Label(L.t(panelShown ? "panel.hide" : "panel.show"), systemImage: "sidebar.left")
            }
            .help(L.t(panelShown ? "panel.hide" : "panel.show"))
        }
    }

    private func fitAll() {
        let points = atlas.trips.flatMap(\.points) + atlas.places.map(\.place.coordinate)
        guard !points.isEmpty else { return }
        fitted = true
        if let fit = Geo.position(points, margin: 0.08) { position = fit }
    }

    /// Brings [trip]'s route into view.
    private func focus(on trip: TripSummary) {
        guard let fit = Geo.position(trip.points, margin: 0.12) else { return }
        fitted = true
        withAnimation(.smooth) { position = fit }
    }

    private func focus(on id: String) {
        guard let place = app.store.places[id] else { return }
        fitted = true
        withAnimation(.smooth) { position = .camera(MapCamera(centerCoordinate: place.coordinate, distance: 4000)) }
    }
}

/// The floating panel at the top left, as Maps on the Mac draws it in Liquid Glass: a search field
/// over places and trips; while it holds text, what matches, grouped into Trips and Places, with
/// ↑↓ to move and ↩ to open; while it is empty, the recents; with something chosen, its card.
struct MapPanel: View {
    let atlas: Atlas
    @Binding var query: String
    let chosen: Chosen?
    let recents: [Chosen]
    var searching: FocusState<Bool>.Binding
    let choose: (Chosen) -> Void
    let close: () -> Void
    @State private var highlighted = 0
    @Environment(AppModel.self) private var app

    private var typing: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            field
            if typing {
                Divider().padding(.horizontal, 12)
                results
            } else if let chosen {
                Divider().padding(.horizontal, 12)
                switch chosen {
                case let .place(id):
                    if app.store.places[id] != nil { PlaceInspector(id: id, close: close).frame(width: 340).frame(maxHeight: 640).clipped() }
                case let .trip(uid):
                    if let trip = atlas.trips.first(where: { $0.id == uid }) {
                        TripCard(summary: trip, color: atlas.color(trip.trip.year), close: close)
                    }
                }
            } else if !recents.isEmpty {
                Divider().padding(.horizontal, 12)
                list([(L.t("search.recents"), recents.compactMap(match))])
            }
        }
        .frame(width: 340)
        .fixedSize(horizontal: false, vertical: true)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .onChange(of: query) { highlighted = 0 }
    }

    private var field: some View {
        HStack(spacing: 7) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(L.t("map.search"), text: $query)
                .textFieldStyle(.plain)
                .focused(searching)
                .onSubmit {
                    let all = flat
                    if all.indices.contains(highlighted) { choose(all[highlighted].chosen) }
                }
                .onKeyPress(.downArrow) {
                    guard typing else { return .ignored }
                    highlighted = min(highlighted + 1, max(flat.count - 1, 0))
                    return .handled
                }
                .onKeyPress(.upArrow) {
                    guard typing else { return .ignored }
                    highlighted = max(highlighted - 1, 0)
                    return .handled
                }
            if !query.isEmpty {
                Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L.t("action.close"))
            }
        }
        .font(.title3)
        .padding(.horizontal, 12)
        .frame(height: 40)
    }

    struct Match: Identifiable {
        let id: String
        let chosen: Chosen
        let icon: String
        let color: Color
        let title: String
        let detail: String
    }

    private func match(_ chosen: Chosen) -> Match? {
        switch chosen {
        case let .trip(uid):
            guard let trip = atlas.trips.first(where: { $0.id == uid }) else { return nil }
            return Match(id: chosen.key, chosen: chosen, icon: "suitcase.fill", color: .teal, title: trip.trip.title,
                         detail: "\(Formats.shortDay(trip.trip.startDay))　\(L.f("trip.days", trip.trip.days))")
        case let .place(id):
            guard let place = atlas.places.first(where: { $0.id == id }) else { return nil }
            return Match(id: chosen.key, chosen: chosen, icon: PlaceKinds.symbol(place.place.kind), color: atlas.color(place.firstYear), title: place.place.name,
                         detail: [place.place.area, L.f("place.momentCountShort", place.count)].filter { !$0.isEmpty }.joined(separator: "　"))
        }
    }

    /// What matches, grouped: trips by name, then places by name or area, the most been to first.
    private var groups: [(String, [Match])] {
        let text = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !text.isEmpty else { return [] }
        let trips = atlas.trips.filter { $0.trip.title.lowercased().contains(text) }.prefix(6).compactMap { match(.trip($0.id)) }
        let places = atlas.places
            .filter { $0.place.name.lowercased().contains(text) || $0.place.area.lowercased().contains(text) }
            .sorted { $0.count > $1.count }
            .prefix(20)
            .compactMap { match(.place($0.id)) }
        return [(L.t("search.trips"), trips), (L.t("search.places"), places)].filter { !$0.1.isEmpty }
    }

    private var flat: [Match] { groups.flatMap(\.1) }

    private var results: some View {
        Group {
            if groups.isEmpty {
                Text(L.t("map.search.none"))
                    .foregroundStyle(.secondary)
                    .padding(16)
            } else {
                list(groups, highlight: flat.indices.contains(highlighted) ? flat[highlighted].id : nil)
            }
        }
    }

    private func list(_ groups: [(String, [Match])], highlight: String? = nil) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(groups, id: \.0) { title, matches in
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.top, 10)
                        .padding(.bottom, 4)
                    ForEach(matches) { match in
                        row(match, highlighted: match.id == highlight)
                    }
                }
            }
            .padding(.bottom, 6)
        }
        .frame(maxHeight: 560)
    }

    private func row(_ match: Match, highlighted: Bool) -> some View {
        Button { choose(match.chosen) } label: {
            HStack(spacing: 10) {
                IconTile(match.icon, color: match.color, size: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(match.title).lineLimit(1)
                    if !match.detail.isEmpty {
                        Text(match.detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .frame(minHeight: 44)
            .background(highlighted ? AnyShapeStyle(.tint.opacity(0.18)) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            switch match.chosen {
            case let .place(id): if let place = app.store.places[id] { PlaceMenu(place: place) }
            case let .trip(uid): if let trip = atlas.trips.first(where: { $0.id == uid }) { TripMenu(trip: trip.trip) }
            }
        }
    }
}

/// A trip's card in the panel, as Maps draws a place: its name and dates, a row of actions (the
/// site, Share), and its details: how long, how far, how many moments.
struct TripCard: View {
    let summary: TripSummary
    let color: Color
    let close: () -> Void

    var body: some View {
        let trip = summary.trip
        VStack(alignment: .leading, spacing: 16) {
            CardHeader(title: trip.title, line: Formats.span(trip), close: close)
            if let url = Site.trip(trip) {
                HStack(spacing: 8) {
                    Link(destination: url) {
                        CardAction(prominent: true) { Label(L.t("action.site"), systemImage: "safari.fill") }
                    }
                    ShareLink(item: url) {
                        CardAction { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
                    }
                }
                .buttonStyle(.plain)
            }
            CardSection(L.t("place.details")) {
                CardFact(name: L.t("trip.length"), value: L.f("trip.days", trip.days))
                if summary.distance > 0 {
                    Divider()
                    CardFact(name: L.t("trip.distance"), value: Formats.distance(summary.distance))
                }
                Divider()
                CardFact(name: L.t("place.momentCount"), value: "\(summary.moments)")
            }
        }
        .padding(16)
    }
}

/// The kinds a place can be (`placeKindGroups` in packages/data-spec/src/index.ts), by group,
/// and the symbol its balloon shows.
enum PlaceKinds {
    static let groups: [(group: String, kinds: [String])] = [
        ("area", ["city", "town", "region"]),
        ("food", ["food", "cafe", "tea", "bar", "dessert"]),
        ("shopping", ["shopping", "supermarket", "market"]),
        ("stay", ["stay", "camp"]),
        ("scenic", ["scenic", "park", "museum", "temple", "nature"]),
        ("leisure", ["leisure", "show", "sport"]),
        ("transport", ["transport", "station", "metro", "bus", "airport", "port", "parking", "charging", "fuel"]),
        ("life", ["life", "hospital", "school", "work", "residential"]),
    ]

    static func symbol(_ kind: String?) -> String {
        switch kind {
        case "cafe": "cup.and.saucer.fill"
        case "tea", "bar": "wineglass.fill"
        case "dessert": "birthday.cake.fill"
        case "camp": "tent.fill"
        case "park", "nature": "tree.fill"
        case "museum": "building.columns.fill"
        case "temple": "building.fill"
        case "show": "theatermasks.fill"
        case "sport": "figure.run"
        case "station": "tram.fill"
        case "metro": "tram.tunnel.fill"
        case "bus": "bus.fill"
        case "airport": "airplane"
        case "port": "ferry.fill"
        case "parking": "parkingsign"
        case "charging": "bolt.car.fill"
        case "fuel": "fuelpump.fill"
        case "hospital": "cross.case.fill"
        case "school": "graduationcap.fill"
        case "work": "briefcase.fill"
        case "residential": "house.fill"
        case let kind?:
            switch groups.first(where: { $0.kinds.contains(kind) })?.group {
            case "area": "building.2.fill"
            case "food": "fork.knife"
            case "shopping": "bag.fill"
            case "stay": "bed.double.fill"
            case "scenic": "camera.fill"
            case "leisure": "star.fill"
            case "transport": "car.fill"
            case "life": "storefront.fill"
            default: "mappin"
            }
        case nil: "mappin"
        }
    }
}
