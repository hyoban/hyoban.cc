import SwiftUI

// 时间线: everything as a conversation with oneself, the newest day first and each day in the order
// it happened. Each day opens with its date in a capsule; Moments come from the right in bubbles, the
// day's bills, charges, devices and subscriptions from the left in one outlined bubble, the
// footprints last. A trip's banner stands above its last day. It starts folded, the small title
// and the search; while changes wait, 发布更改 heads the list. Scrolling down slides the tab bar away.

struct TimelineView: View {
    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router

    var body: some View {
        let days = Set(store.dayKeys).union(ledger.byDay.keys).sorted(by: >)
        ScrollView {
            LazyVStack(spacing: 0) {
                if store.pendingCount > 0 || store.publishing != nil {
                    ChangesRow().padding(.bottom, Space.l)
                }
                if store.loaded && days.isEmpty {
                    EmptyTimeline()
                }
                ForEach(days, id: \.self) { day in
                    ChatDay(day: day)
                }
            }
            .padding(.bottom, 96)
        }
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y } action: { old, new in
            let delta = new - old
            if new < 40 {
                if router.tabBarAway { router.tabBarAway = false }
            } else if delta > 6, !router.tabBarAway {
                router.tabBarAway = true
            } else if delta < -6, router.tabBarAway {
                router.tabBarAway = false
            }
        }
        .onDisappear { router.tabBarAway = false }
        .refreshable { await store.refresh() }
        .page(Text("tab.timeline"), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.push(.search) } label: { Icon("search", size: 22) }
                    .accessibilityLabel(Text("tab.search"))
            }
        }
    }
}

private struct EmptyTimeline: View {
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        VStack(spacing: Space.l) {
            Text(store.hasAPI ? "timeline.emptyHint" : "timeline.noApiHint")
                .textStyle(.body)
                .foregroundStyle(Tokens.tx2)
                .multilineTextAlignment(.center)
            if !store.hasAPI {
                Button("sync.title") { router.tab = .me; router.me = [.sync] }.buttonStyle(.text)
            }
        }
        .padding(.horizontal, Space.gutter)
        .padding(.top, 120)
    }
}

/// One day of a conversation: its trip's banner when it is the trip's last, its date, then what
/// happened in order, Moments and records each in runs.
struct ChatDay: View {
    var day: String
    /// In a trip, which of its days this is; its Moments alone, not the trip's banner.
    var trip: Trip?
    var tripDay: Int?

    @Environment(AppStore.self) private var store
    @Environment(LedgerStore.self) private var ledger
    @Environment(FootprintStore.self) private var footprints

