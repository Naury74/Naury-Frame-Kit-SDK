// swift-tools-version:5.9
// FrameKit iOS SDK. 바이너리 XCFramework로만 배포한다.
import PackageDescription

let package = Package(
    name: "FrameKit",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "FrameKit", targets: ["FrameKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "FrameKit",
            url: "https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-1.0.0-alpha09/FrameKit.xcframework.zip",
            checksum: "396e5e754f8d1aa1140b0b83210be1e36aaab1a7d59d2966235d49a34b3f1d31"
        ),
    ]
)
