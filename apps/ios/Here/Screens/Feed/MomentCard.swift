import SwiftUI

// What a Moment's long press and its page's menu offer, and how it is shared; where its card grows
// into its page from.

/// The design's Menu card for a Moment: first what to do (share, copy, edit), then where to go
/// (its place, trip and day), deleting last.
struct MomentMenu: View {
    var entry: Entry
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        Section {
            ShareLink(item: ShareText.of(entry, store: store)) { Label("menu.share", icon: "share") }
            Button { UIPasteboard.general.string = entry.text } label: { Label("menu.copy", icon: "copy") }
            Button { router.cover = .editMoment(entry.uid) } label: { Label("menu.edit", icon: "pen") }
        }
        Section {
            if let place = entry.place, store.places[place] != nil {
                Button { router.push(.place(place)) } label: { Label("menu.place", icon: "pin") }
            }
            if let trip = store.trip(of: entry) {
                Button { router.push(.trip(trip.id)) } label: { Label("menu.trip", icon: "route") }
            }
            Button { router.push(.day(entry.day)) } label: { Label("menu.day", icon: "calendar") }
        }
        Section {
            Button(role: .destructive) { store.delete(entry.uid) } label: { Label("menu.delete", icon: "trash") }
        }
    }
}

/// A Moment's words as they are shared, a meal's place, share and verdict on a line after them.
enum ShareText {
    static func of(_ entry: Entry, store: AppStore) -> String {
        var text = entry.text
        if let food = entry.food {
            var line: [String] = []
            if let place = entry.place.flatMap({ store.places[$0] }) { line.append("📍 \(place.name)") }
            if let cost = food["cost"]?.double {
                line.append("💰 " + String(localized: "food.each \(Formats.money(cost / Double(max(1, food["people"]?.int ?? 1))))"))
            }
            switch food["verdict"]?.string {
            case "good": line.append("😋 " + String(localized: "food.good"))
            case "ok": line.append("🙂 " + String(localized: "food.ok"))
            case "bad": line.append("😖 " + String(localized: "food.bad"))
            default: break
            }
            if !line.isEmpty { text += "\n\n" + line.joined(separator: " ") }
        }
        return text
    }
}

extension EnvironmentValues {
    /// Where a Moment's card grows into its page from: one namespace for the whole app.
    @Entry var zoom: Namespace.ID?
}

private struct ZoomSource: ViewModifier {
    var id: String
    @Environment(\.zoom) private var zoom

    func body(content: Content) -> some View {
        if let zoom { content.matchedTransitionSource(id: id, in: zoom) } else { content }
    }
}

private struct ZoomDestination: ViewModifier {
    var id: String
    @Environment(\.zoom) private var zoom

    func body(content: Content) -> some View {
        if let zoom { content.navigationTransition(.zoom(sourceID: id, in: zoom)) } else { content }
    }
}

extension View {
    /// What a page of [id] grows from.
    func zoomSource(_ id: String) -> some View { modifier(ZoomSource(id: id)) }

    /// A page growing from what [zoomSource] marked with its [id].
    func zoomDestination(_ id: String) -> some View { modifier(ZoomDestination(id: id)) }
}

/// Lays its children out in rows, wrapping when a row is full.
struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(subviews, width: proposal.width ?? .infinity)
        let height = rows.reduce(0) { $0 + $1.height } + CGFloat(max(0, rows.count - 1)) * spacing
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private func rows(_ subviews: Subviews, width: CGFloat) -> [(indices: [Int], width: CGFloat, height: CGFloat)] {
        var rows: [(indices: [Int], width: CGFloat, height: CGFloat)] = []
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if let last = rows.last, last.width + spacing + size.width <= width {
                rows[rows.count - 1].indices.append(index)
                rows[rows.count - 1].width += spacing + size.width
                rows[rows.count - 1].height = max(last.height, size.height)
            } else {
                rows.append(([index], size.width, size.height))
            }
        }
        return rows
    }
}
