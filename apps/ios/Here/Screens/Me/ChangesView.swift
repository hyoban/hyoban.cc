import SwiftUI

// 发布更改 (the design's 16): what publishing would change on the site, under 新写的, 改过的, 改过的旅程
// and 公开状态, each row taking itself back; what changed in a Moment's words marked in place. The
// ink 全部发布到网站 at the foot sends it all as one change set, like a commit.

struct ChangesView: View {
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router
    @State private var failure: String?

    var body: some View {
        let pending = store.pending
        let groups: [(LocalizedStringKey, [PendingChange])] = [
            ("changes.new", pending.filter { $0.kind == .added && !$0.isOutside && !$0.isCollection }),
            ("changes.edited", pending.filter { $0.kind != .added && !$0.isOutside && !$0.isCollection }),
            ("changes.trips", pending.filter(\.isCollection)),
            ("changes.public", pending.filter(\.isOutside)),
        ]
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("changes.privateStays").textStyle(.caption).foregroundStyle(Tokens.tx3).padding(.horizontal, Space.gutter)
                if pending.isEmpty {
                    Text("changes.none").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity).padding(.top, 80)
                }
                ForEach(groups.indices, id: \.self) { index in
                    let (title, changes) = groups[index]
                    if !changes.isEmpty {
                        GroupCaption(title: title)
                        ForEach(changes) { change in row(change) }
                    }
                }
            }
            .padding(.bottom, 120)
        }
        .page(Text("changes.title"), subtitle: Text("changes.count \(pending.count)"))
        .safeAreaInset(edge: .bottom) {
            if !pending.isEmpty {
                VStack(spacing: Space.s) {
                    if let progress = store.publishing {
                        LinearProgress(value: Double(progress.done) / Double(max(1, progress.total)))
                        Text(verbatim: progress.step).textStyle(.caption).foregroundStyle(Tokens.tx2)
                    }
                    if let failure {
                        Text(verbatim: failure).textStyle(.caption).foregroundStyle(Tokens.danger)
                    }
                    Button {
                        failure = nil
                        Task {
                            do { try await store.publish() } catch { failure = error.localizedDescription }
                        }
                    } label: {
                        IconLabel(title: "changes.publishAll", icon: "cloud-upload").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.ink)
                    .disabled(store.publishing != nil)
                    .accessibilityIdentifier("publish_all")
                }
                .padding(.horizontal, Space.gutter)
                .padding(.bottom, Space.s)
            }
        }
    }

    private func row(_ change: PendingChange) -> some View {
        let entry: Entry? = if case .entry(let uid) = change.subject { store.entry(uid) } else { nil }
        let kind: String = switch change.kind {
        case .added: String(localized: "changes.added")
        case .changed: String(localized: "changes.changed")
        case .deleted: String(localized: "changes.deleted")
        }
        return HStack(spacing: Space.m) {
            if let media = entry?.media.first {
                MediaImage(media: media, width: 44, showsPlay: false).frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            } else {
                Icon(icon(change), size: 24).foregroundStyle(Tokens.tx2).frame(width: 44)
            }
            VStack(alignment: .leading, spacing: 0) {
                if let before = change.before, let after = change.after, before != after {
                    TextDiff(before: before, after: after).textStyle(.body).lineLimit(3)
                } else {
                    Text(verbatim: change.title).textStyle(.body).foregroundStyle(change.kind == .deleted ? Tokens.tx2 : Tokens.tx).strikethrough(change.kind == .deleted).lineLimit(1)
                }
                Text(verbatim: [change.detail, kind].filter { !$0.isEmpty }.joined(separator: Formats.gap)).textStyle(.caption).foregroundStyle(Tokens.tx2)
            }
            Spacer(minLength: Space.m)
            if !change.isOutside {
                Button { withAnimation(Springs.spatialDefault) { store.revert(change.subject) } } label: {
                    Icon("undo", size: 22).foregroundStyle(Tokens.tx2).frame(width: 48, height: 48)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("changes.revert"))
            }
        }
        .frame(minHeight: 64)
        .padding(.horizontal, Space.gutter)
        .contentShape(.rect)
        .onTapGesture {
            switch change.subject {
            case .entry(let uid): if store.entry(uid)?.isMoment == true { router.push(.moment(uid)) }
            case .collection(let key): router.push(.trip(key))
            case .place(let id): router.push(.place(id))
            case .outside: break
            }
        }
    }

    private func icon(_ change: PendingChange) -> String {
        switch change.subject {
        case .entry: change.kind == .deleted ? "trash" : "notes"
        case .place: "pin"
        case .collection: "route"
        case .outside(let key): key.hasPrefix("bill:") ? "receipt" : "footprints"
        }
    }
}

/// What changed in a Moment's words, marked in place: what was taken out struck through on `fill`
/// in `danger`, what was put in on `accent-soft`.
struct TextDiff: View {
    var before: String
    var after: String

    var body: some View {
        let a = Array(before), b = Array(after)
        var head = 0
        while head < min(a.count, b.count), a[head] == b[head] { head += 1 }
        var tail = 0
        while tail < min(a.count, b.count) - head, a[a.count - 1 - tail] == b[b.count - 1 - tail] { tail += 1 }
        let removed = String(a[head..<(a.count - tail)])
        let added = String(b[head..<(b.count - tail)])
        var text = AttributedString(String(a[..<head]))
        if !removed.isEmpty {
            var cut = AttributedString(removed)
            cut.foregroundColor = Tokens.danger
            cut.backgroundColor = Tokens.fill
            cut.strikethroughStyle = .single
            text += cut
        }
        if !added.isEmpty {
            var put = AttributedString(added)
            put.backgroundColor = Tokens.accentSoft
            text += put
        }
        text += AttributedString(String(a[(a.count - tail)...]))
        return Text(text).foregroundStyle(Tokens.tx)
    }
}
