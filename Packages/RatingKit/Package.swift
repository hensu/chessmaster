// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "RatingKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "RatingKit", targets: ["RatingKit"])],
    dependencies: [],
    targets: [
        .target(name: "RatingKit", dependencies: []),
        .testTarget(name: "RatingKitTests", dependencies: ["RatingKit"]),
    ]
)
