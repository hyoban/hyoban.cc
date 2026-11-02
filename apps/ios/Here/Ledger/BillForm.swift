import SwiftUI

// 记一笔 (the design's 21): a page of its own, a row of tools on top (close, 支出 收入 转账), the sum
// typed large on the right with its working above it, the categories as round icons, chips for
// where it was paid from, when, and whether it is public, the note, and the keypad fixed at the
// foot with 保存 its one ink key. Opened from a Moment, it belongs to that Moment, its place and trip.

struct BillForm: View {
    /// A bill's id to edit, or `moment:<uid>` for a new one of that Moment, or nil.
    var editing: String?

    @Environment(LedgerStore.self) private var ledger
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var txn: Txn?
    @State private var expression = ""
    @State private var date = Date()
    @State private var choosingTime = false

    var body: some View {
        Group {
            if let binding = Binding($txn) {
                form(binding)
            } else {
                Tokens.bg.onAppear(perform: start)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }

    private func form(_ txn: Binding<Txn>) -> some View {
        let book = ledger.ledger
        let categories = book.categories.filter { $0.book == txn.wrappedValue.book && $0.type == (txn.wrappedValue.kind == .income ? .income : .expense) }
        let cents = Keypad.cents(expression)
        return VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: { Icon("close", size: 22).foregroundStyle(Tokens.tx).frame(width: 48, height: 48) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("action.close"))
                Spacer()
                Segmented(options: [(TxnKind.expense, "txn.expense"), (TxnKind.income, "txn.income"), (TxnKind.transfer, "txn.transfer")], selection: txn.kind)
                    .frame(width: 220)
                Spacer()
                Color.clear.frame(width: 48, height: 48)
            }
            .padding(.horizontal, Space.s)
            .padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.l) {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(verbatim: expression.contains(where: { "+-".contains($0) }) ? expression.replacingOccurrences(of: "-", with: "−") : " ")
                            .textStyle(.caption).foregroundStyle(Tokens.tx3)
                        Text(verbatim: Formats.amount(cents ?? 0, book.book(txn.wrappedValue.book)?.currency ?? txn.wrappedValue.currency))
                            .font(.system(size: 40, weight: .semibold).monospacedDigit())
                            .foregroundStyle(cents == nil ? Tokens.danger : Tokens.tx)
                            .contentTransition(.numericText())
                            .accessibilityIdentifier("bill_amount")
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    if txn.wrappedValue.kind != .transfer {
                        CategoryGrid(categories: categories, selection: txn.category)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: Space.s) {
                            Menu {
                                Picker(selection: txn.account) {
                                    Text("txn.noAccount").tag(String?.none)
                                    ForEach(book.accounts.filter { !$0.archived }) { Text(verbatim: $0.name).tag(String?.some($0.id)) }
                                } label: { EmptyView() }
                            } label: {
                                chipLabel(book.account(txn.wrappedValue.account)?.name ?? String(localized: "txn.noAccount"), icon: "wallet")
                            }
                            if txn.wrappedValue.kind == .transfer {
                                Menu {
                                    Picker(selection: txn.toAccount) {
                                        Text("txn.noAccount").tag(String?.none)
                                        ForEach(book.accounts.filter { !$0.archived }) { Text(verbatim: $0.name).tag(String?.some($0.id)) }
                                    } label: { EmptyView() }
                                } label: {
                                    chipLabel(book.account(txn.wrappedValue.toAccount)?.name ?? String(localized: "txn.to"), icon: "swap")
                                }
                            }
                            Chip(title: date.formatted(date: .abbreviated, time: .shortened), icon: "clock") { choosingTime = true }
                            Menu {
                                Picker(selection: txn.book) {
                                    ForEach(book.books.filter { !$0.archived }) { Text(verbatim: $0.name).tag($0.id) }
                                } label: { EmptyView() }
                            } label: {
                                chipLabel(book.book(txn.wrappedValue.book)?.name ?? "", icon: "book")
                            }
                            Chip(title: String(localized: "txn.public"), selected: book.isPublic(txn.wrappedValue)) {
                                txn.wrappedValue.visibility = book.isPublic(txn.wrappedValue) ? "private" : "public"
                            }
                            Chip(title: String(localized: "txn.excluded"), selected: txn.wrappedValue.excluded) { txn.wrappedValue.excluded.toggle() }
                        }
                        .padding(.horizontal, Space.gutter)
                    }
                    .padding(.horizontal, -Space.gutter)
                    TextField(text: txn.note, prompt: Text("txn.note").foregroundStyle(Tokens.tx3), axis: .vertical) { Text("txn.note") }
                        .textStyle(.body)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .background(Tokens.surface, in: .mediumShape)
                        .overlay(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous).strokeBorder(Tokens.borderStrong))
                    if let moment = txn.wrappedValue.moment.flatMap({ store.entry($0) }) {
                        HStack(spacing: Space.s) {
                            Icon("link", size: 18)
                            Text(verbatim: String(moment.text.prefix(40))).textStyle(.caption).lineLimit(1)
                        }
                        .foregroundStyle(Tokens.tx2)
                    }
                }
                .padding(.horizontal, Space.gutter)
                .padding(.vertical, Space.m)
            }
            BillKeypad(expression: $expression, canSave: (cents ?? 0) > 0) { save() }
        }
        .background(Tokens.bg)
        .sheet(isPresented: $choosingTime) {
            DatePicker(selection: $date) { Text("compose.time") }
                .datePickerStyle(.graphical)
                .padding()
                .presentationDetents([.medium])
        }
    }

    private func chipLabel(_ title: String, icon: String) -> some View {
        HStack(spacing: 6) {
            Icon(icon, size: 18)
            Text(verbatim: title).textStyle(.label).lineLimit(1)
        }
        .foregroundStyle(Tokens.tx2)
        .padding(.leading, 10)
        .padding(.trailing, 14)
        .frame(height: 36)
        .background(Tokens.fill, in: .capsule)
    }

    private func start() {
        if let id = editing, let existing = ledger.ledger.txns.first(where: { $0.id == id }) {
            txn = existing
            expression = existing.decimal.formatted(.number.grouping(.never))
            date = existing.date
            return
        }
        let book = ledger.ensureBook()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var draft = Txn(
            id: Ulid.make(), book: book.id, kind: .expense, time: now, offsetMinutes: TimeZone.current.secondsFromGMT() / 60,
            amount: 0, currency: book.currency, category: ledger.ledger.categories.first { $0.book == book.id && $0.type == .expense }?.id,
            account: ledger.ledger.txns.first?.account, createdAt: now, updatedAt: now
        )
        if let id = editing, id.hasPrefix("moment:"), let entry = store.entry(String(id.dropFirst(7))) {
            draft.moment = entry.uid
            draft.place = entry.place
            draft.trip = store.trip(of: entry)?.id
            draft.time = Int64(entry.date.timeIntervalSince1970 * 1000)
            if let cost = entry.food?["cost"]?.double { expression = cost.formatted(.number.grouping(.never)) }
            if let food = ledger.ledger.categories.first(where: { $0.book == book.id && ($0.name == "三餐" || $0.name == "Meals") }) { draft.category = food.id }
        }
        date = draft.date
        txn = draft
    }

    private func save() {
        guard var txn, let cents = Keypad.cents(expression), cents > 0 else { return }
        txn.amount = cents
        txn.time = Int64(date.timeIntervalSince1970 * 1000)
        if txn.kind == .income, ledger.ledger.category(txn.category)?.type != .income {
            txn.category = ledger.ledger.categories.first { $0.book == txn.book && $0.type == .income }?.id
        }
        if txn.kind == .transfer { txn.category = nil }
        ledger.save(txn)
        dismiss()
    }
}

