import SwiftUI

/// One of the design system's icons on its 24 grid: lines drawn `AppIcon.stroke` wide with round
/// ends and joins, filled marks, and marks filled even-odd so a shape inside cuts a hole. The
/// icons themselves are generated into `AppIcons.swift` as absolute M, L, Q, C and Z path data.
public struct AppIcon: Sendable, Identifiable {
    public let name: String
    /// Whether it points a way, and so is turned round right to left.
    public let mirror: Bool
    public let strokes: Path
    public let fills: Path
    public let holed: Path

    public var id: String { name }

    init(_ name: String, mirror: Bool, strokes: [String], fills: [String] = [], holed: [String] = []) {
        self.name = name
        self.mirror = mirror
        self.strokes = Path(pathData: strokes)
        self.fills = Path(pathData: fills)
        self.holed = Path(pathData: holed)
    }
}

extension Path {
    /// Path data of absolute M, L, Q, C and Z commands alone, as the icon generator writes it.
    init(pathData: [String]) {
        self.init()
        for data in pathData { Self.add(data, to: &self) }
    }

    /// The generator writes each command's letter, then its numbers apart by spaces.
    private static func add(_ data: String, to path: inout Path) {
        var command: Character?
        var numbers: [CGFloat] = []
        var number = ""

        func endNumber() {
            if let value = Double(number) { numbers.append(value) }
            number = ""
        }

        func endCommand() {
            endNumber()
            defer { numbers.removeAll(keepingCapacity: true) }
            guard let command else { return }
            let point = { (i: Int) in CGPoint(x: numbers[i], y: numbers[i + 1]) }
            switch (command, numbers.count) {
            case ("M", 2): path.move(to: point(0))
            case ("L", 2): path.addLine(to: point(0))
            case ("Q", 4): path.addQuadCurve(to: point(2), control: point(0))
            case ("C", 6): path.addCurve(to: point(4), control1: point(0), control2: point(2))
            case ("Z", 0): path.closeSubpath()
            default: assertionFailure("Not path data the icon generator writes: \(command)")
            }
        }

        for character in data {
            if character.isLetter {
                endCommand()
                command = character
            } else if character == " " {
                endNumber()
            } else {
                number.append(character)
            }
        }
        endCommand()
    }
}

/// An icon drawn at `size`, in the foreground style around it.
public struct AppIconView: View {
    public let icon: AppIcon
    public var size: CGFloat

    public init(_ icon: AppIcon, size: CGFloat = Size.icon) {
        self.icon = icon
        self.size = size
    }

    public var body: some View {
        let scale = CGAffineTransform(scaleX: size / 24, y: size / 24)
        ZStack {
            icon.strokes.applying(scale).stroke(
                style: StrokeStyle(lineWidth: AppIcon.stroke * size / 24, lineCap: .round, lineJoin: .round)
            )
            icon.fills.applying(scale).fill()
            icon.holed.applying(scale).fill(style: FillStyle(eoFill: true))
        }
        .frame(width: size, height: size)
        .flipsForRightToLeftLayoutDirection(icon.mirror)
        .accessibilityHidden(true)
    }
}
