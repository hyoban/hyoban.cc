import Foundation

/// Where a published photo or video is served, as the site picks it: a photo's smallest variant
/// at least as wide as it is shown, or for an original the API kept, that width from Cloudflare
/// Images; a video's poster, or its first frame from Media Transformations. A photo not yet
/// published is read from where it is on this computer.
public struct MediaURLs: Sendable {
    public let origin: String

    public init(origin: String) {
        self.origin = origin.hasSuffix("/") ? String(origin.dropLast()) : origin
    }

    /// The widths an original is resized to, as the site asks for them.
    public static let resizedWidths = [480, 960, 1920]

    public func asset(_ key: String) -> URL? {
        URL(string: "\(origin)/\(Self.encodeKey(key))")
    }

    /// The still to show for [media] at [width] pixels: a photo, a live photo's still, a video's poster.
    public func still(_ media: MediaItem, width: Int) -> URL? {
        let published = media.json["published"]
        if published.isNull { return nil }
        switch media.kind {
        case .image, .livePhoto:
            return image(published, width: width)
        case .video:
            if !published["poster"].isNull { return image(published["poster"], width: width) }
            guard published["original"].bool == true, let own = media.width else { return nil }
            return transformed("media/mode=frame,time=0s,width=\(Self.size(own: own, wanted: width)),format=jpg", key: published["key"].string ?? "")
        }
    }

    /// A published image at the smallest of its sizes at least [width] wide, else its largest.
    public func image(_ image: JSON, width: Int) -> URL? {
        let key = image["key"].string ?? ""
        if image["original"].bool == true {
            let own = image["width"].int ?? Self.resizedWidths.last!
            return transformed("image/width=\(Self.size(own: own, wanted: width)),format=auto,onerror=redirect", key: key)
        }
        var sources = image["variants"].array ?? []
        if (image["width"].int ?? Int.max) <= 1920 { sources.append(image) }
        sources.sort { ($0["width"].int ?? 0) < ($1["width"].int ?? 0) }
        let source = sources.first { ($0["width"].int ?? 0) >= width } ?? sources.last ?? image
        return asset(source["key"].string ?? key)
    }

    private func transformed(_ options: String, key: String) -> URL? {
        URL(string: "\(origin)/cdn-cgi/\(options)/\(Self.encodeKey(key))")
    }

    /// The smallest of the site's widths at least [wanted], never wider than the original.
    public static func size(own: Int, wanted: Int) -> Int {
        var widths = resizedWidths.filter { $0 < own }
        let top = min(own, 1920)
        if !widths.contains(top) { widths.append(top) }
        return widths.first { $0 >= wanted } ?? widths.last ?? top
    }

    private static func encodeKey(_ key: String) -> String {
        key.split(separator: "/", omittingEmptySubsequences: false).map { segment in
            let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!~*'()")
            return String(segment).addingPercentEncoding(withAllowedCharacters: allowed) ?? String(segment)
        }.joined(separator: "/")
    }
}
