import Charts
import SwiftUI

// 东西: 设备, what is owned and what was sold, each with what it costs a day; 订阅, what is paid
// by the month, by the year and once; each an entry with its plugin's part, edited in a sheet and
// published with the rest from 发布更改. 车 shows a month of the Zeekr import at a time and is
// read-only: only an import changes the car's data.

struct DevicesView: View {
    @Environment(AppStore.self) private var store
    @State private var editing: Entry?

    var body: some View {
        let devices = store.entries.values.filter { $0.device != nil }.sorted { $0.at > $1.at }
        let owned = devices.filter { $0.device?["sold"] == nil }
        let sold = devices.filter { $0.device?["sold"] != nil }
        let perDay = owned.reduce(0.0) { total, entry in
            let days = max(1, Date().timeIntervalSince(entry.date) / 86400)
            return total + (entry.device?["price"]?.double ?? 0) / days
        }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if devices.isEmpty {
                    Text("things.noDevices").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity).padding(.top, 80)
                } else {
                    AmountHero(caption: String(localized: "things.perDayAll"), amount: Formats.money(perDay)).padding(.horizontal, Space.gutter)
                }
                if !owned.isEmpty {
                    GroupCaption(title: "things.owned")
                    ForEach(owned) { row($0) }
                }
                if !sold.isEmpty {
                    GroupCaption(title: "things.sold")
                    ForEach(sold) { row($0) }
                }
            }
            .padding(.bottom, Space.xl)
        }
        .page(Text("things.devices"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editing = Entry(DeviceEditor.blank()) } label: { Label("action.add", icon: "plus") }
            }
        }
        .sheet(item: $editing) { DeviceEditor(entry: $0) }
    }

    private func row(_ entry: Entry) -> some View {
        let device = entry.device ?? [:]
        let price = device["price"]?.double ?? 0
        let end = device["sold"]?["on"]?.string.flatMap(Times.date) ?? Date()
        let days = max(1, Int(end.timeIntervalSince(entry.date) / 86400))
        let cost = (price - (device["sold"]?["price"]?.double ?? 0)) / Double(days)
        return PlainRow(
            icon: "laptop",
            title: device["name"]?.string ?? "",
            detail: String(localized: "things.daysOwned \(device["category"]?.string ?? "") \(days)") + Formats.gap + String(localized: "things.perDay \(Formats.money(cost))"),
            trailing: .amount(Formats.money(price))
        ) { editing = entry }
    }
}

/// A device: its name, kind, price and day bought, and once sold, when and for how much.
private struct DeviceEditor: View {
    @State var entry: Entry
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var category = ""
    @State private var price = ""
    @State private var bought = Date()
    @State private var isSold = false
    @State private var soldOn = Date()
    @State private var soldFor = ""

    static func blank() -> JSON {
        ["uid": JSON(Ulid.make()), "at": JSON(Times.day(Date())), "parts": [:], "createdAt": JSON(Times.now()), "updatedAt": JSON(Times.now())]
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("things.name", text: $name)
                    TextField("things.category", text: $category)
                    TextField("things.price", text: $price).keyboardType(.decimalPad)
                    DatePicker("things.bought", selection: $bought, displayedComponents: .date)
                }
                Section {
                    Toggle("things.isSold", isOn: $isSold)
                    if isSold {
                        DatePicker("things.soldOn", selection: $soldOn, displayedComponents: .date)
                        TextField("things.soldFor", text: $soldFor).keyboardType(.decimalPad)
                    }
                }
                if store.entry(entry.uid) != nil {
                    Section { Button("action.delete", role: .destructive) { store.delete(entry.uid); dismiss() } }
                }
            }
            .navigationTitle(Text("things.device"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Label("action.cancel", icon: "close") } }
                ToolbarItem(placement: .confirmationAction) {
                    Button { save() } label: { Label("action.save", icon: "check") }.disabled(name.isEmpty || category.isEmpty)
                }
            }
            .onAppear {
                let device = entry.device ?? [:]
                name = device["name"]?.string ?? ""
                category = device["category"]?.string ?? ""
                price = device["price"]?.double.map { $0.formatted(.number.grouping(.never)) } ?? ""
                bought = entry.date
                if let sold = device["sold"] {
                    isSold = true
                    soldOn = sold["on"]?.string.flatMap(Times.date) ?? Date()
                    soldFor = sold["price"]?.double.map { $0.formatted(.number.grouping(.never)) } ?? ""
                }
            }
        }
    }

    private func save() {
        var json = entry.json
        var device = entry.device ?? ["v": 1]
        device["name"] = JSON(name)
        device["category"] = JSON(category)
        device["price"] = JSON(Double(price) ?? 0)
        if device["id"] == nil {
            let slug = name.lowercased().map { $0.isLetter || $0.isNumber ? ($0.isASCII ? String($0) : "") : "-" }.joined()
            device["id"] = JSON(slug.split(separator: "-").joined(separator: "-").isEmpty ? "device-\(Ulid.make().suffix(6).lowercased())" : slug.split(separator: "-").joined(separator: "-"))
        }
        device["sold"] = isSold ? ["on": JSON(Times.day(soldOn)), "price": JSON(Double(soldFor) ?? 0)] : nil
        json["at"] = JSON(Times.day(bought))
        json["parts"]?["device"] = device
        store.save(Entry(json))
        dismiss()
    }
}

