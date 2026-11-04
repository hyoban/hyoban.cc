import SwiftUI

// The ledger's components as the design system's cards: AmountHero, DayHeader, BillRow, StatRow,
// DailyBars, ShareBar, Keypad and CategoryGrid; Android's are in ui/ledger and ui/feed.

/// A large amount straight on the page: a line above it saying what, small figures below.
struct AmountHero: View {
    var caption: String
    var amount: String
    var figures: [(String, String, Bool)] = []
    var previous: (() -> Void)?
    var next: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 2) {
                if let previous {
                    Button(action: previous) { Icon("chevron-left", size: 18) }.buttonStyle(.plain).accessibilityLabel(Text("ledger.previous"))
                }
                Text(verbatim: caption)
                if let next {
                    Button(action: next) { Icon("chevron-right", size: 18) }.buttonStyle(.plain).accessibilityLabel(Text("ledger.next"))
                }
            }
            .textStyle(.caption)
            .foregroundStyle(Tokens.tx2)
            Text(verbatim: amount)
                .textStyle(.titlePage, strong: true)
                .foregroundStyle(Tokens.tx)
                .contentTransition(.numericText())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if !figures.isEmpty {
                figures.indices.reduce(Text(verbatim: "")) { text, index in
                    let figure = figures[index]
                    return Text("\(text)\(Text(verbatim: (index == 0 ? "" : "\u{2003}") + figure.0 + " ").foregroundStyle(Tokens.tx2))\(Text(verbatim: figure.1).foregroundStyle(figure.2 ? Tokens.income : Tokens.tx2))")
                }
                .textStyle(.caption)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A ledger day's first line: the day, its weekday and its sum.
struct DayHeader: View {
    var day: String
    var sum: String

    var body: some View {
        let today = day == Times.day(Date())
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Text(verbatim: String(Int(day.suffix(2)) ?? 0)).textStyle(.title, strong: true).foregroundStyle(today ? Tokens.accent : Tokens.tx)
            Text(verbatim: Formats.weekday(day)).textStyle(.caption).foregroundStyle(Tokens.tx2)
            Spacer()
            Text(verbatim: sum).textStyle(.caption).foregroundStyle(Tokens.tx2)
        }
        .padding(.horizontal, Space.gutter)
        .padding(.bottom, Space.xs)
    }
}

/// A bill in the ledger: its category's icon, its name and note, its amount; no card, no rule.
struct BillRow: View {
    var txn: Txn
    /// On a day's page: the icon `tx2`, 48 high, its time under the amount.
    var onDay = false

    @Environment(LedgerStore.self) private var ledger
    @Environment(Router.self) private var router

    var body: some View {
        let book = ledger.ledger
        Button { router.push(.bill(txn.id)) } label: {
            HStack(spacing: Space.m) {
                Icon(BillLook.icon(txn, book), size: 24).foregroundStyle(onDay ? Tokens.tx2 : Tokens.tx3)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: BillLook.title(txn, book)).textStyle(.body).foregroundStyle(Tokens.tx).lineLimit(1)
                    let detail = [txn.note, book.isPublic(txn) ? "" : String(localized: "visibility.private")].filter { !$0.isEmpty }.joined(separator: "\u{2003}")
                    if !detail.isEmpty {
                        Text(verbatim: detail).textStyle(.caption).foregroundStyle(Tokens.tx3).lineLimit(1)
                    }
                }
                Spacer(minLength: Space.m)
                VStack(alignment: .trailing, spacing: 0) {
                    Text(verbatim: ledger.hideAmounts ? "••••" : BillLook.amount(txn))
                        .textStyle(.body)
                        .foregroundStyle(txn.isInflow ? Tokens.income : Tokens.tx)
                    if onDay, let time = Formats.time(txn.at) {
                        Text(verbatim: time).textStyle(.caption).foregroundStyle(Tokens.tx3)
                    }
                }
            }
            .frame(minHeight: onDay ? 48 : 44)
            .padding(.vertical, Space.xs)
            .padding(.horizontal, Space.gutter)
            .contentShape(.rect)
        }
        .buttonStyle(PressWash(shape: Rectangle()))
    }
}

/// How a bill is shown, wherever it is.
enum BillLook {
    static func title(_ txn: Txn, _ book: Ledger) -> String {
        switch txn.kind {
        case .transfer: "\(book.account(txn.account)?.name ?? "?") → \(book.account(txn.toAccount)?.name ?? "?")"
        case .refund: String(localized: "txn.refund")
        case .reimbursement: String(localized: "txn.reimbursement")
        default: book.categoryName(txn.category) ?? String(localized: "record.bill")
        }
    }

