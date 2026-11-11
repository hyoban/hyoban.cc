import HereKit
import SwiftUI

/// The settings window (⌘,): the API the records are read from, as System Settings draws a form.
/// A change takes effect at once; Refresh reads the API again.
struct SettingsView: View {
    @Environment(AppModel.self) private var app

    private var syncing: Bool { app.store.phase == .syncing }

    private var rowsRead: Int {
        _ = app.store.version
        return CopyTables.names.reduce(0) { $0 + app.store.copy.count($1) }
    }

    var body: some View {
        @Bindable var app = app
        Form {
            Section {
                TextField(L.t("sync.api.address"), text: $app.apiURL, prompt: Text(verbatim: "https://api.example.com"))
                SecureField(L.t("sync.api.token"), text: $app.token)
                LabeledContent(L.t("sync.read")) {
                    HStack(spacing: 8) {
                        if syncing { ProgressView().controlSize(.small) }
                        Group {
                            if let at = app.store.syncedAt {
                                HStack(spacing: 6) {
                                    Text(L.f("sync.read.count", rowsRead.formatted()))
                                    Text(at, format: .relative(presentation: .named))
                                }
                            } else if rowsRead > 0 {
                                Text(L.f("sync.read.count", rowsRead.formatted()))
                            } else {
                                Text(L.t("sync.read.none"))
                            }
                        }
                        .foregroundStyle(.secondary)
                        Button(L.t("sync.refresh")) { Task { await app.sync() } }
                            .disabled(app.api == nil || syncing)
                    }
                }
            } header: {
                Text(verbatim: "API")
            } footer: {
                if case let .failed(message) = app.store.phase {
                    Text(message).font(.footnote).foregroundStyle(.red)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 560, height: 220)
    }
}
