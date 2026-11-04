// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.

import SwiftUI
import UIKit

/// The design system's colours, each with its light and dark value, following the system's appearance.
nonisolated enum Tokens {
    static let bg = Color.dynamic(Rgba(0.9922, 0.9922, 0.9882, 1), Rgba(0.0667, 0.0667, 0.0627, 1))
    static let surface = Color.dynamic(Rgba(0.9765, 0.9765, 0.9725, 1), Rgba(0.098, 0.098, 0.0941, 1))
    static let fill = Color.dynamic(Rgba(0.9529, 0.949, 0.9412, 1), Rgba(0.1294, 0.1294, 0.1216, 1))
    static let border = Color.dynamic(Rgba(0.9137, 0.9137, 0.9059, 1), Rgba(0.1647, 0.1647, 0.1569, 1))
    static let borderStrong = Color.dynamic(Rgba(0.5608, 0.5569, 0.5333, 1), Rgba(0.4314, 0.4275, 0.4039, 1))
    static let tx = Color.dynamic(Rgba(0.1294, 0.1255, 0.1098, 1), Rgba(0.9333, 0.9333, 0.9255, 1))
    static let tx2 = Color.dynamic(Rgba(0.298, 0.298, 0.2784, 1), Rgba(0.7098, 0.702, 0.6784, 1))
    static let tx3 = Color.dynamic(Rgba(0.451, 0.451, 0.4235, 1), Rgba(0.5216, 0.5137, 0.4863, 1))
    static let accent = Color.dynamic(Rgba(0.2471, 0.3882, 0.651, 1), Rgba(0.6157, 0.7137, 0.902, 1))
    static let onAccent = Color.dynamic(Rgba(1, 1, 1, 1), Rgba(0.0627, 0.1294, 0.2471, 1))
    static let accentSoft = Color.dynamic(Rgba(0.9294, 0.9451, 0.9725, 1), Rgba(0.1059, 0.1333, 0.1961, 1))
    static let danger = Color.dynamic(Rgba(0.702, 0.2118, 0.1216, 1), Rgba(0.9412, 0.5725, 0.4863, 1))
    static let income = Color.dynamic(Rgba(0.1843, 0.4353, 0.5608, 1), Rgba(0.5255, 0.7647, 0.8745, 1))
    static let scrim = Color.dynamic(Rgba(0, 0, 0, 0.45), Rgba(0, 0, 0, 0.45))
    static let onMedia = Color.dynamic(Rgba(1, 1, 1, 1), Rgba(1, 1, 1, 1))
    static let year1 = Color.dynamic(Rgba(0.2314, 0.5098, 0.9647, 1), Rgba(0.2314, 0.5098, 0.9647, 1))
    static let year2 = Color.dynamic(Rgba(0.5451, 0.3608, 0.9647, 1), Rgba(0.5451, 0.3608, 0.9647, 1))
    static let year3 = Color.dynamic(Rgba(0.9608, 0.6196, 0.0431, 1), Rgba(0.9608, 0.6196, 0.0431, 1))
    static let year4 = Color.dynamic(Rgba(0.0627, 0.7255, 0.5059, 1), Rgba(0.0627, 0.7255, 0.5059, 1))
    static let year5 = Color.dynamic(Rgba(0.9373, 0.2667, 0.2667, 1), Rgba(0.9373, 0.2667, 0.2667, 1))
    static let year6 = Color.dynamic(Rgba(0.0235, 0.7137, 0.8314, 1), Rgba(0.0235, 0.7137, 0.8314, 1))
    static let series1 = Color.dynamic(Rgba(0.1647, 0.4706, 0.8392, 1), Rgba(0.2235, 0.5294, 0.898, 1))
    static let series2 = Color.dynamic(Rgba(0.9216, 0.4078, 0.2039, 1), Rgba(0.851, 0.349, 0.149, 1))
    static let series3 = Color.dynamic(Rgba(0.1059, 0.6863, 0.4784, 1), Rgba(0.098, 0.6196, 0.4392, 1))
    static let series4 = Color.dynamic(Rgba(0.9294, 0.6314, 0, 1), Rgba(0.7882, 0.5216, 0, 1))
    static let series5 = Color.dynamic(Rgba(0.9098, 0.4824, 0.6431, 1), Rgba(0.8353, 0.3176, 0.5059, 1))
    static let series6 = Color.dynamic(Rgba(0, 0.5137, 0, 1), Rgba(0, 0.5137, 0, 1))

    /// The map's year colours in order, the same in both themes and on the site.
    static let yearColors: [Color] = [year1, year2, year3, year4, year5, year6]
    /// The charts' series colours in order.
    static let seriesColors: [Color] = [series1, series2, series3, series4, series5, series6]
}

/// The 4pt grid every gap and padding is a step of, and the gaps that mean something.
nonisolated enum Space {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 24
    static let gutter: CGFloat = 20
}

/// The corner sizes, each drawn as a continuous curve.
nonisolated enum Radius {
    static let medium: CGFloat = 12
    static let large: CGFloat = 18
}

/// How strong a wash or a fade is.
nonisolated enum Opacity {
    static let disabled: Double = 0.38
    static let press: Double = 0.1
    static let hover: Double = 0.06
}

/// The six text styles: the system sans with tabular figures, each scaling with Dynamic Type
/// as the system style beside it does.
nonisolated enum TextStyle: CaseIterable, Sendable {
    case titlePage, title, bodyReading, body, label, caption

    var size: CGFloat {
        switch self {
        case .titlePage: 26
        case .title: 18
        case .bodyReading: 17
        case .body: 15
        case .label: 14
        case .caption: 12
        }
    }

    var lineHeight: CGFloat {
        switch self {
        case .titlePage: 34
        case .title: 26
        case .bodyReading: 30
        case .body: 24
        case .label: 20
        case .caption: 18
        }
    }

    var weight: UIFont.Weight {
        switch self {
        case .titlePage: .semibold
        case .title: .semibold
        case .bodyReading: .regular
        case .body: .regular
        case .label: .medium
        case .caption: .regular
        }
    }

    var scalesWith: UIFont.TextStyle {
        switch self {
        case .titlePage: .title1
        case .title: .title3
        case .bodyReading: .body
        case .body: .subheadline
        case .label: .subheadline
        case .caption: .caption1
        }
    }
}

/// The springs everything moves on, none overshooting, by damping ratio and stiffness at a mass of 1.
nonisolated enum Springs {
    static let spatialFast = Animation.interpolatingSpring(mass: 1, stiffness: 1400, damping: 67.3498)
    static let spatialDefault = Animation.interpolatingSpring(mass: 1, stiffness: 700, damping: 47.6235)
    static let effectsFast = Animation.interpolatingSpring(mass: 1, stiffness: 3800, damping: 123.2883)
    static let effectsDefault = Animation.interpolatingSpring(mass: 1, stiffness: 1600, damping: 80)
}
