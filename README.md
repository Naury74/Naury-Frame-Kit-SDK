# FrameKit

Android 앱에 넣어 쓰는 사진·영상·음성 편집 SDK입니다. 편집 화면을 한 줄로 띄우고, 편집이 끝나면 결과 파일을 돌려받습니다. 원본은 바꾸지 않습니다.

## 기능

- **사진**: 자르기·회전, 보정·필터, 텍스트·스티커·그리기, 모자이크, 배경 제거, 여러 장 편집, PDF 저장
- **영상**: 여러 클립 이어 붙이기·자르기, 보정·필터·속도, 배경 음악·음성, 텍스트·스티커, MP4 저장
- **움직이는 이미지**: 사진으로 슬라이드쇼 만들기, GIF·WebP 편집, MP4·GIF·WebP 저장
- **음성**: 녹음(탭·누르고 말하기), 소리 파일 편집, M4A 저장
- **워터마크**: 사진·영상에 글자 워터마크
- **기타**: 카메라 촬영 직후 편집, 편집 화면 없이 처리(headless), 앱 필터·스티커·폰트 추가, 테마·13개 언어

## 설치

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
    implementation("com.naurylab:framekit:1.0.0-alpha02")
}
```

일부 기능만 쓰려면 해당 모듈만 추가해도 됩니다.

| 모듈 | 내용 |
| --- | --- |
| `framekit` | 모든 편집기와 통합 진입점 `FrameKitContract` |
| `framekit-ui-image` | 사진 편집기만(`ImageEditorContract`) |
| `framekit-ui-video` | 영상 편집기만(`VideoEditorContract`) |
| `framekit-ui-motion` | 움직이는 이미지 편집기만(`MotionEditorContract`) |
| `framekit-ui-audio` | 음성 편집기만(`AudioEditorContract`) |
| `framekit-segmentation` | (선택) 배경 제거 |
| `framekit-ocr` | (선택) PDF 글자 검색 |

- minSdk 26, Kotlin 2.2 이상. 호스트 앱이 Compose를 쓰지 않아도 됩니다.
- R8에 별도 keep 규칙이 필요 없습니다.

## 사용법

### 편집기 열기

```kotlin
class MainActivity : ComponentActivity() {

    private val editor = registerForActivityResult(FrameKitContract()) { result ->
        when (result) {
            is FrameKitResult.Success -> show(result.output.uri, result.output.mediaType)
            FrameKitResult.Cancelled -> Unit
            is FrameKitResult.Failure -> showError(result.error.code)
        }
    }

    fun edit() {
        // 사진이나 영상을 고르면 알맞은 편집기가 열린다.
        editor.launch(FrameKitRequest(EditorInput.Pick(MediaKind.ANY)))
    }
}
```

Compose에서는 `rememberLauncherForActivityResult(FrameKitContract())`를 씁니다. 결과가 여러 개면(여러 장을 사진마다 저장) `result.outputs`에 모두 들어 있습니다.

### 입력 바꾸기

```kotlin
EditorInput.UriSource(uri)                              // 가진 파일 하나
EditorInput.Multiple(uris.map(EditorInput::UriSource))  // 가진 파일 여러 개
EditorInput.Pick(MediaKind.IMAGE, maxItems = 10)        // 사진 최대 10장 고르기
EditorInput.Pick(MediaKind.AUDIO, maxItems = 5)         // 소리 파일 고르기
EditorInput.Capture(MediaKind.IMAGE)                    // 카메라로 찍고 편집 (VIDEO: 녹화)
EditorInput.Capture(MediaKind.AUDIO)                    // 녹음하고 편집
```

원본 종류(사진·영상·움직이는 GIF·소리)에 맞는 편집기가 자동으로 열립니다.

### 무엇을 할지 묻기

자동으로 열지 않고, 원본을 보여 주며 "편집 / PDF로 묶기 / 움직이는 이미지 만들기 / 편집 없이 보내기" 중에서 고르게 할 수 있습니다.

```kotlin
editor.launch(FrameKitRequest(EditorInput.Multiple(uris.map(EditorInput::UriSource)), chooser = EditorChooser.ASK))
```

편집 없이 보내기를 고르면 원본이 그대로 `Success`로 오고 `isOriginal`이 `true`입니다.

### 자주 쓰는 설정

```kotlin
// 여러 장을 A4 PDF 하나로
FrameKitRequest(
    EditorInput.Pick(MediaKind.IMAGE, maxItems = 20),
    imageExport = ImageExportConfig(format = ImageFormat.PDF, pdf = PdfOptions(pageSize = PdfPageSize.A4)),
)

