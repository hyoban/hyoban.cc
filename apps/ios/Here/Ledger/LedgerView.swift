import Charts
import SwiftUI

// 记账's page (the design's 17–19): the book's name large, a menu over it switching books or showing
// 全部账本, and three segments that stay under the circles: 账单, the month's spending and its days of
// bills; 统计, a month or a year of spending, earning and what is left with its charts; 资产, net
// assets and the accounts by kind. The ink + writes a bill.

struct LedgerView: View {
    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router
    @State private var tab: Tab = .bills
    @State private var month = Date()

    enum Tab: Hashable { case bills, stats, assets }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Menu {
                    Button { ledger.currentBook = nil } label: { Text("ledger.allBooks") }
                    ForEach(ledger.ledger.books.filter { !$0.archived }) { book in
                        Button { ledger.currentBook = book.id } label: { Text(verbatim: book.name) }
                    }
                } label: {
                    HStack(spacing: 2) {
                        Text(verbatim: ledger.ledger.book(ledger.currentBook)?.name ?? String(localized: "ledger.allBooks"))
                            .textStyle(.titlePage, strong: true)
                        Icon("caret-down", size: 24)
                    }
                    .foregroundStyle(Tokens.tx)
                }
                .padding(.horizontal, Space.gutter)
                Segmented(options: [(Tab.bills, "ledger.bills"), (Tab.stats, "ledger.stats"), (Tab.assets, "ledger.assets")], selection: $tab)
                    .padding(.horizontal, Space.gutter)
                    .padding(.top, Space.m)
                switch tab {
                case .bills: BillsList(month: $month)
                case .stats: LedgerStats()
                case .assets: AssetsList()
                }
            }
            .padding(.bottom, 120)
        }
        .page(Text(verbatim: ""), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.push(.search) } label: { Icon("search", size: 22) }
                    .accessibilityLabel(Text("tab.search"))
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { router.sheet = .newBill(nil) } label: {
                Icon("plus", size: 24).foregroundStyle(Tokens.bg).frame(width: 56, height: 56)
                    .background(Tokens.tx, in: .circle)
                    .glassEffect(.regular.tint(Tokens.tx).interactive(), in: .circle)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("ledger.addBill"))
            .accessibilityIdentifier("new_bill")
            .padding(.trailing, Space.gutter)
            .padding(.bottom, Space.s)
        }
    }
}

/// A month of the book's bills: what it spent, then its days, newest first.
private struct BillsList: View {
    @Binding var month: Date
    @Environment(LedgerStore.self) private var ledger

    var body: some View {
        let key = String(Times.day(month).prefix(7))
        let txns = ledger.shownTxns.filter { $0.day.hasPrefix(key) }
        let byDay = Dictionary(grouping: txns, by: \.day)
        let entriesByDay = Dictionary(grouping: ledger.shownEntries.filter { $0.txn.day.hasPrefix(key) }, by: \.txn.day)
        let spent = ledger.ledger.sums(entriesByDay.values.flatMap { $0 }).spent
        VStack(alignment: .leading, spacing: 0) {
            AmountHero(
                caption: month.formatted(.dateTime.year().month(.wide)),
                amount: ledger.hideAmounts ? "••••" : Formats.amount(spent, sign: spent > 0 ? .minus : .none),
                previous: { move(-1) },
                next: { move(1) }
            )
            .padding(.horizontal, Space.gutter)
            .padding(.top, Space.l)
            if txns.isEmpty {
                Text("ledger.empty").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity).padding(.top, 60)
            }
            LazyVStack(spacing: 0) {
                ForEach(byDay.keys.sorted(by: >), id: \.self) { day in
                    let sums = ledger.ledger.sums(entriesByDay[day] ?? [])
                    let net = sums.earned - sums.spent
                    VStack(spacing: 0) {
                        DayHeader(day: day, sum: ledger.hideAmounts ? "••••" : Formats.amount(net, sign: net > 0 ? .plus : .none))
                        ForEach(byDay[day] ?? []) { txn in BillRow(txn: txn) }
                    }
                    .padding(.bottom, Space.xl)
                }
            }
            .padding(.top, Space.l)
        }
    }

    private func move(_ by: Int) {
        withAnimation(Springs.spatialDefault) { month = Calendar.current.date(byAdding: .month, value: by, to: month) ?? month }
    }
}

