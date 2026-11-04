import Foundation
import Observation

// The ledger kept in `ledger.sqlite` under the app's own store, out of the system's backup and
// never synced: each book, account, category and bill a row of its JSON. Bills are private; a
// book can make its new bills public and each bill can say otherwise. A public bill publishes only
// its kind, amount, currency, category and note, never its account, book or balance.

@Observable
final class LedgerStore {
    private(set) var ledger = Ledger() {
        didSet { byDay = Dictionary(grouping: ledger.txns, by: \.day) }
    }
    /// The bills by day, every book's, newest first in each.
    private(set) var byDay: [String: [Txn]] = [:]
    private(set) var loaded = false
    /// The book shown, or nil for every book at once.
    var currentBook: String? {
        didSet { UserDefaults.standard.set(currentBook, forKey: "ledger-book") }
    }
    /// Every amount hidden, on 我's card and the ledger's pages.
    var hideAmounts: Bool = UserDefaults.standard.bool(forKey: "ledger-hide") {
        didSet { UserDefaults.standard.set(hideAmounts, forKey: "ledger-hide") }
    }

    private var db: SQLite?

    init() {}

    func load() {
        guard !loaded else { return }
        do {
            let db = try SQLite(path: Storage.file("ledger.sqlite").path)
            try db.execute("CREATE TABLE IF NOT EXISTS records (kind TEXT NOT NULL, id TEXT NOT NULL, record TEXT NOT NULL, PRIMARY KEY (kind, id))")
            try db.execute("CREATE TABLE IF NOT EXISTS rates (currency TEXT NOT NULL, day TEXT NOT NULL, per_cny REAL NOT NULL, PRIMARY KEY (currency, day))")
            self.db = db
            readRates()
            read()
        } catch {
            print("Ledger failed to open: \(error)")
        }
        currentBook = UserDefaults.standard.string(forKey: "ledger-book")
        loaded = true
    }

    private func read() {
        guard let db else { return }
        func all<T: Decodable>(_ kind: String, _: T.Type) -> [T] {
            let decoder = JSONDecoder()
            return ((try? db.query("SELECT record FROM records WHERE kind = ?", [.text(kind)]) { $0.text(0) ?? "" }) ?? [])
                .compactMap { try? decoder.decode(T.self, from: Data($0.utf8)) }
        }
        ledger = Ledger(
            books: all("book", Book.self).sorted { $0.position < $1.position },
            accounts: all("account", Account.self).sorted { $0.position < $1.position },
            categories: all("category", Category.self).sorted { $0.position < $1.position },
            txns: all("txn", Txn.self).sorted { ($0.time, $0.id) > ($1.time, $1.id) },
            rates: rates
        )
    }

    private var rates = Rates()

    /// The exchange rates, each currency's per CNY by day.
    private func readRates() {
        let rows = (try? db?.query("SELECT currency, day, per_cny FROM rates ORDER BY currency, day") { ($0.text(0) ?? "", $0.text(1) ?? "", $0.double(2) ?? 1) }) ?? []
        var byCurrency: [String: [(day: String, perCny: Double)]] = [:]
        for (currency, day, perCny) in rows { byCurrency[currency, default: []].append((day, perCny)) }
        rates = Rates(byCurrency: byCurrency)
    }

    private func put<T: Encodable & Identifiable<String>>(_ kind: String, _ values: [T]) {
        guard let db else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        try? db.transaction {
            try db.run(
                "INSERT INTO records (kind, id, record) VALUES (?, ?, ?) ON CONFLICT (kind, id) DO UPDATE SET record = excluded.record",
                values.map { [.text(kind), .text($0.id), .text(String(decoding: try! encoder.encode($0), as: UTF8.self))] }
            )
        }
        read()
    }

    private func remove(_ kind: String, _ id: String) {
        try? db?.run("DELETE FROM records WHERE kind = ? AND id = ?", [.text(kind), .text(id)])
        read()
    }

