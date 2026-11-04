import SwiftUI

// 同步与来源 (the design's 25), settings groups: 网站 (the API's address, its token, 刷新) and 媒体 (where
// photos are served from), each row saying what it is set to and opening a sheet to change it. The
// app names no API of its own: until both address and token are set it asks nothing of one. Another
// API drops the copy and reads everything again.

struct SyncView: View {
    @Environment(AppStore.self) private var store
    @State private var editing: Field?
    @State private var answer: String?

    enum Field: String, Identifiable { case api, token, media; var id: Self { self } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                SettingsGroup(title: "sync.site", footer: "sync.footer") {
                    SettingsRow(title: String(localized: "sync.api"), detail: Settings.apiURL?.host() ?? String(localized: "sync.none"), trailing: .chevron) { editing = .api }
                    SettingsRow(title: String(localized: "sync.token"), detail: String(localized: Settings.token?.isEmpty == false ? "sync.tokenSet" : "sync.none"), trailing: .chevron) { editing = .token }
                    SettingsRow(title: String(localized: "action.refresh"), detail: answer ?? (store.revision > 0 ? String(localized: "sync.revision \(store.revision)") : nil), trailing: store.syncing ? .custom(AnyView(ProgressView())) : .none) {
                        guard store.hasAPI, !store.syncing else { return }
                        Task {
                            await store.refresh()
                            answer = store.error?.localizedDescription ?? String(localized: "sync.ok \(store.revision)")
                        }
                    }
                }
                SettingsGroup(title: "sync.mediaGroup") {
                    SettingsRow(title: String(localized: "sync.media"), detail: Settings.mediaOrigin.isEmpty ? String(localized: "sync.none") : URL(string: Settings.mediaOrigin)?.host() ?? Settings.mediaOrigin, trailing: .chevron) { editing = .media }
                }
                if let error = store.error {
                    Text(verbatim: error.localizedDescription).textStyle(.caption).foregroundStyle(Tokens.danger).padding(.horizontal, Space.gutter)
                }
            }
            .padding(.vertical, Space.l)
        }
        .page(Text("sync.title"))
        .sheet(item: $editing) { field in
            SettingSheet(field: field)
                .presentationDetents([.height(260)])
        }
    }
}

/// One setting changed in a sheet: its field, and 保存.
private struct SettingSheet: View {
    var field: SyncView.Field
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var value = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Space.l) {
            switch field {
            case .api: LabeledField(title: "sync.api", text: $value, prompt: "https://api.example.com")
            case .token: LabeledField(title: "sync.token", text: $value, secure: true, note: "sync.tokenNote")
            case .media: LabeledField(title: "sync.media", text: $value, prompt: "https://image.example.com")
            }
            HStack {
                Spacer()
                Button("action.cancel") { dismiss() }.buttonStyle(.text)
                Button("action.save") { save(); dismiss() }.buttonStyle(.ink)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .padding(Space.xl)
        .onAppear {
            value = switch field {
            case .api: Settings.apiURL?.absoluteString ?? ""
            case .token: Settings.token ?? ""
            case .media: Settings.mediaOrigin
            }
        }
    }

    private func save() {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        switch field {
        case .api:
            // Another API than the one the copy is of; a first one keeps a copy restored from a backup.
            let previous = Settings.apiURL?.absoluteString
            Settings.apiURL = trimmed.isEmpty ? nil : URL(string: trimmed)
            if previous != nil, trimmed != previous { store.clearCopy() }
        case .token: Settings.token = trimmed
        case .media: Settings.mediaOrigin = trimmed
        }
        Task { await store.refresh() }
    }
}

/// 记录项与公开: which of the phone's own records are public. A book makes its new bills public; the
/// footprints are public only a trip at a time, from the trip's page.
struct ItemsView: View {
    @Environment(LedgerStore.self) private var ledger

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                SettingsGroup(title: "items.bills", footer: "items.billsFooter") {
                    ForEach(ledger.ledger.books.filter { !$0.archived }) { book in
                        SwitchRow(icon: "book", title: book.name, isOn: Binding(
                            get: { book.visibility == Visibility.public.rawValue },
                            set: { on in
                                var book = book
                                book.visibility = on ? Visibility.public.rawValue : Visibility.private.rawValue
                                ledger.save(book)
                            }
                        ))
                    }
                    if ledger.ledger.books.isEmpty {
                        SettingsRow(title: String(localized: "items.noBooks"))
                    }
                }
                SettingsGroup(title: "me.footprints", footer: "items.footprints") {
                    SettingsRow(icon: "footprints", title: String(localized: "visibility.private"))
                }
            }
            .padding(.vertical, Space.l)
        }
        .page(Text("items.title"))
    }
}
