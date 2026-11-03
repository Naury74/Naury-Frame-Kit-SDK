# iOS

iOS 앱에 사진·영상·움직이는 이미지·음성 편집 화면을 붙이는 방법입니다. 기능과 화면, 결과 형식은 Android 판과 같고, iOS에서 쓸 수 없는 기능은 빠져 있습니다(맨 아래 "Android와 다른 점").

## 요구 사항

- iOS 17 이상, Xcode 16 이상
- Swift 6과 Swift 5 프로젝트 모두에서 쓸 수 있습니다.
- 시뮬레이터는 Apple 실리콘 Mac(arm64)에서만 돌아갑니다.
- 앱 확장(공유 확장 등)에서는 쓸 수 없습니다. 앱 본체에서 여세요.
- 개인정보 매니페스트(`PrivacyInfo.xcprivacy`)가 들어 있습니다. 추적·데이터 수집은 없고, 파일 날짜(C617.1)와 남은 저장 공간(E174.1)을 읽습니다.
- SDK 안의 Kotlin 코드는 FrameKit 안에 숨겨져 있어, 앱의 다른 Kotlin Multiplatform 프레임워크와 이름이 겹치지 않습니다.

## 설치

Swift Package Manager로 추가합니다. Xcode에서 **File › Add Package Dependencies…**를 고르고 아래 주소를 넣습니다.

```
https://github.com/Naury74/Naury-Frame-Kit-SDK
```

SDK는 컴파일된 바이너리(`FrameKit.xcframework`)로만 배포합니다. 소스나 내부 모듈은 들어 있지 않습니다.

## 한 번에 열기

`FrameKitRequest` 하나로 고른 원본의 종류에 맞는 편집기를 엽니다. 사진은 사진 편집기, 영상은 영상 편집기, 움직이는 GIF·WebP·PNG 한 장은 움직이는 이미지 편집기, 소리는 음성 편집기로 갑니다.

```swift
Button("편집") { show = true }
    .frameKitEditor(isPresented: $show, request: FrameKitRequest(input: .pickMultiple(maxItems: 10), chooser: .ask)) { result in
        if case .success(_, let outputs) = result { print(outputs.map(\.url)) }
    }
```

UIKit은 `FrameKitViewController(request:completion:)`를 씁니다.

- `mediaKind`: `.pick`·`.pickMultiple`·`.capture`로 고를 종류. `.image`, `.video`, `.any`(사진과 영상), `.audio`. `.capture`에는 `.any`를 쓸 수 없습니다.
- `chooser`: `.auto`는 바로 편집기를 열고, `.ask`는 할 일을 묻는 시트를 보입니다. 할 일이 하나뿐이면 묻지 않습니다.
- `chooserActions`: 시트에 보일 할 일(`editPhoto`, `makePDF`, `motion`, `editVideo`, `editAudio`, `sendOriginal`).
  - 사진: 사진 편집, PDF로 묶기, 움직이는 이미지 만들기, 원본 보내기
  - 움직이는 이미지 한 장: 움직이는 이미지 편집, 첫 장면만 사진으로 편집, 원본 보내기
  - 영상: 영상 편집, 원본 보내기 / 소리: 음성 편집, 원본 보내기
  - 종류가 섞이면(예: 사진과 영상) 원본 보내기만 보입니다. `.auto`에서는 `unsupportedOperation`으로 실패합니다.
- 원본 보내기는 파일을 결과 폴더로 복사해 `isOriginal == true`로 돌려줍니다.
- `image`·`imageExport`·`video`·`videoExport`·`motion`·`motionExport`·`audio`·`audioExport`·`ui`·`catalog`는 각 편집기 설정 그대로 쓰입니다. `openAnimatedInMotionEditor`(기본 `true`)가 `false`면 GIF도 사진 편집기로 엽니다.

## 편집기 열기

### SwiftUI

```swift
import FrameKit

struct ContentView: View {
    @State private var showEditor = false

    var body: some View {
        Button("사진 편집") { showEditor = true }
            .frameKitImageEditor(isPresented: $showEditor, request: ImageEditorRequest(input: .pick)) { result in
                switch result {
                case .success(let output, let outputs):
                    print(output.url, outputs.count)
                case .cancelled:
                    break
                case .failure(let error):
                    print(error.code, error.diagnosticId)
                }
            }
    }
}
```

### UIKit

```swift
let editor = FrameKitImageEditorViewController(request: ImageEditorRequest(input: .pick)) { result in
    // 편집기는 스스로 닫힌 뒤 결과를 한 번 넘깁니다.
}
present(editor, animated: true)
```

