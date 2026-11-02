import SwiftUI

// The design system's controls (its Button, Chip, SegmentedControl, Switch, IconButton, Badge,
// StatusPill and Progress cards), named as the cards and Android's ui/design: one ink button for a
// page's main action, text buttons for the rest, one chip look, the design's own segments and switch.

/// The page's one main action: `tx` behind, `bg` words, a capsule.
struct InkButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(.label)
            .foregroundStyle(enabled ? Tokens.bg : Tokens.tx3)
            .padding(.horizontal, Space.xl - 4)
            .frame(minHeight: 40)
            .background(enabled ? Tokens.tx : Tokens.fill, in: .capsule)
            .overlay(Capsule().fill(Tokens.bg.opacity(configuration.isPressed ? Opacity.press : 0)))
            .contentShape(.capsule)
            .frame(minHeight: 48)
            .animation(Springs.effectsFast, value: configuration.isPressed)
    }
}

/// Everything else: words alone, the capsule showing only while pressed.
struct TextButtonStyle: ButtonStyle {
    var role: ButtonRole?
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(.label)
            .foregroundStyle(!enabled ? Tokens.tx3 : role == .destructive ? Tokens.danger : Tokens.tx)
            .padding(.horizontal, Space.l)
            .frame(minHeight: 40)
            .background(Tokens.tx.opacity(configuration.isPressed ? Opacity.press : 0), in: .capsule)
            .contentShape(.capsule)
            .frame(minHeight: 48)
            .animation(Springs.effectsFast, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == InkButtonStyle {
    static var ink: InkButtonStyle { InkButtonStyle() }
}

extension ButtonStyle where Self == TextButtonStyle {
    static var text: TextButtonStyle { TextButtonStyle() }
    static var destructiveText: TextButtonStyle { TextButtonStyle(role: .destructive) }
}

/// A button's words with an icon before them, 18 and 8 apart.
struct IconLabel: View {
    var title: LocalizedStringKey
    var icon: String

    var body: some View {
        HStack(spacing: Space.s) {
            Icon(icon, size: 18)
            Text(title)
        }
    }
}

/// A chip: `fill` and `tx2`; chosen, `accent-soft` and `tx` behind a tick.
struct Chip: View {
    var title: String
    var selected = false
    var icon: String?
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Icon("check", size: 18).foregroundStyle(Tokens.accent)
                } else if let icon {
                    Icon(icon, size: 18)
                }
                Text(verbatim: title).textStyle(.label).lineLimit(1)
            }
            .foregroundStyle(selected ? Tokens.tx : Tokens.tx2)
            .padding(.leading, selected || icon != nil ? 10 : 14)
            .padding(.trailing, 14)
            .frame(height: 36)
            .background(selected ? Tokens.accentSoft : Tokens.fill, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressWash(shape: Capsule()))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Several choices side by side: a `fill` track, the chosen one raised on `surface`.
struct Segmented<Value: Hashable>: View {
    var options: [(value: Value, title: LocalizedStringKey)]
    @Binding var selection: Value
    @Namespace private var segment

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let chosen = option.value == selection
                Button {
                    withAnimation(Springs.spatialFast) { selection = option.value }
                } label: {
                    Text(option.title)
                        .textStyle(.label)
                        .foregroundStyle(chosen ? Tokens.tx : Tokens.tx2)
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background {
                            if chosen {
                                Capsule().fill(Tokens.surface)
                                    .overlay(Capsule().strokeBorder(Tokens.border))
                                    .matchedGeometryEffect(id: "segment", in: segment)
                            }
                        }
                        .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(chosen ? .isSelected : [])
            }
        }
        .padding(3)
        .frame(height: 44)
        .background(Tokens.fill, in: .capsule)
    }
}

/// The design's switch: 44 by 26, `accent` when on with its thumb on the right.
struct AppSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack {
                configuration.label
                Spacer(minLength: Space.l)
                AppSwitch(on: configuration.isOn)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityValue(Text(configuration.isOn ? "switch.on" : "switch.off"))
    }
}

struct AppSwitch: View {
    var on: Bool

    var body: some View {
        ZStack(alignment: on ? .trailing : .leading) {
            Capsule()
                .fill(on ? Tokens.accent : .clear)
                .overlay(Capsule().strokeBorder(on ? .clear : Tokens.borderStrong, lineWidth: 2))
            Circle()
                .fill(on ? Tokens.onAccent : Tokens.borderStrong)
                .frame(width: 20, height: 20)
                .padding(3)
        }
        .frame(width: 44, height: 26)
        .animation(Springs.spatialFast, value: on)
        .accessibilityHidden(true)
    }
}

extension ToggleStyle where Self == AppSwitchStyle {
    static var app: AppSwitchStyle { AppSwitchStyle() }
}

/// A small state in a meta line: 未发布, with the phone icon.
struct StatusPill: View {
    var title: LocalizedStringKey
    var icon = "phone"
    /// In a Moment's bubble, `bg` and `tx2`, not to meet the bubble's `fill`.
    var inBubble = false

    var body: some View {
        HStack(spacing: 4) {
            Icon(icon, size: 12)
            Text(title).textStyle(.caption)
        }
        .foregroundStyle(inBubble ? Tokens.tx2 : Tokens.accent)
        .padding(.horizontal, 6)
        .frame(height: 18)
        .background(inBubble ? Tokens.bg : Tokens.accentSoft, in: .capsule)
    }
}

/// The 8 `accent` dot of something waiting.
struct BadgeDot: View {
    var body: some View {
        Circle().fill(Tokens.accent).frame(width: 8, height: 8)
    }
}

/// A thin line of progress: `accent` on `fill`, 4 high.
struct LinearProgress: View {
    var value: Double

    var body: some View {
        GeometryReader { geometry in
            Capsule().fill(Tokens.fill)
                .overlay(alignment: .leading) {
                    Capsule().fill(Tokens.accent).frame(width: geometry.size.width * min(max(value, 0), 1))
                }
        }
        .frame(height: 4)
        .animation(Springs.effectsDefault, value: value)
    }
}

/// The press wash in place of a highlight, in any shape.
struct PressWash<S: Shape>: ButtonStyle {
    var shape: S

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(shape.fill(Tokens.tx.opacity(configuration.isPressed ? Opacity.press : 0)).allowsHitTesting(false))
            .animation(Springs.effectsFast, value: configuration.isPressed)
    }
}

/// A 40 circle floating over a page, a photo or the map, in glass: its icon and its read name.
struct FloatingButton: View {
    var icon: String
    var label: LocalizedStringKey
    var on = false
    var dot = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Icon(icon, size: 22)
                .foregroundStyle(on ? Tokens.onAccent : Tokens.tx)
                .frame(width: 40, height: 40)
                .glassEffect(on ? .regular.tint(Tokens.accent).interactive() : .regular.interactive(), in: .circle)
                .overlay(alignment: .topTrailing) {
                    if dot {
                        BadgeDot().padding(2).background(Tokens.surface, in: .circle).offset(x: 2, y: -2)
                    }
                }
                .frame(width: 48, height: 48)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
