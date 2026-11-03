<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.png">
    <img src="docs/images/logo-light.png" alt="FrameKit" width="420">
  </picture>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.naurylab/framekit"><img src="https://img.shields.io/maven-central/v/com.naurylab/framekit?label=Maven%20Central&color=6C5CE7" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/minSdk-26-3DDC84" alt="minSdk 26">
  <img src="https://img.shields.io/badge/iOS-17%2B-000000" alt="iOS 17+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License"></a>
</p>

**FrameKit**은 Android와 iOS 앱에 사진, 영상, 움직이는 이미지, 음성 편집 화면을 붙이는 SDK입니다.
편집기를 열면 고르기부터 편집, 저장까지 화면이 모두 들어 있고, 앱은 결과 파일만 받으면 됩니다. 원본 파일은 바꾸지 않습니다. 두 플랫폼의 기능과 화면, 결과 형식은 같고, 한쪽에서 쓸 수 없는 기능만 빠져 있습니다.

![FrameKit](docs/images/hero.png)

## 지원 환경

| | Android | iOS |
| --- | --- | --- |
| 최소 버전 | Android 8.0 (API 26) | iOS 17 |
| 개발 환경 | Kotlin 2.2 이상, Compose 없이도 사용 | Xcode 16 이상, Swift 5·6 |
| 배포 | Maven Central `com.naurylab:framekit` | Swift Package Manager (컴파일된 `FrameKit.xcframework`) |
| 진입점 | `FrameKitContract` (Activity Result) | `.frameKitEditor` (SwiftUI), `FrameKitViewController` (UIKit) |

## 기능

- **사진**: 자르기와 회전, 보정과 필터, 텍스트와 스티커, 그리기와 도형, 모자이크, 배경 제거를 여러 장에 한 번에 씁니다. 여러 장을 글자 검색이 되는 PDF로 묶습니다.
- **영상**: 여러 클립을 잇고 자르며, 필터와 속도, 배경 음악과 녹음, 텍스트를 넣어 MP4로 저장합니다.
- **움직이는 이미지**: 사진으로 슬라이드쇼를 만들거나 GIF를 고쳐 MP4나 GIF로 저장합니다.
- **음성**: 탭하거나 누르고 말해서 녹음하고, 파형을 보며 나누고 끼워 넣어 M4A로 저장합니다.
- **한 번에 열기**: 고른 파일 종류에 맞는 편집기를 열고, 할 일(편집, PDF, 움직이는 이미지, 원본 보내기)을 묻는 시트도 띄울 수 있습니다. 사진과 영상을 섞어 고르면 항목을 바꿔 가며 편집하고 한 번에 저장합니다.
- **워터마크**: 사진과 영상에 문구를 한 곳, 반복, 대각선, 띠 모양으로 넣고 사용자가 지우지 못하게 잠글 수 있습니다.
- **꾸미기**: 다크와 라이트 테마, 브랜드 색, 모서리, 글꼴을 앱에 맞추고 13개 언어로 보여 줍니다.
- **여러 화면 형태**: 가로 화면과 태블릿에서는 도구를 옆으로 옮깁니다. Android 폴드는 반쯤 접으면 접힌 선을 따라 나누고, iOS는 상태 바가 옆에 있는 기기와 iPad 분할 화면에서도 조작부가 가려지지 않습니다.

## 화면

### Android

윗줄은 다크 테마, 아랫줄은 라이트 테마입니다.

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/images/screen-photo.png" width="200" alt="사진 편집"><br><b>사진 편집</b></td>
    <td align="center" width="25%"><img src="docs/images/screen-scan.png" width="200" alt="문서 스캔"><br><b>문서 스캔</b></td>
    <td align="center" width="25%"><img src="docs/images/screen-gallery.png" width="200" alt="갤러리"><br><b>갤러리</b></td>
    <td align="center" width="25%"><img src="docs/images/screen-video.png" width="200" alt="영상 편집"><br><b>영상 편집</b></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/screen-motion-light.png" width="200" alt="움직이는 이미지"><br><b>움직이는 이미지</b></td>
    <td align="center"><img src="docs/images/screen-record-light.png" width="200" alt="녹음"><br><b>녹음</b></td>
    <td align="center"><img src="docs/images/screen-audio-light.png" width="200" alt="음성 편집"><br><b>음성 편집</b></td>
    <td align="center"><img src="docs/images/screen-watermark-light.png" width="200" alt="워터마크"><br><b>워터마크</b></td>
  </tr>