// 도구 제한, 저장 형식, 영상 길이, 테마
FrameKitRequest(
    EditorInput.UriSource(uri),
    image = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP, ImageTool.ROTATE)),
    imageExport = ImageExportConfig(format = ImageFormat.PNG, maxWidth = 2048),
    video = VideoEditorConfig(maxTimelineDurationUs = 15_000_000),
    ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, primaryArgb = 0xFF1E6BFF.toInt()),
)

// 녹음만 하고 바로 받기(누르고 말하기)
FrameKitRequest(
    EditorInput.Capture(MediaKind.AUDIO),
    audio = AudioEditorConfig(recordMode = RecordMode.HOLD, editAfterRecording = false),
)

// 워터마크: 앱이 정한 문구를 바꿀 수 없게 넣기
val watermark = WatermarkConfig(text = "© MyShop", pattern = WatermarkPattern.TILE, applyOnOpen = true, locked = true)
FrameKitRequest(EditorInput.Pick(MediaKind.ANY), image = ImageEditorConfig(watermark = watermark), video = VideoEditorConfig(watermark = watermark))
```

움직이는 이미지는 `MotionEditorContract`로 엽니다.

```kotlin
val motion = registerForActivityResult(MotionEditorContract()) { result -> /* ... */ }
motion.launch(MotionEditorRequest(EditorInput.Pick(MediaKind.IMAGE, maxItems = 20)))
```

### 편집 화면 없이 처리

```kotlin
ImageProcessor(context).use { processor ->
    val source = processor.open(EditorInput.UriSource(uri))
    val project = processor.newProject(source).copy(filter = FilterSelection("bright", 1.0))
    val result = processor.startExport(project, source, ImageExportConfig(), scope = lifecycleScope).awaitResult()
}
```

영상은 `VideoProcessor`로 같은 방식입니다.

### 앱의 필터·스티커·폰트 추가

```kotlin
FrameKitRequest(
    EditorInput.Pick(MediaKind.ANY),
    catalog = EditorCatalog(
        filters = listOf(CustomFilter("brand-warm", "Warm", temperature = 0.4)),
        stickers = listOf(CustomSticker("logo", "Logo", CatalogFile.Asset("stickers/logo.png"))),
        fonts = listOf(CustomFont("brand", "Brand", CatalogFile.Asset("fonts/brand.ttf"))),
    ),
)
```

### 결과 파일

- 결과는 앱 내부 저장소에 만들어지고 `content://` Uri로 전달됩니다. 파일은 호스트 앱 소유이며, 필요 없으면 `FrameKitOutputs.deleteOutput(context, uri)`로 지웁니다.
- 다른 앱에 공유할 때는 Intent에 `FLAG_GRANT_READ_URI_PERMISSION`을 넣습니다.

## 주의사항

- SDK는 권한을 추가하지 않습니다. 원본을 읽을 권한은 호스트가 가지고 있어야 합니다.
- 녹음하려면 호스트 manifest에 `RECORD_AUDIO`를 선언해야 합니다. 없으면 녹음 버튼이 숨겨집니다.
- 호스트가 `CAMERA`를 선언했다면 카메라 촬영 때 SDK가 권한을 요청합니다.
- 배경 제거·글자 검색 모듈은 Google Play 서비스(ML Kit)가 필요하고 `INTERNET` 권한이 추가됩니다. 처음 쓸 때 모델을 내려받을 수 있습니다.
- 사진과 영상을 한 번에 섞어 편집할 수 없습니다.
- 저장 위치는 앱 내부 저장소만 지원합니다(갤러리 저장은 호스트가 처리).
- 저장 시 위치 정보 등 민감한 메타데이터는 기본으로 제거됩니다.
- 프로세스가 종료돼도 적용한 편집은 이어서 열리지만, 실행 취소 기록은 남지 않습니다.
- `localeTag`로 언어를 고정하면서 App Bundle 언어 분할을 쓰면 문구가 빠질 수 있습니다.

## 문서

- [연동 가이드](docs/integration.md): 진입점별 예시, 결과·오류 처리, 움직이는 이미지·음성·워터마크
- [설정](docs/configuration.md): 모든 옵션의 기본값과 범위
- [편집 화면 없이 처리](docs/headless.md)
- [지원 형식](docs/supported-formats.md)
- [변경 이력](docs/release-notes.md)

저장소의 `app` 모듈은 위 기능을 직접 실행해 볼 수 있는 예제 앱입니다.

## License

Apache License 2.0. [LICENSE](LICENSE)를 보세요. 일부 아이콘은 [Material Icons](https://github.com/google/material-design-icons)를 사용합니다([NOTICE](NOTICE)).
