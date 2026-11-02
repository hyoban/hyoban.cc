import CoreTransferable
import ImageIO
import MapKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

// 记录: a Moment's words, up to nine photos and videos, a time and a place, and under 加一项 a
// meal. It is 公开 or 私有. Photos are copied in whole, with their EXIF. The time is the earliest
// photo's, else now; the place is where the first photo was taken, the known place within 2 km
// or a new one named by MapKit, else none until one is chosen: the page never locates the phone
// on its own. Nothing reaches the site until it is published from 发布更改.

struct ComposeView: View {
    var editing: String?
    var day: String?

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var text = ""
    @State private var media: [DraftMedia] = []
    @State private var picks: [PhotosPickerItem] = []
    @State private var at = Date()
    @State private var zone = TimeZone.current
    @State private var timeSet = false
    @State private var place: PlaceChoice?
    @State private var isPrivate = false
    @State private var food: DraftFood?
    @State private var choosingPlace = false
    @State private var loadingMedia = false
    @State private var refused: String?
    @FocusState private var focused: Bool

    private var original: Entry? { editing.flatMap { store.entry($0) } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("compose.placeholder", text: $text, axis: .vertical)
                        .lineLimit(4...)
                        .textStyle(.bodyReading)
                        .focused($focused)
                        .accessibilityIdentifier("compose_text")
                }

                Section {
                    if !media.isEmpty || loadingMedia { mediaStrip }
                    PhotosPicker(selection: $picks, maxSelectionCount: max(0, 9 - media.count), matching: .any(of: [.images, .videos]), photoLibrary: .shared()) {
                        Label { Text("compose.addMedia") } icon: { Image("photo-add").renderingMode(.template) }
                    }
                    .disabled(media.count >= 9)
                } footer: {
                    if let refused { Text(verbatim: refused).foregroundStyle(Tokens.danger) }
                }

                Section {
                    DatePicker(selection: Binding(get: { at }, set: { at = $0; timeSet = true })) {
                        Label("compose.time", icon: "clock")
                    }
                    Button { choosingPlace = true } label: {
                        HStack {
                            Label("compose.place", icon: "pin")
                            Spacer()
                            Text(verbatim: place?.place.name ?? String(localized: "compose.noPlace"))
                                .foregroundStyle(place == nil ? Tokens.tx3 : Tokens.tx2)
                        }
                    }
                    .foregroundStyle(Tokens.tx)
                    Toggle(isOn: $isPrivate) {
                        Label("visibility.private", icon: "eye-off")
                    }
                }

                if let binding = Binding($food) {
                    FoodSection(food: binding) { food = nil }
                } else {
                    Section {
                        Button { food = DraftFood() } label: { Label("compose.addFood", icon: "food") }
                    } header: {
                        Text("compose.addItem")
                    }
                }
            }
            .navigationTitle(Text(editing == nil ? "compose.new" : "compose.edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("action.cancel", icon: "close") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { save() } label: { Label("action.save", icon: "check") }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && media.isEmpty || loadingMedia)
                        .accessibilityIdentifier("compose_save")
                }
            }
            .sheet(isPresented: $choosingPlace) {
                PlacePicker(choice: $place, near: media.compactMap(\.location).first)
            }
            .onChange(of: picks) { _, items in
                guard !items.isEmpty else { return }
                picks = []
                Task { await take(items) }
            }
            .onAppear(perform: start)
        }
    }

    private var mediaStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s) {
                ForEach(media) { item in
                    ZStack(alignment: .topTrailing) {
                        DraftThumbnail(item: item)
                            .frame(width: 88, height: 88)
                            .clipShape(.mediumShape)
                        Button {
                            withAnimation(Springs.spatialDefault) { media.removeAll { $0.id == item.id } }
                        } label: {
                            Icon("close", size: 12).padding(6)
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .padding(4)
                        .accessibilityLabel(Text("action.delete"))
                    }
                }
                if loadingMedia {
                    ProgressView().frame(width: 88, height: 88)
                }
            }
        }
        .listRowInsets(EdgeInsets(top: Space.s, leading: Space.l, bottom: Space.s, trailing: Space.l))
    }

    // MARK: Starting and saving

    private func start() {
        guard let original else {
            if let day, let date = Times.date(day) { at = date.addingTimeInterval(12 * 3600); timeSet = true }
            focused = true
            return
        }
        text = original.text
        media = original.media.map { DraftMedia(item: $0) }
        at = original.date
        zone = Formats.offsetZone(original.at) ?? .current
        timeSet = true
        isPrivate = original.isPrivate
        if let id = original.place, let known = store.places[id] { place = PlaceChoice(id: id, place: known, isNew: false) }
        if let json = original.food { food = DraftFood(json) }
    }

    private func save() {
        let now = Times.now()
        var json = original?.json ?? [
            "uid": JSON(Ulid.make(at)),
            "createdAt": JSON(now),
        ]
        json["at"] = JSON(Times.local(at, zone: zone))
        json["place"] = place.map { JSON($0.id) }
        var parts = json["parts"]?.object ?? [:]
        parts["text"] = ["v": 1, "text": JSON(text)]
        parts["media"] = media.isEmpty ? nil : ["v": 1, "items": .array(media.map(\.json))]
        parts["food"] = food?.json
        json["parts"] = .object(parts)
        json["visibility"] = isPrivate ? "private" : nil
        json["updatedAt"] = JSON(now)
        let newPlace = place.flatMap { $0.isNew ? (id: $0.id, place: $0.place) : nil }
        store.save(Entry(json), place: newPlace)
        dismiss()
    }

    // MARK: Media

    private func take(_ items: [PhotosPickerItem]) async {
        loadingMedia = true
        defer { loadingMedia = false }
        refused = nil
        for item in items {
            do {
                if let draft = try await DraftMedia.load(item) {
                    media.append(draft)
                }
            } catch {
                refused = error.localizedDescription
            }
        }
        // The time is the earliest photo's; the place where the first was taken.
        if !timeSet, let earliest = media.compactMap(\.takenAt).min() {
            at = earliest
        }
        if place == nil, let location = media.compactMap(\.location).first {
            place = await PlaceChoice.near(location, store: store)
        }
    }
}

