#!/bin/sh
# 배포용 FrameKit.xcframework(기기 arm64 + 시뮬레이터 arm64)와 zip, SPM checksum을 만든다.
# core(FrameKitCore)는 정적으로 들어가고 공개 인터페이스에는 드러나지 않는다.
#
# 서명: FRAMEKIT_SIGN_IDENTITY에 인증서 이름(예: "Apple Distribution: …")을 주면 xcframework에 서명한다.
# 결과: ios/build/release/FrameKit.xcframework, FrameKit.xcframework.zip, checksum.txt
set -eu
IOS="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
OUT="$IOS/build/release"

"$IOS/scripts/build-core.sh"
rm -rf "$OUT"
mkdir -p "$OUT"

for platform in "iOS" "iOS Simulator"; do
  name=$(echo "$platform" | tr ' ' '-')
  xcodebuild archive -quiet \
    -project "$IOS/FrameKit.xcodeproj" -scheme FrameKit -configuration Release \
    -destination "generic/platform=$platform" \
    -archivePath "$OUT/$name.xcarchive" \
    -derivedDataPath "$IOS/build/dd-release" \
    SKIP_INSTALL=NO BUILD_LIBRARY_FOR_DISTRIBUTION=YES
done

xcodebuild -create-xcframework \
  -archive "$OUT/iOS.xcarchive" -framework FrameKit.framework \
  -archive "$OUT/iOS-Simulator.xcarchive" -framework FrameKit.framework \
  -output "$OUT/FrameKit.xcframework"

# 공개 인터페이스가 core를 참조하면 호스트 앱에서 빌드가 깨지므로 막는다.
if grep -R "FrameKitCore" "$OUT/FrameKit.xcframework" --include="*.swiftinterface" >/dev/null; then
  echo "error: swiftinterface가 FrameKitCore를 참조합니다." >&2
  exit 1
fi

if [ -n "${FRAMEKIT_SIGN_IDENTITY:-}" ]; then
  codesign --timestamp -s "$FRAMEKIT_SIGN_IDENTITY" "$OUT/FrameKit.xcframework"
fi

(cd "$OUT" && ditto -c -k --sequesterRsrc --keepParent FrameKit.xcframework FrameKit.xcframework.zip)
# SPM checksum은 zip의 SHA-256이다. 저장소 안에서 swift package를 부르면 루트 Package.swift 때문에 .build가 생긴다.
shasum -a 256 "$OUT/FrameKit.xcframework.zip" | cut -d" " -f1 > "$OUT/checksum.txt"
echo "checksum: $(cat "$OUT/checksum.txt")"
