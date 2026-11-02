import Foundation

// The ledger: books of bills over accounts in their own currencies, kept on this phone alone.
// Amounts are whole cents (a hundredth of the currency's unit), always positive; what a bill does
// to its accounts and to the books' sums follows from its kind. The same model as Android's.

/// A ledger book, its bills and categories its own, summed in its currency.
nonisolated struct Book: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var name: String
    var currency = "CNY"
    var position = 0
    var archived = false
    /// Whether its new bills are public: `public`, else private.
    var visibility = "private"
}

/// What sort of account: how the assets page groups it and whether its balance is owed.
nonisolated enum AccountKind: String, Codable, CaseIterable, Sendable {
    case cash, debit, credit, wallet, stored, investment, loan, person, other

    /// Whether its balance is normally below zero, money owed rather than held.
    var liability: Bool { self == .credit || self == .loan }

    var icon: String {
        switch self {
        case .cash: "cash"
        case .debit: "card"
        case .credit: "card"
        case .wallet: "wallet"
        case .stored: "gift-card"
        case .investment: "trend-up"
        case .loan: "bank"
        case .person: "people"
        case .other: "savings"
        }
    }
}

/// Where money is: its balance, in its currency, is its opening plus what its bills did to it.
nonisolated struct Account: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var name: String
    var kind: AccountKind = .other
    var currency = "CNY"
    var opening = 0
    /// Counted in the net assets; a hidden one still keeps its bills.
    var counted = true
    var archived = false
    var note = ""
    var position = 0
}

nonisolated enum CategoryType: String, Codable, Sendable { case expense, income }

/// A bill's category in one book, under a parent when it is a second-level one.
nonisolated struct Category: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var book: String
    var type: CategoryType
    var name: String
    var parent: String?
    var position = 0
}

nonisolated enum TxnKind: String, Codable, CaseIterable, Sendable {
    case expense, income, transfer
    /// Money back for an expense, its related bill: less spent.
    case refund
    /// Money back for an expense to be reimbursed.
    case reimbursement
}

/// A bill.
nonisolated struct Txn: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var book: String
    var kind: TxnKind
    /// When, in epoch milliseconds, and the offset it was written at, for its local day and time.
    var time: Int64
    var offsetMinutes: Int
    var amount: Int
    var currency: String
    var category: String?
    /// The account paid from or into; for a transfer, the one it goes out of.
    var account: String?
    /// A transfer's account it goes into.
    var toAccount: String?
    var note = ""
    /// The expense a refund or a reimbursement is for.
    var related: String?
    /// Kept out of the books' sums, still moving its accounts.
    var excluded = false
    /// The Moment it belongs to, its place and its trip, as the site names them.
    var moment: String?
    var place: String?
    var trip: String?
    var createdAt: Int64
    var updatedAt: Int64
    /// Public or private by its own say, over its book's.
    var visibility: String?
    /// What of it was last published, its public part as JSON; nil while the site has none of it.
    var published: String?
    /// What a transfer is, named as iCost names it: transfer, repay, borrow, lend or collect.
    var transfer: String?
    /// What it came to in its account's currency, where that isn't the bill's.
    var accountAmount: Int?
    /// Paid from several accounts, in place of its account.
    var splits: [Split]?
    /// What arrived in a transfer's account, in its currency.
    var toAmount: Int?
    /// A transfer's fee, an expense of its fee account; its discount, an income of its discount account.
    var fee: Int?
    var feeAccount: String?
    var discount: Int?
    var discountAccount: String?
    /// Whether an expense is to be paid back by someone else, and whether it has been.
    var reimburse: Reimburse?

    var date: Date { Date(timeIntervalSince1970: Double(time) / 1000) }
    var zone: TimeZone { TimeZone(secondsFromGMT: offsetMinutes * 60) ?? .current }

    /// The day it happened, `YYYY-MM-DD`, in the offset it was written at.
    var day: String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// Its time as a Moment's `at` is written.
    var at: String { Times.local(date, zone: zone) }

    /// Whether money comes in by it, as its amount is coloured.
    var isInflow: Bool { kind == .income || kind == .refund || kind == .reimbursement }

    var decimal: Double { Double(amount) / 100 }

    /// The accounts it pays from or into, each with its share in the bill's currency.
    var payers: [Split] {
        if let splits, !splits.isEmpty { return splits }
        return account.map { [Split(account: $0, amount: amount)] } ?? []
    }
}