</table>

폴드에서는 이렇게 보입니다.

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/images/screen-fold-photo.png" width="280" alt="펼친 폴드에서 사진 편집"><br><b>사진 편집</b><br>도구를 옆 패널에</td>
    <td align="center" width="33%"><img src="docs/images/screen-fold-video.png" width="280" alt="반쯤 접은 폴드에서 영상 편집"><br><b>영상 편집</b><br>반쯤 접으면 위는 영상, 아래는 도구</td>
    <td align="center" width="33%"><img src="docs/images/screen-fold-gallery.png" width="280" alt="펼친 폴드에서 갤러리"><br><b>갤러리</b><br>폭에 맞춰 늘어나는 격자</td>
  </tr>
</table>

### iOS

고르기·사진 편집은 다크 테마, 영상·움직이는 이미지·음성은 라이트 테마입니다.

<table>
  <tr>
    <td align="center" width="20%"><img src="docs/images/ios-chooser.png" width="160" alt="iOS 고르기"><br><b>고르기</b></td>
    <td align="center" width="20%"><img src="docs/images/ios-photo.png" width="160" alt="iOS 사진 편집"><br><b>사진 편집</b></td>
    <td align="center" width="20%"><img src="docs/images/ios-video.png" width="160" alt="iOS 영상 편집"><br><b>영상 편집</b></td>
    <td align="center" width="20%"><img src="docs/images/ios-motion.png" width="160" alt="iOS 움직이는 이미지"><br><b>움직이는 이미지</b></td>
    <td align="center" width="20%"><img src="docs/images/ios-audio.png" width="160" alt="iOS 음성 편집"><br><b>음성 편집</b></td>
  </tr>
</table>

상태 바가 옆에 있는 듀오의 덮개 화면에서는 이렇게 보입니다(사진 편집은 다크, 나머지는 라이트).

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/images/ios-duo-photo.png" width="240" alt="듀오에서 사진 편집"><br><b>사진 편집</b><br>오른쪽 상태 바를 피해 배치</td>
    <td align="center" width="33%"><img src="docs/images/ios-duo-video.png" width="240" alt="듀오에서 영상 편집"><br><b>영상 편집</b><br>타임라인과 도구가 화면 폭에 맞춤</td>
    <td align="center" width="33%"><img src="docs/images/ios-duo-mixed.png" width="240" alt="듀오에서 사진·영상 혼합 편집"><br><b>혼합 편집</b><br>아래 줄에서 사진과 영상을 오감</td>
  </tr>
</table>

## 샘플 앱

- **Android**: [framekit-showcase.apk](https://github.com/Naury74/Naury-Frame-Kit-SDK/releases/latest/download/framekit-showcase.apk)를 받으면 소스 없이 모든 편집기를 써 볼 수 있습니다. 기능별 예제와 설정을 바꿔 가며 편집기를 여는 Playground가 들어 있습니다. 처음에는 "출처를 알 수 없는 앱" 설치를 허용해야 합니다.
- **iOS**: 저장소의 `ios/FrameKit.xcodeproj`에서 `FrameKitSample`을 실행합니다. 편집기별 예제, 결과 보기, 테마·언어·저장 형식을 바꾸는 설정 탭이 들어 있습니다.

## 설치

### Android

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.naurylab:framekit:1.0.0-alpha10")
}
```

필요한 편집기만 쓰려면 그 모듈만 넣습니다.

| 모듈 | 내용 |
| --- | --- |
| `framekit` | 전체 편집기와 통합 진입점 `FrameKitContract` |
| `framekit-ui-image` | 사진 편집기 `ImageEditorContract` |
| `framekit-ui-video` | 영상 편집기 `VideoEditorContract` |
| `framekit-ui-motion` | 움직이는 이미지 편집기 `MotionEditorContract` |
| `framekit-ui-audio` | 음성 편집기 `AudioEditorContract` |
| `framekit-picker` | 앱 안 갤러리 `MediaPickerContract` |
| `framekit-scanner` | 카메라 문서 스캐너 `DocumentScanContract` |
| `framekit-segmentation` | 배경 제거 (선택) |
| `framekit-ocr` | PDF 글자 검색 (선택) |

R8 규칙을 따로 넣을 필요는 없습니다.

### iOS

Xcode에서 **File › Add Package Dependencies…**를 고르고 아래 주소를 넣습니다. `FrameKit` 하나에 모든 편집기가 들어 있습니다.

```
https://github.com/Naury74/Naury-Frame-Kit-SDK
```

```swift
// Package.swift
.package(url: "https://github.com/Naury74/Naury-Frame-Kit-SDK", exact: "1.0.0-alpha10")
```

카메라나 마이크를 쓰는 기능이 있으면 앱 Info.plist에 아래 권한 사용 설명을 추가하세요. 사진·영상 고르기는 시스템 선택기를 써서 사진 접근 권한이 필요 없습니다.

| 키 | 필요한 때 |
| --- | --- |
| `NSCameraUsageDescription` | 사진 찍기·영상 녹화 |
| `NSMicrophoneUsageDescription` | 영상 녹화, 음성 녹음 |

## 사용법

### Android

```kotlin
class MainActivity : ComponentActivity() {

