// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "SupabaseSync",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "SupabaseSync", targets: ["SupabaseSync"])],
    dependencies: [
        .package(path: "../ChessDomain"),
        .package(path: "../PersistenceKit"),
        .package(url: "https://github.com/supabase/supabase-swift", from: "2.5.0"),
    ],
    targets: [
        .target(name: "SupabaseSync", dependencies: [
            "ChessDomain",
            "PersistenceKit",
            .product(name: "Supabase", package: "supabase-swift"),
        ]),
        .testTarget(name: "SupabaseSyncTests", dependencies: ["SupabaseSync"]),
    ]
)
