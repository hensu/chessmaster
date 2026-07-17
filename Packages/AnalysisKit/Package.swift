// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "AnalysisKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "AnalysisKit", targets: ["AnalysisKit"])],
    dependencies: [.package(path: "../ChessDomain"), .package(path: "../EngineKit")],
    targets: [
        .target(name: "AnalysisKit", dependencies: ["ChessDomain", "EngineKit"]),
        .testTarget(name: "AnalysisKitTests", dependencies: ["AnalysisKit"]),
    ]
)
