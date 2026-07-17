// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "ChessDomain",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ChessDomain", targets: ["ChessDomain"])],
    dependencies: [
        .package(url: "https://github.com/chesskit-app/chesskit-swift", from: "0.17.0")
    ],
    targets: [
        .target(name: "ChessDomain", dependencies: [
            .product(name: "ChessKit", package: "chesskit-swift")
        ]),
        .testTarget(name: "ChessDomainTests", dependencies: ["ChessDomain"]),
    ]
)