    static func icon(_ txn: Txn, _ book: Ledger) -> String {
        switch txn.kind {
        case .transfer: "swap"
        case .refund, .reimbursement: "undo"
        default: CategoryIcons.of(book.category(txn.category)?.name ?? book.topCategory(txn.category)?.name, income: txn.kind == .income)
        }
    }

    /// `−¥58.00`, `+¥12,480.00`; a transfer has no sign.
    static func amount(_ txn: Txn) -> String {
        let sign: Formats.Sign = txn.kind == .transfer ? .none : txn.isInflow ? .plus : .minus
        return Formats.amount(txn.amount, txn.currency, sign: sign)
    }
}

/// Up to three figures in a row: the number, what it is under it.
struct StatRow: View {
    var stats: [(value: String, label: String)]

    var body: some View {
        HStack(alignment: .top, spacing: Space.l) {
            ForEach(stats.indices, id: \.self) { index in
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: stats[index].value).textStyle(.title, strong: true).foregroundStyle(Tokens.tx)
                    Text(verbatim: stats[index].label).textStyle(.caption).foregroundStyle(Tokens.tx2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

/// A thin bar a day, `accent`, the days still to come grey.
struct DailyBars: View {
    var values: [Double]
    var today: Int?
    var height: CGFloat = 56

    var body: some View {
        let top = max(values.max() ?? 0, 1)
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(values.indices, id: \.self) { index in
                let future = today.map { index >= $0 } ?? false
                UnevenRoundedRectangle(topLeadingRadius: 3, topTrailingRadius: 3)
                    .fill(future ? Tokens.borderStrong.opacity(0.5) : Tokens.accent)
                    .frame(height: max(2, height * values[index] / top))
            }
        }
        .frame(height: height, alignment: .bottom)
        .accessibilityHidden(true)
    }
}

/// A category's share: its name and percentage, a thin bar under them.
struct ShareBar: View {
    var title: String
    var share: Double
    var fraction: Double
    var color: Color

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Text(verbatim: title).textStyle(.body)
                Spacer()
                Text(verbatim: share.formatted(.percent.precision(.fractionLength(0)))).textStyle(.body)
            }
            GeometryReader { geometry in
                Capsule().fill(Tokens.fill)
                    .overlay(alignment: .leading) { Capsule().fill(color).frame(width: geometry.size.width * fraction) }
            }
            .frame(height: 6)
        }
        .padding(.vertical, 6)
    }
}

/// An icon for a category, from the design's set, by its name.
enum CategoryIcons {
    static func of(_ name: String?, income: Bool = false) -> String {
        guard let name else { return income ? "wallet" : "bill" }
        let table: [(String, String)] = [
            ("咖啡", "cafe"), ("奶茶", "bubble-tea"), ("饮料", "bottle"), ("水果", "fruit"), ("零食", "snack"), ("菜", "vegetable"),
            ("餐", "cutlery"), ("饭", "food"), ("Food", "cutlery"), ("Meal", "food"),
            ("地铁", "train"), ("火车", "train"), ("飞机", "plane"), ("机票", "plane"), ("打车", "taxi"), ("出租", "taxi"), ("公交", "bus"),
            ("停车", "parking"), ("加油", "fuel"), ("充电", "battery-charging"), ("交通", "bus"), ("Transport", "bus"),
            ("服饰", "clothes"), ("数码", "laptop"), ("日用", "basket"), ("购物", "bag"), ("Shopping", "bag"),
            ("房租", "home"), ("住", "home"), ("水费", "drop"), ("电费", "bolt"), ("Housing", "home"),
            ("话费", "phone"), ("网络", "wifi"), ("通讯", "phone"), ("订阅", "subscription"),
            ("游戏", "game"), ("娱乐", "game"), ("电影", "film"), ("门票", "ticket"), ("医", "pill"), ("药", "pill"),
            ("礼物", "gift"), ("红包", "red-packet"), ("人情", "gift"), ("酒店", "hotel"), ("住宿", "bed"), ("旅行", "luggage"),
            ("工资", "bank"), ("奖金", "gift"), ("利息", "savings"), ("理财", "trend-up"), ("二手", "package"),
        ]
        return table.first { name.contains($0.0) }?.1 ?? (income ? "wallet" : "bill")
    }
}

