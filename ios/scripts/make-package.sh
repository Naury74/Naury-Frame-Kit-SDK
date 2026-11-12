#!/bin/sh
# 배포용 Package.swift를 만든다. build-xcframework.sh로 만든 zip을 GitHub Release에 올린 뒤 실행한다.
# 사용: make-package.sh <버전>   예) make-package.sh 1.0.0-alpha15
# 결과: 저장소 루트 Package.swift (zip URL과 checksum이 들어간 바이너리 패키지)
set -eu
VERSION="${1:?버전을 넘기세요}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHECKSUM_FILE="$ROOT/ios/build/release/checksum.txt"
[ -f "$CHECKSUM_FILE" ] || { echo "error: 먼저 build-xcframework.sh를 실행하세요." >&2; exit 1; }
CHECKSUM="$(cat "$CHECKSUM_FILE")"
URL="https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/download/ios-$VERSION/FrameKit.xcframework.zip"
cat > "$ROOT/Package.swift" <<SWIFT
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
            url: "$URL",
            checksum: "$CHECKSUM"
        ),
    ]
)
SWIFT
echo "Package.swift: $URL"
