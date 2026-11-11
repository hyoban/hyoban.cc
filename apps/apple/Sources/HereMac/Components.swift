import HereKit
import SwiftUI

// What the map's views share: the system-colour icon tiles, the year colours, days and distances.

/// A rounded square of a system colour with a white symbol in it, as System Settings draws them.
struct IconTile: View {
    let symbol: String
    let color: Color
    let size: CGFloat

    init(_ symbol: String, color: Color, size: CGFloat = 20) {
        self.symbol = symbol
        self.color = color
        self.size = size
    }


    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.55, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// The map's year colours, shared with the web and Android: each year by its order among the
/// years shown, oldest first, through the design's six.
enum YearColors {
    static func color(for year: Int, among years: [Int]) -> Color {
        let sorted = Array(Set(years)).sorted()
        let index = sorted.firstIndex(of: year) ?? 0
        return Tokens.yearColors[index % Tokens.yearColors.count]
    }
}

/// Days and times as the pages write them, in the system's language.
enum Formats {
    /// `Nov 11, Tuesday` / `Tuesday, Nov 11`, the year added when it isn't this one.
    static func day(_ key: String) -> String {
        guard let date = Times.day(key) else { return key }
        let thisYear = Times.today().prefix(4) == key.prefix(4)
        return formatter(thisYear ? "MMMdEEEE" : "yMMMdEEEE").string(from: date)
    }

    /// `Mar 1, 2025`.
    static func shortDay(_ key: String) -> String {
        guard let date = Times.day(key) else { return key }
        return formatter("yMMMd").string(from: date)
    }

    static func formatter(_ template: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = .autoupdatingCurrent
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter
    }

    /// An entry's time as written, `14:05`, or its day when it has no time.
    static func time(_ entry: Entry) -> String {
        entry.wallTime ?? shortDay(entry.day)
    }

    /// Metres as `12.3 km` or `850 m`.
    static func distance(_ metres: Double) -> String {
        Measurement(value: metres, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road).locale(.autoupdatingCurrent))
    }
}
