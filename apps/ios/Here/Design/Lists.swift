import SwiftUI

// Rows, as the design's PlainRows and SettingsGroup cards: rows straight on the page with the
// icon at the page's edge and the words at 60, no card and no rule; settings in a `surface` group
// with a hairline round it and between its rows. A group's caption is above it.

/// What a row ends with.
enum RowTrailing {
    case none
    case chevron
    case value(String)
    case amount(String, income: Bool = false, owed: Bool = false)
    case custom(AnyView)
}

/// A row straight on the page: icon 24 at the edge, the words from 60, 56 high, 64 with a second line.
struct PlainRow: View {
    var icon: String?
    var title: String
    var detail: String?
    var trailing: RowTrailing = .none
    var action: (() -> Void)?

    init(icon: String? = nil, title: String, detail: String? = nil, trailing: RowTrailing = .none, action: (() -> Void)? = nil) {
        self.icon = icon
        self.title = title
        self.detail = detail
        self.trailing = trailing
        self.action = action
    }

    var body: some View {
        if let action {
            Button(action: action) { content }
                .buttonStyle(PressWash(shape: Rectangle()))
        } else {
            content
        }
    }

    private var content: some View {
        HStack(spacing: 0) {
            if let icon {
                Icon(icon, size: 24).foregroundStyle(Tokens.tx2).frame(width: 60, alignment: .leading)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: title).textStyle(.body).foregroundStyle(Tokens.tx).lineLimit(2)
                if let detail, !detail.isEmpty {
                    Text(verbatim: detail).textStyle(.caption).foregroundStyle(Tokens.tx2).lineLimit(2)
                }
            }
            Spacer(minLength: Space.l)
            RowEnd(trailing: trailing)
        }
        .frame(minHeight: detail?.isEmpty == false ? 64 : 56)
        .padding(.horizontal, Space.gutter)
        .contentShape(.rect)
    }
}

struct RowEnd: View {
    var trailing: RowTrailing

    var body: some View {
        switch trailing {
        case .none: EmptyView()
        case .chevron: Icon("chevron-right", size: 18).foregroundStyle(Tokens.tx3)
        case .value(let value):
            HStack(spacing: Space.xs) {
                Text(verbatim: value).textStyle(.caption).foregroundStyle(Tokens.tx2)
                Icon("chevron-right", size: 18).foregroundStyle(Tokens.tx3)
            }
        case .amount(let amount, let income, let owed):
            Text(verbatim: amount).textStyle(.body).foregroundStyle(income ? Tokens.income : owed ? Tokens.danger : Tokens.tx)
        case .custom(let view): view
        }
    }
}

/// A group's caption on the page: `caption`, `tx3`, 24 above it.
struct GroupCaption: View {
    var title: LocalizedStringKey

    var body: some View {
        Text(title)
            .textStyle(.caption)
            .foregroundStyle(Tokens.tx3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.gutter)
            .padding(.top, Space.xl)
            .padding(.bottom, Space.xs)
    }
}

/// A settings group: a `surface` block with a hairline round it and between its rows.
struct SettingsGroup<Content: View>: View {
    var title: LocalizedStringKey?
    var footer: LocalizedStringKey?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title {
                Text(title).textStyle(.caption).foregroundStyle(Tokens.tx2).padding(.leading, Space.xs)
            }
            VStack(spacing: 0) {
                Group(subviews: content) { rows in
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        row
                        if index < rows.count - 1 {
                            Rectangle().fill(Tokens.border).frame(height: 1).padding(.leading, Space.l)
                        }
                    }
                }
            }
            .background(Tokens.surface, in: .mediumShape)
            .overlay(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous).strokeBorder(Tokens.border))
            .clipShape(.mediumShape)
            if let footer {
                Text(footer).textStyle(.caption).foregroundStyle(Tokens.tx2).padding(.horizontal, Space.xs).lineLimit(3)
            }
        }
        .padding(.horizontal, Space.gutter)
    }
}

/// A row in a settings group: icon `tx2`, title, a second line, and what it ends with.
struct SettingsRow: View {
    var icon: String?
    var title: String
    var detail: String?
    var trailing: RowTrailing = .none
    var selected = false
    var action: (() -> Void)?

    var body: some View {
        let content = HStack(spacing: Space.l) {
            if let icon { Icon(icon, size: 24).foregroundStyle(selected ? Tokens.accent : Tokens.tx2) }
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: title).textStyle(.body).foregroundStyle(Tokens.tx)
                if let detail, !detail.isEmpty { Text(verbatim: detail).textStyle(.caption).foregroundStyle(Tokens.tx2) }
            }
            Spacer(minLength: 0)
            RowEnd(trailing: trailing)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, Space.l)
        .frame(minHeight: detail == nil ? 56 : 64)
        .background(selected ? Tokens.accentSoft : .clear)
        .contentShape(.rect)
        if let action {
            Button(action: action) { content }.buttonStyle(PressWash(shape: Rectangle()))
        } else {
            content
        }
    }
}

/// A settings row with the design's switch at its end; the whole row switches it.
struct SwitchRow: View {
    var icon: String?
    var title: String
    var detail: String?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: Space.l) {
                if let icon { Icon(icon, size: 24).foregroundStyle(Tokens.tx2) }
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: title).textStyle(.body).foregroundStyle(Tokens.tx)
                    if let detail { Text(verbatim: detail).textStyle(.caption).foregroundStyle(Tokens.tx2) }
                }
            }
        }
        .toggleStyle(.app)
        .padding(.vertical, 10)
        .padding(.horizontal, Space.l)
        .frame(minHeight: detail == nil ? 56 : 64)
    }
}

/// A field: its name above it, a `surface` box with a `border-strong` edge, `tx` when focused.
struct LabeledField: View {
    var title: LocalizedStringKey
    @Binding var text: String
    var prompt: String = ""
    var secure = false
    var note: LocalizedStringKey?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).textStyle(.caption).foregroundStyle(Tokens.tx2).padding(.leading, Space.xs)
            Group {
                if secure {
                    SecureField(text: $text, prompt: Text(verbatim: prompt).foregroundStyle(Tokens.tx3)) { Text(title) }
                } else {
                    TextField(text: $text, prompt: Text(verbatim: prompt).foregroundStyle(Tokens.tx3)) { Text(title) }
                }
            }
            .focused($focused)
            .textStyle(.body)
            .foregroundStyle(Tokens.tx)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Tokens.surface, in: .mediumShape)
            .overlay(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous).strokeBorder(focused ? Tokens.tx : Tokens.borderStrong))
            if let note { Text(note).textStyle(.caption).foregroundStyle(Tokens.tx2).padding(.leading, Space.xs) }
        }
    }
}