private struct LedgerStats: View {
    @Environment(LedgerStore.self) private var ledger
    @State private var period: Period = .month
    @State private var anchor = Date()

    enum Period: Hashable { case month, year }

    var body: some View {
        let key = period == .month ? String(Times.day(anchor).prefix(7)) : String(Times.day(anchor).prefix(4))
        let book = ledger.ledger
        let entries = ledger.shownEntries.filter { $0.txn.day.hasPrefix(key) }
        let (spent, earned) = book.sums(entries)
        let expenses = entries.filter(\.expense).map { entry in
            (entry: entry, value: Double(book.rates.convert(entry.amount, from: entry.txn.currency, to: "CNY", day: entry.txn.day) ?? entry.amount) / 100)
        }
        VStack(alignment: .leading, spacing: Space.xl) {
            Segmented(options: [(Period.month, "ledger.month"), (Period.year, "ledger.year")], selection: $period)
            AmountHero(
                caption: period == .month ? anchor.formatted(.dateTime.year().month(.wide)) : anchor.formatted(.dateTime.year()),
                amount: ledger.hideAmounts ? "••••" : Formats.amount(spent),
                figures: ledger.hideAmounts ? [] : [(String(localized: "ledger.earned"), Formats.amount(earned), true), (String(localized: "ledger.left"), Formats.amount(earned - spent), false)],
                previous: { move(-1) },
                next: { move(1) }
            )
            VStack(alignment: .leading, spacing: Space.m) {
                Text(period == .month ? "ledger.byDay" : "ledger.byMonth").textStyle(.body, strong: true)
                let slots = period == .month ? (Calendar.current.range(of: .day, in: .month, for: anchor)?.count ?? 30) : 12
                let groups = Dictionary(grouping: expenses, by: { Int(period == .month ? $0.entry.txn.day.suffix(2) : $0.entry.txn.day.dropFirst(5).prefix(2)) ?? 1 })
                DailyBars(values: (1...slots).map { slot in (groups[slot] ?? []).reduce(0) { $0 + $1.value } }, today: nil, height: 96)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text("ledger.byCategory").textStyle(.body, strong: true).padding(.bottom, Space.s)
                let groups = Dictionary(grouping: expenses, by: { book.topCategory($0.entry.category)?.name ?? String(localized: "ledger.other") })
                let sorted = groups.map { ($0.key, $0.value.reduce(0) { $0 + $1.value }) }.filter { $0.1 > 0 }.sorted { $0.1 > $1.1 }
                let total = max(sorted.reduce(0) { $0 + $1.1 }, 1)
                ForEach(Array(sorted.prefix(8).enumerated()), id: \.element.0) { index, item in
                    ShareBar(title: item.0, share: item.1 / total, fraction: item.1 / max(sorted.first?.1 ?? 1, 1), color: index < 6 ? Tokens.seriesColors[index] : Tokens.borderStrong)
                }
            }
            VStack(alignment: .leading, spacing: Space.m) {
                Text("ledger.byWeekday").textStyle(.body, strong: true)
                let calendar = Calendar.current
                let groups = Dictionary(grouping: expenses, by: { calendar.component(.weekday, from: $0.entry.txn.date) })
                Chart(1...7, id: \.self) { weekday in
                    BarMark(x: .value("weekday", calendar.veryShortWeekdaySymbols[weekday - 1]), y: .value("amount", (groups[weekday] ?? []).reduce(0) { $0 + $1.value }))
                        .foregroundStyle(Tokens.accent)
                        .cornerRadius(3)
                }
                .chartYAxis(.hidden)
                .frame(height: 96)
            }
        }
        .padding(.horizontal, Space.gutter)
        .padding(.top, Space.l)
        .redacted(reason: ledger.hideAmounts ? .privacy : [])
    }

    private func move(_ by: Int) {
        withAnimation(Springs.spatialDefault) { anchor = Calendar.current.date(byAdding: period == .month ? .month : .year, value: by, to: anchor) ?? anchor }
    }
}

private struct AssetsList: View {
    @Environment(LedgerStore.self) private var ledger
    @State private var editing: Account?

