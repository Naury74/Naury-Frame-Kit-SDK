# iOS 연동 가이드

iOS 앱에서 사진·영상·움직이는 이미지·음성 편집기를 열고 결과를 받는 방법입니다. 기능과 화면, 결과 형식은 Android와 같고, iOS에서 쓸 수 없는 기능만 빠져 있습니다(맨 아래 [Android와 다른 점](#android와-다른-점)).

## 요구 사항

- iOS 15 이상, Xcode 16 이상. iPhone·iPad 모두 됩니다. 배경 제거(`cutout`)는 iOS 17 이상에서만 보이고, 그 아래에서는 도구가 숨겨집니다.
- iOS 15·16에서는 녹음 시트 등 작은 시트가 큰 시트로 열리고, 글자 입력란이 한 줄입니다(iOS 16 미만).
- Swift 6과 Swift 5 프로젝트 모두에서 쓸 수 있습니다.
- 앱 확장(공유 확장 등)에서는 쓸 수 없습니다. 앱 본체에서 여세요.
- 개인정보 매니페스트(`PrivacyInfo.xcprivacy`)가 들어 있습니다. 추적·데이터 수집은 없고, 파일 날짜(C617.1)와 남은 저장 공간(E174.1)을 읽습니다.
- SDK 안의 Kotlin 코드는 FrameKit 안에 숨겨져 있어, 앱의 다른 Kotlin Multiplatform 프레임워크와 이름이 겹치지 않습니다.

## 설치

Xcode에서 **File › Add Package Dependencies…**를 고르고 아래 주소를 넣은 뒤 `FrameKit` 라이브러리를 앱 타깃에 추가합니다.

```
https://github.com/Naury74/Naury-Frame-Kit-SDK
```

`Package.swift`로 추가한다면:

```swift
dependencies: [
    .package(url: "https://github.com/Naury74/Naury-Frame-Kit-SDK", exact: "1.0.0-alpha15"),
],
targets: [
    .target(name: "MyApp", dependencies: [.product(name: "FrameKit", package: "Naury-Frame-Kit-SDK")]),
]
```

SDK는 컴파일된 바이너리(`FrameKit.xcframework`)로만 배포합니다. 소스나 내부 모듈은 들어 있지 않고, 기기와 시뮬레이터(Apple 실리콘·Intel Mac)에서 모두 빌드됩니다.

### Info.plist

SDK에는 권한 사용 설명이 들어 있지 않습니다. 카메라나 마이크를 쓰는 기능이 있으면 앱 Info.plist에 아래 키를 추가하세요.

| 키 | 필요한 때 | 없으면 |
| --- | --- | --- |
| `NSCameraUsageDescription` | `.capture`로 사진을 찍거나 영상을 녹화할 때 | `.capture`가 `invalidConfiguration`으로 끝남 |
| `NSMicrophoneUsageDescription` | 영상 녹화(`.capture`), 음성 녹음, 영상·움직이는 이미지의 녹음 | 영상 녹화와 음성 `.capture`는 `invalidConfiguration`, 녹음 도구는 숨김 |

사진·영상 고르기는 시스템 사진 선택기를 써서 사진 접근 권한(`NSPhotoLibraryUsageDescription`)이 필요 없습니다.

## 편집기 열기

| 요청 | SwiftUI | UIKit | 여는 편집기 |
| --- | --- | --- | --- |
| `FrameKitRequest` | `.frameKitEditor` | `FrameKitViewController` | 원본 종류에 맞는 편집기(사진·영상·움직이는 이미지·음성) |
| `ImageEditorRequest` | `.frameKitImageEditor` | `FrameKitImageEditorViewController` | 사진 편집기 |
| `VideoEditorRequest` | `.frameKitVideoEditor` | `FrameKitVideoEditorViewController` | 영상 편집기 |
| `MotionEditorRequest` | `.frameKitMotionEditor` | `FrameKitMotionEditorViewController` | 움직이는 이미지 편집기 |
| `AudioEditorRequest` | `.frameKitAudioEditor` | `FrameKitAudioEditorViewController` | 음성 편집기 |

모두 결과는 `FrameKitResult`이고 `success`의 `EditedMedia.mediaType`으로 종류를 구분합니다.

### SwiftUI

```swift
import FrameKit

struct ContentView: View {
    @State private var showEditor = false

    var body: some View {
        Button("편집") { showEditor = true }
            .frameKitEditor(isPresented: $showEditor, request: FrameKitRequest(input: .pickMultiple(maxItems: 10))) { result in
                switch result {
                case .success(let output, let outputs): show(outputs)   // output은 outputs의 첫 항목
                case .cancelled: break
                case .failure(let error): showError(error.code)
                }
            }
    }
}
```

- `isPresented`가 `true`가 되면 전체 화면으로 띄우고, 끝나면 `isPresented`를 `false`로 돌린 뒤 결과를 한 번 넘깁니다.
- 아래로 끌어 닫기는 막혀 있습니다. 닫기는 편집기의 닫기 단추로 합니다.
- 직접 띄우는 방식을 정하려면 `FrameKitEditor(request:onResult:)` 같은 뷰(`FrameKitImageEditor` 등)를 `fullScreenCover` 안에 넣습니다. 이때는 결과를 받은 뒤 앱이 화면을 닫습니다. 결과 전에 화면을 내리면 `cancelled`가 옵니다.

### UIKit

```swift
let editor = FrameKitViewController(request: FrameKitRequest(input: .pickMultiple(maxItems: 10))) { [weak self] result in
    // 편집기가 스스로 닫힌 뒤 한 번 불립니다.
    if case .success(_, let outputs) = result { self?.show(outputs) }
}
present(editor, animated: true)
```

- 화면에 떠 있는 뷰 컨트롤러에서 `present`합니다. 전체 화면(`fullScreen`)으로 뜨고 끌어 닫기는 막혀 있습니다.
- 편집기가 스스로 닫은 뒤 completion을 메인 스레드에서 한 번 부릅니다. 앱이 먼저 `dismiss`하면 `cancelled`가 옵니다.
- 상태 바 색은 편집기 테마를 따릅니다.

### SwiftUI와 UIKit 차이

| | SwiftUI | UIKit |
| --- | --- | --- |
| 띄우기 | `isPresented`를 `true`로 | `present(_:animated:)` |
| 닫기 | SDK가 `isPresented`를 `false`로 돌림 | SDK가 스스로 `dismiss` |
| 결과 시점 | 닫기 시작과 함께 | 닫힘이 끝난 뒤 |
| 결과 전에 앱이 닫으면 | `cancelled` | `cancelled` |

요청·설정·결과 타입은 같습니다.

### 호스트 몸짓으로 닫기

화면 가장자리를 밀어 닫기처럼 앱이 만든 몸짓을 편집기 닫기에 이으려면 `FrameKitEditorControl`을 만들어 넘깁니다. 모든 편집기와 진입점에서 쓸 수 있습니다.

| API | 내용 |
| --- | --- |
| `requestClose()` | 편집기의 닫기 단추와 같은 흐름으로 닫습니다. 닫히면 SDK가 화면을 내리고 결과를 한 번 넘깁니다. 앱은 `dismiss`하지 않습니다 |
| `canDismissInteractively` | 지금 몸짓으로 닫기를 시작해도 되면 `true`. `@Published`라 SwiftUI에서 바로 쓸 수 있습니다 |
| `canDismissInteractivelyPublisher` | 위 값의 바뀜. 구독하면 지금 값을 먼저 보냅니다 |

`requestClose()`가 하는 일:

- 바꾼 내용이 없으면 `cancelled`로 닫습니다. 있으면 버리기 확인을 띄우고, 버리면 `cancelled`입니다.
- 도구를 연 채로 적용하지 않은 내용도 바꾼 것으로 봅니다. 버리기 확인에서 버리면 적용하지 않고 닫고, 계속 편집하면 도구가 그대로 열려 있습니다.
- 녹음 중이면(녹음 화면·녹음 시트) 녹음을 멈추고 그 파일을 지운 뒤 이어서 닫습니다. 녹음한 조각은 더하지 않습니다.
- 사진 추가·음악 고르기·녹음·저장 형식 시트가 떠 있으면 시트를 닫고 이어서 닫습니다. 혼합 편집에서는 항목 편집기의 시트가 떠 있으면 아무것도 하지 않습니다.
- 원본 고르기 화면과 진입점의 고르기 시트에서는 `cancelled`로 닫습니다. 원본을 열지 못한 화면에서는 그 오류로 `failure`입니다(닫기 단추와 같음).
- 저장 중, 저장 실패 안내, 확인 대화상자가 떠 있을 때, 원본을 여는 중, 연결 전(화면이 처음 나타나기 전), 끝난 뒤에는 아무것도 하지 않습니다.

`canDismissInteractively`는 위에서 아무것도 하지 않는 경우와 SDK 시트가 떠 있을 때 `false`입니다. 녹음 중이거나 바꾼 내용이 있어도 `true`입니다. 결과는 닫기·저장이 겹쳐도 한 번만 옵니다.

UIKit:

```swift
let control = FrameKitEditorControl()
let editor = FrameKitImageEditorViewController(request: request, control: control) { result in
    // 편집기가 스스로 닫힌 뒤 한 번 불립니다.
}
// editor.control로도 읽을 수 있습니다.
let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(edgePan(_:)))
edge.edges = .left
editor.view.addGestureRecognizer(edge)
present(editor, animated: true)

@objc private func edgePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
    guard gesture.state == .ended, gesture.translation(in: gesture.view).x > 80 else { return }
    if control.canDismissInteractively { control.requestClose() }
}
```

SwiftUI:

```swift
@StateObject private var control = FrameKitEditorControl()

Button("편집") { showEditor = true }
    .frameKitEditor(isPresented: $showEditor, request: request, control: control) { result in ... }

// fullScreenCover 안에 직접 넣을 때
FrameKitImageEditor(request: request, control: control) { result in ... }
    .overlay(alignment: .leading) {
        Color.clear.frame(width: 12).contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 20).onEnded { value in
                if value.translation.width > 80 { control.requestClose() }
            })
            .allowsHitTesting(control.canDismissInteractively)
    }
```

- 컨트롤 하나는 한 번에 편집기 하나에만 연결됩니다. 끝난 뒤 새 편집기에 다시 넘기면 그 편집기에 연결됩니다.
- 몸짓 영역이 편집기 왼쪽 위 닫기 단추를 가리지 않게 좁게 둡니다.

## 입력

| `EditorInput` | 사용 | 권한 |
| --- | --- | --- |
| `.file(URL)` | 앱이 읽을 수 있는 파일 하나. 보안 범위 URL이면 SDK가 접근을 열고 닫음 | 없음 |
| `.files([URL])` | 파일 여러 개. 순서가 편집·저장 순서 | 없음 |
| `.data(Data)` | 이미지 데이터. 사진·움직이는 이미지 편집기만 | 없음 |
| `.pick` | 시스템 사진 선택기로 하나. 음성은 파일 앱 | 없음 |
| `.pickMultiple(maxItems:)` | 여러 개(1~100). 고른 순서가 편집·저장 순서 | 없음 |
| `.capture` | 카메라로 찍거나 녹화한 뒤 편집. 음성은 SDK 녹음 화면 | [Info.plist](#infoplist) |

`FrameKitRequest`에서 `.pick`·`.pickMultiple`·`.capture`로 고를 종류는 `mediaKind`로 정합니다: `.image`, `.video`, `.any`(사진과 영상, 기본), `.audio`. `.capture`에는 `.any`를 쓸 수 없습니다.

### 진입점 조합

| 하고 싶은 일 | request |
| --- | --- |
| 사진 한 장 편집 | `FrameKitRequest(input: .file(url))` |
| 영상 한 개 편집 | `FrameKitRequest(input: .file(videoURL))` — 형식을 보고 영상 편집기가 열림 |
| 사진 여러 장을 한 번에 편집 | `FrameKitRequest(input: .files(urls))` |
| 영상 여러 개를 이어 붙여 편집 | `FrameKitRequest(input: .files(videoURLs))` |
| 사진·영상을 섞어 각각 편집 | `FrameKitRequest(input: .files(mixedURLs))` — 혼합 편집 화면 |
| 사용자가 사진이나 영상을 직접 고름 | `FrameKitRequest(input: .pickMultiple(maxItems: 10))` |
| 사진만 최대 10장 고르게 함 | `FrameKitRequest(input: .pickMultiple(maxItems: 10), mediaKind: .image)` |
| 방금 찍은 사진 편집 | `FrameKitRequest(input: .capture, mediaKind: .image)` |
| 방금 녹화한 영상 편집 | `FrameKitRequest(input: .capture, mediaKind: .video)` |
| 여러 장을 PDF 한 개로 | 위 입력 + `imageExport: ImageExportConfig(format: .pdf)` |
| 새로 녹음하고 편집 | `FrameKitRequest(input: .capture, mediaKind: .audio)` |
| 녹음만 하고 바로 받기 | 위 입력 + `audio: AudioEditorConfig(editAfterRecording: false)` |
| 소리 파일을 고르게 해서 편집 | `FrameKitRequest(input: .pickMultiple(maxItems: 5), mediaKind: .audio)` |

- 여러 개를 넘기면 사진 편집기는 아래쪽 목록에서 한 장씩 골라 편집하고, 영상 편집기는 고른 순서대로 클립을 이어 붙입니다.
- 편집기 상한은 `ImageEditorConfig.maxImageCount`(기본 20, 최대 100), `VideoEditorConfig.maxClipCount`(기본 10, 최대 20), `MotionEditorConfig.maxImageCount`(기본 20, 최대 50), `AudioEditorConfig.maxSourceCount`(기본 10, 최대 20)입니다. `.files`가 상한보다 많으면 앞에서부터 상한까지만 열고 화면에 알림을 보이며, `.pickMultiple`의 `maxItems`도 상한으로 줄어듭니다. 혼합 편집도 20개를 넘으면 같게 알립니다.
- 고른 파일 가운데 일부를 열 수 없으면 나머지만 열고 알림을 보입니다. 모두 열 수 없으면 오류 화면을 보이고, 닫으면 그 오류(`failure`)로 끝납니다.
- 혼합 편집에서 열지 못한 항목은 그 항목의 닫기로 목록에서 뺄 수 있습니다. 그대로 저장하면 그 항목을 고르고 다시 시도할 수 없는 오류(`recoverable == false`)를 보입니다.
- 사진과 영상을 섞어 넘기거나 고르면 `mixedSelection`을 따릅니다.
  - `.edit`(기본): 혼합 편집 화면을 엽니다. 아래 줄에서 항목을 누르면 그 종류의 편집 화면으로 바뀌고, 항목마다 편집 내용을 따로 기억하며, 저장은 한 번에 해서 고른 순서대로 `outputs`를 돌려줍니다. 최대 20개입니다. 영상 항목에서는 일반 영상 편집기처럼 나누고, 순서를 바꾸고, 클립을 더할 수 있으며, 항목마다 결과 하나로 저장합니다.
  - `.reject`: `chooser`가 `.auto`면 `unsupportedOperation`으로 끝나고, `.ask`면 원본 보내기만 보입니다.
  - 소리는 다른 종류와 섞을 수 없습니다(`unsupportedOperation`, 고르기 시트에서는 원본 보내기만).
- 원격 URL은 받지 않습니다. 내려받기를 마친 파일 URL을 넘기세요.

### 카메라

`.capture`는 시스템 카메라 화면을 띄웁니다. SDK가 카메라를 직접 다루지 않으므로 별도 카메라 라이브러리가 필요 없습니다.

- 찍은 파일은 SDK 임시 폴더에 두고 편집이 끝나면 지웁니다.
- 카메라 권한을 아직 묻지 않았으면 촬영 화면 전에 묻습니다.
- 카메라가 없는 기기(시뮬레이터 등)는 `cameraUnavailable`, 사용자가 카메라 권한을 거부했으면 `permissionDenied`(`suggestedAction == .openSettings`), 촬영을 취소하면 `cancelled`입니다.
- 소리 녹음(`mediaKind: .audio`)에서 마이크 권한을 거부했으면 `permissionDenied`(`.openSettings`)로 끝납니다.

## 자동으로 열기와 고르기 시트

`FrameKitRequest`는 원본을 받은 뒤 편집기를 여는 방식을 `chooser`로 고릅니다.

| 값 | 동작 |
| --- | --- |
| `.auto`(기본) | 원본 종류를 보고 바로 엽니다. 사진→사진 편집, 영상→영상 편집, 움직이는 GIF·WebP·PNG 한 장→움직이는 이미지 편집, 소리→음성 편집, 섞인 사진·영상→혼합 편집 |
| `.ask` | 원본 미리보기와 함께 "어떻게 할까요?" 시트를 띄워 사용자가 고릅니다 |

```swift
// 사진·영상을 고르게 하고, 고른 뒤 무엇을 할지 묻기
FrameKitRequest(input: .pickMultiple(maxItems: 10), chooser: .ask)

// 앱 앨범에서 이미 고른 파일을 넘기고 무엇을 할지 묻기
FrameKitRequest(input: .files(urls), chooser: .ask)

// 고를 수 있는 할 일 제한(사진 편집과 그대로 보내기만)
FrameKitRequest(input: .files(urls), chooser: .ask, chooserActions: [.editPhoto, .sendOriginal])
```

원본에 맞는 할 일만 보입니다.

| 원본 | 선택지 |
| --- | --- |
| 사진 한 장·여러 장 | 사진 편집(여러 장이면 한 장씩) · PDF로 묶기 · 움직이는 이미지 만들기 · 편집 없이 보내기 |
| 움직이는 GIF·WebP·PNG 하나 | 움직이는 이미지 편집 · 첫 장면만 사진으로 편집 · 편집 없이 보내기 |
| 영상 | 영상 편집 · 편집 없이 보내기 |
| 소리 파일 | 소리 편집 · 편집 없이 보내기 |
| 사진과 영상이 섞임 | 하나씩 편집 · 편집 없이 보내기 |
| 소리가 다른 종류와 섞임 | 편집 없이 보내기 |

- 선택지가 하나만 남으면 묻지 않고 바로 실행합니다.
- 시트를 닫으면 `cancelled`입니다. `.capture`는 시트 없이 바로 엽니다.
- **편집 없이 보내기**는 원본을 결과 폴더로 복사해 `isOriginal == true`로 돌려줍니다.
- 움직이는 GIF 한 장도 사진 편집기로 열려면 `openAnimatedInMotionEditor: false`를 씁니다.

## 설정

`FrameKitRequest`의 `image`·`imageExport`·`video`·`videoExport`·`motion`·`motionExport`·`audio`·`audioExport`·`ui`·`catalog`는 각 편집기 설정 그대로 쓰입니다.

```swift
FrameKitRequest(
    input: .pickMultiple(maxItems: 10),
    image: ImageEditorConfig(enabledTools: [.crop, .rotate, .adjust, .filter, .text], maxImageCount: 20),
    imageExport: ImageExportConfig(format: .jpeg, quality: 92, maxWidth: 2048, metadataPolicy: .safe),
    video: VideoEditorConfig(maxTimelineDuration: 60, maxClipCount: 5),
    videoExport: VideoExportConfig(maxShortSide: 720),
    ui: EditorUIConfig(themeMode: .system, primaryARGB: 0xFF1E_6BFF, cornerRadius: 22, localeIdentifier: "ko")
)
```

- `enabledTools`의 순서가 도구 줄의 순서입니다. 사진 도구: `crop`, `rotate`, `cutout`(배경 제거), `adjust`, `filter`, `text`, `sticker`, `draw`, `privacy`(가리기), `watermark`.
- `enabledTools`를 비우면 도구 없이 보고 저장만 하는 화면이 됩니다.
- `allowUndo`·`allowRedo`로 실행 취소·다시 실행 버튼을 숨길 수 있습니다(`allowRedo`만 켤 수는 없음).
- 범위를 벗어난 설정은 `request.validate()`로 미리 확인할 수 있습니다. 그대로 열면 편집기가 `invalidConfiguration`으로 끝납니다.

### 사진 저장

| `ImageExportConfig` | 기본값 | 내용 |
| --- | --- | --- |
| `format` | `.jpeg` | `.jpeg`, `.png`, `.heic`, `.pdf` |
| `quality` | 92 | 0~100. PNG는 무시 |
| `maxWidth`·`maxHeight` | 없음 | 결과 크기 상한(px). 원본보다 키우지 않음 |
| `maxOutputPixels` | 16 000 000 | 결과 픽셀 수 상한 |
| `metadataPolicy` | `.safe` | `.safe`는 촬영 시각·카메라 설정만, `.none`은 아무것도, `.all`은 위치 정보까지 옮김. 방향은 항상 '정상'으로 저장 |
| `jpegBackgroundARGB` | 검정 | JPEG에서 투명 영역(배경 제거 등)을 채울 색 |
| `pdf` | `PdfOptions()` | 아래 PDF |

PDF는 `PdfOptions`로 정합니다.

| `PdfOptions` | 기본값 | 내용 |
| --- | --- | --- |
| `pageSize` | `.a4` | `.fitImage`(사진 크기), `.a4`, `.a5`, `.letter`, `.legal` |
| `orientation` | `.auto` | `.auto`는 사진이 가로로 길면 가로 쪽 |
| `marginMm` | 10 | 네 변 여백(0~50mm) |
| `dpi` | 200 | 쪽 이미지 해상도(72~600) |
| `combinePages` | `true` | `false`면 장마다 PDF 하나 |
| `recognizeText` | `true` | 기기 안 Vision으로 글자를 인식해 보이지 않는 글자 레이어를 넣음(검색·복사 가능). 한국어·영어·일본어·중국어 |

### 테마와 모양

| `EditorUIConfig` | 기본값 | 내용 |
| --- | --- | --- |
| `themeMode` | `.dark` | `.dark`, `.light`, `.system` |
| `primaryARGB` | 보라 | 강조색(버튼·선택 표시). 위에 올라가는 글자색은 명암비를 보고 자동으로 고름 |
| `cornerRadius` | 14 | 버튼·패널 모서리(0~32pt) |
| `palette` | 없음 | 배경·표면·버튼·글자·흐린 글자·캔버스·강조색 위 글자 7가지 색. 비운 항목은 테마 기본값 |
| `uiFontId` | 없음 | `catalog.fonts`의 글꼴 id. 편집기 글자에 씀 |
| `enableHaptics` | `true` | 진동 피드백 |
| `showExportProgress` | `true` | `false`면 저장 단계 이름과 진행률을 숨김 |
| `localeIdentifier` | 없음 | 아래 [언어](#언어) |

- `.dark`·`.light`는 기기 설정과 상관없이 그 테마로 엽니다.
- `.system`은 편집기를 띄운 화면의 밝기 설정을 따릅니다. 앱이 `overrideUserInterfaceStyle`로 밝기를 고정했으면 그 값을, 아니면 기기 설정을 따르고, 편집 중 설정을 바꾸면 바로 바뀝니다.
- 상태 바 글자색도 테마에 맞춥니다.
- iOS 26 이상에서는 버튼·상단 바 바탕에 시스템 Liquid Glass를 쓰고, 그 아래 버전에서는 같은 자리에 흐림 재질을 씁니다. 강조색 버튼은 `primaryARGB`를 유리 색조로 씁니다.
- `EditorPalette().contrastWarnings(themeMode:primaryARGB:)`로 명암비가 낮은 색 조합을 미리 확인할 수 있습니다.
- 글자 크기는 시스템 글자 크기 설정을 따르고, 조작부가 가려지지 않도록 접근성 1단계 크기까지만 커집니다.

## 앱 전용 필터, 스티커, 글꼴

```swift
FrameKitRequest(
    input: .pickMultiple(maxItems: 10),
    catalog: EditorCatalog(
        filters: [CustomFilter(id: "brand.warm", label: "Warm", temperature: 0.4)],
        stickers: [CustomSticker(id: "logo", label: "Logo", file: Bundle.main.url(forResource: "logo", withExtension: "png")!)],
        fonts: [CustomFont(id: "brand", label: "Brand", file: Bundle.main.url(forResource: "Brand", withExtension: "ttf")!)]
    )
)
```

- 스티커는 PNG·HEIC·JPEG(긴 변 512px로 읽음), 글꼴은 TTF·OTF입니다. 글꼴은 이 앱 안에서만 등록합니다.
- 편집 결과에는 id만 남으므로 앱 버전이 바뀌어도 id를 유지하세요.
- `showDefaultFilters`·`showDefaultStickers`·`showDefaultFonts`를 `false`로 하면 기본 목록을 숨깁니다.

## 결과

`FrameKitResult`는 한 번의 실행에 정확히 한 번 전달됩니다.

| 결과 | 언제 |
| --- | --- |
| `.success(output:outputs:)` | 저장이 끝났을 때. `outputs`는 만들어진 모든 결과(여러 장을 사진마다 저장하면 여러 개), `output`은 첫 번째 결과 |
| `.cancelled` | 선택기를 닫았거나, 변경 없이 닫았거나, 변경을 버리고 닫았을 때 |
| `.failure(EditorError)` | 잘못된 request, 열 수 없는 원본 등 편집을 시작할 수 없을 때 |

저장 중 취소는 편집 화면으로 돌아갈 뿐 `cancelled`를 보내지 않습니다. 저장 실패도 편집 화면에서 다시 시도할 수 있으므로 바로 `failure`를 보내지 않습니다.

`EditedMedia`:

| 필드 | 설명 |
| --- | --- |
| `url` | 앱 `Application Support/FrameKit/Exports` 안 파일 URL |
| `mediaType` | `.image`, `.video`, `.document`(PDF), `.animatedImage`(GIF), `.audio` |
| `width`, `height` | 실제 인코딩된 픽셀 크기. 소리는 0 |
| `durationMs` | 영상·움직이는 이미지·소리 길이. 사진·PDF는 `nil` |
| `mimeType` | `image/jpeg`, `image/png`, `image/heic`, `image/gif`, `application/pdf`, `video/mp4`, `audio/mp4` |
| `pageCount` | PDF의 쪽 수. 그 밖에는 `nil` |
| `recognizedText` | PDF 글자 레이어의 글자 |
| `fileSize` | 바이트 |
| `warnings` | `colorSpaceConvertedToSRGB`, `hdrGainMapDropped`, `hdrConvertedToSDR`, `frameRateReduced`, `textRecognitionSkipped` |
| `isOriginal` | 편집 없이 보낸 원본이면 `true` |

### 오류 처리

`EditorError`는 `code`, `recoverable`, `suggestedAction`, `diagnosticId`를 가집니다. 화면 문구는 들어 있지 않으므로 앱이 코드별 문구를 정합니다. `diagnosticId`는 SDK 로그(하위 시스템 `com.naurylab.framekit`)에 같이 남고, 파일 이름이나 경로는 기록하지 않습니다.

| 코드 | 대표 원인 | 권장 행동 |
| --- | --- | --- |
| `invalidConfiguration` | request 값이 범위를 벗어남, Info.plist 설명 없음 | 개발자 수정 |
| `invalidSource` | 형식·종류가 편집기와 맞지 않음 | 다른 파일 선택 |
| `permissionDenied` | 카메라·마이크 권한 없음(`suggestedAction == .openSettings`), 파일을 읽을 권한 없음(`.chooseAnotherSource`) | 설정에서 허용 또는 다시 선택 |
| `sourceUnavailable` | 지워짐·옮겨짐 | 다시 선택 |
| `sourceTooLarge` | 2억 5천만 픽셀 초과, 100MB 넘는 움직이는 이미지 | 다른 파일 선택 |
| `unsupportedFormat` | iOS가 읽지 못하는 형식(OGG·Opus 등) | 다른 파일 선택 |
| `decodeFailed` | 손상되었거나 빈 파일. 같은 파일로 다시 해도 안 되므로 `recoverable == false` | 다른 파일 선택 |
| `unsupportedOperation` | 사진·영상을 섞고 `.reject`, 소리를 다른 종류와 섞음 | 입력 수정 |
| `cameraUnavailable` | 카메라가 없음 | 사진 선택으로 대체 |

저장 단계 오류(`insufficientMemory`, `insufficientStorage`, `encodeFailed`, `outputWriteFailed`)는 편집 화면의 다시 시도 창으로 처리됩니다. 저장 공간이 모자라면 `insufficientStorage`, 앱이 오래 뒤로 가 있거나 미디어 서비스가 끊겨 멈추면 `outputWriteFailed`(다시 시도)입니다. `resultUnavailable`은 Android와 코드를 맞추려고 둔 값이며 iOS에서는 나오지 않습니다. 여러 장을 저장하다 한 장이라도 실패하면 그 호출에서 만든 파일을 모두 지우므로 일부만 남지 않습니다.

## 결과 파일 관리

- 결과 파일은 앱 소유이며 SDK가 지우지 않습니다.
- 필요 없어진 결과는 `FrameKitOutputs.delete(url)`로 지웁니다. FrameKit이 만든 파일이 아니면 `false`를 돌려주고 아무것도 지우지 않습니다.

### 다른 앱에 공유

```swift
// SwiftUI
ShareLink(item: media.url)

// UIKit
present(UIActivityViewController(activityItems: [media.url], applicationActivities: nil), animated: true)
```

## 언어

편집기 문구는 13개 언어를 제공합니다: 한국어·영어·일본어·중국어(간체·번체)·베트남어·태국어·인도네시아어·러시아어·스페인어·포르투갈어(브라질)·프랑스어·독일어. 그 밖의 언어는 영어로 보입니다.

`EditorUIConfig.localeIdentifier`가 `nil`이면 앱 언어를, 언어 코드(`"ko"`, `"en"`, `"ja"`, `"zh-Hans"`, `"zh-Hant"`, `"vi"`, `"th"`, `"id"`, `"ru"`, `"es"`, `"pt-BR"`, `"fr"`, `"de"`)면 그 언어를 씁니다. 앱의 언어 설정은 바뀌지 않습니다.

## 화면 크기와 앱 상태

- 편집기는 전체 화면으로 열리고 safe area를 따릅니다. 노치, Dynamic Island, 상태 바가 옆에 있는 기기(iPhone Duo 등), 가로 화면, iPad 분할 화면에서도 조작부가 가려지지 않고, 크기가 바뀌어도 편집 상태가 그대로 유지됩니다.
- 앱이 백그라운드에 있다 돌아와도 편집 상태와 실행 취소 기록이 그대로입니다.
- 편집 중 앱이 종료된 뒤 같은 편집기를 같은 입력으로 다시 열면 마지막으로 **적용한** 편집을 복원하고 "이전 편집을 복원했습니다" 안내를 띄웁니다.
  - 실행 취소 기록과 열려 있던 도구의 적용하지 않은 변경은 복원하지 않습니다. 원래 원본과 비교한 변경 여부는 유지되므로 닫을 때 확인을 묻습니다.
  - 입력이 같다는 것은 `.file`·`.files`는 같은 경로, `.data`는 같은 데이터, `.pick`·`.pickMultiple`·`.capture`는 같은 종류라는 뜻입니다. 고르거나 찍은 원본은 SDK가 보관하므로 선택기·카메라를 다시 열지 않습니다.
  - `.file`·`.files`는 파일 크기·수정 시각을, `.data`는 내용을 대조해 바뀌었으면 복원하지 않고 처음부터 엽니다.
  - 진입점(`FrameKitViewController`·`FrameKitEditor`)은 고른 편집기를 바로 엽니다. 사진과 영상을 함께 편집한 경우는 복원하지 않습니다.
  - 저장 중에 종료됐다면 결과 파일은 만들어지지 않으므로 다시 저장합니다.
- 편집 값과 원본 사본은 Application Support/FrameKit/Sessions에 둡니다. 편집기가 결과(성공·취소·실패)를 보내면 바로 지우고, 중단된 세션은 7일이 지나면 다음 편집기 실행 때 지웁니다.
- 저장하는 동안 화면이 꺼지지 않게 하고, 끝나면 앱이 정해 둔 값으로 돌립니다.
- 저장 중에 앱이 잠시 뒤로 가도 시스템이 허락하는 동안 저장을 이어 갑니다.
- 녹음 중 전화 등으로 끊기면 일시 정지 상태가 되고, 이어 녹음은 사용자가 고릅니다. 녹음이 끝나면 앱의 오디오 세션 카테고리·모드·옵션을 돌려 두고, 앱이 세션을 쓰고 있지 않았을 때만 세션을 끕니다.
- 편집기를 띄운 뒤 그 위에 다른 화면을 띄우거나 탭을 바꿔도 편집은 끝나지 않습니다. 앱이 편집기를 내리거나 내비게이션에서 빼면 `cancelled`로 끝나고, 그때까지 만든 임시 파일과 혼합 편집에서 저장해 둔 파일은 지웁니다.
- 앱이 저장 중에 종료되어 남은 임시 파일은 다음에 편집기를 열 때 지웁니다(24시간 지난 것).

## 움직이는 이미지

사진 여러 장으로 움직이는 이미지를 만들거나, 움직이는 GIF·WebP·PNG를 편집합니다.

```swift
// 사진을 골라 슬라이드쇼 만들기
.frameKitMotionEditor(isPresented: $showMotion, request: MotionEditorRequest(input: .pickMultiple(maxItems: 20))) { result in
    if case .success(let output, _) = result { show(output) }  // MP4면 .video, GIF면 .animatedImage
}

// 움직이는 GIF 하나 편집(처음 저장 형식은 GIF)
MotionEditorRequest(input: .file(gifURL), export: MotionExportConfig(format: .gif))
```

- 원본이 하나이고 움직이면 그 이미지를 편집하고, 아니면 사진들로 슬라이드쇼를 만듭니다. 슬라이드쇼에 넣은 움직이는 이미지는 첫 프레임만 씁니다.
- 원본: `.pick`·`.pickMultiple`, `.file`·`.files`, `.data`. `.capture`는 쓸 수 없습니다.
- 도구(`MotionTool`): 움직임(사진마다 확대·이동), 전환(페이드·밀기·줌), 길이(사진당 시간, 움직이는 원본은 구간·속도), 반복(반복·부메랑), 화면 비율(채우기·맞추기, 배경 색), 보정, 필터, 텍스트·스티커(나타나기·팝·올라오기·통통·흔들기·두근두근), 소리(음악·음성 파일, 녹음).
- 저장할 때 사용자가 형식과 크기를 고릅니다. `MotionExportConfig.formats`로 고를 수 있는 형식을, `format`·`size`로 처음 선택값을 정합니다.

| 형식 | 크기(작게·보통·크게, 긴 변) | 특징 |
| --- | --- | --- |
| MP4 | 540·720·1080 px, 30fps | 화질이 가장 좋고 파일이 작음. 소리(AAC)를 담음 |
| GIF | 240·360·480 px, 15fps | 거의 모든 곳에서 열림. 프레임마다 256색, 소리 없음. `loopCount`(0은 무한) |

- `frameRate`(5~60)로 초당 프레임을 바꿀 수 있고, 움직이는 원본은 원본보다 촘촘하게 만들지 않습니다. 같은 그림이 이어지는 구간은 한 프레임으로 합쳐 크기를 줄입니다.
- 움직이는 원본은 `maxAnimationFrames`(기본 300, 10~1000)까지 읽고, 100MB가 넘는 파일은 `sourceTooLarge`로 열지 않습니다.
- `FrameKitRequest`로 사진 한 장을 고르거나 넘겼을 때 그 사진이 움직이면 움직이는 이미지 편집기가 열립니다.

## 워터마크

사진·영상 편집기의 **워터마크** 도구로 글자 워터마크를 넣습니다. 무늬(`WatermarkStyle`)는 `.single`(아홉 자리 중 한 곳), `.tile`(화면 전체 반복), `.diagonal`(대각선으로 크게 하나), `.band`(위나 아래 띠) 네 가지입니다.

```swift
// 문구만 주고 사용자가 고르고 꾸미게 하기
let suggested = WatermarkConfig(text: "© MyShop", suggestions: ["© MyShop", "@myshop"])

// 열자마자 얹고 사용자가 바꾸거나 지울 수 있게 하기
let applied = WatermarkConfig(text: "@myshop", style: .single, anchor: .bottomEnd, applyOnOpen: true)

// 바꿀 수 없는 반복 워터마크(도구가 숨겨지고 저장할 때 항상 들어감)
let locked = WatermarkConfig(text: "SAMPLE", style: .tile, opacity: 0.35, applyOnOpen: true, locked: true)

FrameKitRequest(input: .pickMultiple(maxItems: 10), image: ImageEditorConfig(watermark: locked), video: VideoEditorConfig(watermark: locked))
```

| `WatermarkConfig` | 기본값 | 내용 |
| --- | --- | --- |
| `text` | `""` | 문구, 한 줄 최대 60자. `applyOnOpen`·`locked`면 필수 |
| `style`·`anchor` | `.single`·`.bottomEnd` | 무늬와 자리(`topStart`~`bottomEnd` 아홉 곳) |
| `sizeRatio` | 0.04 | 글자 높이/짧은 변(0.015~0.2) |
| `opacity` | 0.7 | 0.05~1 |
| `colorARGB` | 흰색 | 글자 색. 대비되는 그림자·상자가 자동으로 붙음 |
| `rotationDegrees`·`density` | -30·1 | 반복 무늬의 기울기(-90~90)와 촘촘함(0.5~2) |
| `background` | `false` | 글자 뒤 둥근 반투명 상자 |
| `applyOnOpen` | `false` | 편집기를 열 때 바로 얹기 |
| `locked` | `false` | 바꾸거나 지울 수 없음. `applyOnOpen: true`가 필요 |
| `suggestions` | 없음 | 도구에 보여 줄 추천 문구(최대 8개) |

움직이는 이미지 편집기는 워터마크를 지원하지 않습니다.

## 영상 편집기

```swift
.frameKitVideoEditor(isPresented: $showVideo, request: VideoEditorRequest(input: .pickMultiple(maxItems: 5))) { result in
    if case .success(let output, _) = result { show(output.url, output.durationMs ?? 0) }
}
```

- 원본: `.pick`·`.pickMultiple`(영상만 보임), `.file`·`.files`, `.capture`(영상 녹화). `.data`는 쓸 수 없습니다.
- 도구(`VideoTool`): 구간, 자르기, 회전, 화면 비율(맞추기·채우기), 보정, 필터, 속도(0.25~4배, 음높이 유지), 소리, 텍스트, 스티커, 가리기(사각형·원, 최대 8개), 워터마크.
- 클립은 최대 `maxClipCount`개를 이어 붙이고, 재생 위치에서 나누고, 순서를 바꾸고, 지웁니다. 결과 전체 길이는 `maxTimelineDuration`(기본 10분, 최대 1시간)을 넘지 않습니다.
- 결과: H.264 + AAC MP4(SDR). `VideoExportConfig`의 `maxShortSide`(기본 1080, 144~2160)와 `maxFrameRate`(기본 30, 1~60)로 크기와 초당 프레임을 정합니다. 원본보다 키우지 않고 긴 변은 4096을 넘지 않습니다.
- HDR 영상은 SDR로 바꾸고 `hdrConvertedToSDR` 경고를, 초당 프레임을 줄였으면 `frameRateReduced` 경고를 붙입니다.

### 타임라인

타임라인은 영상·원본 소리·배경 음악·음성·텍스트·가리기를 줄로 나눠 보여 줍니다.

- 영상 클립을 고르면 양 끝을 끌어 그 자리에서 자릅니다.
- 원본 소리 줄을 누르면 구간 음소거·되살리기·전체 음소거·볼륨이 나옵니다. 끈 구간은 원본 시간으로 저장되어 클립을 옮기거나 잘라도 같은 장면의 소리가 꺼져 있습니다.
- 배경 음악·음성은 한 줄에 여러 조각을 둡니다. 조각을 끌어서 옮기거나 양 끝을 끌어 자르고, 나누기·반복·삭제·볼륨·페이드인/아웃을 합니다. 소리가 있는 줄에는 파형이 그려집니다.
- 텍스트·스티커·가리기는 재생 위치부터 3초 동안 보이고 '여기서 시작·끝'으로 바꿉니다. 고른 가리기는 끌어서 옮기고 모서리로 크기를 바꿉니다.

## 음성 편집기

소리 파일을 편집하거나 새로 녹음합니다. `FrameKitRequest`에 `mediaKind: .audio`를 쓰거나 `AudioEditorRequest`로 엽니다.

```swift
// 녹음하고 편집
FrameKitRequest(input: .capture, mediaKind: .audio)

// 누르고 말하기 녹음기로만 쓰기(편집 없이 바로 결과)
FrameKitRequest(input: .capture, mediaKind: .audio, audio: AudioEditorConfig(recordMode: .hold, editAfterRecording: false))

// 소리 파일 최대 5개를 골라 이어 붙여 편집
FrameKitRequest(input: .pickMultiple(maxItems: 5), mediaKind: .audio)

// 음성 편집기만 쓰기
AudioEditorRequest(input: .file(audioURL), export: AudioExportConfig(bitrate: 96_000))
```

| 설정 | 기본값 | 내용 |
| --- | --- | --- |
| `recordMode` | `.both` | `.tap`(눌러 시작·다시 눌러 끝, 잠시 멈춤), `.hold`(누르는 동안 녹음, 위로 밀면 고정·왼쪽으로 밀면 취소), `.both`(사용자가 고름) |
| `maxRecordingDuration` | 600초 | 한 번 녹음 최대 길이(1~3600초). 닿으면 저절로 멈춤 |
| `editAfterRecording` | `true` | `.capture`로 시작했을 때 녹음 후 편집 화면으로 갈지 |
| `recorderPresentation` | `.fullScreen` | 녹음만 하는 화면(`.capture`, `editAfterRecording: false`)을 띄우는 방식. `.sheet`는 녹음 조작부 높이의 아래 시트. 다른 경우에는 무시하고 전체 화면 |
| `enabledTools` | 전부 | `record`, `importFile`, `split`, `range`, `volume` |
| `maxSourceCount` | 10 | 한 번에 여는 파일 수(1~20) |
| `AudioExportConfig.bitrate` | 128 000 | AAC 비트레이트(32 000~320 000). 64kbps 미만은 모노 |

- 원본 고르기는 파일 앱을 씁니다. `.data`는 쓸 수 없습니다.
- 편집 화면은 위 줄(본 줄)과 아래 줄(겹친 소리)로 나뉩니다. 본 줄은 빈틈 없이 이어지고, 녹음·파일을 끼워 넣으면 뒤가 밀리고 지우면 당겨집니다. 타임라인은 두 손가락으로 확대합니다.
- 결과는 AAC M4A(`audio/mp4`, `.audio`)이며 너비·높이는 0입니다.
- 0.5초보다 짧은 녹음은 버립니다.

### 녹음 시트

```swift
// 편집 없이 녹음만 하고, 아래 시트로 띄우기
let config = AudioEditorConfig(editAfterRecording: false, recorderPresentation: .sheet)
present(FrameKitAudioEditorViewController(request: AudioEditorRequest(input: .capture, config: config)) { result in ... }, animated: true)

// SwiftUI
.frameKitAudioEditor(isPresented: $recording, request: AudioEditorRequest(input: .capture, config: config)) { result in ... }
// 진입점도 같음
.frameKitEditor(isPresented: $recording, request: FrameKitRequest(input: .capture, mediaKind: .audio, audio: config)) { result in ... }
```

- iOS 16 이상은 녹음 조작부에 맞춘 높이의 시트, iOS 15는 UIKit에서 중간 높이(`.medium`), SwiftUI에서 큰 시트입니다. 손잡이가 보입니다.
- 아래로 끌어 닫으면 닫기 단추와 같습니다. 결과는 `cancelled` 한 번입니다. 녹음 중이면 녹음을 멈추고 그 파일을 지운 뒤 닫습니다(UIKit은 시트를 되돌린 뒤 닫고, SwiftUI는 그대로 내려갑니다). 녹음 파일을 넘기는 중에는 끌어 닫을 수 없습니다.
- `FrameKitEditorControl`도 그대로 씁니다.
- 편집 화면 안의 녹음 시트(녹음 도구)도 조작부 높이에 맞춰 뜹니다.

### 녹음과 오디오 세션

- 녹음은 Info.plist에 `NSMicrophoneUsageDescription`이 있을 때만 보입니다. 권한은 녹음 버튼을 처음 누를 때 묻고, 거부하면 설정에서 허용하라는 안내가 나옵니다.
- 녹음하는 동안 오디오 세션을 녹음용으로 바꾸고, 끝나면 앱이 쓰던 카테고리·모드·옵션으로 돌립니다.
- 미리 보기 소리는 앱의 오디오 세션 설정을 따릅니다. 카테고리가 `.ambient`·`.soloAmbient`면 무음 모드에서 소리가 나지 않습니다.

## 배경 제거

사진 편집기의 배경 제거(`cutout`)는 기기 안 Vision으로 피사체를 찾습니다. 사진을 서버로 보내지 않고, 따로 넣을 모듈이나 내려받을 모델이 없습니다. 지원하지 않는 기기(iOS 17 미만, 시뮬레이터 포함)에서는 도구가 보이지 않습니다. JPEG로 저장하면 지운 배경은 `jpegBackgroundARGB` 색으로 채워지므로, 투명하게 남기려면 PNG나 HEIC를 쓰세요.

## 지원 형식

| 종류 | 읽기 | 저장 |
| --- | --- | --- |
| 사진 | JPEG, PNG, HEIC/HEIF, WebP(움직이지 않는 것) | JPEG, PNG, HEIC, PDF |
| 움직이는 이미지 | GIF, 움직이는 WebP, APNG | MP4, GIF |
| 영상 | iOS가 재생하는 형식(MP4, MOV 등) | MP4(H.264 + AAC) |
| 소리 | M4A, AAC, MP3, WAV, AIFF, CAF 등 | M4A(AAC) |

- 넓은 색 영역(Display P3) 사진은 sRGB로 저장하고 `colorSpaceConvertedToSRGB` 경고를 붙입니다. HDR 게인 맵은 저장하지 않습니다.
- 메모리가 모자라면 `insufficientMemory`, 저장 공간이 모자라면 `insufficientStorage`로 실패합니다. `maxOutputPixels`를 줄이면 피할 수 있습니다.

## Android와 다른 점

iOS에서 쓸 수 없거나 같은 결과를 낼 수 없는 기능은 iOS SDK에 넣지 않았습니다.

- 문서 감지·펴기(사진 편집기의 문서 보정 도구)와 실시간 문서 스캐너는 없습니다. PDF로 묶기는 사진을 그대로 PDF 쪽으로 만듭니다.
- 앱 안 갤러리는 없고, 권한이 필요 없는 시스템 사진 선택기와 파일 앱을 씁니다.
- WebP로 저장할 수 없습니다(사진·움직이는 이미지). 사진은 HEIC로 저장할 수 있습니다.
- 화면 없이 코드로 처리하는 API는 없습니다.
- 앱이 종료된 뒤 이어 편집하려면 앱이 같은 편집기를 같은 입력으로 다시 열어야 합니다. Android처럼 시스템이 화면을 되살려 주지 않습니다.
- 결과 위치(`OutputTarget`)는 따로 고르지 않습니다.
- 영상 인코더 대체(`allowFallback`)는 없습니다.
- 이름이 다른 설정: 워터마크 `style`·`anchor`(Android `pattern`·`position`), 언어 `localeIdentifier`(Android `localeTag`), 시간 값은 초 단위 `TimeInterval`(`maxTimelineDuration`, `maxRecordingDuration`).
