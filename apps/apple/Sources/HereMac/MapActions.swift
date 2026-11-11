import AppKit
import CoreLocation
import HereKit
import MapKit
import SwiftUI

// What a place or a trip can do from its card, a search row's context menu or its pin, as Maps
// offers them: Directions in Maps, Share, Copy, and a trip's page on the site.

enum PlaceActions {
    /// Apple Maps, with directions to [place] from where this Mac is.
    static func directions(to place: Place) {
        let item = MKMapItem(location: CLLocation(latitude: place.latitude, longitude: place.longitude), address: nil)
        item.name = place.name
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
    }

    /// The place as an Apple Maps link, to share.
    static func link(_ place: Place) -> URL? {
        var parts = URLComponents(string: "https://maps.apple.com/")
        parts?.queryItems = [
            URLQueryItem(name: "ll", value: "\(place.latitude),\(place.longitude)"),
            URLQueryItem(name: "q", value: place.name),
        ]
        return parts?.url
    }

    /// Its name and area, as Copy puts them on the pasteboard.
    static func copy(_ place: Place) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString([place.name, place.area].filter { !$0.isEmpty }.joined(separator: " "), forType: .string)
    }
}

/// The context menu of a place: on its photo pin, a search row and a recent row.
struct PlaceMenu: View {
    let place: Place

    var body: some View {
        Button(L.t("place.directions"), systemImage: "arrow.triangle.turn.up.right.diamond") { PlaceActions.directions(to: place) }
        Button(L.t("action.copy"), systemImage: "doc.on.doc") { PlaceActions.copy(place) }
        if let url = PlaceActions.link(place) {
            ShareLink(item: url) { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
        }
    }
}

/// The context menu of a trip.
struct TripMenu: View {
    let trip: Trip

    var body: some View {
        if let url = Site.trip(trip) {
            Link(destination: url) { Label(L.t("action.openSite"), systemImage: "safari") }
            ShareLink(item: url) { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
        }
    }
}

/// A card's action, as Maps lays them in a row under the title: an icon over a short name, the
/// first one filled with the accent.
struct CardAction<Label: View>: View {
    let prominent: Bool
    let label: Label

    init(prominent: Bool = false, @ViewBuilder label: () -> Label) {
        self.prominent = prominent
        self.label = label()
    }

    var body: some View {
        label
            .labelStyle(CardActionLabel())
            .foregroundStyle(prominent ? AnyShapeStyle(.white) : AnyShapeStyle(.tint))
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(prominent ? AnyShapeStyle(.tint) : AnyShapeStyle(.fill.tertiary), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct CardActionLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 3) {
            configuration.icon.font(.system(size: 15, weight: .semibold))
            configuration.title.font(.caption.weight(.medium))
        }
    }
}

/// A card's head as Maps draws it: a bold title, a line under it, and a round close button.
struct CardHeader: View {
    let title: String
    let line: String
    let close: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title2.weight(.bold)).lineLimit(2)
                if !line.isEmpty {
                    Text(line).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 24, height: 24)
                    .background(.fill.tertiary, in: Circle())
            }
            .buttonStyle(.plain)
            .help(L.t("action.close"))
            .accessibilityLabel(L.t("action.close"))
        }
    }
}

/// A titled group of a card, as Maps titles its Details.
struct CardSection<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.headline)
            VStack(alignment: .leading, spacing: 0) { content }
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }
}

/// A row of a card's section: a name on the left, its value on the right.
struct CardFact: View {
    let name: String
    let value: String

    var body: some View {
        HStack {
            Text(name).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value).monospacedDigit()
        }
        .padding(.vertical, 6)
    }
}
