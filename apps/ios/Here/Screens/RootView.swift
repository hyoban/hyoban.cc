import SwiftUI

// Three tabs, each its own navigation stack, under the design's floating tab bar with the ink 记录
// beside it. The bar is on the tabs' own pages; a page over a tab has its way back instead. The
// system's navigation bar stays: on iOS 26 it is the design's top bar, circles floating over a large
// title that folds into a small one as the page scrolls, the page's edge softening under them.

struct RootView: View {
    @Environment(Router.self) private var router
    @Namespace private var zoom

    var body: some View {
        @Bindable var router = router
        ZStack {
            stack(.timeline, path: $router.timeline) { TimelineView() }
            stack(.map, path: $router.map) { MapScreen() }
            stack(.me, path: $router.me) { MeView() }
        }
        .environment(\.zoom, zoom)
        .overlay(alignment: .bottom) {
            if showsTabBar {
                AppTabBar()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(Springs.spatialDefault, value: showsTabBar)
        .fullScreenCover(item: $router.cover) { cover in
            switch cover {
            case .newMoment(let day): ComposeView(editing: nil, day: day)
            case .editMoment(let uid): ComposeView(editing: uid, day: nil)
            case .media(let entry, let index): MediaViewer(entryUid: entry, index: index)
            case .newBill: EmptyView()
            }
        }
        .sheet(item: $router.sheet) { sheet in
            switch sheet {
            case .newBill(let id): BillForm(editing: id)
            default: EmptyView()
            }
        }
    }

    /// The bar shows on a tab's own page, unless the timeline scrolled it away.
    private var showsTabBar: Bool {
        switch router.tab {
        case .timeline: router.timeline.isEmpty && !router.tabBarAway
        case .map: router.map.isEmpty
        case .me: router.me.isEmpty
        case .search: false
        }
    }

    private func stack<Content: View>(_ tab: AppTab, path: Binding<[Route]>, @ViewBuilder root: () -> Content) -> some View {
        let shown = router.tab == tab
        return NavigationStack(path: path) {
            root().routes()
        }
        .opacity(shown ? 1 : 0)
        .allowsHitTesting(shown)
        .accessibilityHidden(!shown)
    }
}

extension View {
    /// The pages any tab's stack can open.
    func routes() -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .moment(let uid): MomentView(uid: uid).zoomDestination(uid)
            case .trip(let id): TripView(id: id)
            case .play(let id): TripPlayer(id: id)
            case .day(let day): DayView(day: day)
            case .place(let id): PlaceView(id: id)
            case .changes: ChangesView()
            case .sync: SyncView()
            case .items: ItemsView()
            case .ledger: LedgerView()
            case .ledgerSettings: LedgerSettingsView()
            case .bill(let id): BillDetail(id: id)
            case .footprints: FootprintsView()
            case .footprintSettings: FootprintSettingsView()
            case .devices: DevicesView()
            case .subscriptions: SubscriptionsView()
            case .car: CarView()
            case .search: SearchView()
            }
        }
    }

    /// A page of the design: `bg` behind it, its title the design's large title.
    func page(_ title: Text, subtitle: Text? = nil, large: Bool = true) -> some View {
        self
            .background(Tokens.bg)
            .navigationTitle(title)
            .navigationSubtitle(subtitle ?? Text(verbatim: ""))
            .navigationBarTitleDisplayMode(large ? .large : .inline)
            .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
    }
}
