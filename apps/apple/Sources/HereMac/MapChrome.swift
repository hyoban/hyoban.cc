import CoreLocation
import HereKit
import MapKit
import SwiftUI

// What the map has over it: the year legend at the bottom left, the buttons down the top right,
// the photo pins and the title bar's glass on hover.

/// The years as a row of glass capsules, each with its colour; one clicked shows only that year,
/// clicked again shows them all.
struct YearLegend: View {
    let years: [Int]
    let color: (Int) -> Color
    @Binding var selected: Int?

    var body: some View {
        GlassEffectContainer(spacing: 6) {
            HStack(spacing: 6) {
                ForEach(years, id: \.self) { year in
                    Button {
                        withAnimation(.snappy) { selected = selected == year ? nil : year }
                    } label: {
                        HStack(spacing: 5) {
                            Circle().fill(color(year)).frame(width: 8, height: 8)
                            Text(String(year)).monospacedDigit()
                        }
                        .font(.callout)
                        .padding(.horizontal, 10)
                        .frame(height: 28)
                        .opacity(selected == nil || selected == year ? 1 : 0.45)
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .glassEffect(selected == year ? .regular.tint(color(year).opacity(0.25)).interactive() : .regular.interactive(), in: .capsule)
                    .help(selected == year ? L.t("legend.all") : L.f("legend.only", String(year)))
                    .accessibilityAddTraits(selected == year ? .isSelected : [])
                }
            }
        }
        .padding(16)
    }
}

/// Where this Mac is, asked for when the owner presses My Location; nil when it isn't allowed or known.
@MainActor
func currentLocation() async -> CLLocationCoordinate2D? {
    Locator.manager.requestWhenInUseAuthorization()
    do {
        for try await update in CLLocationUpdate.liveUpdates() {
            if let location = update.location { return location.coordinate }
            if update.authorizationDenied || update.authorizationDeniedGlobally || update.authorizationRestricted { return nil }
        }
    } catch {
        return nil
    }
    return nil
}

@MainActor
private enum Locator {
    static let manager = CLLocationManager()
}

/// The fog's switch, kept for every map page.
@MainActor
extension AppModel {
    var fogOn: Bool {
        get { preference("map.fog") as? Bool ?? false }
        set { setPreference("map.fog", newValue) }
    }
}

/// A point of the map as a round photo with a ring of its year's colour.
struct PhotoPin: View {
    let item: MediaItem
    let color: Color
    var size: CGFloat = 34
    var selected = false

    var body: some View {
        MediaImage(item, width: Int(selected ? size * 1.5 : size))
            .frame(width: selected ? size * 1.5 : size, height: selected ? size * 1.5 : size)
            .clipShape(RoundedRectangle(cornerRadius: (selected ? size * 1.5 : size) * 0.28, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: (selected ? size * 1.5 : size) * 0.28, style: .continuous)
                    .strokeBorder(.white, lineWidth: 2)
            }
            .overlay(alignment: .bottomTrailing) {
                Circle().fill(color).frame(width: 9, height: 9).overlay(Circle().strokeBorder(.white, lineWidth: 1.5)).offset(x: 3, y: 3)
            }
            .shadow(color: .black.opacity(0.25), radius: 3, y: 1)
            .animation(.snappy, value: selected)
    }
}

/// Whether the pointer is over the top of the window, where the toolbar is and the window can be
/// dragged. Maps shows its title bar's glass only then; the toolbar's own items stay.
struct TitlebarHover: ViewModifier {
    @Binding var hovering: Bool
    var height: CGFloat = 52

    func body(content: Content) -> some View {
        content.background(Watcher(hovering: $hovering, height: height))
    }

    private struct Watcher: NSViewRepresentable {
        @Binding var hovering: Bool
        let height: CGFloat

        func makeCoordinator() -> Coordinator { Coordinator() }

        func makeNSView(context: Context) -> NSView {
            let view = NSView()
            let coordinator = context.coordinator
            coordinator.monitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .mouseExited, .leftMouseDragged]) { [weak view] event in
                guard let window = view?.window, event.window == window, let frame = window.contentView?.bounds else { return event }
                let point = event.locationInWindow
                let inside = event.type != .mouseExited && frame.contains(point) && point.y >= frame.height - height
                if inside != coordinator.inside {
                    coordinator.inside = inside
                    withAnimation(.easeOut(duration: 0.2)) { hovering = inside }
                }
                return event
            }
            DispatchQueue.main.async { view.window?.acceptsMouseMovedEvents = true }
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}

        static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
            if let monitor = coordinator.monitor { NSEvent.removeMonitor(monitor) }
        }

        final class Coordinator {
            var monitor: Any?
            var inside = false
        }
    }
}

/// The map's buttons as Maps stacks them down the top right: a glass capsule with the fog and My
/// Location, MapKit's zoom stepper under it, and its compass.
struct MapButtons: View {
    @Binding var fogOn: Bool
    let scope: Namespace.ID
    let found: (CLLocationCoordinate2D) -> Void
    @State private var locating = false

    var body: some View {
        VStack(spacing: 10) {
            VStack(spacing: 0) {
                button(L.t("action.fog"), symbol: fogOn ? "cloud.fog.fill" : "cloud.fog", on: fogOn) {
                    fogOn.toggle()
                }
                button(L.t("action.locate"), symbol: "location", on: false) {
                    locating = true
                    Task {
                        if let here = await currentLocation() { found(here) }
                        locating = false
                    }
                }
                .disabled(locating)
            }
            .glassEffect(.regular.interactive(), in: Capsule())
            MapZoomStepper(scope: scope)
            MapCompass(scope: scope)
                .mapControlVisibility(.visible)
        }
        .padding(.trailing, 12)
        .padding(.top, 60)
    }

    private func button(_ title: String, symbol: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .focusEffectDisabled()
        .help(title)
        .accessibilityLabel(title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
