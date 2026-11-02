import Foundation

// Where a published photo or video is served, as the site picks it (src/asset-url.ts): a photo's
// smallest variant at least as wide as it is shown, or for an original the API kept, that width
// from Cloudflare Images; a video's poster, or its first frame from Media Transformations. A photo
// not yet published is read from where it is on the phone.

enum MediaURLs {
    /// The widths an original is resized to, as the site asks for them.
    static let resizedWidths = [480, 960, 1920]

    static var origin: String { Settings.mediaOrigin.trimmingSuffix("/") }

    /// The still to show for a media item at [width] pixels: a photo, a live photo's still, a video's poster.
    static func still(_ media: MediaItem, width: Int) -> URL? {
        guard let published = media.published else { return media.localFile.map(LocalStore.mediaURL) }
        switch media.kind {
        case .image, .livePhoto:
            return image(published, width: width)
        case .video:
            if let poster = published["poster"] { return image(poster, width: width) }
            guard published["original"]?.bool == true, let key = published["key"]?.string else { return nil }
            return transformed("media/mode=frame,time=0s,width=\(size(media.width ?? 1920, width)),format=jpg", key)
        }
    }

    /// The motion to play: a video, or a live photo's video.
    static func motion(_ media: MediaItem) -> URL? {
        guard let published = media.published else { return media.localVideoFile.map(LocalStore.mediaURL) }
        switch media.kind {
        case .image: return nil
        case .livePhoto: return published["video"]?["key"]?.string.flatMap(asset)
        case .video: return published["key"]?.string.flatMap(asset)
        }
    }

    /// A published image at the smallest of its sizes at least [width] wide, else its largest.
    static func image(_ image: JSON, width: Int) -> URL? {
        guard let key = image["key"]?.string else { return nil }
        if image["original"]?.bool == true {
            return transformed("image/width=\(size(image["width"]?.int ?? 1920, width)),format=auto,onerror=redirect", key)
        }
        var sources = image["variants"]?.array ?? []
        if (image["width"]?.int ?? .max) <= 1920 { sources.append(image) }
        sources.sort { ($0["width"]?.int ?? 0) < ($1["width"]?.int ?? 0) }
        let source = sources.first { ($0["width"]?.int ?? 0) >= width } ?? sources.last ?? image
        return source["key"]?.string.flatMap(asset)
    }

    static func asset(_ key: String) -> URL? {
        guard !origin.isEmpty else { return nil }
        return URL(string: "\(origin)/\(encode(key))")
    }

    private static func transformed(_ options: String, _ key: String) -> URL? {
        guard !origin.isEmpty else { return nil }
        return URL(string: "\(origin)/cdn-cgi/\(options)/\(encode(key))")
    }

    /// The smallest of the site's widths at least [wanted], never wider than the original.
    static func size(_ own: Int, _ wanted: Int) -> Int {
        var widths = resizedWidths.filter { $0 < own }
        let top = min(own, 1920)
        if !widths.contains(top) { widths.append(top) }
        return widths.first { $0 >= wanted } ?? widths.last ?? top
    }

    /// An object's key in a URL, each of its segments encoded as JavaScript's encodeURIComponent.
    private static func encode(_ key: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")
        return key.split(separator: "/", omittingEmptySubsequences: false)
            .map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? String($0) }
            .joined(separator: "/")
    }
}
