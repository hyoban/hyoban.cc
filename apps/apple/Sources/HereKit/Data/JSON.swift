import Foundation

/// A JSON value, kept whole so a record's fields this app doesn't know travel through untouched,
/// as the spec's loose objects ask.
public enum JSON: Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public init(parsing text: String) throws {
        self = try JSON(any: JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed]))
    }

    public init(data: Data) throws {
        self = try JSON(any: JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    /// From what `JSONSerialization` reads.
    public init(any value: Any) {
        switch value {
        case let value as NSNumber:
            // A boolean is an NSNumber too; only CFBoolean's type says which.
            if CFGetTypeID(value) == CFBooleanGetTypeID() {
                self = .bool(value.boolValue)
            } else {
                self = .number(value.doubleValue)
            }
        case let value as String: self = .string(value)
        case let value as [Any]: self = .array(value.map(JSON.init(any:)))
        case let value as [String: Any]: self = .object(value.mapValues(JSON.init(any:)))
        default: self = .null
        }
    }

    /// What `JSONSerialization` writes.
    public var any: Any {
        switch self {
        case .null: NSNull()
        case let .bool(value): value
        case let .number(value):
            if value.rounded() == value, abs(value) < 9_007_199_254_740_992 { Int64(value) } else { value }
        case let .string(value): value
        case let .array(value): value.map(\.any)
        case let .object(value): value.mapValues(\.any)
        }
    }

    /// The value as compact JSON text, its keys sorted so the same record is always the same text.
    public var text: String {
        if case let .string(value) = self {
            return (try? String(data: JSONSerialization.data(withJSONObject: [value], options: [.withoutEscapingSlashes]), encoding: .utf8))
                .map { String($0.dropFirst().dropLast()) } ?? "\"\""
        }
        guard let data = try? JSONSerialization.data(withJSONObject: any, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }

    public var data: Data { Data(text.utf8) }

    public subscript(key: String) -> JSON {
        get {
            if case let .object(object) = self { return object[key] ?? .null }
            return .null
        }
        set {
            var object: [String: JSON] = if case let .object(object) = self { object } else { [:] }
            if case .null = newValue { object.removeValue(forKey: key) } else { object[key] = newValue }
            self = .object(object)
        }
    }

    public subscript(index: Int) -> JSON {
        if case let .array(array) = self, array.indices.contains(index) { return array[index] }
        return .null
    }

    public var string: String? { if case let .string(value) = self { value } else { nil } }
    public var double: Double? { if case let .number(value) = self { value } else { nil } }
    public var int: Int? { double.map { Int($0) } }
    public var bool: Bool? { if case let .bool(value) = self { value } else { nil } }
    public var array: [JSON]? { if case let .array(value) = self { value } else { nil } }
    public var object: [String: JSON]? { if case let .object(value) = self { value } else { nil } }
    public var isNull: Bool { if case .null = self { true } else { false } }

    /// The value at a dotted path, `parts.text.text`.
    public func at(_ path: String) -> JSON {
        path.split(separator: ".").reduce(self) { $0[String($1)] }
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSON)...) { self = .object(Dictionary(elements, uniquingKeysWith: { $1 })) }
    public init(nilLiteral: ()) { self = .null }
}

public extension JSON {
    init(_ value: String?) { self = value.map(JSON.string) ?? .null }
    init(_ value: Double?) { self = value.map(JSON.number) ?? .null }
    init(_ value: Int?) { self = value.map { .number(Double($0)) } ?? .null }
    init(_ value: Bool?) { self = value.map(JSON.bool) ?? .null }
}
