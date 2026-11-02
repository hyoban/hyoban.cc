import CoreLocation
import SwiftUI
import UniformTypeIdentifiers

// 足迹: a day's footprints on a map with how far they went, a switch to record, GPX out and in,
// and deleting a day. 足迹记录 sets how recording behaves and asks for the location it needs.

struct FootprintsView: View {
    @Environment(FootprintStore.self) private var footprints
    @Environment(Router.self) private var router
    @State private var day = Date()
    @State private var importing = false
    @State private var message: String?
    @State private var confirmDelete = false

    var body: some View {
        let key = Times.day(day)
        let lines = { _ = footprints.version; return FootprintStore.lines(footprints.samples(day: key)) }()
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HereMap(
                    lines: lines.enumerated().map { MapLine(id: "f\($0.offset)-\($0.element.count)", coordinates: $0.element.map(\.coordinate), style: .footprints, color: Tokens.tx) },
                    frame: MapFrame(id: "\(key)-\(lines.count)", coordinates: lines.flatMap { $0.map(\.coordinate) }, animated: false),
                    showsUser: footprints.recording,
                    insets: UIEdgeInsets(top: 100, left: 30, bottom: 30, right: 30)
                )
                .frame(height: 320)
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: Formats.day(key)).textStyle(.titlePage, strong: true)
                        Text(verbatim: [Formats.distance(FootprintStore.distance(lines)), footprints.recording ? String(localized: footprints.resting ? "footprints.resting" : "footprints.recording") : nil].compactMap { $0 }.joined(separator: Formats.gap))
                            .textStyle(.caption).foregroundStyle(Tokens.tx2)
                    }
                    Spacer()
                    DatePicker(selection: $day, in: ...Date(), displayedComponents: .date) { EmptyView() }.labelsHidden()
                }
                .padding(.horizontal, Space.gutter)
                .padding(.top, Space.l)
                SwitchRow(icon: "footprints", title: String(localized: "footprints.dailyRecording"), detail: String(localized: "footprints.footer"), isOn: Binding(get: { footprints.daily }, set: { footprints.daily = $0 }))
                    .padding(.horizontal, Space.gutter - Space.l)
                    .padding(.top, Space.m)
                GroupCaption(title: "footprints.data")
                if !lines.isEmpty {
                    ShareLink(item: GPXFile(store: footprints, day: key), preview: SharePreview(Text(verbatim: "\(key).gpx"))) {
                        PlainRow(icon: "download", title: String(localized: "footprints.export"))
                    }
                    .buttonStyle(.plain)
                }
                PlainRow(icon: "file-open", title: String(localized: "footprints.import"), detail: message) { importing = true }
                if !lines.isEmpty {
                    Button { confirmDelete = true } label: {
                        HStack(spacing: 0) {
                            Icon("trash", size: 24).frame(width: 60, alignment: .leading)
                            Text("footprints.deleteDay").textStyle(.body)
                            Spacer()
                        }
                        .foregroundStyle(Tokens.danger)
                        .frame(minHeight: 56)
                        .padding(.horizontal, Space.gutter)
                        .contentShape(.rect)
                    }
                    .buttonStyle(PressWash(shape: Rectangle()))
                }
            }
            .padding(.bottom, Space.xl)
        }
        .ignoresSafeArea(edges: .top)
        .page(Text("me.footprints"), large: false)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.push(.footprintSettings) } label: { Label("footprintSettings.title", icon: "settings") }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "gpx") ?? .xml, .xml]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            message = String(localized: "footprints.imported \(footprints.importGPX(url))")
        }
        .confirmationDialog("footprints.deleteConfirm", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("footprints.deleteDay", role: .destructive) { footprints.delete(day: key) }
        }
    }
}

/// A day's raw samples as GPX, written only when it is shared.
struct GPXFile: Transferable {
    var store: FootprintStore
    var day: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: UTType(filenameExtension: "gpx") ?? .xml) { file in
            guard let url = await file.store.gpx(day: file.day) else { throw CocoaError(.fileWriteUnknown) }
            return SentTransferredFile(url)
        }
    }
}

/// 足迹记录: daily recording, the location it needs (always, precise), and what is kept.
struct FootprintSettingsView: View {
    @Environment(FootprintStore.self) private var footprints

    var body: some View {
        let summary = footprints.summary
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                SettingsGroup(footer: "footprints.dailyFooter") {
                    SwitchRow(title: String(localized: "footprints.dailyRecording"), isOn: Binding(get: { footprints.daily }, set: { footprints.daily = $0 }))
                }
                SettingsGroup(title: "footprints.permission", footer: "footprints.permissionFooter") {
                    SettingsRow(title: String(localized: "footprints.permission"), detail: String(localized: permission))
                    if footprints.authorization != .authorizedAlways {
                        SettingsRow(title: String(localized: "footprints.askAlways"), trailing: .chevron) { footprints.requestAlways() }
                    }
                    SettingsRow(title: String(localized: "footprints.openSettings"), trailing: .chevron) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                SettingsGroup(title: "footprints.data", footer: "footprints.privacy") {
                    SettingsRow(title: String(localized: "footprints.samples"), trailing: .value(summary.count.formatted()))
                    if let first = summary.first {
                        SettingsRow(title: String(localized: "footprints.since"), trailing: .value(first.formatted(date: .abbreviated, time: .omitted)))
                    }
                }
            }
            .padding(.vertical, Space.l)
        }
        .page(Text("footprintSettings.title"))
    }

    private var permission: String.LocalizationValue {
        switch footprints.authorization {
        case .authorizedAlways: "footprints.always"
        case .authorizedWhenInUse: "footprints.whenInUse"
        case .denied, .restricted: "footprints.denied"
        default: "footprints.notAsked"
        }
    }
}
