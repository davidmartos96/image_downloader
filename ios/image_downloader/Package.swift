// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "image_downloader",
    platforms: [
        .iOS(.v12),
    ],
    products: [
        .library(name: "image-downloader", targets: ["image_downloader"]),
    ],
    targets: [
        .target(
            name: "image_downloader",
            resources: []
        ),
    ]
)