- 결과는 편집기마다 정확히 한 번 옵니다. 아래로 끌어 닫기는 막혀 있고, 닫기는 편집기의 닫기 단추로 합니다.
- 편집기는 전체 화면으로 열리고 safe area를 따릅니다. 노치, Dynamic Island, 상태 바가 옆에 있는 기기(iPhone Duo 등), 가로 화면, iPad 분할 화면에서도 조작부가 가려지지 않습니다.

## 원본

| `EditorInput` | 설명 |
| --- | --- |
| `.pick` | 시스템 사진 선택기로 한 장. 사진 접근 권한이 필요 없습니다. |
| `.pickMultiple(maxItems:)` | 여러 장. 고른 순서가 편집·저장 순서입니다. |
| `.file(URL)` | 앱이 읽을 수 있는 파일. 보안 범위 URL도 됩니다. |
| `.files([URL])` | 여러 파일. |
| `.data(Data)` | 이미지 데이터. |
| `.capture` | 카메라로 찍기. 앱 Info.plist에 `NSCameraUsageDescription`이 있어야 합니다. |

읽는 형식은 JPEG, PNG, HEIC/HEIF, WebP(움직이지 않는 것)입니다. 2억 5천만 픽셀이 넘는 원본은 `sourceTooLarge`로 실패합니다. 원본 파일은 바꾸지 않습니다.

## 설정

```swift
ImageEditorRequest(
    input: .pickMultiple(maxItems: 10),
    config: ImageEditorConfig(
        enabledTools: [.crop, .rotate, .adjust, .filter, .text],   // 보여 줄 도구와 순서
        watermark: WatermarkConfig(text: "© Company", style: .tile, applyOnOpen: true, locked: true),
        maxImageCount: 20
    ),
    export: ImageExportConfig(format: .jpeg, quality: 92, maxOutputPixels: 16_000_000, metadataPolicy: .safe),
    ui: EditorUIConfig(themeMode: .dark, primaryARGB: 0xFF63_5BFF, localeIdentifier: "ko"),
    catalog: EditorCatalog(filters: [CustomFilter(id: "brand.warm", label: "Warm", temperature: 0.4)])
)
```

- 범위를 벗어난 설정은 `request.validate()`로 미리 확인할 수 있고, 그대로 열면 편집기가 `invalidConfiguration`으로 끝납니다.
- 도구: 자르기, 회전, 배경 제거, 보정, 필터, 텍스트, 스티커, 그리기, 가리기, 워터마크.
- 저장 형식: JPEG, PNG, HEIC, PDF. PDF는 `PdfOptions`로 쪽 크기, 방향, 여백, 해상도를 정하고, 기기 안에서 글자를 인식해 검색·복사가 되는 글자 레이어를 넣습니다.
- 메타데이터: `safe`는 촬영 시각과 카메라 설정만, `none`은 아무것도, `all`은 위치 정보까지 옮깁니다. 방향은 항상 '정상'으로 저장합니다.
- 모양: `EditorUIConfig`의 `themeMode`, `primaryARGB`(강조색), `cornerRadius`, `palette`(배경·표면·글자 등 7가지 색), `uiFontId`(`catalog.fonts`의 글꼴을 편집기 글자에 씀), `enableHaptics`, `showExportProgress`(`false`면 저장 단계 이름과 진행률을 숨김). `EditorPalette.contrastWarnings(themeMode:primaryARGB:)`로 명암비가 낮은 색 조합을 미리 확인할 수 있습니다.
- 글자 크기: 시스템 글자 크기 설정을 따르고, 조작부가 가려지지 않도록 접근성 1단계 크기까지만 커집니다.
- 언어: 13개 언어(한국어, 영어, 일본어, 중국어 간체·번체, 베트남어, 태국어, 인도네시아어, 러시아어, 스페인어, 포르투갈어(브라질), 프랑스어, 독일어). 없는 언어는 영어로 보입니다.

## 결과

`EditedMedia`의 `url`은 앱의 `Application Support/FrameKit/Exports` 안 파일입니다. 파일은 앱 소유이며 SDK가 지우지 않습니다. 다 쓴 파일은 `FrameKitOutputs.delete(_:)`로 지울 수 있습니다(SDK 결과 폴더 밖 파일은 지우지 않습니다).

오류에는 화면 문구가 없습니다. `EditorError.code`로 앱이 문구를 정하고, `suggestedAction`으로 다음 행동을 고를 수 있습니다. `diagnosticId`는 SDK 로그(하위 시스템 `com.naurylab.framekit`)에 같이 남습니다.

