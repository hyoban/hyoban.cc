import AVKit
import SwiftUI

// The photo viewer, over everything: a Moment's photos and videos paged through on black, a photo
// pinched to zoom, a video or a live photo's motion played by the system's player. A photo that
// can't be fetched says so, with 重试.

struct MediaViewer: View {
    var entryUid: String
    @State var index: Int

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    init(entryUid: String, index: Int) {
        self.entryUid = entryUid
        _index = State(initialValue: index)
    }

    var body: some View {
        let media = store.entry(entryUid)?.media ?? []
        NavigationStack {
            TabView(selection: $index) {
                ForEach(Array(media.enumerated()), id: \.element.uid) { offset, item in
                    MediaPage(media: item)
                        .tag(offset)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: media.count > 1 ? .always : .never))
            .background(.black)
            .ignoresSafeArea()
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Label("action.close", icon: "close") }
                }
                if media.indices.contains(index), let alt = Optional(media[index].alt), !alt.isEmpty {
                    ToolbarItem(placement: .bottomBar) {
                        Text(verbatim: alt).textStyle(.caption).foregroundStyle(.white).lineLimit(2)
                    }
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
        .statusBarHidden()
    }
}

private struct MediaPage: View {
    var media: MediaItem

    var body: some View {
        if media.kind == .video, let url = MediaURLs.motion(media) {
            VideoPlayer(player: AVPlayer(url: url))
        } else {
            ZoomablePhoto(media: media)
        }
    }
}

/// A photo that pinches to zoom and pans while zoomed, double-tap to zoom in and out.
private struct ZoomablePhoto: View {
    var media: MediaItem
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var showMotion = false

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                MediaImage(media: media, width: geometry.size.width * 2, contentMode: .fit)
                    .background(.black)
                if showMotion, let url = MediaURLs.motion(media) {
                    LoopingVideo(url: url)
                }
            }
            .scaleEffect(scale)
            .offset(offset)
            .frame(width: geometry.size.width, height: geometry.size.height)
            .gesture(
                MagnifyGesture()
                    .onChanged { scale = max(1, lastScale * $0.magnification) }
                    .onEnded { _ in
                        lastScale = scale
                        if scale == 1 { withAnimation(Springs.spatialDefault) { offset = .zero }; lastOffset = .zero }
                    }
                    .simultaneously(with: DragGesture()
                        .onChanged { value in
                            guard scale > 1 else { return }
                            offset = CGSize(width: lastOffset.width + value.translation.width, height: lastOffset.height + value.translation.height)
                        }
                        .onEnded { _ in lastOffset = offset })
            )
            .onTapGesture(count: 2) {
                withAnimation(Springs.spatialDefault) {
                    scale = scale > 1 ? 1 : 2.5
                    lastScale = scale
                    offset = .zero
                    lastOffset = .zero
                }
            }
            .onLongPressGesture(minimumDuration: 0.3) {
                if media.kind == .livePhoto { showMotion = true }
            } onPressingChanged: { pressing in
                if !pressing { showMotion = false }
            }
            .background(.black)
        }
    }
}

/// A live photo's motion, played while it is held.
private struct LoopingVideo: UIViewControllerRepresentable {
    var url: URL

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = false
        controller.player = AVPlayer(url: url)
        controller.player?.isMuted = false
        controller.player?.play()
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}
}