/// A share of a bill paid from one account, in the bill's currency.
nonisolated struct Split: Codable, Hashable, Sendable {
    var account: String
    var amount: Int
}

/// Whether an expense is to be paid back by someone else, and whether it has been.
nonisolated enum Reimburse: String, Codable, Sendable { case none, pending, done }

/// What one bill adds to the books' sums, in its own currency: spent or earned, under a category.
nonisolated struct LedgerEntry: Sendable {
    var txn: Txn
    var expense: Bool
    var category: String?
    var amount: Int
}

/// The European Central Bank's rates, each currency's per CNY by day, oldest first.
nonisolated struct Rates: Sendable {
    var byCurrency: [String: [(day: String, perCny: Double)]] = [:]

    /// [cents] of one currency in another on [day], at the latest rate on or before it.
    func convert(_ cents: Int, from: String, to: String, day: String) -> Int? {
        if from == to { return cents }
        guard let a = rate(from, day), let b = rate(to, day) else { return nil }
        return Int((Double(cents) / a * b).rounded())
    }

    private func rate(_ currency: String, _ day: String) -> Double? {
        if currency == "CNY" { return 1 }
        guard let days = byCurrency[currency], !days.isEmpty else { return nil }
        var low = 0, high = days.count - 1, found: Int?
        while low <= high {
            let middle = (low + high) / 2
            if days[middle].day <= day { found = middle; low = middle + 1 } else { high = middle - 1 }
        }
        return days[found ?? 0].perCny
    }
}