## 영상 편집기

```swift
Button("영상 편집") { showVideo = true }
    .frameKitVideoEditor(isPresented: $showVideo, request: VideoEditorRequest(input: .pickMultiple(maxItems: 5))) { result in
        if case .success(let output, _) = result { print(output.url, output.durationMs ?? 0) }
    }
```

UIKit은 `FrameKitVideoEditorViewController(request:completion:)`를 씁니다.

- 원본: `.pick`·`.pickMultiple`(영상만 보임), `.file`·`.files`, `.capture`(영상 촬영). `.data`는 쓸 수 없습니다.
- 도구: 구간, 자르기, 회전, 화면 비율(맞추기·채우기), 보정, 필터, 속도(0.25~4배, 음높이 유지), 소리(원본 볼륨·음소거·구간 음소거, 배경 음악, 음성 파일, 녹음), 텍스트, 스티커, 가리기(사각형·원, 최대 8개), 워터마크. 텍스트·스티커·가리기는 재생 위치부터 3초 동안 보이고 '여기서 시작·끝'으로 바꿉니다. 고른 가리기는 끌어서 옮기고 모서리로 크기를 바꿉니다. 음악·음성 조각은 타임라인에서 끌어 옮기고 양 끝을 끌어 줄입니다.
- 클립: 최대 `maxClipCount`개(기본 10, 최대 20)를 이어 붙이고, 재생 위치에서 나누고, 순서를 바꾸고, 지웁니다. 결과 전체 길이는 `maxTimelineDuration`(기본 10분, 최대 1시간)을 넘지 않습니다.
- 녹음은 앱 Info.plist에 `NSMicrophoneUsageDescription`이 있을 때만 보입니다. 없으면 음성은 파일로만 넣습니다.
- 결과: H.264 + AAC MP4(SDR). `VideoExportConfig`의 `maxShortSide`(기본 1080, 144~2160)와 `maxFrameRate`(기본 30, 1~60)로 크기와 초당 프레임을 정합니다. 원본보다 키우지 않고 긴 변은 4096을 넘지 않습니다. `EditedMedia.durationMs`에 길이가 들어갑니다.
- HDR 영상은 SDR로 바꾸고 `hdrConvertedToSDR` 경고를, 초당 프레임을 줄였으면 `frameRateReduced` 경고를 붙입니다.

## 움직이는 이미지 편집기

```swift
Button("움직이는 이미지") { showMotion = true }
    .frameKitMotionEditor(isPresented: $showMotion, request: MotionEditorRequest(input: .pickMultiple(maxItems: 20))) { result in
        if case .success(let output, _) = result { print(output.url, output.mediaType, output.durationMs ?? 0) }
    }
```

UIKit은 `FrameKitMotionEditorViewController(request:completion:)`를 씁니다.

- 사진 한 장이 움직이는 GIF·WebP·PNG면 그 원본을 편집하고, 그 밖에는 사진들(최대 `maxImageCount`, 기본 20·최대 50)로 슬라이드쇼를 만듭니다. 여러 장 가운데 움직이는 이미지는 첫 프레임만 씁니다.
- 원본: `.pick`·`.pickMultiple`, `.file`·`.files`, `.data`. `.capture`는 쓸 수 없습니다.
- 도구: 움직임(사진마다 확대·이동), 전환(페이드·밀기·줌), 길이(사진당 시간, 움직이는 원본은 속도·구간), 반복(반복·부메랑), 화면 비율(채우기·맞추기, 배경 색), 보정, 필터, 텍스트·스티커(나타나기·팝·올라오기·통통·흔들기·두근두근), 소리(음악·음성 파일, 녹음).
- 움직이는 원본은 `maxAnimationFrames`(기본 300, 10~1000)까지 읽고 나머지는 버립니다. 100MB가 넘는 파일은 `sourceTooLarge`로 열지 않습니다.
- 저장: 사용자가 저장 시트에서 형식과 크기를 고릅니다. `MotionExportConfig.formats`로 보일 형식을, `format`·`size`로 처음 고른 값을 정합니다.
  - MP4(H.264, 소리는 AAC): 긴 변 540·720·1080px. `mediaType`은 `.video`.
  - GIF: 긴 변 240·360·480px, 프레임마다 256색. 소리는 담기지 않습니다. `loopCount`(0은 무한)로 반복 횟수를 정합니다. `mediaType`은 `.animatedImage`.
  - 초당 프레임은 `frameRate`(5~60, 기본 MP4 30·GIF 15)이고, 움직이는 원본은 원본보다 촘촘하게 만들지 않습니다. 그림이 바뀌지 않는 구간은 한 프레임으로 합칩니다.
