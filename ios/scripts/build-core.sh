#!/bin/sh
# framekit-core를 FrameKitCore.xcframework(Release, 정적)로 만들어 ios/Frameworks에 둔다.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
"$ROOT/gradlew" -p "$ROOT" :framekit-core:assembleFrameKitCoreReleaseXCFramework -q
rm -rf "$ROOT/ios/Frameworks/FrameKitCore.xcframework"
mkdir -p "$ROOT/ios/Frameworks"
cp -R "$ROOT/framekit-core/build/XCFrameworks/release/FrameKitCore.xcframework" "$ROOT/ios/Frameworks/"