/// Everything in the ledger, as the store last read it.
nonisolated struct Ledger: Sendable {
    var books: [Book] = []
    var accounts: [Account] = []
    var categories: [Category] = []
    /// Newest first.
    var txns: [Txn] = []
    var rates = Rates()
    /// Worked out once from the rest: each account's balance, and what each bill adds to the sums.
    private(set) var balances: [String: Int] = [:]
    private(set) var entries: [LedgerEntry] = []
    private var byId: [String: Txn] = [:]

    init(books: [Book] = [], accounts: [Account] = [], categories: [Category] = [], txns: [Txn] = [], rates: Rates = Rates()) {
        self.books = books
        self.accounts = accounts
        self.categories = categories
        self.txns = txns
        self.rates = rates
        byId = Dictionary(txns.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        balances = Accounting.balances(self)
        entries = Accounting.entries(self)
    }

    func txn(_ id: String?) -> Txn? { id.flatMap { byId[$0] } }
    func book(_ id: String?) -> Book? { books.first { $0.id == id } }
    func account(_ id: String?) -> Account? { accounts.first { $0.id == id } }
    func category(_ id: String?) -> Category? { categories.first { $0.id == id } }

    /// A category's name with its parent's: `餐饮 › 三餐`.
    func categoryName(_ id: String?) -> String? {
        guard let category = category(id) else { return nil }
        guard let parent = self.category(category.parent) else { return category.name }
        return "\(parent.name) › \(category.name)"
    }

    /// The first-level category a bill counts under.
    func topCategory(_ id: String?) -> Category? {
        guard let category = category(id) else { return nil }
        return self.category(category.parent) ?? category
    }

    /// An account's balance, in its currency: its opening plus what its bills did to it.
    func balance(_ account: Account) -> Int { balances[account.id] ?? account.opening }

    /// The net assets in CNY at the latest rates: every counted account's balance.
    var netAssets: Int {
        let today = Times.day(Date())
        return accounts.filter { $0.counted && !$0.archived }.reduce(0) { total, account in
            let balance = balance(account)
            return total + (rates.convert(balance, from: account.currency, to: "CNY", day: today) ?? balance)
        }
    }

    /// What [entries] spent and earned in CNY, each converted on its own day.
    func sums(_ entries: some Sequence<LedgerEntry>) -> (spent: Int, earned: Int) {
        var spent = 0, earned = 0
        for entry in entries {
            let value = rates.convert(entry.amount, from: entry.txn.currency, to: "CNY", day: entry.txn.day) ?? entry.amount
            if entry.expense { spent += value } else { earned += value }
        }
        return (spent, earned)
    }

    /// The entries of these bills, and of the refunds of them wherever they were written.
    func entries(of txns: some Sequence<Txn>) -> [LedgerEntry] {
        let ids = Set(txns.map(\.id))
        return entries.filter { ids.contains($0.txn.id) || ($0.txn.related.map(ids.contains) ?? false) }
    }

    /// Whether a bill is public: its own say, else its book's.
    func isPublic(_ txn: Txn) -> Bool {
        (txn.visibility ?? book(txn.book)?.visibility) == Visibility.public.rawValue
    }
}

enum LedgerDefaults {
    private static let expenseZh: [(String, [String])] = [
        ("餐饮", ["三餐", "零食", "饮料", "水果"]),
        ("交通", ["公交", "地铁", "打车", "火车", "飞机"]),
        ("购物", ["日用", "服饰", "数码"]),
        ("住房", ["房租", "水费", "电费"]),
        ("通讯", ["话费", "网络"]),
        ("娱乐", []), ("医疗", []),
        ("人情", ["礼物", "红包"]),
        ("旅行", ["住宿", "门票"]),
        ("其他", []),
    ]
    private static let incomeZh = ["工资", "奖金", "红包", "利息", "二手", "其他"]
    private static let expenseEn: [(String, [String])] = [
        ("Food", ["Meals", "Snacks", "Drinks", "Fruit"]),
        ("Transport", ["Bus", "Metro", "Taxi", "Train", "Flight"]),
        ("Shopping", ["Daily", "Clothes", "Gadgets"]),
        ("Housing", ["Rent", "Water", "Electricity"]),
        ("Phone", ["Mobile", "Internet"]),
        ("Fun", []), ("Health", []),
        ("Gifts", ["Presents", "Red packets"]),
        ("Travel", ["Stays", "Tickets"]),
        ("Other", []),
    ]
    private static let incomeEn = ["Salary", "Bonus", "Gifts", "Interest", "Sold", "Other"]

    static var chinese: Bool { Locale.preferredLanguages.first?.hasPrefix("zh") ?? true }

    /// The categories a new book starts with.
    static func categories(book: String) -> [Category] {
        var out: [Category] = []
        for (name, children) in chinese ? expenseZh : expenseEn {
            let parent = Category(id: Ulid.make(), book: book, type: .expense, name: name, position: out.count)
            out.append(parent)
            for child in children {
                out.append(Category(id: Ulid.make(), book: book, type: .expense, name: child, parent: parent.id, position: out.count))
            }
        }
        for name in chinese ? incomeZh : incomeEn {
            out.append(Category(id: Ulid.make(), book: book, type: .income, name: name, position: out.count))
        }
        return out
    }
}

/// Sums typed on the keypad: `12+3.4-1` in cents, or nil while it doesn't add up.
enum Keypad {
    static func cents(_ expression: String) -> Int? {
        var total = 0
        var sign = 1
        var number = ""
        func flush() -> Bool {
            if number.isEmpty { return true }
            guard let value = Decimal(string: number) else { return false }
            total += sign * NSDecimalNumber(decimal: value * 100).intValue
            number = ""
            return true
        }
        for character in expression {
            switch character {
            case "+", "-":
                guard flush() else { return nil }
                sign = character == "+" ? 1 : -1
            case "0"..."9", ".": number.append(character)
            default: return nil
            }
        }
        guard flush() else { return nil }
        return total
    }
}
