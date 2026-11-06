// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.

import SwiftUI

/// The design system's colours in one theme; `Tokens.light` and `Tokens.dark` hold them.
public struct Tokens: Sendable {
    public let bg: Color
    public let surface: Color
    public let fill: Color
    public let border: Color
    public let borderStrong: Color
    public let tx: Color
    public let tx2: Color
    public let tx3: Color
    public let accent: Color
    public let onAccent: Color
    public let accentSoft: Color
    public let danger: Color
    public let income: Color
    public let scrim: Color
    public let onMedia: Color
    public let year1: Color
    public let year2: Color
    public let year3: Color
    public let year4: Color
    public let year5: Color
    public let year6: Color
    public let series1: Color
    public let series2: Color
    public let series3: Color
    public let series4: Color
    public let series5: Color
    public let series6: Color
}

extension Tokens {
    private static func rgb(_ hex: UInt32, _ alpha: Double = 1) -> Color {
        Color(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }

    public static let light = Tokens(
        bg: rgb(0xFDFDFC),
        surface: rgb(0xF9F9F8),
        fill: rgb(0xF3F2F0),
        border: rgb(0xE9E9E7),
        borderStrong: rgb(0x8F8E88),
        tx: rgb(0x21201C),
        tx2: rgb(0x4C4C47),
        tx3: rgb(0x73736C),
        accent: rgb(0x3F63A6),
        onAccent: rgb(0xFFFFFF),
        accentSoft: rgb(0xEDF1F8),
        danger: rgb(0xB3361F),
        income: rgb(0x2F6F8F),
        scrim: rgb(0x000000, 0.45),
        onMedia: rgb(0xFFFFFF),
        year1: rgb(0x3B82F6),
        year2: rgb(0x8B5CF6),
        year3: rgb(0xF59E0B),
        year4: rgb(0x10B981),
        year5: rgb(0xEF4444),
        year6: rgb(0x06B6D4),
        series1: rgb(0x2A78D6),
        series2: rgb(0xEB6834),
        series3: rgb(0x1BAF7A),
        series4: rgb(0xEDA100),
        series5: rgb(0xE87BA4),
        series6: rgb(0x008300)
    )

    public static let dark = Tokens(
        bg: rgb(0x111110),
        surface: rgb(0x191918),
        fill: rgb(0x21211F),
        border: rgb(0x2A2A28),
        borderStrong: rgb(0x6E6D67),
        tx: rgb(0xEEEEEC),
        tx2: rgb(0xB5B3AD),
        tx3: rgb(0x85837C),
        accent: rgb(0x9DB6E6),
        onAccent: rgb(0x10213F),
        accentSoft: rgb(0x1B2232),
        danger: rgb(0xF0927C),
        income: rgb(0x86C3DF),
        scrim: rgb(0x000000, 0.45),
        onMedia: rgb(0xFFFFFF),
        year1: rgb(0x3B82F6),
        year2: rgb(0x8B5CF6),
        year3: rgb(0xF59E0B),
        year4: rgb(0x10B981),
        year5: rgb(0xEF4444),
        year6: rgb(0x06B6D4),
        series1: rgb(0x3987E5),
        series2: rgb(0xD95926),
        series3: rgb(0x199E70),
        series4: rgb(0xC98500),
        series5: rgb(0xD55181),
        series6: rgb(0x008300)
    )

    /// The colours of a colour scheme.
    public static func of(_ scheme: ColorScheme) -> Tokens {
        scheme == .dark ? dark : light
    }

    /// The map's year colours in order, the same in both themes and on the site.
    public static let yearColors: [Color] = [light.year1, light.year2, light.year3, light.year4, light.year5, light.year6]
}

/// The 4pt grid every gap and padding is a step of, and the gaps that mean something.
public enum Space {
    public static let xs: CGFloat = 4
    public static let s: CGFloat = 8
    public static let m: CGFloat = 12
    public static let l: CGFloat = 16
    public static let xl: CGFloat = 24

    /// The page's side margin in a window this wide: wider from 600pt up.
    public static func gutter(width: CGFloat) -> CGFloat {
        width >= 600 ? 32 : 20
    }
}

/// The corner sizes, each a continuous curve where it is drawn.
public enum Radius {
    public static let medium: CGFloat = 12
    public static let large: CGFloat = 18
}

/// The one shadow, as the design writes it: black at `opacity`, `y` down, blurred by `blur`.
public enum Shadow {
    public static let y: CGFloat = 3
    public static let blur: CGFloat = 12
    public static let opacity: Double = 0.1
}

/// How far the page is blurred under what is fixed over it.
public enum Blur {
    public static let edge: CGFloat = 28
}

/// The sizes every component shares.
public enum Size {
    public static let touch: CGFloat = 48
    public static let icon: CGFloat = 24
    public static let iconSmall: CGFloat = 18
}

/// How strong a wash or a fade is.
public enum Opacity {
    public static let disabled: Double = 0.38
    public static let press: Double = 0.1
    public static let hover: Double = 0.06
}

/// A text style: the system sans at a size, a line height and a weight, with tabular figures.
public struct TextToken: Sendable {
    public let size: CGFloat
    public let lineHeight: CGFloat
    public let weight: Font.Weight

    public var font: Font {
        .system(size: size, weight: weight).monospacedDigit()
    }
}

/// The six text styles.
public enum TextStyles {
    public static let titlePage = TextToken(size: 26, lineHeight: 34, weight: .semibold)
    public static let title = TextToken(size: 18, lineHeight: 26, weight: .semibold)
    public static let bodyReading = TextToken(size: 17, lineHeight: 30, weight: .regular)
    public static let body = TextToken(size: 15, lineHeight: 24, weight: .regular)
    public static let label = TextToken(size: 14, lineHeight: 20, weight: .medium)
    public static let caption = TextToken(size: 12, lineHeight: 18, weight: .regular)
}

/// A spring that doesn't overshoot, by its damping ratio and stiffness at a mass of one.
public struct SpringToken: Sendable {
    public let dampingRatio: Double
    public let stiffness: Double

    public var animation: Animation {
        .interpolatingSpring(mass: 1, stiffness: stiffness, damping: 2 * dampingRatio * stiffness.squareRoot())
    }
}

/// The springs everything moves on.
public enum Springs {
    public static let spatialFast = SpringToken(dampingRatio: 0.9, stiffness: 1400)
    public static let spatialDefault = SpringToken(dampingRatio: 0.9, stiffness: 700)
    public static let effectsFast = SpringToken(dampingRatio: 1, stiffness: 3800)
    public static let effectsDefault = SpringToken(dampingRatio: 1, stiffness: 1600)
}
