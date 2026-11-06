// swift-tools-version: 6.0

import PackageDescription

// HereKit: what the Apple apps of Here share across iPhone, iPad and Mac. Its Generated folder is
// written from packages/design-tokens and packages/data-spec, never by hand.
let package = Package(
    name: "HereKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "HereKit", targets: ["HereKit"]),
    ],
    targets: [
        .target(name: "HereKit"),
        .testTarget(name: "HereKitTests", dependencies: ["HereKit"]),
    ]
)
