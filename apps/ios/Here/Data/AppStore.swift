import CryptoKit
import Foundation
import Observation
import UIKit

// The records the screens show: read from the copy first, then brought up to date from the API's
// rows. Beside them, what the phone changed (LocalChanges), which no sync touches and none of
// which reaches the API until 全部发布到网站 sends it all as one change set, after it has been
// reviewed in 发布更改. What of the ledger and the footprints is public goes with it.

/// Something the phone changed that publishing would send.
struct PendingChange: Identifiable, Hashable {
    enum Kind { case added, changed, deleted }
    enum Subject: Hashable { case entry(String), place(String), collection(String), outside(String) }

    var subject: Subject
    var kind: Kind
    var title: String
    var detail: String
    /// What it was before, and is now, for a look at what changed.
    var before: String?
    var after: String?

    var id: Subject { subject }

    /// The ledger's or the footprints', taken back where it was made public rather than here.
    var isOutside: Bool { if case .outside = subject { true } else { false } }
    var isCollection: Bool { if case .collection = subject { true } else { false } }
}

/// What the ledger and the footprints add to a change set: public bills as entries, public spans as tracks.
struct OutsideChanges {
    var entries: [JSON] = []
    var deleted: [String] = []
    var tracks: [JSON] = []
    var deletedTracks: [String] = []
    var pending: [PendingChange] = []
    var published: (AppliedChanges) -> Void = { _ in }
}

@Observable
final class AppStore {
    // The copy's records.
    private var copyEntries: [String: Entry] = [:]
    private var copyPlaces: [String: Place] = [:]
    private var copyCollections: [String: Collection] = [:]
    private(set) var tracks: [String: PublicTrack] = [:]
    private(set) var vehicleMonths: [String: JSON] = [:]

    /// What the phone changed and hasn't published.
    private(set) var local = LocalChanges()

    // What the screens read: the copy with the phone's changes over it.
    private(set) var entries: [String: Entry] = [:]
    private(set) var places: [String: Place] = [:]
    private(set) var collections: [String: Collection] = [:]
    /// The Moments, newest first.
    private(set) var moments: [Entry] = []
    /// Newest first.
    private(set) var trips: [Trip] = []
    /// The entries by day, newest day first.
    private(set) var days: [String: [Entry]] = [:]
    private(set) var dayKeys: [String] = []
    private(set) var firstVisits: [String: Int] = [:]
    private(set) var yearColor: [Int: Int] = [:]
    /// Each published trip's route by its key, from `GET /v2/routes`.
    private(set) var routes: [String: [RouteStretch]] = [:]

    private(set) var loaded = false
    private(set) var syncing = false
    private(set) var error: Error?
    private(set) var revision: Int64 = 0
    private(set) var publishing: PublishProgress?

    /// The ledger's and the footprints' part of publishing, set by them once they have loaded.
    var outside: () -> OutsideChanges = { OutsideChanges() }

    struct PublishProgress: Equatable {
        var done: Int
        var total: Int
        var step: String
    }

    private var copy: CopyStore?

    init() {}

    var hasAPI: Bool { API.current != nil }

    // MARK: Reading

    /// Reads the copy on the phone and the phone's changes.
    func load() {
        guard !loaded else { return }
        do {
            copy = try CopyStore()
        } catch {
            self.error = error
        }
        readCopy()
        local = LocalStore.read()
        if let data = try? Data(contentsOf: Storage.file("routes.json")),
            let kept = try? JSONDecoder().decode([String: [RouteStretch]].self, from: data)
        {
            routes = kept
        }
        rebuild()
        loaded = true
    }

    private func readCopy() {
        guard let copy else { return }
        revision = copy.revision()
        copyEntries = Self.parse(copy.live("entries")) { Entry($0) }
        copyPlaces = Self.parse(copy.live("places")) { Place($0) }
        copyCollections = Dictionary(
            Self.parse(copy.live("collections")) { Collection($0) }.values.map { ($0.id, $0) },
            uniquingKeysWith: { a, _ in a }
        )
        tracks = Self.parse(copy.live("tracks")) { PublicTrack(json: $0) }
        vehicleMonths = Self.parse(copy.live("vehicle_months")) { $0 }
    }

    private static func parse<T>(_ rows: [String: String], _ make: (JSON) -> T) -> [String: T] {
        var out: [String: T] = [:]
        out.reserveCapacity(rows.count)
        for (key, text) in rows {
            if let json = try? JSON.parse(text) { out[key] = make(json) }
        }
        return out
    }

