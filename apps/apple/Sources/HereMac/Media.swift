import AppKit
import HereKit
import SwiftUI

/// Images read from the network or this computer, kept in memory while the app runs.
@MainActor
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSURL, NSImage>()
    private var loading: [URL: Task<NSImage?, Never>] = [:]

    private init() {
        cache.countLimit = 600
    }

    func cached(_ url: URL) -> NSImage? { cache.object(forKey: url as NSURL) }

    func image(_ url: URL) async -> NSImage? {
        if let image = cached(url) { return image }
        if let task = loading[url] { return await task.value }
        let task = Task<NSImage?, Never> {
            let data: Data?
            if url.isFileURL {
                data = try? Data(contentsOf: url)
            } else {
                data = try? await URLSession.shared.data(from: url).0
            }
            return data.flatMap(NSImage.init(data:))
        }
        loading[url] = task
        let image = await task.value
        loading[url] = nil
        if let image { cache.setObject(image, forKey: url as NSURL) }
        return image
    }
}

/// A picture from [url], filling its frame, a quiet fill while it loads.
struct RemoteImage: View {
    let url: URL?
    let contentMode: ContentMode
    @State private var image: NSImage?

    init(url: URL?, contentMode: ContentMode = .fill) {
        self.url = url
        self.contentMode = contentMode
        _image = State(initialValue: url.flatMap { ImageCache.shared.cached($0) })
    }

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: contentMode)
            } else {
                Rectangle().fill(.quaternary)
            }
        }
        .task(id: url) {
            guard let url else { return }
            image = await ImageCache.shared.image(url)
        }
    }
}

/// A media item's still at about [width] points, filling its frame.
struct MediaImage: View {
    @Environment(AppModel.self) private var app
    let item: MediaItem
    let width: Int
    let contentMode: ContentMode

    init(_ item: MediaItem, width: Int, contentMode: ContentMode = .fill) {
        self.item = item
        self.width = width
        self.contentMode = contentMode
    }

    var body: some View {
        // Twice the points for a Retina screen.
        RemoteImage(url: app.media.still(item, width: width * 2), contentMode: contentMode)
    }
}