    var body: some View {
        let book = ledger.ledger
        let accounts = book.accounts.filter { !$0.archived }
        let today = Times.day(Date())
        let values = accounts.filter(\.counted).map { book.rates.convert(book.balance($0), from: $0.currency, to: "CNY", day: today) ?? book.balance($0) }
        VStack(alignment: .leading, spacing: 0) {
            AmountHero(
                caption: String(localized: "ledger.netAssets"),
                amount: ledger.hideAmounts ? "••••" : Formats.amount(book.netAssets),
                figures: ledger.hideAmounts ? [] : [(String(localized: "ledger.held"), Formats.amount(values.filter { $0 > 0 }.reduce(0, +)), false), (String(localized: "ledger.owed"), Formats.amount(-values.filter { $0 < 0 }.reduce(0, +)), false)]
            )
            .padding(.horizontal, Space.gutter)
            .padding(.top, Space.l)
            ForEach(AccountKind.allCases, id: \.self) { kind in
                let group = accounts.filter { $0.kind == kind }
                if !group.isEmpty {
                    GroupCaption(title: LocalizedStringKey("account.\(kind.rawValue)"))
                    ForEach(group) { account in
                        let balance = book.balance(account)
                        PlainRow(icon: kind.icon, title: account.name, detail: account.counted ? nil : String(localized: "account.notCounted"), trailing: .amount(ledger.hideAmounts ? "••••" : Formats.amount(balance, account.currency), owed: balance < 0)) {
                            editing = account
                        }
                    }
                }
            }
            Button { editing = Account(id: Ulid.make(), name: "", position: book.accounts.count) } label: { IconLabel(title: "account.add", icon: "plus") }
                .buttonStyle(.text)
                .padding(.horizontal, Space.s)
                .padding(.top, Space.l)
        }
        .sheet(item: $editing) { AccountEditor(account: $0) }
    }
}

/// An account: its name, kind and currency, whether it counts in the net assets, and 校准余额.
struct AccountEditor: View {
    @State var account: Account
    @Environment(LedgerStore.self) private var ledger
    @Environment(\.dismiss) private var dismiss
    @State private var balance = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.l) {
                    LabeledField(title: "account.name", text: $account.name)
                    LabeledField(title: "account.currency", text: $account.currency)
                    LabeledField(title: "account.calibrate", text: $balance, note: "account.calibrateFooter")
                    VStack(alignment: .leading, spacing: 6) {
                        Text("account.kind").textStyle(.caption).foregroundStyle(Tokens.tx2).padding(.leading, Space.xs)
                        FlowLayout(spacing: Space.s) {
                            ForEach(AccountKind.allCases, id: \.self) { kind in
                                Chip(title: String(localized: String.LocalizationValue("account.\(kind.rawValue)")), selected: account.kind == kind) { account.kind = kind }
                            }
                        }
                    }
                    SettingsGroup {
                        SwitchRow(title: String(localized: "account.counted"), isOn: $account.counted)
                        if ledger.ledger.account(account.id) != nil {
                            SwitchRow(title: String(localized: "account.archived"), isOn: $account.archived)
                        }
                    }
                    .padding(.horizontal, -Space.gutter)
                    if ledger.ledger.account(account.id) != nil {
                        Button("action.delete") { ledger.deleteAccount(account); dismiss() }.buttonStyle(.destructiveText)
                    }
                }
                .padding(Space.gutter)
            }
            .background(Tokens.bg)
            .navigationTitle(Text(account.name.isEmpty ? "account.add" : "account.edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Icon("close", size: 22) }.accessibilityLabel(Text("action.cancel")) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.save") {
                        ledger.save(account)
                        if let cents = Keypad.cents(balance.replacingOccurrences(of: ",", with: "")) {
                            ledger.calibrate(ledger.ledger.account(account.id) ?? account, to: cents)
                        }
                        dismiss()
                    }
                    .disabled(account.name.isEmpty)
                }
            }
            .onAppear {
                if ledger.ledger.account(account.id) != nil {
                    balance = (Double(ledger.ledger.balance(account)) / 100).formatted(.number.grouping(.never))
                }
            }
        }
    }
}
