// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LumisightDesktopMac",
    defaultLocalization: "zh-Hans",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(
            name: "LumisightDesktopMac",
            targets: ["LumisightDesktopMac"]
        )
    ],
    targets: [
        .executableTarget(
            name: "LumisightDesktopMac",
            path: "Sources"
        )
    ]
)
