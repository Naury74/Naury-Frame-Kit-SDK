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
            url: "https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-1.0.0-alpha13/FrameKit.xcframework.zip",
            checksum: "c025aaa278ad9f86c18d60690bf587d61a00bc858df67e7a19da3cbbd5aab33b"
        ),
    ]
)