struct SubscriptionsView: View {
    @Environment(AppStore.self) private var store
    @State private var editing: Entry?

    var body: some View {
        let all = store.entries.values.filter { $0.subscription != nil }.sorted { $0.at > $1.at }
        let active = all.filter { $0.subscription?["ended"] == nil }
        let monthly = active.reduce(0.0) { total, entry in
            let part = entry.subscription ?? [:]
            let price = part["price"]?.double ?? 0
            switch part["period"]?.string {
            case "month": return total + price
            case "year": return total + price / 12
            default: return total
            }
        }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if all.isEmpty {
                    Text("things.noSubscriptions").textStyle(.body).foregroundStyle(Tokens.tx2).frame(maxWidth: .infinity).padding(.top, 80)
                } else {
                    AmountHero(caption: String(localized: "things.perMonth"), amount: Formats.money(monthly)).padding(.horizontal, Space.gutter)
                }
                ForEach(["month", "year", "once"], id: \.self) { period in
                    let group = active.filter { $0.subscription?["period"]?.string == period }
                    if !group.isEmpty {
                        GroupCaption(title: LocalizedStringKey("things.period.\(period)"))
                        ForEach(group) { entry in row(entry) }
                    }
                }
                let ended = all.filter { $0.subscription?["ended"] != nil }
                if !ended.isEmpty {
                    GroupCaption(title: "things.ended")
                    ForEach(ended) { row($0) }
                }
            }
            .padding(.bottom, Space.xl)
        }
        .page(Text("things.subscriptions"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editing = Entry(DeviceEditor.blank()) } label: { Label("action.add", icon: "plus") }
            }
        }
        .sheet(item: $editing) { SubscriptionEditor(entry: $0) }
    }

    private func row(_ entry: Entry) -> some View {
        let part = entry.subscription ?? [:]
        return PlainRow(
            icon: "subscription",
            title: part["name"]?.string ?? "",
            detail: part["renews"]?.string.map { String(localized: "things.renews \(Formats.day($0))") },
            trailing: .amount(Formats.money(part["price"]?.double ?? 0, part["currency"]?.string ?? "CNY"))
        ) { editing = entry }
    }
}

