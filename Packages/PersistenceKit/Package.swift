// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "PersistenceKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PersistenceKit", targets: ["PersistenceKit"])],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift", from: "7.0.0")
    ],
    targets: [
        .target(name: "PersistenceKit", dependencies: [
            .product(name: "GRDB", package: "GRDB.swift")
        ]),
        .testTarget(name: "PersistenceKitTests", dependencies: ["PersistenceKit"]),
    ]
)
