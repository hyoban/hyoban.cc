// Generated from packages/data-spec/src/entry.ts by `vp run data:tables`. Do not edit by hand.

/// The modules the spec defines (`modules` in `packages/data-spec/src/entry.ts`), in its order:
/// what the items page lets the owner turn on or off as one, each owning the plugins whose
/// parts are its and the tables past the entries that are its. Off, a module's rows, pages
/// and records are put away and its background work stops; its data stays on the device for
/// when it is turned on again. `Module(rawValue:)` finds one by its key.
public enum Module: String, CaseIterable, Sendable {
    case footprints = "footprints"
    case ledger = "ledger"
    case things = "things"
    case car = "car"
    case food = "food"
    case checkin = "checkin"
    case occasions = "occasions"

    /// The plugins whose parts are this module's.
    public var plugins: [String] {
        switch self {
        case .footprints: []
        case .ledger: ["bill"]
        case .things: ["device", "subscription"]
        case .car: ["charge"]
        case .food: ["food"]
        case .checkin: ["checkin"]
        case .occasions: ["occasion"]
        }
    }

    /// The tables past the entries that are this module's.
    public var tables: [String] {
        switch self {
        case .footprints: ["tracks"]
        case .ledger: []
        case .things: []
        case .car: ["vehicle_months", "vehicle_assisted", "vehicle_captures"]
        case .food: []
        case .checkin: []
        case .occasions: []
        }
    }
}
