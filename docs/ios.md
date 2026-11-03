# iOS

iOS 앱에 사진 편집 화면을 붙이는 방법입니다. 기능과 화면, 결과 형식은 Android 판과 같습니다.

## 요구 사항

- iOS 17 이상, Xcode 16 이상
- Swift 6과 Swift 5 프로젝트 모두에서 쓸 수 있습니다.

## 설치

Swift Package Manager로 추가합니다. Xcode에서 **File › Add Package Dependencies…**를 고르고 아래 주소를 넣습니다.

```
https://github.com/Naury74/Naury-Frame-Kit-SDK
```

SDK는 컴파일된 바이너리(`FrameKit.xcframework`)로만 배포합니다. 소스나 내부 모듈은 들어 있지 않습니다.

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
- 도구: 자르기, 회전, 문서 보정, 배경 제거, 보정, 필터, 텍스트, 스티커, 그리기, 가리기, 워터마크. 문서 보정은 문서가 보이거나 PDF로 저장할 때만 나옵니다.
- 저장 형식: JPEG, PNG, HEIC, PDF. PDF는 `PdfOptions`로 쪽 크기, 방향, 여백, 해상도를 정하고, 기기 안에서 글자를 인식해 검색·복사가 되는 글자 레이어를 넣습니다.
- 메타데이터: `safe`는 촬영 시각과 카메라 설정만, `none`은 아무것도, `all`은 위치 정보까지 옮깁니다. 방향은 항상 '정상'으로 저장합니다.
- 언어: 13개 언어(한국어, 영어, 일본어, 중국어 간체·번체, 베트남어, 태국어, 인도네시아어, 러시아어, 스페인어, 포르투갈어(브라질), 프랑스어, 독일어). 없는 언어는 영어로 보입니다.

## 결과

`EditedMedia`의 `url`은 앱의 `Application Support/FrameKit/Exports` 안 파일입니다. 파일은 앱 소유이며 SDK가 지우지 않습니다. 다 쓴 파일은 `FrameKitOutputs.delete(_:)`로 지울 수 있습니다(SDK 결과 폴더 밖 파일은 지우지 않습니다).

오류에는 화면 문구가 없습니다. `EditorError.code`로 앱이 문구를 정하고, `suggestedAction`으로 다음 행동을 고를 수 있습니다. `diagnosticId`는 SDK 로그(하위 시스템 `com.naurylab.framekit`)에 같이 남습니다.

## 주의

- 배경 제거는 기기 안 Vision을 씁니다. 시뮬레이터와 일부 오래된 기기에서는 지원하지 않는다는 안내가 나옵니다.
- 넓은 색 영역(Display P3) 사진은 sRGB로 저장하고 `colorSpaceConvertedToSRGB` 경고를 붙입니다. HDR 게인 맵은 저장하지 않습니다.
- 메모리가 모자라면 `insufficientMemory`, 저장 공간이 모자라면 `insufficientStorage`로 실패합니다. `maxOutputPixels`를 줄이면 피할 수 있습니다.
- 영상, 움직이는 이미지, 음성 편집기와 앱 안 갤러리, 실시간 문서 스캐너는 아직 iOS에 없습니다.
