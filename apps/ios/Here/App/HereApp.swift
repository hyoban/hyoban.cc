import SwiftUI

// Here on iOS: the site's Moments, places and trips on the phone, new ones written there, and what
// stays the phone's own, a ledger and GPS footprints. Three tabs and a search, each its own
// navigation stack; every page has a `moments://` link, which opens over the tab it is under.

@main
struct HereApp: App {
    @State private var store = AppStore()
    @State private var ledger = LedgerStore()
    @State private var footprints = FootprintStore()
    @State private var router = Router()
    @Environment(\.scenePhase) private var phase

    init() {
        // The design's top bar: the large title `title-page`, the small one `body` at 600, both `tx`.
        let bar = UINavigationBar.appearance()
        let ink = UIColor(Tokens.tx)
        bar.largeTitleTextAttributes = [.font: UIFontMetrics(forTextStyle: .title1).scaledFont(for: .systemFont(ofSize: TextStyle.titlePage.size, weight: .semibold)), .foregroundColor: ink]
        bar.titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .subheadline).scaledFont(for: .systemFont(ofSize: TextStyle.body.size, weight: .semibold)), .foregroundColor: ink]
        bar.tintColor = ink
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(ledger)
                .environment(footprints)
                .environment(router)
                .tint(Tokens.accent)
                .onOpenURL { router.open($0) }
                .task {
                    store.load()
                    ledger.load()
                    footprints.load()
                    store.outside = { [ledger, footprints] in
                        Outside.changes(ledger: ledger, footprints: footprints)
                    }
                    DebugLaunch.apply(store: store, router: router)
                    await store.refresh()
                }
                .onChange(of: phase) { _, phase in
                    if phase == .active, store.loaded { Task { await store.refresh() } }
                }
        }
    }
}

/// What a test run can set from the command line, in debug builds only: an API and its token
/// for a stand-in API on this Mac, and a page to open.
enum DebugLaunch {
    static func apply(store: AppStore, router: Router) {
        #if DEBUG
        let defaults = UserDefaults.standard
        if let api = defaults.string(forKey: "debugApi") { Settings.apiURL = URL(string: api) }
        if let token = defaults.string(forKey: "debugToken") { Settings.token = token }
        if let media = defaults.string(forKey: "debugMedia") { Settings.mediaOrigin = media }
        if let link = defaults.string(forKey: "debugOpen"), let url = URL(string: link) { router.open(url) }
        #endif
    }
}
