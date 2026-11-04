import SwiftUI

// The floating tab bar (the design's TabBar card): a capsule of three icons at the foot of the
// page, the ink 记录 beside it, in glass on iOS. A dot on 我 while changes wait; on the timeline it
// slides away as the list scrolls down and comes back on the way up.

struct AppTabBar: View {
    @Environment(Router.self) private var router
    @Environment(AppStore.self) private var store
    @Namespace private var selection

    var body: some View {
        HStack(spacing: Space.m) {
            GlassEffectContainer {
                HStack(spacing: 0) {
                    tab(.timeline, icon: "timeline", label: "tab.timeline")
                    tab(.map, icon: "map", label: "tab.map")
                    tab(.me, icon: "me", label: "tab.me", dot: router.tab != .me && store.pendingCount > 0)
                }
                .padding(4)
                .glassEffect(.regular.interactive(), in: .capsule)
            }
            Button {
                router.cover = .newMoment(nil)
            } label: {
                Icon("plus", size: 24)
                    .foregroundStyle(Tokens.bg)
                    .frame(width: 56, height: 56)
                    .background(Tokens.tx, in: .circle)
                    .glassEffect(.regular.tint(Tokens.tx).interactive(), in: .circle)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("action.record"))
            .accessibilityIdentifier("new_moment")
        }
        .padding(.bottom, Space.s)
    }

    private func tab(_ tab: AppTab, icon: String, label: LocalizedStringKey, dot: Bool = false) -> some View {
        let chosen = router.tab == tab
        return Button {
            if router.tab == tab {
                switch tab {
                case .timeline: router.timeline = []
                case .map: router.map = []
                case .me: router.me = []
                case .search: break
                }
            }
            withAnimation(Springs.spatialFast) { router.tab = tab }
        } label: {
            Icon(chosen ? "\(icon)-fill" : icon, size: 24)
                .foregroundStyle(chosen ? Tokens.tx : Tokens.tx3)
                .frame(width: 64, height: 48)
                .overlay(alignment: .topTrailing) {
                    if dot { BadgeDot().offset(x: -18, y: 10) }
                }
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityValue(dot ? Text("changes.count \(store.pendingCount)") : Text(verbatim: ""))
        .accessibilityAddTraits(chosen ? .isSelected : [])
        .accessibilityIdentifier("tab_\(tab)")
    }
}
