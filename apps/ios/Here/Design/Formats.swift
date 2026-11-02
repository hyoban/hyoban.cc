import Foundation

// Dates, times and money as the pages say them, in the phone's language. Several things on a line
// are set apart by an em space, never a middle dot.

nonisolated enum Formats {
    static let gap = "\u{2003}"

    private static func style(_ base: Date.FormatStyle) -> Date.FormatStyle {
        var style = base
        style.timeZone = Times.siteZone
        return style
    }

    /// `10月28日`, with its year when not this year.
    static func shortDay(_ key: String) -> String {
        guard let date = Times.date(key) else { return key }
        var format = style(Date.FormatStyle(date: .omitted, time: .omitted).month(.abbreviated).day())
        if key.prefix(4) != Times.day(Date()).prefix(4) { format = format.year() }
        return date.formatted(format)
    }

    /// `周三`.
    static func weekday(_ key: String) -> String {
        guard let date = Times.date(key) else { return "" }
        return date.formatted(style(Date.FormatStyle(date: .omitted, time: .omitted).weekday(.abbreviated)))
    }

    /// `10月28日 周二`, or `今天 周三`.
    static func day(_ key: String) -> String {
        let name = key == Times.day(Date()) ? String(localized: "day.today") : shortDay(key)
        return name + gap + weekday(key)
    }

    /// A date capsule's words: in a trip `第 1 天` first.
    static func capsule(_ key: String, tripDay: Int? = nil) -> String {
        guard let tripDay else { return day(key) }
        return String(localized: "trip.dayN \(tripDay)") + gap + day(key)
    }

    /// The time of day a timestamp names, in its own offset; nil for a day alone.
    static func time(_ at: String) -> String? {
        guard at.count > 10, let date = Times.date(at) else { return nil }
        var format = Date.FormatStyle(date: .omitted, time: .shortened)
        format.timeZone = offsetZone(at) ?? .current
        return date.formatted(format)
    }

    /// The zone of a timestamp's offset.
    static func offsetZone(_ at: String) -> TimeZone? {
        if at.hasSuffix("Z") { return TimeZone(secondsFromGMT: 0) }
        let tail = at.suffix(6)
        guard tail.count == 6, let sign = tail.first, sign == "+" || sign == "-" else { return nil }
        let parts = tail.dropFirst().split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        let seconds = (parts[0] * 3600 + parts[1] * 60) * (sign == "-" ? -1 : 1)
        return TimeZone(secondsFromGMT: seconds)
    }

    /// The span of a trip: `4月2日 – 4月7日`.
    static func span(_ start: String, _ end: String) -> String {
        start == end ? shortDay(start) : "\(shortDay(start)) – \(shortDay(end))"
    }

    enum Sign { case none, plus, minus }

    /// The symbol a currency is written with: `¥` for the yuan, `JP¥`, `US$` and the like for the rest.
    static func symbol(_ currency: String) -> String {
        if currency == "CNY" { return "¥" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.numberStyle = .currency
        formatter.currencyCode = currency
        return formatter.currencySymbol ?? currency
    }

    /// Cents in their currency, two places: `−¥58.00`, `+¥12,480.00`.
    static func amount(_ cents: Int, _ currency: String = "CNY", sign: Sign = .none) -> String {
        let number = (Double(abs(cents)) / 100).formatted(.number.precision(.fractionLength(2)).grouping(.automatic))
        let mark = switch sign {
        case .none: cents < 0 ? "\u{2212}" : ""
        case .plus: "+"
        case .minus: "\u{2212}"
        }
        return mark + symbol(currency) + number
    }

    /// An amount in its currency's unit, two places, no sign.
    static func money(_ amount: Double, _ currency: String = "CNY") -> String {
        Formats.amount(Int((amount * 100).rounded()), currency)
    }

    static func distance(_ metres: Double) -> String {
        Measurement(value: metres, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road))
    }
}