/// A meal as it is written: the dishes, how it was, what it cost and how many it fed.
struct DraftFood: Equatable {
    var dishes = ""
    var verdict: String?
    var cost = ""
    var people = 1

    init() {}

    init(_ json: JSON) {
        dishes = (json["dishes"]?.strings ?? []).joined(separator: "、")
        verdict = json["verdict"]?.string
        cost = json["cost"]?.double.map { $0.formatted(.number.grouping(.never)) } ?? ""
        people = json["people"]?.int ?? 1
    }

    var json: JSON {
        var out: JSON = ["v": 1]
        let list = dishes.split(whereSeparator: { "、,，\n".contains($0) }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        out["dishes"] = .array(list.map { JSON($0) })
        if let verdict { out["verdict"] = JSON(verdict) }
        if let value = Double(cost), value > 0 { out["cost"] = JSON(value) }
        if people > 1 { out["people"] = JSON(people) }
        return out
    }
}

private struct FoodSection: View {
    @Binding var food: DraftFood
    var remove: () -> Void

    var body: some View {
        Section {
            TextField("food.dishes", text: $food.dishes)
            Picker(selection: $food.verdict) {
                Text("food.none").tag(String?.none)
                Text("food.good").tag(String?.some("good"))
                Text("food.ok").tag(String?.some("ok"))
                Text("food.bad").tag(String?.some("bad"))
            } label: {
                Text("food.verdict")
            }
            .pickerStyle(.segmented)
            TextField("food.cost", text: $food.cost).keyboardType(.decimalPad)
            Stepper(value: $food.people, in: 1...50) {
                Text("food.people \(food.people)")
            }
        } header: {
            HStack {
                Label("food.title", icon: "food")
                Spacer()
                Button("action.delete", role: .destructive, action: remove).textCase(nil)
            }
        }
    }
}

/// A photo or video picked for a Moment: copied into the app's own store, with when and where it was taken.
struct DraftMedia: Identifiable, Equatable {
    var id: String
    var json: JSON
    var takenAt: Date?
    var location: CLLocationCoordinate2D?

    init(item: MediaItem) {
        id = item.uid
        json = item.json
        takenAt = item.takenAt.flatMap(Times.date)
    }

    static func == (a: DraftMedia, b: DraftMedia) -> Bool { a.id == b.id }

    var item: MediaItem { MediaItem(json) }

    struct Refused: LocalizedError {
        var errorDescription: String?
    }

