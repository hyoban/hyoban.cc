import HereKit
import SwiftUI

/// Here on the Mac, in SwiftUI: a try of what MapKit can do with the API's real records. The map's
/// window (the page canvas's Mac boards, M) and the settings window (⌘,), where the API is set.
@main
struct HereApp: App {
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup(id: "main") {
            MainWindow()
                .environment(app)
        }
        .defaultSize(width: 1360, height: 880)
        .commands { HereCommands(app: app) }

        Settings {
            SettingsView()
                .environment(app)
        }
    }
}

/// The menu bar: syncing, finding a place or trip, and the map's panel.
struct HereCommands: Commands {
    let app: AppModel

    var body: some Commands {
        ToolbarCommands()

        CommandGroup(replacing: .newItem) {
            Button(L.t("menu.sync")) { Task { await app.sync() } }
                .keyboardShortcut("r")
        }

        CommandGroup(after: .pasteboard) {
            Divider()
            Button(L.t("menu.find")) { app.findRequest += 1 }
                .keyboardShortcut("f")
        }

        CommandGroup(after: .sidebar) {
            Button(L.t("menu.panel")) { app.panelRequest += 1 }
                .keyboardShortcut("s", modifiers: [.command, .control])
            Divider()
        }
    }
}