    /// Works out what the screens show from the copy and the phone's changes.
    private func rebuild() {
        var entries = copyEntries
        for uid in local.deleted { entries[uid] = nil }
        for (uid, json) in local.entries { entries[uid] = Entry(json) }
        self.entries = entries

        var places = copyPlaces
        for (id, json) in local.places { places[id] = Place(json) }
        self.places = places

        var collections = copyCollections
        for (key, json) in local.collections { collections[key] = Collection(json) }
        self.collections = collections

        let all = Array(entries.values)
        moments = all.filter(\.isMoment).sorted { ($0.date, $0.uid) > ($1.date, $1.uid) }
        trips = Trips.make(collections: Array(collections.values), entries: all, places: places)
        let byDay = Dictionary(grouping: all.filter { !$0.isMark }, by: \.day)
        days = byDay.mapValues { $0.sorted { ($0.date, $0.order ?? 0, $0.uid) > ($1.date, $1.order ?? 0, $1.uid) } }
        dayKeys = byDay.keys.sorted(by: >)
        firstVisits = Years.firstVisits(moments)
        yearColor = Years.colors(firstVisits.values)
    }

    func entry(_ uid: String) -> Entry? { entries[uid] }
    func trip(_ id: String) -> Trip? { trips.first { $0.id == id } }
    func trip(of entry: Entry) -> Trip? { Trips.of(entry, in: trips) }

    /// The Moments at a place, newest first.
    func moments(at place: String) -> [Entry] { moments.filter { $0.place == place } }

    /// Whether an entry is the phone's own or changed here, so not as the site has it.
    func isUnpublished(_ uid: String) -> Bool { local.entries[uid] != nil }

    // MARK: Syncing

    /// Brings the copy up to the API's rows, `/v2/rows`: every row that changed since the last
    /// refresh, page by page, written over the copy's at once. A database restored to an earlier
    /// revision than the copy's, or another than the copy's, is read again from the start.
    func refresh() async {
        guard let api = API.current, let copy, !syncing else { return }
        syncing = true
        defer { syncing = false }
        do {
            let known = copy.revision()
            var since = known
            var page = try await api.rows(since: since, cursor: nil)
            let database = copy.database()
            let another = database != nil && page.database != nil && page.database != database
            if page.revision < known || another {
                since = 0
                page = try await api.rows(since: 0, cursor: nil)
            }
            let first = page
            if first.revision != known || since != known {
                var rows = page.rows
                while let next = page.next {
                    page = try await api.rows(since: since, cursor: next)
                    rows += page.rows
                }
                let copied = rows.filter { CopyTables.names.contains($0.table) }.map(\.copyRow)
                try copy.apply(copied, revision: first.revision, fresh: since == 0, database: first.database)
                readCopy()
                settle()
                rebuild()
            } else if database == nil, let id = first.database {
                try copy.apply([], revision: known, database: id)
            }
            await refreshRoutes(api)
            error = nil
        } catch {
            self.error = error
        }
    }

    /// Drops the phone's changes the site now has as the phone made them, and deletions already done.
    private func settle() {
        var changes = local
        for (uid, json) in changes.entries where copyEntries[uid]?.json == json { changes.entries[uid] = nil }
        changes.deleted = changes.deleted.filter { copyEntries[$0] != nil }
        for (id, json) in changes.places where copyPlaces[id]?.json == json { changes.places[id] = nil }
        for (key, json) in changes.collections where copyCollections[key]?.json == json { changes.collections[key] = nil }
        if changes != local { keep(changes) }
    }

    private var routesTag: String? {
        get { UserDefaults.standard.string(forKey: "routes-etag") }
        set { UserDefaults.standard.set(newValue, forKey: "routes-etag") }
    }

    private func refreshRoutes(_ api: API) async {
        guard let answer = try? await api.routes(etag: routes.isEmpty ? nil : routesTag) else { return }
        routes = answer.routes
        routesTag = answer.etag
        if let data = try? JSONEncoder().encode(answer.routes) { try? data.write(to: Storage.file("routes.json")) }
    }

    /// Forgets the copy, as when the records are to come from another API. What was written on the
    /// phone stays; what was changed or deleted of the old API's records goes with them.
    func clearCopy() {
        try? copy?.clear()
        routes = [:]
        routesTag = nil
        try? FileManager.default.removeItem(at: Storage.file("routes.json"))
        var changes = local
        changes.entries = changes.entries.filter { $0.value["published"] == nil }
        changes.deleted = []
        changes.collections = [:]
        keep(changes)
        readCopy()
        rebuild()
    }

