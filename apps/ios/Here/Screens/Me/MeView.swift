import SwiftUI

// 我 (the design's 15): its large title with when it last synced, the changes waiting, this
// month's spending with a bar a day, then plain rows under small captions: 账单与资产 and 足迹, 东西
// (设备, 订阅, 车), 通用 (记录项与公开, 同步与来源) and 只在这台手机上 (记账, 足迹记录), each with what
// it is set to under its name. Theme and language have no setting; they follow the phone.

struct MeView: View {
    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(FootprintStore.self) private var footprints
    @Environment(Router.self) private var router

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if store.hasAPI, store.pendingCount > 0 || store.publishing != nil {
                    ChangesRow().padding(.top, Space.s)
                }
                SpendingCard()
                    .padding(.horizontal, Space.gutter)
                VStack(spacing: 0) {
                    PlainRow(icon: "bill", title: String(localized: "me.ledger"), detail: String(localized: "me.netAssets \(ledger.hideAmounts ? "••••" : Formats.amount(ledger.ledger.netAssets))"), trailing: .chevron) { router.push(.ledger) }
                    PlainRow(icon: "footprints", title: String(localized: "me.footprints"), detail: footprintsLine, trailing: .chevron) { router.push(.footprints) }
                }
                .padding(.top, Space.m)

                GroupCaption(title: "me.things")
                PlainRow(icon: "laptop", title: String(localized: "things.devices"), detail: devicesLine, trailing: .chevron) { router.push(.devices) }
                PlainRow(icon: "subscription", title: String(localized: "things.subscriptions"), trailing: .chevron) { router.push(.subscriptions) }
                PlainRow(icon: "car", title: String(localized: "things.car"), trailing: .chevron) { router.push(.car) }

                GroupCaption(title: "me.general")
                PlainRow(icon: "eye", title: String(localized: "items.title"), trailing: .chevron) { router.push(.items) }
                PlainRow(icon: "cloud-sync", title: String(localized: "sync.title"), detail: store.hasAPI ? Settings.apiURL?.host() : String(localized: "sync.none"), trailing: .chevron) { router.push(.sync) }

                GroupCaption(title: "me.phone")
                PlainRow(icon: "book", title: String(localized: "ledgerSettings.title"), detail: ledger.ledger.books.isEmpty ? nil : String(localized: "ledgerSettings.books \(ledger.ledger.books.count)"), trailing: .chevron) { router.push(.ledgerSettings) }
                PlainRow(icon: "footprints", title: String(localized: "footprintSettings.title"), detail: String(localized: footprints.daily ? "footprints.daily" : "footprints.off"), trailing: .chevron) { router.push(.footprintSettings) }

                HStack(spacing: Space.m) {
                    Image("Mantou").resizable().scaledToFill().frame(width: 32, height: 32).clipShape(.circle)
                        .accessibilityLabel(Text("me.mantou"))
                    Text(verbatim: "Here \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")" + Formats.gap + String(localized: "me.thanks"))
                        .textStyle(.caption).foregroundStyle(Tokens.tx3)
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.xl)
                .padding(.bottom, 120)
            }
        }
        .refreshable { await store.refresh() }
        .page(Text("tab.me"), subtitle: syncLine)
    }

    private var syncLine: Text? {
        guard store.hasAPI else { return nil }
        return Text("me.synced \(store.moments.count) \(store.places.count)")
    }

    private var footprintsLine: String? {
        guard footprints.recording else { return nil }
        _ = footprints.version
        let today = FootprintStore.distance(FootprintStore.lines(footprints.samples(day: Times.day(Date()))))
        return String(localized: "footprints.on") + Formats.gap + String(localized: "me.today \(Formats.distance(today))")
    }

    private var devicesLine: String? {
        let owned = store.entries.values.filter { $0.device != nil && $0.device?["sold"] == nil }.count
        return owned == 0 ? nil : String(localized: "me.devicesOwned \(owned)")
    }
}

/// This month's spending as the design's AmountHero, its income and what is left, a bar a day,
/// and the eye that hides every amount.
private struct SpendingCard: View {
    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router

    var body: some View {
        @Bindable var ledger = ledger
        let month = String(Times.day(Date()).prefix(7))
        let entries = ledger.shownEntries.filter { $0.txn.day.hasPrefix(month) }
        let (spent, earned) = ledger.ledger.sums(entries)
        let today = Int(Times.day(Date()).suffix(2)) ?? 1
        let days = Calendar.current.range(of: .day, in: .month, for: Date())?.count ?? 30
        let byDay = Dictionary(grouping: entries.filter(\.expense), by: { Int($0.txn.day.suffix(2)) ?? 1 })
        let values = (1...days).map { day in Double(ledger.ledger.sums(byDay[day] ?? []).spent) }
        let hidden = ledger.hideAmounts
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center) {
                Text("ledgerSettings.title").textStyle(.body, strong: true).foregroundStyle(Tokens.tx)
                Text(verbatim: ledger.ledger.book(ledger.currentBook)?.name ?? String(localized: "ledger.allBooks")).textStyle(.caption).foregroundStyle(Tokens.tx3)
                Spacer()
                Button {
                    withAnimation(Springs.effectsDefault) { ledger.hideAmounts.toggle() }
                } label: {
                    Icon(hidden ? "eye-off" : "eye", size: 18).foregroundStyle(Tokens.tx2).frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("ledger.hideAmounts"))
            }
            Button { router.push(.ledger) } label: {
                VStack(alignment: .leading, spacing: Space.m) {
                    AmountHero(
                        caption: String(localized: "me.monthSpent \(Int(month.suffix(2)) ?? 1)"),
                        amount: hidden ? "••••" : Formats.amount(spent),
                        figures: hidden ? [] : [(String(localized: "ledger.earned"), Formats.amount(earned), true), (String(localized: "ledger.left"), Formats.amount(earned - spent), false)]
                    )
                    DailyBars(values: values, today: today, height: 56)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }
}
