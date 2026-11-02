import MapKit
import SwiftUI

// 回放旅程, as the site's player does (packages/trip-map/src/player): the route drawn out never
// still, a dot at its tip, and a card per Moment stepped through from a toolbar, by a swipe or
// with a keyboard's arrows and Space. Each Moment's place is where the route has reached when
// its card shows; between them the route keeps moving towards the next.

struct TripPlayer: View {
    var id: String

    @Environment(AppStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index = 0
    @State private var playing = true
    @State private var progress = 0.0

    var body: some View {
        if let trip = store.trip(id) {
            player(trip)
        } else {
            ContentUnavailableView("trip.gone", image: "route")
        }
    }

    private func player(_ trip: Trip) -> some View {
        let path = TripLines.path(trip, routes: store.routes)
        let marks = Playback.marks(trip: trip, path: path, places: store.places)
        let drawn = Playback.prefix(path, fraction: progress)
        return HereMap(
            pins: trip.stops.map { MapPin(id: $0.placeId, coordinate: $0.place.coordinate, title: $0.place.name, color: Tokens.borderStrong) }
                + (drawn.last.map { [MapPin(id: "tip", coordinate: $0, title: "", color: Tokens.accent, weight: 6)] } ?? []),
            lines: [MapLine(id: "rest", coordinates: path, style: .straight, color: Tokens.borderStrong),
                    MapLine(id: "drawn-\(Int(progress * 400))", coordinates: drawn, style: .playback)],
            frame: MapFrame(id: "play-\(trip.id)", coordinates: path, animated: false),
            insets: UIEdgeInsets(top: 60, left: 40, bottom: 300, right: 40)
        )
        .ignoresSafeArea()
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: Space.m) {
                if trip.moments.indices.contains(index) {
                    let entry = trip.moments[index]
                    MomentBubble(entry: entry)
                        .frame(maxHeight: 260)
                        .id(entry.uid)
                        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity)))
                        .gesture(DragGesture().onEnded { value in
                            if value.translation.width < -40 { step(1, trip) } else if value.translation.width > 40 { step(-1, trip) }
                        })
                }
                GlassEffectContainer {
                    HStack(spacing: Space.l) {
                        Button { step(-1, trip) } label: { Icon("skip-back", size: 22).frame(width: 44, height: 44) }
                            .accessibilityLabel(Text("player.previous"))
                        Button { playing.toggle() } label: { Icon(playing ? "pause" : "play", size: 22).frame(width: 52, height: 52) }
                            .accessibilityLabel(Text(playing ? "player.pause" : "player.play"))
                            .keyboardShortcut(.space, modifiers: [])
                        Button { step(1, trip) } label: { Icon("skip-next", size: 22).frame(width: 44, height: 44) }
                            .accessibilityLabel(Text("player.next"))
                        Text(verbatim: "\(min(index + 1, trip.moments.count)) / \(trip.moments.count)")
                            .textStyle(.label).monospacedDigit()
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, Space.l)
                    .glassEffect(.regular.interactive(), in: .capsule)
                }
                .background {
                    Button("") { step(-1, trip) }.keyboardShortcut(.leftArrow, modifiers: []).hidden()
                    Button("") { step(1, trip) }.keyboardShortcut(.rightArrow, modifiers: []).hidden()
                }
            }
            .padding(.horizontal, Space.gutter)
            .padding(.bottom, Space.s)
        }
        .navigationTitle(Text(verbatim: trip.title))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: playing) {
            guard playing else { return }
            if reduceMotion {
                progress = marks.indices.contains(index) ? marks[index] : 1
                return
            }
            // The route keeps moving: towards the current Moment's mark, then on to the next after a pause.
            while !Task.isCancelled, playing {
                let target = marks.indices.contains(index) ? marks[index] : 1
                if progress < target - 0.0005 {
                    progress = min(target, progress + 0.004)
                    try? await Task.sleep(for: .milliseconds(16))
                } else {
                    try? await Task.sleep(for: .seconds(2.4))
                    if index + 1 < trip.moments.count {
                        withAnimation(Springs.spatialDefault) { index += 1 }
                    } else if progress < 1 {
                        progress = min(1, progress + 0.004)
                    } else {
                        playing = false
                    }
                }
            }
        }
    }

    private func step(_ by: Int, _ trip: Trip) {
        let next = min(max(0, index + by), max(0, trip.moments.count - 1))
        guard next != index else { return }
        withAnimation(Springs.spatialDefault) { index = next }
        let path = TripLines.path(trip, routes: store.routes)
        let marks = Playback.marks(trip: trip, path: path, places: store.places)
        if marks.indices.contains(next), marks[next] < progress { progress = marks[next] }
    }
}

enum Playback {
    /// How far along the path, from 0 to 1, each Moment's place is reached, never going back.
    static func marks(trip: Trip, path: [CLLocationCoordinate2D], places: [String: Place]) -> [Double] {
        let lengths = cumulative(path)
        guard let total = lengths.last, total > 0 else { return trip.moments.map { _ in 1 } }
        var last = 0.0
        return trip.moments.map { entry in
            guard let place = entry.place.flatMap({ places[$0] }) else { return last }
            let here = MKMapPoint(place.coordinate)
            var best = last
            var bestDistance = Double.infinity
            for (index, point) in path.enumerated() where lengths[index] / total >= last {
                let distance = MKMapPoint(point).distance(to: here)
                if distance < bestDistance { bestDistance = distance; best = lengths[index] / total }
            }
            last = best
            return best
        }
    }

    /// The path as far as [fraction] of its length.
    static func prefix(_ path: [CLLocationCoordinate2D], fraction: Double) -> [CLLocationCoordinate2D] {
        let lengths = cumulative(path)
        guard let total = lengths.last, total > 0 else { return path }
        let goal = total * fraction
        var out: [CLLocationCoordinate2D] = []
        for (index, point) in path.enumerated() {
            if lengths[index] <= goal {
                out.append(point)
            } else {
                let previous = path[index - 1]
                let t = (goal - lengths[index - 1]) / (lengths[index] - lengths[index - 1])
                out.append(CLLocationCoordinate2D(latitude: previous.latitude + (point.latitude - previous.latitude) * t, longitude: previous.longitude + (point.longitude - previous.longitude) * t))
                break
            }
        }
        return out
    }

    private static func cumulative(_ path: [CLLocationCoordinate2D]) -> [Double] {
        var out: [Double] = []
        var total = 0.0
        for (index, point) in path.enumerated() {
            if index > 0 { total += MKMapPoint(path[index - 1]).distance(to: MKMapPoint(point)) }
            out.append(total)
        }
        return out
    }
}