    // MARK: Changing

    private func keep(_ changes: LocalChanges) {
        local = changes
        LocalStore.write(changes)
    }

    /// Keeps an entry written or changed on the phone until it is published, with the place it made.
    func save(_ entry: Entry, place: (id: String, place: Place)? = nil) {
        var changes = local
        var json = entry.json
        json["updatedAt"] = JSON(Times.now())
        if let stored = copyEntries[entry.uid], Self.same(stored.json, json) {
            changes.entries[entry.uid] = nil
        } else {
            changes.entries[entry.uid] = json
        }
        changes.deleted.remove(entry.uid)
        if let place { changes.places[place.id] = place.place.json }
        keep(changes)
        rebuild()
    }

    /// Two records the same but for when they were last changed.
    private static func same(_ a: JSON, _ b: JSON) -> Bool {
        var a = a, b = b
        a["updatedAt"] = nil
        b["updatedAt"] = nil
        return a == b
    }

    /// Deletes an entry: one only on the phone at once, one the site has once published.
    func delete(_ uid: String) {
        var changes = local
        changes.entries[uid] = nil
        if copyEntries[uid] != nil { changes.deleted.insert(uid) }
        keep(changes)
        sweepMedia()
        rebuild()
    }

    /// Takes back the phone's change to something, or its deletion.
    func revert(_ subject: PendingChange.Subject) {
        var changes = local
        switch subject {
        case .entry(let uid):
            changes.entries[uid] = nil
            changes.deleted.remove(uid)
        case .place(let id): changes.places[id] = nil
        case .collection(let key): changes.collections[key] = nil
        case .outside: return
        }
        keep(changes)
        sweepMedia()
        rebuild()
    }

    /// Keeps a place made or changed on the phone, for Moments to come.
    func savePlace(id: String, _ place: Place) {
        var changes = local
        if copyPlaces[id]?.json == place.json { changes.places[id] = nil } else { changes.places[id] = place.json }
        keep(changes)
        rebuild()
    }

    /// Keeps a trip changed on the phone as its collection.
    func saveCollection(_ collection: Collection) {
        var changes = local
        var json = collection.json
        json["updatedAt"] = JSON(Times.now())
        if let stored = copyCollections[collection.id], Self.same(stored.json, json) {
            changes.collections[collection.id] = nil
        } else {
            changes.collections[collection.id] = json
        }
        keep(changes)
        rebuild()
    }

    private func sweepMedia() {
        let names = Set(local.entries.values.flatMap { entry in
            Entry(entry).media.flatMap { [$0.localFile, $0.localVideoFile].compactMap { $0 } }
        })
        LocalStore.sweep(keeping: names)
    }

    // MARK: Publishing

    /// What publishing would change on the site.
    var pending: [PendingChange] {
        var out: [PendingChange] = []
        for (uid, json) in local.entries {
            let entry = Entry(json)
            let before = copyEntries[uid]
            out.append(PendingChange(
                subject: .entry(uid), kind: before == nil ? .added : .changed,
                title: Self.title(of: entry), detail: Self.dayLabel(entry.at),
                before: before.map(Self.title(of:)), after: Self.title(of: entry)
            ))
        }
        for uid in local.deleted {
            let entry = copyEntries[uid]
            out.append(PendingChange(subject: .entry(uid), kind: .deleted, title: entry.map(Self.title(of:)) ?? uid, detail: entry.map { Self.dayLabel($0.at) } ?? ""))
        }
        for (id, json) in local.places {
            let place = Place(json)
            out.append(PendingChange(subject: .place(id), kind: copyPlaces[id] == nil ? .added : .changed, title: place.name, detail: String(localized: "changes.place")))
        }
        for (key, json) in local.collections {
            let collection = Collection(json)
            out.append(PendingChange(subject: .collection(key), kind: copyCollections[key] == nil ? .added : .changed, title: collection.title, detail: String(localized: "changes.trip")))
        }
        out.sort { $0.detail > $1.detail }
        return out + outside().pending
    }

    var pendingCount: Int { local.entries.count + local.deleted.count + local.places.count + local.collections.count + outside().pending.count }

