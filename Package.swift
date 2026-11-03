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
            url: "https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-1.0.0-alpha10/FrameKit.xcframework.zip",
            checksum: "081429bfc2f8b4040ec222b44e1ed7e54ef66d78edfc0290485efbf9bc85216a"
        ),
    ]
)
