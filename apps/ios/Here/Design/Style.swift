import SwiftUI
import UIKit

// How the iOS app carries the design system: the system's own controls, bars, sheets and Liquid
// Glass, with the design's colours, spacing, corners, text styles and icons from the generated
// Tokens.swift and the asset catalog's Icons. The accent is the tint; the page is `bg`.

/// A colour's channels from 0 to 1.
nonisolated struct Rgba: Sendable {
    var r, g, b, a: Double
    init(_ r: Double, _ g: Double, _ b: Double, _ a: Double) { (self.r, self.g, self.b, self.a) = (r, g, b, a) }
    var uiColor: UIColor { UIColor(red: r, green: g, blue: b, alpha: a) }
}

nonisolated extension Color {
    /// A colour that is [light] in the light appearance and [dark] in the dark one.
    static func dynamic(_ light: Rgba, _ dark: Rgba) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark.uiColor : light.uiColor })
    }
}

extension TextStyle {
    /// The style's font at the reader's text size.
    var uiFont: UIFont {
        UIFontMetrics(forTextStyle: scalesWith).scaledFont(for: .systemFont(ofSize: size, weight: weight))
    }
}

private struct Styled: ViewModifier {
    var style: TextStyle
    var strong: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    func body(content: Content) -> some View {
        let font = style.uiFont
        let scale = font.pointSize / style.size
        let weight: Font.Weight = strong ? .semibold : Font.Weight(style.weight)
        content
            .font(.system(size: font.pointSize, weight: weight).monospacedDigit())
            .lineSpacing(max(0, style.lineHeight * scale - font.lineHeight))
            .id(typeSize)
    }
}

extension Font.Weight {
    init(_ weight: UIFont.Weight) {
        switch weight {
        case .medium: self = .medium
        case .semibold: self = .semibold
        case .bold: self = .bold
        default: self = .regular
        }
    }
}

extension View {
    /// One of the design's six text styles; [strong] is a heading's 600, never a larger size.
    func textStyle(_ style: TextStyle, strong: Bool = false) -> some View {
        modifier(Styled(style: style, strong: strong))
    }
}

/// One of the design system's icons, tinted as the text around it.
struct Icon: View {
    var name: String
    var size: CGFloat = 22

    init(_ name: String, size: CGFloat = 22) {
        self.name = name
        self.size = size
    }

    var body: some View {
        Image(name)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

extension Label where Title == Text, Icon == HereIconImage {
    /// A label with one of the design's icons.
    init(_ title: LocalizedStringKey, icon: String) {
        self.init { Text(title) } icon: { HereIconImage(name: icon) }
    }

    init(verbatim title: String, icon: String) {
        self.init { Text(verbatim: title) } icon: { HereIconImage(name: icon) }
    }
}

/// An icon as a label's image, sized by the system as SF Symbols are.
struct HereIconImage: View {
    var name: String
    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 22

    var body: some View {
        Image(name).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size)
    }
}

extension Shape where Self == RoundedRectangle {
    static var cardShape: RoundedRectangle { RoundedRectangle(cornerRadius: Radius.large, style: .continuous) }
    static var mediumShape: RoundedRectangle { RoundedRectangle(cornerRadius: Radius.medium, style: .continuous) }
}

/// A block of the page set apart by its hairline: the design's surface.
struct SurfaceBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Tokens.surface, in: .cardShape)
            .overlay(RoundedRectangle(cornerRadius: Radius.large, style: .continuous).strokeBorder(Tokens.border, lineWidth: 1))
    }
}

extension View {
    func surface() -> some View { modifier(SurfaceBackground()) }
}

/// The year colour of an index into the palette.
func yearColor(_ index: Int?) -> Color {
    guard let index else { return Tokens.tx3 }
    return Tokens.yearColors[index % Tokens.yearColors.count]
}