/// A subscription: its name, price and currency, how often it is paid, when it renews, and once
/// cancelled, when it ended. What pays for it is the owner's alone and never published.
private struct SubscriptionEditor: View {
    @State var entry: Entry
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var price = ""
    @State private var currency = "CNY"
    @State private var period = "month"
    @State private var started = Date()
    @State private var renews = Date()
    @State private var hasRenewal = false
    @State private var autoRenew = true
    @State private var ended = false
    @State private var endedOn = Date()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("things.name", text: $name)
                    TextField("things.price", text: $price).keyboardType(.decimalPad)
                    TextField("account.currency", text: $currency).textInputAutocapitalization(.characters)
                    Picker("things.period", selection: $period) {
                        Text("things.period.month").tag("month")
                        Text("things.period.year").tag("year")
                        Text("things.period.once").tag("once")
                    }
                    DatePicker("things.started", selection: $started, displayedComponents: .date)
                }
                Section {
                    Toggle("things.hasRenewal", isOn: $hasRenewal)
                    if hasRenewal {
                        DatePicker("things.renewsOn", selection: $renews, displayedComponents: .date)
                        Toggle("things.autoRenew", isOn: $autoRenew)
                    }
                    Toggle("things.isEnded", isOn: $ended)
                    if ended { DatePicker("things.endedOn", selection: $endedOn, displayedComponents: .date) }
                }
                if store.entry(entry.uid) != nil {
                    Section { Button("action.delete", role: .destructive) { store.delete(entry.uid); dismiss() } }
                }
            }
            .navigationTitle(Text("things.subscription"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Label("action.cancel", icon: "close") } }
                ToolbarItem(placement: .confirmationAction) {
                    Button { save() } label: { Label("action.save", icon: "check") }.disabled(name.isEmpty)
                }
            }
            .onAppear {
                let part = entry.subscription ?? [:]
                name = part["name"]?.string ?? ""
                price = part["price"]?.double.map { $0.formatted(.number.grouping(.never)) } ?? ""
                currency = part["currency"]?.string ?? "CNY"
                period = part["period"]?.string ?? "month"
                started = entry.date
                if let day = part["renews"]?.string.flatMap(Times.date) { renews = day; hasRenewal = true }
                autoRenew = part["autoRenew"]?.bool ?? true
                if let day = part["ended"]?.string.flatMap(Times.date) { endedOn = day; ended = true }
            }
        }
    }

    private func save() {
        var json = entry.json
        var part = entry.subscription ?? ["v": 1]
        part["name"] = JSON(name)
        part["price"] = JSON(Double(price) ?? 0)
        part["currency"] = JSON(currency.uppercased())
        part["period"] = JSON(period)
        part["renews"] = hasRenewal ? JSON(Times.day(renews)) : nil
        part["autoRenew"] = hasRenewal ? JSON(autoRenew) : nil
        part["ended"] = ended ? JSON(Times.day(endedOn)) : nil
        json["at"] = JSON(Times.day(started))
        json["parts"]?["subscription"] = part
        store.save(Entry(json))
        dismiss()
    }
}

/// 车: a month of the car's statistics at a time, its days, charges and assisted driving, read-only.
struct CarView: View {
    @Environment(AppStore.self) private var store
    @State private var month: String?

    var body: some View {
        let months = store.vehicleMonths.values.sorted { ($0["month"]?.string ?? "") > ($1["month"]?.string ?? "") }
        let shown = months.first { $0["month"]?.string == month } ?? months.first
        List {
            if let shown {
                let report = shown["report"] ?? [:]
                Section {
                    Picker("car.month", selection: Binding(get: { shown["month"]?.string ?? "" }, set: { month = $0 })) {
                        ForEach(months.compactMap { $0["month"]?.string }, id: \.self) { Text(verbatim: $0).tag($0) }
                    }
                    if let distance = report["distanceMeters"]?.double {
                        LabeledContent("car.distance", value: Formats.distance(distance))
                    }
                    if let trips = report["tripCount"]?.int { LabeledContent("car.trips", value: "\(trips)") }
                    if let consumption = report["reportedConsumption"]?["whPer100Km"]?.double {
                        LabeledContent("car.consumption", value: String(format: "%.1f kWh/100km", consumption / 1000))
                    }
                    if let charges = shown["charging"]?["reported"]?["count"]?.int { LabeledContent("car.charges", value: "\(charges)") }
                }
                let days = shown["days"]?.array ?? []
                if !days.isEmpty {
                    Section("car.byDay") {
                        Chart(days.indices, id: \.self) { index in
                            BarMark(
                                x: .value("day", String(days[index]["date"]?.string?.suffix(2) ?? "")),
                                y: .value("km", (days[index]["distanceMeters"]?.double ?? 0) / 1000)
                            )
                            .foregroundStyle(Tokens.accent)
                            .cornerRadius(2)
                        }
                        .frame(height: 160)
                    }
                }
                let sessions = shown["charging"]?["sessions"]?.array ?? []
                if !sessions.isEmpty {
                    Section("car.chargeSessions") {
                        ForEach(sessions.indices, id: \.self) { index in
                            let session = sessions[index]
                            LabeledContent {
                                Text(verbatim: String(format: "%.1f kWh", (session["energyWh"]?.double ?? 0) / 1000))
                            } label: {
                                Text(verbatim: "\(session["type"]?.string ?? "") · \(session["startedAt"]?.string.map { Formats.day(String($0.prefix(10))) } ?? "")")
                            }
                        }
                    }
                }
            } else {
                ContentUnavailableView("car.none", image: "car", description: Text("car.noneDetail")).listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .page(Text("things.car"))
    }
}
