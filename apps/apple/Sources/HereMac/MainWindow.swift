import SwiftUI

/// The main window (M09): the map, the whole of the app; until the API is set, a note over it
/// that opens the settings.
struct MainWindow: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        MapPage()
            .overlay {
                if app.api == nil {
                    ContentUnavailableView {
                        Label(L.t("connect.title"), systemImage: "network")
                    } description: {
                        Text(L.t("connect.detail"))
                    } actions: {
                        SettingsLink { Text(L.t("connect.open")) }
                            .buttonStyle(.borderedProminent)
                    }
                    .frame(width: 360, height: 240)
                    .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                }
            }
            .frame(minWidth: 900, minHeight: 600)
            .alert(Text.l("problem.title"), isPresented: Binding { app.problem != nil } set: { if !$0 { app.problem = nil } }) {
                Button(L.t("problem.ok")) { app.problem = nil }
            } message: {
                Text(app.problem ?? "")
            }
            .task { await app.sync() }
    }
}