    private static func title(of entry: Entry) -> String {
        if entry.isMoment { return entry.text.isEmpty ? String(localized: "moment.untitled") : String(entry.text.prefix(80)) }
        if let device = entry.device { return device["name"]?.string ?? "" }
        if let subscription = entry.subscription { return subscription["name"]?.string ?? "" }
        if let bill = entry.bill { return bill["note"]?.string ?? bill["category"]?.string ?? String(localized: "record.bill") }
        return entry.uid
    }

    private static func dayLabel(_ at: String) -> String { String(at.prefix(10)) }

    /// Publishes everything waiting: the new originals first, a few at a time, then one
    /// `POST /v2/changes`, which the API takes whole or not at all. What is published leaves the
    /// phone's store for the site's.
    func publish() async throws {
        guard let api = API.current else { throw NoAPIError() }
        guard publishing == nil else { return }
        let task = UIApplication.shared.beginBackgroundTask(withName: "publish")
        defer {
            publishing = nil
            UIApplication.shared.endBackgroundTask(task)
        }
        let uploads = local.entries.values.flatMap { json in
            Entry(json).media.filter { $0.published == nil }.flatMap { media in
                [(Entry(json).uid, media, UploadPart.image, media.kind == .video ? nil : media.localFile),
                 (Entry(json).uid, media, UploadPart.video, media.kind == .image ? nil : (media.kind == .video ? media.localFile : media.localVideoFile))]
            }.compactMap { item -> (String, MediaItem, UploadPart, String)? in
                guard let file = item.3 else { return nil }
                return (item.0, item.1, item.2, file)
            }
        }
        publishing = PublishProgress(done: 0, total: uploads.count + 1, step: String(localized: "publish.uploading"))
        try await withThrowingTaskGroup(of: Void.self) { group in
            var running = 0
            for (entry, media, part, name) in uploads {
                if running >= 3 {
                    try await group.next()
                    running -= 1
                    publishing?.done += 1
                }
                let file = LocalStore.mediaURL(name)
                let mediaUid = media.uid
                group.addTask {
                    try await Self.upload(api, entry: entry, media: mediaUid, part: part, file: file)
                }
                running += 1
            }
            for try await _ in group { publishing?.done += 1 }
        }

        publishing?.step = String(localized: "publish.sending")
        let outside = outside()
        let entries = local.entries.values.map(Self.sendable) + outside.entries
        let changes: JSON = [
            "places": .object(local.places),
            "entries": .array(entries),
            "deleted": .array((Array(local.deleted) + outside.deleted).map { JSON($0) }),
            "collections": .array(Array(local.collections.values)),
            "deletedCollections": [],
            "tracks": .array(outside.tracks),
            "deletedTracks": .array(outside.deletedTracks.map { JSON($0) }),
        ]
        let applied = try await api.applyChanges(changes)
        publishing?.done += 1
        outside.published(applied)

        // What went out is the site's now: the copy catches up with it from the rows.
        let sent = local
        var changes2 = local
        for uid in sent.entries.keys { changes2.entries[uid] = nil }
        changes2.deleted.subtract(sent.deleted)
        for id in sent.places.keys { changes2.places[id] = nil }
        for key in sent.collections.keys { changes2.collections[key] = nil }
        // Moments naming a provisional place now name the place it became.
        for (uid, json) in changes2.entries {
            if let place = json["place"]?.string, let id = applied.places[place] {
                var json = json
                json["place"] = JSON(id)
                changes2.entries[uid] = json
            }
        }
        keep(changes2)
        await refresh()
        sweepMedia()
        rebuild()
    }

    /// An entry as it goes out: without where its media are on the phone.
    private static func sendable(_ json: JSON) -> JSON {
        var json = json
        guard var items = json["parts"]?["media"]?["items"]?.array else { return json }
        for index in items.indices { items[index]["local"] = nil }
        json["parts"]?["media"]?["items"] = .array(items)
        return json
    }

    private nonisolated static func upload(_ api: API, entry: String, media: String, part: UploadPart, file: URL) async throws {
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        let md5 = Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined()
        if try await api.uploaded(entry: entry, media: media, part: part) == md5 { return }
        try await api.upload(entry: entry, media: media, part: part, file: file, contentType: contentType(file))
    }

    nonisolated static func contentType(_ file: URL) -> String {
        switch file.pathExtension.lowercased() {
        case "heic": "image/heic"
        case "png": "image/png"
        case "webp": "image/webp"
        case "mov": "video/quicktime"
        case "mp4", "m4v": "video/mp4"
        default: "image/jpeg"
        }
    }
}
