import ImageIO
import SwiftUI
import UIKit

// Photos are fetched through the shared URL cache and drawn at the size they are shown, decoded
// once by ImageIO at that size and kept in memory for a while: a 4000 px original on the phone
// costs a thumbnail's memory in a list.

actor ImagePipeline {
    static let shared = ImagePipeline()

    private let cache = NSCache<NSString, UIImage>()
    private var running: [String: Task<UIImage?, Never>] = [:]

    init() {
        cache.totalCostLimit = 96 << 20
        URLCache.shared = URLCache(memoryCapacity: 32 << 20, diskCapacity: 512 << 20)
    }

    func image(_ url: URL, pixels: CGFloat) async -> UIImage? {
        let key = "\(url.absoluteString)#\(Int(pixels))"
        if let image = cache.object(forKey: key as NSString) { return image }
        if let task = running[key] { return await task.value }
        let task = Task<UIImage?, Never> {
            let data: Data?
            if url.isFileURL {
                data = try? Data(contentsOf: url, options: .mappedIfSafe)
            } else {
                data = try? await URLSession.shared.data(from: url).0
            }
            guard let data else { return nil }
            return Self.downsample(data, pixels: pixels)
        }
        running[key] = task
        let image = await task.value
        running[key] = nil
        if let image {
            cache.setObject(image, forKey: key as NSString, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        }
        return image
    }

    private static func downsample(_ data: Data, pixels: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(64, pixels),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// A Moment's photo, a live photo's still or a video's frame, filling its frame.
struct MediaImage: View {
    var media: MediaItem
    /// How wide it is shown, in points.
    var width: CGFloat
    var contentMode: ContentMode = .fill
    /// Whether a video shows the play circle in its middle; MomentPhotos marks it with a pill instead.
    var showsPlay = true

    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            Tokens.fill
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                    .transition(.opacity.animation(Springs.effectsDefault))
            } else if failed {
                Icon("image", size: 22).foregroundStyle(Tokens.tx3)
            }
            if media.kind == .video && showsPlay {
                Icon("play", size: 18)
                    .foregroundStyle(.white)
                    .padding(8)
                    .glassEffect(.regular, in: .circle)
            }
        }
        .clipped()
        .accessibilityElement()
        .accessibilityLabel(media.alt.isEmpty ? Text("media.photo") : Text(verbatim: media.alt))
        .task(id: "\(media.uid)-\(Int(width))") {
            let pixels = width * scale
            guard let url = MediaURLs.still(media, width: Int(pixels)) else {
                failed = true
                return
            }
            image = await ImagePipeline.shared.image(url, pixels: pixels)
            failed = image == nil
        }
    }
}
