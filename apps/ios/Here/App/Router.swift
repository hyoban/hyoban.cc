import Observation
import SwiftUI

// Where each page is: a tab and the pages over it. A `moments://` link opens its page over the tab
// it is under (a trip or a place over 地图, a setting, the ledger or the changes over 我, the rest
// over 时间线), so back leads to that tab.

enum AppTab: Hashable { case timeline, map, me, search }

enum Route: Hashable {
    case moment(String)
    case trip(String)
    case play(String)
    case day(String)
    case place(String)
    case changes
    case sync
    case items
    case ledger
    case ledgerSettings
    case bill(String)
    case footprints
    case footprintSettings
    case devices
    case subscriptions
    case car
    case search

    /// The tab a link to it opens over.
    var tab: AppTab {
        switch self {
        case .trip, .play, .place: .map
        case .moment, .day, .search: .timeline
        default: .me
        }
    }
}

/// A sheet or a page over everything.
enum Cover: Identifiable, Hashable {
    case newMoment(String?)
    case editMoment(String)
    case newBill(String?)
    case media(entry: String, index: Int)

    var id: Self { self }
}

@Observable
final class Router {
    var tab: AppTab = .timeline
    var timeline: [Route] = []
    var map: [Route] = []
    var me: [Route] = []
    var search: [Route] = []
    var cover: Cover?
    /// Whether the timeline has scrolled the tab bar away.
    var tabBarAway = false
    var sheet: Cover?
    /// What the map is asked to show: a place or a trip, framed.
    var mapFocus: MapFocus?

    enum MapFocus: Hashable { case place(String), trip(String) }

    func push(_ route: Route) {
        switch tab {
        case .timeline: timeline.append(route)
        case .map: map.append(route)
        case .me: me.append(route)
        case .search: search.append(route)
        }
    }

    /// Shows a place or a trip on the map tab.
    func showOnMap(_ focus: MapFocus) {
        mapFocus = focus
        map = []
        tab = .map
    }

    /// Opens a `moments://` link.
    func open(_ url: URL) {
        guard url.scheme == "moments" else { return }
        let parts = [url.host() ?? ""] + url.pathComponents.filter { $0 != "/" }
        let route: Route?
        switch (parts.first ?? "", parts.dropFirst().first) {
        case ("moments", _), ("timeline", _): tab = .timeline; timeline = []; return
        case ("map", _), ("trips", _): tab = .map; map = []; return
        case ("settings", _), ("me", _): tab = .me; me = []; return
        case ("search", _): route = .search
        case ("new", _): cover = .newMoment(nil); return
        case ("new-bill", _): sheet = .newBill(nil); return
        case ("moment", let uid?): route = .moment(uid)
        case ("trip", let id?): route = .trip(id)
        case ("play", let id?): route = .play(id)
        case ("day", let day?): route = .day(day)
        case ("place", let id?): route = .place(id)
        case ("changes", _): route = .changes
        case ("sync", _): route = .sync
        case ("items", _): route = .items
        case ("ledger", _): route = .ledger
        case ("ledger-settings", _): route = .ledgerSettings
        case ("footprints", _): route = .footprints
        case ("footprint-settings", _): route = .footprintSettings
        case ("devices", _): route = .devices
        case ("subscriptions", _): route = .subscriptions
        case ("car", _): route = .car
        default: route = nil
        }
        guard let route else { return }
        tab = route.tab
        switch route.tab {
        case .timeline: timeline = [route]
        case .map: map = [route]
        case .me: me = [route]
        case .search: search = [route]
        }
    }
}
