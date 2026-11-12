// swift-tools-version:5.9
// FrameKit iOS SDK. 바이너리 XCFramework로만 배포한다.
import PackageDescription

let package = Package(
    name: "FrameKit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "FrameKit", targets: ["FrameKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "FrameKit",
            url: "https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-1.0.0-alpha15/FrameKit.xcframework.zip",
            checksum: "a2a11fabd38374af25aac2adb23848e31d34a795c98aec8bce4ecd54560fc06d"
        ),
    ]
)
