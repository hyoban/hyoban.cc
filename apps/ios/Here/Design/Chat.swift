import SwiftUI

// The timeline and a trip as a conversation with oneself (the design's MomentBubble, MomentPhotos,
// RecordsBubble, DateCapsule, TripBanner and ChangesRow cards, Android's ui/feed/Chat.kt): Moments
// from the right in `fill` bubbles with a tail, the day's bills, charges and footprints from the
// left in one outlined bubble, each day opening with its date in a capsule.

/// A bubble's tail, 9 by 16, at its lower corner.
private nonisolated struct Tail: Shape {
    var leading: Bool

    func path(in rect: CGRect) -> Path {
        var path = Path()
        if leading {
            // The records bubble's, mirrored: M10 0H8.5C8.5 9 6 14.5 0.5 15.5H10z
            path.move(to: CGPoint(x: 10, y: 0))
            path.addLine(to: CGPoint(x: 8.5, y: 0))
            path.addCurve(to: CGPoint(x: 0.5, y: 15.5), control1: CGPoint(x: 8.5, y: 9), control2: CGPoint(x: 6, y: 14.5))
            path.addLine(to: CGPoint(x: 10, y: 15.5))
        } else {
            // M0 0H1C1 9 3.5 14.5 9 16H0z
            path.move(to: .zero)
            path.addLine(to: CGPoint(x: 1, y: 0))
            path.addCurve(to: CGPoint(x: 9, y: 16), control1: CGPoint(x: 1, y: 9), control2: CGPoint(x: 3.5, y: 14.5))
            path.addLine(to: CGPoint(x: 0, y: 16))
        }
        path.closeSubpath()
        return path
    }
}

/// The open edge of the records bubble's tail, drawn as its hairline.
private nonisolated struct TailEdge: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 8.5, y: 0))
        path.addCurve(to: CGPoint(x: 0.5, y: 15.5), control1: CGPoint(x: 8.5, y: 9), control2: CGPoint(x: 6, y: 14.5))
        path.addLine(to: CGPoint(x: 10, y: 15.5))
        return path
    }
}

/// How a bubble sits among those beside it: corners touching another are `radius-medium`, only the last has a tail.
struct BubbleRun: Equatable {
    var first = true
    var last = true
}

/// A Moment from the right: its photos across the top, its words, its place and time at their end.
struct MomentBubble: View {
    var entry: Entry
    var run = BubbleRun()
    /// In a trip, the trip isn't named again.
    var showPlace = true

    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        let large = Radius.large, medium = Radius.medium
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: large,
            bottomLeadingRadius: large,
            bottomTrailingRadius: run.last ? 0 : medium,
            topTrailingRadius: run.first ? large : medium,
            style: .continuous
        )
        Button { router.push(.moment(entry.uid)) } label: {
            VStack(alignment: .leading, spacing: 0) {
                if !entry.media.isEmpty {
                    MomentPhotos(media: entry.media)
                }
                words
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, Space.s)
            }
            .background(Tokens.fill)
            .clipShape(shape)
            .contentShape(shape)
        }
        .buttonStyle(PressWash(shape: shape))
        .overlay(alignment: .bottomTrailing) {
            if run.last { Tail(leading: false).fill(Tokens.fill).frame(width: 9, height: 16).offset(x: 9) }
        }
        .zoomSource(entry.uid)
        .contextMenu { MomentMenu(entry: entry) }
        .accessibilityIdentifier("moment_\(entry.uid)")
    }

    @ViewBuilder
    private var words: some View {
        let meta = metaText
        let unpublished = store.isUnpublished(entry.uid)
        VStack(alignment: .trailing, spacing: Space.xs) {
            if entry.text.isEmpty {
                meta.textStyle(.caption).frame(maxWidth: .infinity, alignment: .trailing)
            } else {
                Text("\(Text(verbatim: entry.text).foregroundStyle(Tokens.tx))\u{2003}\(meta)")
                    .textStyle(.body)
                    .lineLimit(8)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if unpublished { StatusPill(title: "visibility.unpublished", inBubble: true) }
        }
    }

    /// The place, 私有 and the time, `caption` and `tx3`, em spaces between.
    private var metaText: Text {
        var parts: [String] = []
        if showPlace, let place = entry.place.flatMap({ store.places[$0] }) { parts.append(place.name) }
        if entry.isPrivate { parts.append(String(localized: "visibility.private")) }
        if let time = Formats.time(entry.at) { parts.append(time) }
        return Text(verbatim: parts.joined(separator: "\u{2003}"))
            .font(.system(size: TextStyle.caption.uiFont.pointSize).monospacedDigit())
            .foregroundStyle(Tokens.tx3)
    }
}

