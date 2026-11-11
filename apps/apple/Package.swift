// swift-tools-version: 6.2

import PackageDescription

// HereKit: what the Apple apps of Here share across iPhone, iPad and Mac: the generated tokens,
// icons and tables (its Generated folder is written from packages/design-tokens and
// packages/data-spec, never by hand) and the copy of the API's records.
//
// `HereMac` is the native Mac app, one full-window map for trying what MapKit can do with the
// API's real records, drawn from the page canvas's Mac boards (M). `scripts/mac-app.sh` wraps the
// executable into `Here.app`.
let package = Package(
    name: "HereKit",
    defaultLocalization: "zh-Hans",
    platforms: [.iOS(.v17), .macOS(.v26)],
    products: [
        .library(name: "HereKit", targets: ["HereKit"]),
        .executable(name: "HereMac", targets: ["HereMac"]),
    ],
    targets: [
        .target(name: "HereKit", linkerSettings: [.linkedLibrary("sqlite3")]),
        .executableTarget(name: "HereMac", dependencies: ["HereKit"], resources: [.process("Resources")]),
        .testTarget(name: "HereKitTests", dependencies: ["HereKit"]),
    ]
)
