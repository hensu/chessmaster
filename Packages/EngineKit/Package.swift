// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "EngineKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "EngineKit", targets: ["EngineKit"])],
    targets: [
        .target(
            name: "StockfishCpp",
            exclude: [
                "stockfish/main.cpp",
                "stockfish/Copying.txt",
                "stockfish/incbin/UNLICENCE",
            ],
            publicHeadersPath: "include",
            cxxSettings: [
                .define("NNUE_EMBEDDING_OFF"),
                .define("NDEBUG"),
                .define("IS_64BIT"),
                .define("USE_POPCNT"),
                .define("USE_NEON", to: "8"),
                .define("USE_NEON_DOTPROD"),
                // Engine speed matters even in Debug app builds.
                .unsafeFlags(["-O3", "-fno-exceptions", "-march=armv8.2-a+dotprod", "-w"]),
            ]
        ),
        .target(name: "EngineKit", dependencies: ["StockfishCpp"]),
        .testTarget(name: "EngineKitTests", dependencies: ["EngineKit"]),
    ],
    cxxLanguageStandard: .gnucxx17
)
