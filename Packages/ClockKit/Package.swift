// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "ClockKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ClockKit", targets: ["ClockKit"])],
    dependencies: [],
    targets: [
        .target(name: "ClockKit", dependencies: []),
        .testTarget(name: "ClockKitTests", dependencies: ["ClockKit"]),
    ]
)