/// A bill's page (the design's 20): its amount large under its category, and plain rows of where
/// it belongs: its book, account, time, note, whether it is public, its Moment and place.
struct BillDetail: View {
    var id: String
    @Environment(LedgerStore.self) private var ledger
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if let txn = ledger.ledger.txns.first(where: { $0.id == id }) {
            let book = ledger.ledger
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: Space.s) {
                            Icon(BillLook.icon(txn, book), size: 18)
                            Text(verbatim: BillLook.title(txn, book))
                        }
                        .textStyle(.body).foregroundStyle(Tokens.tx2)
                        Text(verbatim: ledger.hideAmounts ? "••••" : BillLook.amount(txn))
                            .textStyle(.titlePage, strong: true)
                            .foregroundStyle(txn.isInflow ? Tokens.income : Tokens.tx)
                    }
                    .padding(.horizontal, Space.gutter)
                    .padding(.bottom, Space.l)
                    PlainRow(icon: "book", title: book.book(txn.book)?.name ?? "")
                    if let account = book.account(txn.account) { PlainRow(icon: "wallet", title: account.name, detail: txn.kind == .transfer ? String(localized: "txn.from") : nil) }
                    if let account = book.account(txn.toAccount) { PlainRow(icon: "swap", title: account.name, detail: String(localized: "txn.to")) }
                    ForEach(txn.splits ?? [], id: \.account) { split in
                        PlainRow(icon: "wallet", title: book.account(split.account)?.name ?? "", trailing: .amount(Formats.amount(split.amount, txn.currency)))
                    }
                    PlainRow(icon: "clock", title: [Formats.day(txn.day), Formats.time(txn.at) ?? ""].joined(separator: Formats.gap))
                    if !txn.note.isEmpty { PlainRow(icon: "notes", title: txn.note) }
                    PlainRow(icon: book.isPublic(txn) ? "eye" : "eye-off", title: String(localized: book.isPublic(txn) ? "visibility.public" : "visibility.private"), detail: book.isPublic(txn) ? String(localized: "txn.publicFooter") : nil)
                    if let entry = txn.moment.flatMap({ store.entry($0) }) {
                        PlainRow(icon: "link", title: String(entry.text.prefix(60)), trailing: .chevron) { router.push(.moment(entry.uid)) }
                    }
                    if let place = txn.place, let known = store.places[place] {
                        PlainRow(icon: "pin", title: known.name, trailing: .chevron) { router.push(.place(place)) }
                    }
                    Button("action.delete") {
                        ledger.delete(txn)
                        dismiss()
                    }
                    .buttonStyle(.destructiveText)
                    .padding(.horizontal, Space.s)
                    .padding(.top, Space.xl)
                }
                .padding(.bottom, Space.xl)
            }
            .page(Text(verbatim: Formats.day(txn.day)), large: false)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { router.sheet = .newBill(txn.id) } label: { Icon("pen", size: 22) }
                        .accessibilityLabel(Text("action.edit"))
                }
            }
        } else {
            Text("bill.gone").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity, maxHeight: .infinity).background(Tokens.bg)
        }
    }
}
