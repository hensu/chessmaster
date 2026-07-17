// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "PaywallKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PaywallKit", targets: ["PaywallKit"])],
    dependencies: [],
    targets: [
        .target(name: "PaywallKit", dependencies: []),
        .testTarget(name: "PaywallKitTests", dependencies: ["PaywallKit"]),
    ]
)