- 움직이는 WebP로는 저장할 수 없습니다(Android만 지원).

## 음성 편집기

```swift
Button("녹음") { showAudio = true }
    .frameKitAudioEditor(isPresented: $showAudio, request: AudioEditorRequest(input: .capture)) { result in
        if case .success(let output, _) = result { print(output.url, output.durationMs ?? 0) }
    }
```

UIKit은 `FrameKitAudioEditorViewController(request:completion:)`를 씁니다.

- 원본: `.pick`·`.pickMultiple`(파일 앱의 소리 파일), `.file`·`.files`, `.capture`(녹음). `.data`는 쓸 수 없습니다. 여러 파일은 고른 순서대로 이어 붙입니다(최대 `maxSourceCount`, 기본 10·최대 20).
- 녹음은 앱 Info.plist에 `NSMicrophoneUsageDescription`이 있어야 합니다. 없으면 녹음 도구가 보이지 않고, `.capture`는 `invalidConfiguration`으로 실패합니다.
- 녹음 방식 `recordMode`: `.tap`(눌러 시작·일시정지·완료), `.hold`(누르는 동안 녹음, 위로 밀면 고정, 왼쪽으로 밀면 취소), `.both`. 한 번에 `maxRecordingDuration`(기본 600초, 1~3600)까지 녹음합니다. 0.5초보다 짧으면 버립니다.
- `editAfterRecording`이 `false`면 `.capture`로 녹음한 파일을 편집 없이 바로 돌려줍니다.
- 도구: 녹음(재생 위치에 끼워 넣기·겹쳐 넣기), 파일 추가, 나누기, 구간(잘라 내기·그것만 남기기), 볼륨(조각 볼륨 0~200%·페이드). 본 줄은 빈틈 없이 이어지고, 겹친 줄은 자유롭게 옮기고 반복할 수 있습니다. 타임라인은 두 손가락으로 확대하고, 고른 조각의 양 끝을 끌어 줄입니다.
- 결과: AAC M4A(`audio/mp4`, `mediaType` `.audio`, 너비·높이 0). `AudioExportConfig.bitrate`(기본 128000, 32000~320000)로 비트레이트를 정합니다. 64kbps 미만은 모노로 저장합니다.
- OGG·Opus·AMR 파일은 iOS가 읽지 못해 `unsupportedFormat`으로 열지 않습니다.

## 주의

- 배경 제거는 기기 안 Vision을 씁니다. 지원하지 않는 기기(시뮬레이터 포함)에서는 도구가 보이지 않습니다.
- 넓은 색 영역(Display P3) 사진은 sRGB로 저장하고 `colorSpaceConvertedToSRGB` 경고를 붙입니다. HDR 게인 맵은 저장하지 않습니다.
- 메모리가 모자라면 `insufficientMemory`, 저장 공간이 모자라면 `insufficientStorage`로 실패합니다. `maxOutputPixels`를 줄이면 피할 수 있습니다.
- 저장하는 동안 화면이 꺼지지 않게 하고, 끝나면 앱이 정해 둔 값으로 돌립니다. 녹음이 끝나면 오디오 세션의 카테고리·모드·옵션도 앱이 쓰던 값으로 돌립니다.
- 미리 보기 소리는 앱의 오디오 세션 설정을 따릅니다. 카테고리가 `.ambient`·`.soloAmbient`면 무음 모드에서 소리가 나지 않습니다.

## Android와 다른 점

iOS에서 쓸 수 없거나 같은 결과를 낼 수 없는 기능은 iOS SDK에 넣지 않았습니다.

- 문서 감지·펴기(사진 편집기의 문서 보정 도구)와 실시간 문서 스캐너는 없습니다. PDF로 묶기는 사진을 그대로 PDF 쪽으로 만듭니다.
- 앱 안 갤러리(`picker`)는 없고, 권한이 필요 없는 시스템 사진 선택기와 파일 앱을 씁니다.
- WebP로 저장할 수 없습니다(사진·움직이는 이미지). 사진은 HEIC로 저장할 수 있습니다.
- 결과 위치(`OutputTarget`)는 따로 고르지 않습니다. 결과는 앱 Application Support 안 파일 URL입니다.
- 영상 인코더 대체(`allowFallback`, `encoderFallbackApplied` 경고)는 없습니다.
