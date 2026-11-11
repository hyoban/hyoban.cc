import Foundation
import HereKit
import Observation
import SwiftUI

/// The app's state: the records (`store`), the API it reads them from and the window's requests.
/// One for the app, handed to every view as an environment object.
@MainActor
@Observable
final class AppModel {
    let store: HereStore
    let secrets: Secrets
    /// The app's folder in Application Support.
    let folder: URL

    /// The API's address; the site's own when not set.
    var apiURL: String { didSet { defaults.set(apiURL, forKey: "api_url") } }
    var token: String { didSet { secrets.write("api-token", token) } }
    /// Where the media of published records are served from; the API says, or the site's config.
    var assetsOrigin: String { didSet { defaults.set(assetsOrigin, forKey: "assets_origin") } }

    /// Bumped by the Edit › Find menu item (⌘F) to put the focus in the map's search field.
    var findRequest = 0
    /// Bumped by the View › Show/Hide Panel menu item (⌃⌘S) to show or hide the map's panel.
    var panelRequest = 0
    /// What the app says went wrong last, shown until dismissed.
    var problem: String?

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = support.appendingPathComponent(Bundle.main.object(forInfoDictionaryKey: "HereDataFolder") as? String ?? "HereMac", isDirectory: true)
        self.folder = folder
        secrets = Secrets(folder: folder.appendingPathComponent("secrets", isDirectory: true))
        do {
            store = try HereStore(folder: folder)
        } catch {
            fatalError("Can't open the copy in \(folder.path): \(error)")
        }
        let site = Bundle.main.infoDictionary ?? [:]
        apiURL = defaults.string(forKey: "api_url") ?? site["HereAPIURL"] as? String ?? ""
        token = secrets.read("api-token")
        assetsOrigin = defaults.string(forKey: "assets_origin") ?? site["HereAssetsOrigin"] as? String ?? ""
    }

    /// A small preference of the map's own, kept by key.
    func preference(_ key: String) -> Any? { defaults.object(forKey: key) }
    func setPreference(_ key: String, _ value: Any?) { defaults.set(value, forKey: key) }

    /// The API with the settings' address and token, or nil until both are set.
    var api: HereAPI? {
        guard let url = URL(string: apiURL), !apiURL.isEmpty, !token.isEmpty else { return nil }
        return HereAPI(baseURL: url, token: token)
    }

    var media: MediaURLs { MediaURLs(origin: assetsOrigin) }

    /// Brings the copy up to date with the API, learning where its media are served from.
    func sync() async {
        guard let api else { return }
        if assetsOrigin.isEmpty, let site = try? await api.site(), let assets = site.assets { assetsOrigin = assets }
        await store.sync(with: api)
        if case let .failed(message) = store.phase { problem = message }
    }
}