/// The keypad fixed at the foot of 记一笔: four columns, 保存 the one ink key.
struct BillKeypad: View {
    @Binding var expression: String
    var canSave: Bool
    var save: () -> Void

    var body: some View {
        let rows: [[String]] = [["7", "8", "9", "⌫"], ["4", "5", "6", "+"], ["1", "2", "3", "−"], [".", "0", "00", "save"]]
        VStack(spacing: 6) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 6) {
                    ForEach(row, id: \.self) { key in keyView(key) }
                }
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.top, Space.s)
        .padding(.bottom, Space.s)
        .background(Tokens.surface)
        .overlay(alignment: .top) { Rectangle().fill(Tokens.border).frame(height: 1) }
        .sensoryFeedback(.selection, trigger: expression)
    }

    @ViewBuilder
    private func keyView(_ key: String) -> some View {
        let operation = ["⌫", "+", "−"].contains(key)
        if key == "save" {
            Button(action: save) {
                Text("action.save").textStyle(.label).foregroundStyle(canSave ? Tokens.bg : Tokens.tx3)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(canSave ? Tokens.tx : Tokens.fill, in: .mediumShape)
            }
            .buttonStyle(PressWash(shape: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous)))
            .disabled(!canSave)
            .accessibilityIdentifier("bill_save")
        } else {
            Button { press(key) } label: {
                Group {
                    if key == "⌫" { Icon("backspace", size: 22) } else { Text(verbatim: key).textStyle(.title) }
                }
                .foregroundStyle(operation ? Tokens.tx2 : Tokens.tx)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(operation ? Tokens.fill : Tokens.bg, in: .mediumShape)
                .overlay {
                    if !operation { RoundedRectangle(cornerRadius: Radius.medium, style: .continuous).strokeBorder(Tokens.border) }
                }
            }
            .buttonStyle(PressWash(shape: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous)))
            .accessibilityLabel(key == "⌫" ? Text("keypad.backspace") : Text(verbatim: key))
        }
    }

    private func press(_ key: String) {
        switch key {
        case "⌫": if !expression.isEmpty { expression.removeLast() }
        case "−": expression += "-"
        default: expression += key
        }
    }
}

/// Choosing a category: round icons five to a row, the chosen one's second level opening under its row.
struct CategoryGrid: View {
    var categories: [Category]
    @Binding var selection: String?
    @Environment(LedgerStore.self) private var ledger

    var body: some View {
        let top = categories.filter { $0.parent == nil }
        let rows = stride(from: 0, to: top.count, by: 5).map { Array(top[$0..<min($0 + 5, top.count)]) }
        let chosenTop = ledger.ledger.topCategory(selection)
        VStack(spacing: Space.m) {
            ForEach(rows.indices, id: \.self) { index in
                HStack(alignment: .top, spacing: 0) {
                    ForEach(rows[index]) { category in
                        cell(category, chosen: chosenTop?.id == category.id, dot: categories.contains { $0.parent == category.id })
                    }
                    ForEach(0..<(5 - rows[index].count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
                if let chosenTop, rows[index].contains(where: { $0.id == chosenTop.id }) {
                    let children = categories.filter { $0.parent == chosenTop.id }
                    if !children.isEmpty {
                        FlowLayout(spacing: Space.s) {
                            ForEach(children) { child in
                                Chip(title: child.name, selected: selection == child.id) {
                                    selection = selection == child.id ? chosenTop.id : child.id
                                }
                            }
                        }
                        .padding(Space.m)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Tokens.surface, in: .mediumShape)
                        .overlay(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous).strokeBorder(Tokens.border))
                    }
                }
            }
        }
    }

    private func cell(_ category: Category, chosen: Bool, dot: Bool) -> some View {
        Button { selection = category.id } label: {
            VStack(spacing: 4) {
                Icon(CategoryIcons.of(category.name, income: category.type == .income), size: 22)
                    .foregroundStyle(chosen ? Tokens.accent : Tokens.tx2)
                    .frame(width: 44, height: 44)
                    .background(chosen ? Tokens.accentSoft : Tokens.fill, in: .circle)
                    .overlay { if chosen { Circle().strokeBorder(Tokens.accent, lineWidth: 1.5) } }
                    .overlay(alignment: .topTrailing) {
                        if dot { Circle().fill(Tokens.tx3).frame(width: 5, height: 5).offset(x: -2, y: 4) }
                    }
                Text(verbatim: category.name).textStyle(.caption).foregroundStyle(chosen ? Tokens.tx : Tokens.tx2).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}
