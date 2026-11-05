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
            url: "https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-1.0.0-alpha14/FrameKit.xcframework.zip",
            checksum: "e18dc619511f68fabbcd5ac093287e1548f70672a43b95e3bacc43e62ca1b49b"
        ),
    ]
)