    /// Copies a picked item into the app's store, or refuses a video the site can't take.
    static func load(_ pick: PhotosPickerItem) async throws -> DraftMedia? {
        let uid = Ulid.make()
        if pick.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
            guard let movie = try await pick.loadTransferable(type: Movie.self) else { return nil }
            let size = (try? FileManager.default.attributesOfItem(atPath: movie.url.path)[.size] as? Int) ?? 0
            guard size <= 100 << 20 else { throw Refused(errorDescription: String(localized: "compose.videoTooLarge")) }
            let ext = movie.url.pathExtension.lowercased()
            guard ["mp4", "mov", "m4v", "webm"].contains(ext) else { throw Refused(errorDescription: String(localized: "compose.videoType")) }
            let name = "\(uid).\(ext == "m4v" ? "mp4" : ext)"
            try? FileManager.default.removeItem(at: LocalStore.mediaURL(name))
            try FileManager.default.moveItem(at: movie.url, to: LocalStore.mediaURL(name))
            let asset = AVURLAsset(url: LocalStore.mediaURL(name))
            var json: JSON = ["uid": JSON(uid), "type": "video", "alt": "", "local": ["uri": JSON(name), "copied": true]]
            if let track = try? await asset.loadTracks(withMediaType: .video).first,
                let size = try? await track.load(.naturalSize), let transform = try? await track.load(.preferredTransform)
            {
                let shown = size.applying(transform)
                json["width"] = JSON(Int(abs(shown.width)))
                json["height"] = JSON(Int(abs(shown.height)))
            }
            let created = try? await asset.load(.creationDate)?.load(.dateValue)
            if let created { json["takenAt"] = JSON(Times.local(created)) }
            var draft = DraftMedia(id: uid, json: json)
            draft.takenAt = created
            return draft
        }
        guard let data = try await pick.loadTransferable(type: Data.self) else { return nil }
        let type = pick.supportedContentTypes.first { $0.conforms(to: .image) }
        let ext = type?.preferredFilenameExtension ?? "jpg"
        let name = "\(uid).\(ext == "jpeg" ? "jpg" : ext)"
        try data.write(to: LocalStore.mediaURL(name))
        var json: JSON = ["uid": JSON(uid), "type": "image", "alt": "", "local": ["uri": JSON(name), "copied": true]]
        var draft = DraftMedia(id: uid, json: json)
        if let source = CGImageSourceCreateWithData(data as CFData, nil),
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        {
            var width = properties[kCGImagePropertyPixelWidth] as? Int ?? 0
            var height = properties[kCGImagePropertyPixelHeight] as? Int ?? 0
            if let orientation = properties[kCGImagePropertyOrientation] as? Int, orientation >= 5 { swap(&width, &height) }
            if width > 0 { json["width"] = JSON(width); json["height"] = JSON(height) }
            if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any], let taken = Self.exifDate(exif) {
                json["takenAt"] = JSON(Times.local(taken.date, zone: taken.zone))
                draft.takenAt = taken.date
            }
            if let gps = properties[kCGImagePropertyGPSDictionary] as? [CFString: Any],
                var lat = gps[kCGImagePropertyGPSLatitude] as? Double, var lng = gps[kCGImagePropertyGPSLongitude] as? Double
            {
                if gps[kCGImagePropertyGPSLatitudeRef] as? String == "S" { lat = -lat }
                if gps[kCGImagePropertyGPSLongitudeRef] as? String == "W" { lng = -lng }
                draft.location = CLLocationCoordinate2D(latitude: lat, longitude: lng)
            }
        }
        draft.json = json
        return draft
    }

    private init(id: String, json: JSON) {
        self.id = id
        self.json = json
    }

    /// When the photo was taken, in the offset EXIF says it was taken at.
    private static func exifDate(_ exif: [CFString: Any]) -> (date: Date, zone: TimeZone)? {
        guard let original = exif[kCGImagePropertyExifDateTimeOriginal] as? String else { return nil }
        let offset = exif[kCGImagePropertyExifOffsetTimeOriginal] as? String
        let zone = offset.flatMap { Formats.offsetZone("0000-00-00T00:00:00" + $0) } ?? .current
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
        formatter.timeZone = zone
        return formatter.date(from: original).map { ($0, zone) }
    }
}

/// A video picked, as a file the picker hands over.
struct Movie: Transferable {
    var url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).\(received.file.pathExtension)")
            try FileManager.default.copyItem(at: received.file, to: copy)
            return Movie(url: copy)
        }
    }
}

private struct DraftThumbnail: View {
    var item: DraftMedia
    var body: some View {
        MediaImage(media: item.item, width: 88)
    }
}

import AVFoundation
