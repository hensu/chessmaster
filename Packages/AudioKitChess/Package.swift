// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "AudioKitChess",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "AudioKitChess", targets: ["AudioKitChess"])],
    targets: [
        .target(
            name: "AudioKitChess",
            resources: [.process("Resources")]
        ),
        .testTarget(name: "AudioKitChessTests", dependencies: ["AudioKitChess"]),
    ]
)
