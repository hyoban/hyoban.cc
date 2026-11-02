import MapKit
import SwiftUI

// Every map in the app is MapKit's: places as dots in the colour of the year they were first
// visited, lines (a trip's route, the footprints, a public track) drawn by kind, and the fog of
// war under them. MKMapView rather than SwiftUI's Map, as the fog is some two thousand polygons
// that only multi-polygon overlays draw at once.

/// A place on a map.
struct MapPin: Identifiable, Hashable {
    var id: String
    var coordinate: CLLocationCoordinate2D
    var title: String
    var color: Color
    /// How many Moments it has, for the dot's size.
    var weight = 1

    static func == (a: MapPin, b: MapPin) -> Bool { a.id == b.id && a.weight == b.weight && a.title == b.title }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// A line on a map.
struct MapLine: Identifiable, Hashable {
    enum Style: Hashable { case route, straight, footprints, track, playback }

    var id: String
    var coordinates: [CLLocationCoordinate2D]
    var style: Style
    var color: Color = Tokens.accent

    static func == (a: MapLine, b: MapLine) -> Bool { a.id == b.id && a.coordinates.count == b.coordinates.count && a.style == b.style }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// What a map is asked to frame, once, when [id] changes.
struct MapFrame: Equatable {
    var id: String
    var coordinates: [CLLocationCoordinate2D]
    var animated = true

    static func == (a: MapFrame, b: MapFrame) -> Bool { a.id == b.id }
}

struct HereMap: UIViewRepresentable {
    var pins: [MapPin] = []
    var lines: [MapLine] = []
    var fog: FogOverlay? = nil
    var frame: MapFrame? = nil
    var showsUser = false
    var selected: String? = nil
    var onSelect: (String?) -> Void = { _ in }
    var insets = UIEdgeInsets(top: 80, left: 40, bottom: 140, right: 40)

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.pointOfInterestFilter = .excludingAll
        map.showsCompass = true
        map.showsScale = true
        map.register(PinView.self, forAnnotationViewWithReuseIdentifier: PinView.id)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        map.showsUserLocation = showsUser

        if coordinator.fog !== fog {
            map.removeOverlays(map.overlays.filter { $0 is FogPolygons })
            if let fog { for overlay in fog.overlays { map.insertOverlay(overlay, at: 0, level: .aboveRoads) } }
            coordinator.fog = fog
        }

        if coordinator.lines != lines {
            map.removeOverlays(map.overlays.filter { $0 is LineOverlay })
            map.addOverlays(lines.filter { $0.coordinates.count >= 2 }.map(LineOverlay.init), level: .aboveRoads)
            coordinator.lines = lines
        }

        if coordinator.pins != pins {
            let existing = Dictionary(uniqueKeysWithValues: map.annotations.compactMap { $0 as? PinAnnotation }.map { ($0.pin.id, $0) })
            let wanted = Set(pins.map(\.id))
            map.removeAnnotations(existing.values.filter { !wanted.contains($0.pin.id) })
            for pin in pins {
                if let annotation = existing[pin.id] {
                    if annotation.pin != pin {
                        map.removeAnnotation(annotation)
                        map.addAnnotation(PinAnnotation(pin))
                    }
                } else {
                    map.addAnnotation(PinAnnotation(pin))
                }
            }
            coordinator.pins = pins
        }

        if let selected, let annotation = map.annotations.first(where: { ($0 as? PinAnnotation)?.pin.id == selected }) {
            if !map.selectedAnnotations.contains(where: { $0 === annotation }) { map.selectAnnotation(annotation, animated: true) }
        } else if selected == nil, !map.selectedAnnotations.isEmpty {
            map.selectedAnnotations.forEach { map.deselectAnnotation($0, animated: true) }
        }

        if let frame, coordinator.framed != frame.id {
            coordinator.framed = frame.id
            DispatchQueue.main.async { Self.fit(map, frame.coordinates, insets: insets, animated: frame.animated && coordinator.ready) }
        }
    }