    var body: some View {
        let stream = Chat.stream(day: day, trip: trip, store: store, ledger: ledger, footprints: footprints)
        VStack(spacing: 0) {
            if trip == nil, let banner = store.trips.first(where: { Chat.isBannerDay($0, day) }) {
                TripBanner(trip: banner).padding(.top, Space.xl)
            }
            DateCapsule(day: day, tripDay: tripDay)
                .padding(.top, 7)
                .padding(.bottom, 7)
            ForEach(stream.indices, id: \.self) { index in
                let run = stream[index]
                switch run {
                case .moments(let entries):
                    VStack(alignment: .trailing, spacing: Space.xs) {
                        ForEach(entries.indices, id: \.self) { position in
                            MomentBubble(entry: entries[position], run: BubbleRun(first: position == 0, last: position == entries.count - 1), showPlace: true)
                                .frame(maxWidth: entries[position].media.isEmpty ? 420 : 380, alignment: .trailing)
                                .containerRelativeFrame(.horizontal, alignment: .trailing) { width, _ in width * 0.88 - 9 }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, Space.gutter + 9)
                    .padding(.top, index == 0 ? 0 : Space.m)
                case .records(let items):
                    Group {
                        if items.count == 1 {
                            RecordsBubble(items: items, day: day).fixedSize(horizontal: true, vertical: false)
                        } else {
                            RecordsBubble(items: items, day: day)
                                .frame(maxWidth: 420, alignment: .leading)
                                .containerRelativeFrame(.horizontal, alignment: .leading) { width, _ in width * 0.86 - 10 }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, Space.gutter + 10)
                        .padding(.top, index == 0 ? 0 : Space.m)
                }
            }
        }
    }
}

/// What a day holds, in the order it happened, run by run.
enum Chat {
    enum Run {
        case moments([Entry])
        case records([RecordItem])
    }

    /// The trip's banner stands above its last day, or above today while it is under way.
    static func isBannerDay(_ trip: Trip, _ day: String) -> Bool {
        if trip.dayToday() != nil { return day == min(trip.end, Times.day(Date())) }
        return day == trip.end
    }

    static func stream(day: String, trip: Trip?, store: AppStore, ledger: LedgerStore, footprints: FootprintStore) -> [Run] {
        var events: [(Date, Either)] = []
        let entries = store.days[day] ?? []
        for entry in entries {
            if entry.isMoment {
                if let trip, !trip.moments.contains(where: { $0.uid == entry.uid }) { continue }
                events.append((entry.date, .moment(entry)))
            } else if trip == nil, let item = record(entry, store: store) {
                events.append((entry.date, .record(item)))
            }
        }
        if trip == nil || trip != nil {
            let site = Set(entries.map(\.uid))
            for txn in ledger.txns(on: day) where !site.contains(txn.id) {
                if let trip, txn.trip != trip.id, !(txn.day >= trip.start && txn.day <= trip.end) { continue }
                events.append((txn.date, .record(RecordItem(
                    id: txn.id, time: Formats.time(txn.at), icon: BillLook.icon(txn, ledger.ledger),
                    title: BillLook.title(txn, ledger.ledger), value: ledger.hideAmounts ? "••••" : BillLook.amount(txn),
                    income: txn.isInflow, route: .bill(txn.id)
                ))))
            }
        }
        events.sort { $0.0 < $1.0 }
        var runs: [Run] = []
        for (_, event) in events {
            switch event {
            case .moment(let entry):
                if case .moments(let list) = runs.last { runs[runs.count - 1] = .moments(list + [entry]) } else { runs.append(.moments([entry])) }
            case .record(let item):
                if case .records(let list) = runs.last { runs[runs.count - 1] = .records(list + [item]) } else { runs.append(.records([item])) }
            }
        }
        // The footprints, always last.
        _ = footprints.version
        let lines = FootprintStore.lines(footprints.samples(day: day))
        if !lines.isEmpty {
            let item = RecordItem(id: "footprints-\(day)", time: nil, icon: "footprints", title: String(localized: "record.footprints"), value: Formats.distance(FootprintStore.distance(lines)), route: .day(day))
            if case .records(let list) = runs.last { runs[runs.count - 1] = .records(list + [item]) } else { runs.append(.records([item])) }
        }
        return runs
    }

    private enum Either {
        case moment(Entry)
        case record(RecordItem)
    }

    /// One of the site's records that isn't a Moment, as a line.
    static func record(_ entry: Entry, store: AppStore) -> RecordItem? {
        let time = Formats.time(entry.at)
        if let bill = entry.bill {
            let cents = Int(((bill["amount"]?.double ?? 0) * 100).rounded())
            let income = bill["kind"]?.string == "income"
            return RecordItem(id: entry.uid, time: time, icon: CategoryIcons.of(bill["category"]?.string, income: income), title: bill["category"]?.string ?? String(localized: "record.bill"), value: Formats.amount(cents, bill["currency"]?.string ?? "CNY", sign: income ? .plus : .minus), income: income)
        }
        if let charge = entry.charge {
            let kwh = (charge["energyWh"]?.double ?? 0) / 1000
            return RecordItem(id: entry.uid, time: time, icon: "battery-charging", title: String(localized: "record.charge"), value: String(format: "%.1f kWh", kwh), route: .day(entry.day))
        }
        if let device = entry.device {
            return RecordItem(id: entry.uid, time: nil, icon: "laptop", title: device["name"]?.string ?? "", value: Formats.money(device["price"]?.double ?? 0), route: .devices)
        }
        if let subscription = entry.subscription {
            return RecordItem(id: entry.uid, time: nil, icon: "subscription", title: subscription["name"]?.string ?? "", value: Formats.money(subscription["price"]?.double ?? 0, subscription["currency"]?.string ?? "CNY"), route: .subscriptions)
        }
        return nil
    }
}
