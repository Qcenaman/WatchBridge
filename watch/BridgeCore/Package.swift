// swift-tools-version:5.5
import PackageDescription

let package = Package(
    name: "BridgeCore",
    platforms: [.watchOS(.v6), .iOS(.v13), .macOS(.v10_15)],
    products: [.library(name: "BridgeCore", targets: ["BridgeCore"])],
    targets: [
        .target(name: "BridgeCore"),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"]),
    ]
)
