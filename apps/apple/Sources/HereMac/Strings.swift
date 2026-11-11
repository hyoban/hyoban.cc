import Foundation
import HereKit
import SwiftUI

/// The app's copy, from its `Localizable.strings`: Chinese first, English in `en.lproj`.
enum L {
    static func t(_ key: String) -> String {
        NSLocalizedString(key, bundle: .module, comment: "")
    }

    /// A string with `%@`/`%d` holes filled in.
    static func f(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: t(key), arguments: arguments)
    }

    /// A place's kind as the pages name it, `cafe` as Café.
    static func kind(_ kind: String) -> String {
        let key = "kind.\(kind)"
        let name = t(key)
        return name == key ? kind : name
    }
}

extension Text {
    /// The app's copy as a Text.
    static func l(_ key: String) -> Text { Text(L.t(key)) }
}

/// What the site and the app's Info.plist say of where things are.
enum Site {
    /// The site's address, `https://example.com`, without a trailing slash; nil when not set.
    static var url: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "HereSiteURL") as? String, !value.isEmpty else { return nil }
        return value.hasSuffix("/") ? String(value.dropLast()) : value
    }

    /// A trip's page on the site, `/trip/<slug or uid>/`, as apps/web names it.
    static func trip(_ trip: Trip) -> URL? {
        guard let url else { return nil }
        return URL(string: "\(url)/trip/\(TripRoutes.key(trip))/")
    }
}
