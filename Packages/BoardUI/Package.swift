// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "BoardUI",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "BoardUI", targets: ["BoardUI"])],
    dependencies: [.package(path: "../ChessDomain")],
    targets: [
        .target(
            name: "BoardUI",
            dependencies: ["ChessDomain"],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "BoardUITests", dependencies: ["BoardUI"]),
    ]
)
