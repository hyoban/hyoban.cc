import SwiftUI
import UniformTypeIdentifiers

// 记账's settings: the books (each with its name, currency, public switch and categories,
// archiving and deleting), and the data: the whole ledger backed up to a JSON file and restored
// from one, and the bills exported as CSV.

struct LedgerSettingsView: View {
    @Environment(LedgerStore.self) private var ledger
    @State private var editing: Book?
    @State private var adding = false
    @State private var restoring = false
    @State private var message: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                SettingsGroup(title: "ledgerSettings.books") {
                    ForEach(ledger.ledger.books) { book in
                        SettingsRow(icon: "book", title: book.name, detail: [book.currency, book.visibility == "public" ? String(localized: "book.public") : nil, book.archived ? String(localized: "book.archived") : nil].compactMap { $0 }.joined(separator: Formats.gap), trailing: .chevron) { editing = book }
                    }
                    SettingsRow(icon: "plus", title: String(localized: "ledgerSettings.addBook")) { adding = true }
                }
                SettingsGroup(title: "ledgerSettings.data", footer: "ledgerSettings.dataFooter") {
                    if !ledger.ledger.books.isEmpty {
                        ShareLink(item: LedgerFile(ledger: ledger.ledger, csv: false), preview: SharePreview(Text("ledgerSettings.backup"))) {
                            SettingsRow(icon: "download", title: String(localized: "ledgerSettings.backup"))
                        }
                        .buttonStyle(.plain)
                    }
                    SettingsRow(icon: "file-open", title: String(localized: "ledgerSettings.restore"), detail: message) { restoring = true }
                    if !ledger.ledger.txns.isEmpty {
                        ShareLink(item: LedgerFile(ledger: ledger.ledger, csv: true), preview: SharePreview(Text("ledgerSettings.csv"))) {
                            SettingsRow(icon: "table", title: String(localized: "ledgerSettings.csv"))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.vertical, Space.l)
        }
        .page(Text("ledgerSettings.title"))
        .sheet(item: $editing) { BookEditor(book: $0) }
        .alert("ledgerSettings.addBook", isPresented: $adding) {
            AddBookAlert()
        }
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            message = (try? LedgerBackup.restore(url, into: ledger)).map { String(localized: "ledgerSettings.restored \($0)") }
                ?? String(localized: "ledgerSettings.restoreFailed")
        }
    }
}

private struct AddBookAlert: View {
    @Environment(LedgerStore.self) private var ledger
    @State private var name = ""

    var body: some View {
        TextField("book.name", text: $name)
        Button("action.add") { if !name.isEmpty { ledger.addBook(name: name, currency: "CNY") } }
        Button("action.cancel", role: .cancel) {}
    }
}

/// A book: its name, currency, whether its new bills are public, its categories, archiving and deleting.
private struct BookEditor: View {
    @State var book: Book
    @Environment(LedgerStore.self) private var ledger
    @Environment(\.dismiss) private var dismiss
    @State private var newCategory = ""
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("book.name", text: $book.name)
                    TextField("account.currency", text: $book.currency).textInputAutocapitalization(.characters)
                    Toggle("book.public", isOn: Binding(get: { book.visibility == "public" }, set: { book.visibility = $0 ? "public" : "private" }))
                    Toggle("book.archived", isOn: $book.archived)
                }
                Section("book.categories") {
                    FlowLayout(spacing: Space.s) {
                        ForEach(ledger.ledger.categories.filter { $0.book == book.id && $0.parent == nil }) { category in
                            Menu {
                                Button("action.delete", role: .destructive) { ledger.deleteCategory(category) }
                            } label: {
                                Text(verbatim: category.name)
                                    .textStyle(.label)
                                    .padding(.horizontal, Space.m)
                                    .frame(height: 36)
                                    .background(Tokens.fill, in: .capsule)
                            }
                            .foregroundStyle(Tokens.tx)
                        }
                    }
                    HStack {
                        TextField("book.newCategory", text: $newCategory)
                        Button("action.add") {
                            ledger.save(Category(id: Ulid.make(), book: book.id, type: .expense, name: newCategory, position: ledger.ledger.categories.count))
                            newCategory = ""
                        }
                        .disabled(newCategory.isEmpty)
                    }
                }
                Section {
                    Button("book.delete", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(Text(verbatim: book.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Label("action.cancel", icon: "close") } }
                ToolbarItem(placement: .confirmationAction) { Button { ledger.save(book); dismiss() } label: { Label("action.save", icon: "check") } }
            }
            .confirmationDialog("book.deleteConfirm", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("book.delete", role: .destructive) { ledger.deleteBook(book); dismiss() }
            }
        }
    }
}

/// The ledger as a file to share, written only when it is shared.
struct LedgerFile: Transferable {
    var ledger: Ledger
    var csv: Bool

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { file in
            guard let url = file.csv ? LedgerBackup.csv(file.ledger) : LedgerBackup.file(file.ledger) else { throw CocoaError(.fileWriteUnknown) }
            return SentTransferredFile(url)
        }
    }
}

/// The ledger as a file: everything in it as JSON to restore, or its bills as CSV.
nonisolated enum LedgerBackup {
    struct Snapshot: Codable {
        var books: [Book]
        var accounts: [Account]
        var categories: [Category]
        var txns: [Txn]
    }

    static func file(_ ledger: Ledger) -> URL? {
        guard !ledger.books.isEmpty else { return nil }
        let snapshot = Snapshot(books: ledger.books, accounts: ledger.accounts, categories: ledger.categories, txns: ledger.txns)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let url = FileManager.default.temporaryDirectory.appending(path: "here-ledger-\(Times.day(Date())).json")
        guard (try? encoder.encode(snapshot).write(to: url)) != nil else { return nil }
        return url
    }

    /// Restores a backup over the ledger, adding what it has; gives how many bills it held.
    @MainActor static func restore(_ url: URL, into store: LedgerStore) throws -> Int {
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: url))
        for book in snapshot.books { store.save(book) }
        for account in snapshot.accounts { store.save(account) }
        for category in snapshot.categories { store.save(category) }
        for txn in snapshot.txns { store.save(txn) }
        return snapshot.txns.count
    }

    /// The bills in iCost's columns.
    static func csv(_ ledger: Ledger) -> URL? {
        guard !ledger.txns.isEmpty else { return nil }
        let quote = { (text: String) in "\"\(text.replacingOccurrences(of: "\"", with: "\"\""))\"" }
        var lines = ["日期,类型,金额,一级分类,二级分类,账户1,账户2,备注,货币,标签"]
        for txn in ledger.txns {
            let category = ledger.category(txn.category)
            let top = ledger.topCategory(txn.category)
            let kind = switch txn.kind {
            case .expense: "支出"
            case .income: "收入"
            case .transfer: "转账"
            case .refund: "退款"
            case .reimbursement: "报销"
            }
            lines.append([
                "\(txn.day) \(Formats.time(txn.at) ?? "")", kind, String(format: "%.2f", txn.decimal),
                top?.name ?? "", category?.parent == nil ? "" : category?.name ?? "",
                ledger.account(txn.account)?.name ?? "", ledger.account(txn.toAccount)?.name ?? "",
                txn.note, txn.currency, "",
            ].map(quote).joined(separator: ","))
        }
        let url = FileManager.default.temporaryDirectory.appending(path: "here-bills-\(Times.day(Date())).csv")
        guard (try? lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)) != nil else { return nil }
        return url
    }
}