/// A Moment's photos as one block: one 4:3, two side by side, three a large one and two beside it,
/// the last covered with `+n` when there are more; 实况 and 视频 marked.
struct MomentPhotos: View {
    var media: [MediaItem]
    var width: CGFloat = 360

    var body: some View {
        let shown = Array(media.prefix(3))
        let more = media.count - shown.count
        Group {
            switch shown.count {
            case 1:
                cell(shown[0], more: 0).aspectRatio(4 / 3, contentMode: .fit)
            case 2:
                HStack(spacing: 3) {
                    cell(shown[0], more: 0)
                    cell(shown[1], more: 0)
                }
                .aspectRatio(3 / 2, contentMode: .fit)
            default:
                HStack(spacing: 3) {
                    cell(shown[0], more: 0)
                    VStack(spacing: 3) {
                        cell(shown[1], more: 0)
                        cell(shown[2], more: more)
                    }
                }
                .aspectRatio(3 / 2, contentMode: .fit)
            }
        }
    }

    private func cell(_ item: MediaItem, more: Int) -> some View {
        Color.clear
            .overlay { MediaImage(media: item, width: width, showsPlay: false) }
            .clipped()
            .overlay {
                if more > 0 {
                    Tokens.scrim
                    Text(verbatim: "+\(more)").textStyle(.title).foregroundStyle(Tokens.onMedia)
                }
            }
            .overlay(alignment: item.kind == .video ? .bottomLeading : .topLeading) {
                if item.kind == .livePhoto {
                    MediaPill(title: "media.live")
                } else if item.kind == .video {
                    MediaPill(title: "media.video", icon: "play")
                }
            }
    }
}

/// A small `scrim` capsule over a photo: 实况, 视频.
struct MediaPill: View {
    var title: LocalizedStringKey
    var icon: String?

    var body: some View {
        HStack(spacing: 2) {
            if let icon { Icon(icon, size: 12) }
            Text(title).textStyle(.caption)
        }
        .foregroundStyle(Tokens.onMedia)
        .padding(.horizontal, 6)
        .padding(.vertical, 1)
        .background(Tokens.scrim, in: .capsule)
        .padding(Space.s)
    }
}

/// One line of a records bubble.
struct RecordItem: Identifiable {
    var id: String
    var time: String?
    var icon: String
    var title: String
    var value: String
    var income = false
    var route: Route?
}

/// The day's bills, charges and footprints from the left: outlined, a line each, the footprints last;
/// more than five shows four and says how many more.
struct RecordsBubble: View {
    var items: [RecordItem]
    /// Where the whole bubble leads when it holds several.
    var day: String?

    @Environment(Router.self) private var router

    var body: some View {
        let shown = items.count > 5 ? Array(items.prefix(4)) : items
        let hidden = items.count - shown.count
        let timed = items.contains { $0.time != nil }
        let shape = UnevenRoundedRectangle(topLeadingRadius: Radius.large, bottomLeadingRadius: 0, bottomTrailingRadius: Radius.large, topTrailingRadius: Radius.large, style: .continuous)
        Button {
            if items.count == 1, let route = items[0].route { router.push(route) } else if let day { router.push(.day(day)) }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(shown) { item in
                    HStack(spacing: Space.s) {
                        if timed {
                            Text(verbatim: item.time ?? "").textStyle(.caption).foregroundStyle(Tokens.tx3).frame(width: 40, alignment: .leading)
                        }
                        Icon(item.icon, size: 18).foregroundStyle(Tokens.tx3)
                        Text(verbatim: item.title).textStyle(.body).foregroundStyle(Tokens.tx).lineLimit(1)
                        Spacer(minLength: Space.m)
                        Text(verbatim: item.value).textStyle(.body).foregroundStyle(item.income ? Tokens.income : Tokens.tx).lineLimit(1)
                    }
                    .frame(minHeight: 32)
                }
                if hidden > 0 {
                    HStack(spacing: Space.s) {
                        if timed { Color.clear.frame(width: 40, height: 1) }
                        Color.clear.frame(width: 18, height: 1)
                        Text("records.more \(hidden)").textStyle(.caption).foregroundStyle(Tokens.tx3)
                    }
                    .frame(minHeight: 28)
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.xs)
            .overlay(shape.strokeBorder(Tokens.border))
            .contentShape(shape)
        }
        .buttonStyle(PressWash(shape: shape))
        .overlay(alignment: .bottomLeading) {
            ZStack {
                Tail(leading: true).fill(Tokens.bg)
                TailEdge().stroke(Tokens.border, lineWidth: 1)
            }
            .frame(width: 10, height: 16)
            .offset(x: -9.5, y: 0.5)
        }
    }
}