    static func fit(_ map: MKMapView, _ coordinates: [CLLocationCoordinate2D], insets: UIEdgeInsets, animated: Bool) {
        guard !coordinates.isEmpty else { return }
        if coordinates.count == 1 {
            map.setRegion(MKCoordinateRegion(center: coordinates[0], latitudinalMeters: 3000, longitudinalMeters: 3000), animated: animated)
            return
        }
        let rect = coordinates.reduce(MKMapRect.null) { rect, coordinate in
            rect.union(MKMapRect(origin: MKMapPoint(coordinate), size: MKMapSize(width: 1, height: 1)))
        }
        map.setVisibleMapRect(rect, edgePadding: insets, animated: animated)
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: HereMap
        var pins: [MapPin] = []
        var lines: [MapLine] = []
        var fog: FogOverlay?
        var framed: String?
        var ready = false

        init(_ parent: HereMap) { self.parent = parent }

        func mapViewDidFinishLoadingMap(_ mapView: MKMapView) { ready = true }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard annotation is PinAnnotation else { return nil }
            return mapView.dequeueReusableAnnotationView(withIdentifier: PinView.id, for: annotation)
        }

        func mapView(_ mapView: MKMapView, didSelect annotation: MKAnnotation) {
            guard let pin = (annotation as? PinAnnotation)?.pin else { return }
            if parent.selected != pin.id { parent.onSelect(pin.id) }
        }

        func mapView(_ mapView: MKMapView, didDeselect annotation: MKAnnotation) {
            guard let pin = (annotation as? PinAnnotation)?.pin, parent.selected == pin.id else { return }
            DispatchQueue.main.async {
                if mapView.selectedAnnotations.isEmpty { self.parent.onSelect(nil) }
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let fog = overlay as? FogPolygons {
                let renderer = MKMultiPolygonRenderer(multiPolygon: fog)
                renderer.fillColor = UIColor(Tokens.tx).withAlphaComponent(fog.opacity)
                renderer.strokeColor = .clear
                return renderer
            }
            if let line = overlay as? LineOverlay {
                let renderer = MKPolylineRenderer(polyline: line)
                renderer.strokeColor = UIColor(line.line.color)
                renderer.lineCap = .round
                renderer.lineJoin = .round
                switch line.line.style {
                case .route: renderer.lineWidth = 4
                case .straight: renderer.lineWidth = 2.5; renderer.lineDashPattern = [2, 8]
                case .footprints: renderer.lineWidth = 3.5
                case .track: renderer.lineWidth = 3; renderer.alpha = 0.8
                case .playback: renderer.lineWidth = 5
                }
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }
    }
}

nonisolated final class LineOverlay: MKPolyline {
    var line: MapLine = MapLine(id: "", coordinates: [], style: .route)

    convenience init(_ line: MapLine) {
        self.init(coordinates: line.coordinates, count: line.coordinates.count)
        self.line = line
    }
}

final class PinAnnotation: NSObject, MKAnnotation {
    let pin: MapPin
    var coordinate: CLLocationCoordinate2D { pin.coordinate }
    var title: String? { pin.title }

    init(_ pin: MapPin) { self.pin = pin }
}

/// A place as a dot in its year's colour, ringed in the page's colour, larger the more Moments it holds.
final class PinView: MKAnnotationView {
    static let id = "pin"

    override var annotation: MKAnnotation? {
        didSet { draw() }
    }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        collisionMode = .circle
        displayPriority = .defaultHigh
        draw()
    }

    required init?(coder: NSCoder) { fatalError() }

    private func draw() {
        guard let pin = (annotation as? PinAnnotation)?.pin else { return }
        let size: CGFloat = pin.weight > 5 ? 18 : pin.weight > 1 ? 14 : 11
        frame = CGRect(x: 0, y: 0, width: size, height: size)
        layer.cornerRadius = size / 2
        layer.backgroundColor = UIColor(pin.color).cgColor
        layer.borderColor = UIColor.white.cgColor
        layer.borderWidth = 2
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.15
        layer.shadowRadius = 3
        layer.shadowOffset = CGSize(width: 0, height: 1)
        accessibilityLabel = pin.title
        canShowCallout = false
    }

    override func setSelected(_ selected: Bool, animated: Bool) {
        super.setSelected(selected, animated: animated)
        UIView.animate(withDuration: 0.2) {
            self.transform = selected ? CGAffineTransform(scaleX: 1.5, y: 1.5) : .identity
        }
    }
}