    private val editor = registerForActivityResult(FrameKitContract()) { result ->
        when (result) {
            is FrameKitResult.Success -> show(result.output.uri, result.output.mediaType)
            FrameKitResult.Cancelled -> Unit
            is FrameKitResult.Failure -> showError(result.error.code)
        }
    }

    // 사용자가 고른 파일 종류에 맞는 편집기가 열린다.
    fun edit() = editor.launch(FrameKitRequest(EditorInput.Pick(MediaKind.ANY)))
}
```

입력은 이렇게 고릅니다.

```kotlin
EditorInput.UriSource(uri)                              // 파일 하나
EditorInput.Multiple(uris.map(EditorInput::UriSource))  // 파일 여러 개
EditorInput.Pick(MediaKind.IMAGE, maxItems = 10)        // 갤러리에서 사진 최대 10장
EditorInput.Capture(MediaKind.IMAGE)                    // 찍은 뒤 편집. AUDIO면 녹음 뒤 편집
```

도구, 저장 형식, 테마는 요청에 함께 넘깁니다.

```kotlin
FrameKitRequest(
    EditorInput.UriSource(uri),
    image = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP, ImageTool.ROTATE)),
    imageExport = ImageExportConfig(format = ImageFormat.PNG, maxWidth = 2048),
    ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, primaryArgb = 0xFF1E6BFF.toInt()),
)
```

결과 파일은 앱 내부 저장소에 저장되고 `content://` Uri로 옵니다. 다른 앱과 공유할 때는 Intent에 `FLAG_GRANT_READ_URI_PERMISSION`을 붙이고, 필요 없어지면 `FrameKitOutputs.deleteOutput(context, uri)`로 지웁니다. 할 일 고르기 시트, 사진·영상 혼합 편집, 문서 스캔, 화면 없이 처리, 앱 전용 필터와 스티커는 [Android 연동 가이드](docs/integration.md)에 있습니다.

### iOS

SwiftUI는 `.frameKitEditor` 수정자로 띄웁니다.

```swift
import FrameKit

struct ContentView: View {
    @State private var showEditor = false

    var body: some View {
        // 사용자가 고른 파일 종류에 맞는 편집기가 열린다.
        Button("편집") { showEditor = true }
            .frameKitEditor(isPresented: $showEditor, request: FrameKitRequest(input: .pickMultiple(maxItems: 10))) { result in
                switch result {
                case .success(_, let outputs): show(outputs)
                case .cancelled: break
                case .failure(let error): showError(error.code)
                }
            }
    }
}
```

UIKit은 `FrameKitViewController`를 `present`합니다. 편집기가 스스로 닫힌 뒤 completion을 한 번 부릅니다.

```swift
let editor = FrameKitViewController(request: FrameKitRequest(input: .pickMultiple(maxItems: 10))) { result in
    if case .success(_, let outputs) = result { show(outputs) }
}
present(editor, animated: true)
```

입력은 이렇게 고릅니다. 고를 종류는 `mediaKind`(`.image`, `.video`, `.any`, `.audio`)로 정합니다.

```swift
FrameKitRequest(input: .file(url))                                   // 파일 하나
FrameKitRequest(input: .files(urls))                                 // 파일 여러 개
FrameKitRequest(input: .pickMultiple(maxItems: 10), mediaKind: .image) // 사진 선택기에서 사진 최대 10장
FrameKitRequest(input: .capture, mediaKind: .image)                  // 찍은 뒤 편집. .audio면 녹음 뒤 편집
```