    // MARK: Books

    /// The book a new bill goes in: the one shown, else the first, made with its categories when there is none.
    func ensureBook() -> Book {
        if let book = ledger.book(currentBook) ?? ledger.books.first(where: { !$0.archived }) { return book }
        return addBook(name: String(localized: "ledger.defaultBook"), currency: "CNY")
    }

    @discardableResult
    func addBook(name: String, currency: String) -> Book {
        let book = Book(id: Ulid.make(), name: name, currency: currency, position: ledger.books.count)
        put("book", [book])
        put("category", LedgerDefaults.categories(book: book.id))
        return book
    }

    func save(_ book: Book) { put("book", [book]) }

    func deleteBook(_ book: Book) {
        for txn in ledger.txns where txn.book == book.id { remove("txn", txn.id) }
        for category in ledger.categories where category.book == book.id { remove("category", category.id) }
        remove("book", book.id)
        if currentBook == book.id { currentBook = nil }
    }

    // MARK: Accounts and categories

    func save(_ account: Account) { put("account", [account]) }

    func deleteAccount(_ account: Account) { remove("account", account.id) }

    /// Sets an account's balance by moving its opening balance.
    func calibrate(_ account: Account, to balance: Int) {
        var account = account
        account.opening += balance - ledger.balance(account)
        save(account)
    }

    func save(_ category: Category) { put("category", [category]) }

    func deleteCategory(_ category: Category) {
        for child in ledger.categories where child.parent == category.id { remove("category", child.id) }
        remove("category", category.id)
    }

    // MARK: Bills

    func save(_ txn: Txn) {
        var txn = txn
        txn.updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        put("txn", [txn])
    }

    func delete(_ txn: Txn) { remove("txn", txn.id) }

    /// What of a bill the site may have: an expense's or an income's kind, amount in the currency's
    /// unit, category and note, when it is public; never its account, book or balance.
    func publicPart(_ txn: Txn) -> JSON? {
        guard ledger.isPublic(txn), txn.kind == .expense || txn.kind == .income else { return nil }
        var part: JSON = [
            "v": 1,
            "visibility": "public",
            "kind": JSON(txn.kind.rawValue),
            "amount": JSON(txn.decimal),
            "currency": JSON(txn.currency),
        ]
        if let category = ledger.categoryName(txn.category) { part["category"] = JSON(category) }
        if !txn.note.isEmpty { part["note"] = JSON(txn.note) }
        return part
    }

    /// Every bill whose public part isn't what was last published: the part to send, or nil to take it off.
    var pendingBills: [(txn: Txn, part: JSON?)] {
        ledger.txns.compactMap { txn in
            let part = publicPart(txn)
            return part?.text == txn.published ? nil : (txn, part)
        }
    }

    /// Notes what of each bill was published, so the next publish sends only what changed since.
    func markPublished(_ ids: [String: String?]) {
        let changed = ids.compactMap { id, part -> Txn? in
            guard var txn = ledger.txns.first(where: { $0.id == id }) else { return nil }
            txn.published = part
            return txn
        }
        put("txn", changed)
    }

    /// The bills of the book shown, or of every book.
    var shownTxns: [Txn] {
        guard let currentBook else { return ledger.txns }
        return ledger.txns.filter { $0.book == currentBook }
    }

    /// What was spent in a month, `YYYY-MM`, across what is shown, in CNY.
    func spent(month: String) -> Int {
        ledger.sums(shownEntries.filter { $0.txn.day.hasPrefix(month) }).spent
    }

    /// What the bills shown add to the sums.
    var shownEntries: [LedgerEntry] {
        guard let currentBook else { return ledger.entries }
        return ledger.entries.filter { $0.txn.book == currentBook }
    }

    /// The bills of a day, across every book, newest first.
    func txns(on day: String) -> [Txn] { byDay[day] ?? [] }
}
