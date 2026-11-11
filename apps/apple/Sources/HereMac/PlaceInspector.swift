import CoreLocation
import HereKit
import MapKit
import SwiftUI

/// Place (M10): the card in the map's panel for the place chosen, as Maps draws a place: its name,
/// kind and area, a row of actions (Directions, Share, Copy), its photos, its details (moments,
/// first visit) and the moments written there. Nothing here is edited or opens another page. × or Esc closes it.
struct PlaceInspector: View {
    let id: String
    let close: () -> Void
    @Environment(AppModel.self) private var app

    var body: some View {
        if let place = app.store.places[id] {
            content(place)
        }
    }

    private func content(_ place: Place) -> some View {
        let entries = app.store.entries(at: id)
        let moments = entries.filter(\.isMoment)
        let photos = moments.flatMap { entry in entry.media.filter { !$0.utility } }
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                CardHeader(title: place.name, line: [place.kind.map(L.kind) ?? "", place.area].filter { !$0.isEmpty }.joined(separator: " · "), close: close)
                HStack(spacing: 8) {
                    Button { PlaceActions.directions(to: place) } label: {
                        CardAction(prominent: true) { Label(L.t("place.directions"), systemImage: "arrow.triangle.turn.up.right.diamond.fill") }
                    }
                    if let url = PlaceActions.link(place) {
                        ShareLink(item: url) {
                            CardAction { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
                        }
                    }
                    Button { PlaceActions.copy(place) } label: {
                        CardAction { Label(L.t("action.copy"), systemImage: "doc.on.doc") }
                    }
                }
                .buttonStyle(.plain)
                if !photos.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(Array(photos.prefix(8).enumerated()), id: \.offset) { _, item in
                                MediaImage(item, width: 160)
                                    .frame(width: 120, height: 120)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    .help(item.alt.isEmpty ? L.t("place.photo") : item.alt)
                            }
                        }
                    }
                }
                CardSection(L.t("place.details")) {
                    CardFact(name: L.t("place.momentCount"), value: "\(moments.count)")
                    if let first = entries.last {
                        Divider()
                        CardFact(name: L.t("place.first"), value: Formats.month(first.day))
                    }
                }
                CardSection(L.t("place.moments")) {
                    if moments.isEmpty {
                        Text(L.t("place.noMoments")).foregroundStyle(.secondary).padding(.vertical, 8)
                    } else {
                        ForEach(moments) { entry in
                            momentRow(entry)
                            if entry.uid != moments.last?.uid { Divider() }
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    private func momentRow(_ entry: Entry) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(firstLine(entry)).lineLimit(1)
            Text(when(entry)).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 7)
    }

    private func firstLine(_ entry: Entry) -> String {
        if let text = entry.text?.split(separator: "\n").first { return String(text) }
        return L.f("place.photos", entry.media.count)
    }

    private func when(_ entry: Entry) -> String {
        [Formats.shortDay(entry.day), entry.wallTime].compactMap { $0 }.joined(separator: " ")
    }
}