도구, 저장 형식, 테마는 요청에 함께 넘깁니다. 테마는 `.dark`, `.light`, `.system`(앱·기기 밝기 설정을 따라 바로 바뀜) 중에서 고릅니다.

```swift
FrameKitRequest(
    input: .file(url),
    image: ImageEditorConfig(enabledTools: [.crop, .rotate]),
    imageExport: ImageExportConfig(format: .png, maxWidth: 2048),
    ui: EditorUIConfig(themeMode: .system, primaryARGB: 0xFF1E_6BFF)
)
```

결과 파일은 앱의 `Application Support/FrameKit/Exports` 안에 저장되고 파일 URL로 옵니다. `ShareLink`나 `UIActivityViewController`로 바로 공유할 수 있고, 필요 없어지면 `FrameKitOutputs.delete(url)`로 지웁니다. 할 일 고르기 시트, 사진·영상 혼합 편집, 앱 전용 필터와 스티커, 편집기별 설정은 [iOS 연동 가이드](docs/ios.md)에 있습니다.

## 주의사항

- 결과는 앱 안에만 저장하고, 위치 정보 같은 민감한 메타데이터는 기본으로 지웁니다.
- 사진과 영상을 섞어 편집할 때는 한 번에 최대 20개까지 열고, 항목마다 결과 하나로 저장합니다. 소리는 다른 종류와 섞을 수 없습니다.

**Android**

- 기본 모듈은 권한을 추가하지 않습니다. 원본을 읽을 권한은 앱이 확보합니다.
- 앱 안 갤러리는 앱이 `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`를 선언했을 때만 열립니다. Google Play 정책상 사진 전체 접근이 핵심 기능인 앱만 선언할 수 있고, 선언하지 않으면 시스템 사진 선택기를 씁니다.
- 녹음은 앱이 `RECORD_AUDIO`를 선언해야 보입니다. 실시간 문서 스캐너는 `CAMERA`를 선언했을 때만 쓰고, 아니면 기기 카메라 앱으로 찍습니다.
- 배경 제거와 글자 검색은 Google Play 서비스가 필요하고 `INTERNET` 권한이 함께 들어옵니다. 처음 쓸 때 모델을 내려받을 수 있습니다.
- 편집 중 앱이 종료되면 편집 내용은 복원되지만 실행 취소 기록은 복원되지 않습니다.
- 앱이 kinetica-ui를 직접 쓴다면 1.0.0-alpha05 이상을 쓰세요.

**iOS**

- SDK에는 권한 사용 설명이 들어 있지 않습니다. 카메라·마이크 사용 설명이 없으면 촬영은 `invalidConfiguration`으로 끝나고 녹음 도구는 보이지 않습니다.
- 앱 확장(공유 확장 등)에서는 쓸 수 없습니다.
- 배경 제거와 PDF 글자 인식은 기기 안 Vision으로 처리해 따로 넣을 모듈이나 내려받을 모델이 없습니다. 배경 제거를 지원하지 않는 기기(시뮬레이터 포함)에서는 도구가 보이지 않습니다.
- 앱이 백그라운드에서 시스템에 의해 종료되면 편집 내용은 남지 않습니다.
- 미리 보기 소리는 앱의 오디오 세션 설정을 따르고, 녹음이 끝나면 앱이 쓰던 오디오 세션 설정으로 돌립니다.

## 문서

| | Android | iOS |
| --- | --- | --- |
| 연동 가이드 | [Android 연동 가이드](docs/integration.md) | [iOS 연동 가이드](docs/ios.md) |
| 설정 | [설정](docs/configuration.md) | [iOS 연동 가이드 › 설정](docs/ios.md#설정) |
| 지원 형식 | [지원 형식](docs/supported-formats.md) | [iOS 연동 가이드 › 지원 형식](docs/ios.md#지원-형식) |
| 화면 없이 처리 | [화면 없이 처리](docs/headless.md) | — |

[변경 이력](docs/release-notes.md)은 두 플랫폼을 함께 적습니다.

## License

Apache License 2.0으로 배포합니다. 자세한 내용은 [LICENSE](LICENSE)를 보세요. 일부 아이콘은 [Material Icons](https://github.com/google/material-design-icons)를 씁니다([NOTICE](NOTICE)).
