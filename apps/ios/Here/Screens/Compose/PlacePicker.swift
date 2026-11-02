import MapKit
import SwiftUI

// Choosing a Moment's place: the known places by name, else a search of MapKit's places, else the
// pin moved on a map. A place found or pinned is new, with a provisional `p-<ulid>` id until
// publishing names it; its kind follows MapKit's category and can be changed.

/// A place chosen for a Moment, and whether it is new.
struct PlaceChoice: Equatable {
    var id: String
    var place: Place
    var isNew: Bool

    /// The known place within 2 km of a photo's location, else a new one named by MapKit after
    /// what is there.
    static func near(_ location: CLLocationCoordinate2D, store: AppStore) async -> PlaceChoice? {
        let here = CLLocation(latitude: location.latitude, longitude: location.longitude)
        let nearest = store.places.min { a, b in
            CLLocation(latitude: a.value.latitude, longitude: a.value.longitude).distance(from: here)
                < CLLocation(latitude: b.value.latitude, longitude: b.value.longitude).distance(from: here)
        }
        if let nearest, CLLocation(latitude: nearest.value.latitude, longitude: nearest.value.longitude).distance(from: here) <= 2000 {
            return PlaceChoice(id: nearest.key, place: nearest.value, isNew: false)
        }
        guard let request = MKReverseGeocodingRequest(location: here), let item = try? await request.mapItems.first else { return nil }
        return new(name: item.name ?? item.address?.shortAddress ?? "", coordinate: location, kind: kind(item.pointOfInterestCategory))
    }

    static func new(name: String, coordinate: CLLocationCoordinate2D, kind: String?) -> PlaceChoice {
        var json: JSON = [
            "name": JSON(name),
            "latitude": JSON((coordinate.latitude * 1e5).rounded() / 1e5),
            "longitude": JSON((coordinate.longitude * 1e5).rounded() / 1e5),
            "createdAt": JSON(Times.now()),
        ]
        if let kind { json["kind"] = JSON(kind) }
        return PlaceChoice(id: "p-\(Ulid.make().lowercased())", place: Place(json), isNew: true)
    }

    /// The spec's kind for MapKit's category.
    static func kind(_ category: MKPointOfInterestCategory?) -> String? {
        switch category {
        case .restaurant?, .cafe?, .bakery?, .brewery?, .winery?, .foodMarket?: "food"
        case .hotel?, .campground?: "stay"
        case .store?: "shopping"
        case .airport?, .publicTransport?, .gasStation?, .evCharger?, .parking?: "transport"
        case .museum?, .park?, .nationalPark?, .beach?, .amusementPark?, .aquarium?, .zoo?, .theater?, .landmark?, .castle?, .nationalMonument?: "scenic"
        default: nil
        }
    }
}

struct PlacePicker: View {
    @Binding var choice: PlaceChoice?
    var near: CLLocationCoordinate2D?

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var found: [MKMapItem] = []
    @State private var pinning = false

    var body: some View {
        NavigationStack {
            List {
                if choice != nil {
                    Button("compose.noPlace", role: .destructive) { choice = nil; dismiss() }
                }
                if !found.isEmpty {
                    Section("place.found") {
                        ForEach(found, id: \.self) { item in
                            Button {
                                choice = PlaceChoice.new(name: item.name ?? "", coordinate: item.location.coordinate, kind: PlaceChoice.kind(item.pointOfInterestCategory))
                                dismiss()
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(verbatim: item.name ?? "").foregroundStyle(Tokens.tx)
                                    if let address = item.address?.shortAddress {
                                        Text(verbatim: address).textStyle(.caption).foregroundStyle(Tokens.tx2)
                                    }
                                }
                            }
                        }
                    }
                }
                Section("place.known") {
                    ForEach(known, id: \.id) { entry in
                        Button {
                            choice = PlaceChoice(id: entry.id, place: entry.place, isNew: false)
                            dismiss()
                        } label: {
                            HStack {
                                Text(verbatim: entry.place.name).foregroundStyle(Tokens.tx)
                                Spacer()
                                if let division = entry.place.division {
                                    Text(verbatim: division).textStyle(.caption).foregroundStyle(Tokens.tx2)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("place.search"))
            .task(id: query) { await search() }
            .navigationTitle(Text("compose.place"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("action.close", icon: "close") }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { pinning = true } label: { Label("place.pin", icon: "pin") }
                }
            }
            .sheet(isPresented: $pinning) {
                PinPlace(start: choice?.place.coordinate ?? near ?? store.places.values.first?.coordinate) { name, coordinate in
                    choice = PlaceChoice.new(name: name, coordinate: coordinate, kind: nil)
                    dismiss()
                }
            }
        }
    }

    private var known: [(id: String, place: Place)] {
        let all = store.places.map { (id: $0.key, place: $0.value) }
        let filtered = query.isEmpty ? all : all.filter { $0.place.name.localizedCaseInsensitiveContains(query) }
        if let near {
            let here = CLLocation(latitude: near.latitude, longitude: near.longitude)
            return filtered.sorted { CLLocation(latitude: $0.place.latitude, longitude: $0.place.longitude).distance(from: here) < CLLocation(latitude: $1.place.latitude, longitude: $1.place.longitude).distance(from: here) }
        }
        return filtered.sorted { $0.place.name < $1.place.name }
    }

    private func search() async {
        guard query.count >= 2 else { found = []; return }
        try? await Task.sleep(for: .milliseconds(300))
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        if let near { request.region = MKCoordinateRegion(center: near, latitudinalMeters: 50_000, longitudinalMeters: 50_000) }
        found = (try? await MKLocalSearch(request: request).start().mapItems) ?? []
    }
}

/// A pin moved on a map, and the name given to where it stands.
private struct PinPlace: View {
    var start: CLLocationCoordinate2D?
    var done: (String, CLLocationCoordinate2D) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var position: MapCameraPosition = .automatic
    @State private var center: CLLocationCoordinate2D?
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Map(position: $position)
                .mapStyle(.standard(pointsOfInterest: .including([.restaurant, .cafe, .hotel, .park, .museum, .store])))
                .onMapCameraChange(frequency: .onEnd) { center = $0.region.center }
                .overlay { Icon("pin", size: 34).foregroundStyle(Tokens.accent).offset(y: -17).allowsHitTesting(false) }
                .safeAreaInset(edge: .bottom) {
                    HStack {
                        TextField("place.name", text: $name)
                            .padding(.horizontal, Space.l)
                            .padding(.vertical, Space.m)
                            .glassEffect(.regular, in: .capsule)
                        Button("action.done") {
                            if let center { done(name.isEmpty ? String(localized: "place.unnamed") : name, center) }
                            dismiss()
                        }
                        .buttonStyle(.glassProminent)
                        .disabled(center == nil)
                    }
                    .padding(Space.gutter)
                }
                .navigationTitle(Text("place.pin"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { dismiss() } label: { Label("action.close", icon: "close") }
                    }
                }
                .task(id: center?.latitude) {
                    guard let center, name.isEmpty else { return }
                    let request = MKReverseGeocodingRequest(location: CLLocation(latitude: center.latitude, longitude: center.longitude))
                    if let item = try? await request?.mapItems.first { name = item.name ?? "" }
                }
                .onAppear {
                    if let start { position = .region(MKCoordinateRegion(center: start, latitudinalMeters: 1500, longitudinalMeters: 1500)) }
                }
        }
    }
}