/// A day's date in a capsule, opening the day: `今天 周三`, `10月28日 周二`, in a trip `第 1 天` first.
struct DateCapsule: View {
    var day: String
    var tripDay: Int?

    @Environment(Router.self) private var router

    var body: some View {
        Button { router.push(.day(day)) } label: {
            Text(verbatim: Formats.capsule(day, tripDay: tripDay))
                .textStyle(.label)
                .foregroundStyle(Tokens.tx2)
                .padding(.horizontal, Space.s)
                .padding(.vertical, 3)
                .background(Tokens.fill, in: .capsule)
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("day_\(day)")
    }
}

/// Where a trip begins in the timeline: its name, its days and Moments, 进行中 while under way.
struct TripBanner: View {
    var trip: Trip
    @Environment(Router.self) private var router

    var body: some View {
        Button { router.push(.trip(trip.id)) } label: {
            HStack(spacing: Space.m) {
                Icon("route", size: 24).foregroundStyle(Tokens.tx2)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: trip.title).textStyle(.body, strong: true).foregroundStyle(Tokens.tx)
                    detail.textStyle(.caption)
                }
                Spacer(minLength: Space.m)
                Icon("chevron-right", size: 18).foregroundStyle(Tokens.tx3)
            }
            .padding(.vertical, Space.m)
            .padding(.horizontal, Space.gutter)
            .contentShape(.rect)
        }
        .buttonStyle(PressWash(shape: Rectangle()))
        .accessibilityIdentifier("trip_\(trip.id)")
    }

    private var detail: Text {
        let rest = Text(verbatim: [
            Formats.span(trip.start, trip.end),
            String(localized: "trip.dayCount \(trip.dayCount)"),
            String(localized: "trip.momentCount \(trip.moments.count)"),
        ].joined(separator: "\u{2003}")).foregroundStyle(Tokens.tx2)
        if let day = trip.dayToday() {
            return Text("\(Text("trip.ongoing").foregroundStyle(Tokens.accent))\u{2003}\(Text("trip.dayN \(day)").foregroundStyle(Tokens.tx2))")
        }
        return rest
    }
}

/// The row at the top while changes wait: 发布更改, how many, and while publishing how far along.
struct ChangesRow: View {
    @Environment(AppStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        Button { router.tab = .me; router.me = [.changes] } label: {
            VStack(spacing: Space.s) {
                HStack(spacing: Space.m) {
                    Icon("cloud-upload", size: 24).foregroundStyle(Tokens.tx2)
                    VStack(alignment: .leading, spacing: 0) {
                        Text("changes.title").textStyle(.body).foregroundStyle(Tokens.tx)
                        Group {
                            if let progress = store.publishing {
                                Text("changes.publishing \(progress.done) \(progress.total)")
                            } else {
                                Text("changes.count \(store.pendingCount)")
                            }
                        }
                        .textStyle(.caption).foregroundStyle(Tokens.tx2)
                    }
                    Spacer()
                    Icon("chevron-right", size: 18).foregroundStyle(Tokens.tx3)
                }
                if let progress = store.publishing {
                    LinearProgress(value: Double(progress.done) / Double(max(1, progress.total)))
                }
            }
            .padding(.vertical, Space.s)
            .padding(.horizontal, Space.m)
            .background(Tokens.surface, in: .cardShape)
            .overlay(RoundedRectangle(cornerRadius: Radius.large, style: .continuous).strokeBorder(Tokens.border))
            .contentShape(.cardShape)
        }
        .buttonStyle(PressWash(shape: RoundedRectangle(cornerRadius: Radius.large, style: .continuous)))
        .padding(.horizontal, Space.gutter)
        .accessibilityIdentifier("changes")
    }
}
