# FrameKit

Android 앱에 넣어 쓰는 **비파괴 이미지·영상 편집 SDK**입니다. 호스트 앱은 Uri를 넘기거나 내장 Photo Picker를 띄우고, FrameKit 편집 화면에서 편집한 뒤 결과 파일의 `content://` Uri와 메타데이터를 받습니다. 원본은 절대 덮어쓰지 않습니다.

> 현재 버전은 **v0.1 (개발 중)** 입니다. 이미지 자르기·회전과 JPEG/PNG 저장까지 동작합니다. 영상 편집은 v0.3에서 추가합니다. Maven 배포 전이므로 지금은 소스 모듈로 의존합니다.

## 지원 기능

| 기능 | 내용 | 상태 |
| --- | --- | --- |
| 자르기 | 자유·원본·1:1·4:3·3:4·16:9·9:16·3:2·2:3, 모서리·변 핸들, 이동 | v0.1 지원 |
| 회전·반전 | 90° 회전, 좌우·상하 반전, 수평 맞추기 ±45° | v0.1 지원 |
| 실행 취소 | 도구 한 번 사용 = 한 단계, 최대 50단계, redo | v0.1 지원 |
| 저장 | JPEG(품질 0..100)·PNG, 최대 16MP, 확대 없음, SAFE EXIF | v0.1 지원 |
| 입력 | content Uri, 앱 내부 파일, 시스템 Photo Picker | v0.1 지원 |
| 결과 | Success/Cancelled/Failure 한 번만 반환, 오류 코드와 다음 행동 | v0.1 지원 |
| 세션 복원 | 프로세스가 종료돼도 확정한 편집 복원, 원본이 같은 이미지인지 확인 | v0.1 지원 |
| 테마·언어 | 다크(기본)/라이트/시스템, accent 색, 모서리, ko/en, localeTag | v0.1 지원 |

계획된 기능(아직 **미지원**): 보정·필터, 텍스트·스티커·그리기, 블러·모자이크, WEBP 저장, headless export handle(v0.2) · 단일 영상 편집(v0.3) · 다중 클립 타임라인(v0.4) · 사용자 정의 카탈로그, Maven 배포(v1.0). 자세한 순서는 [Roadmap](#roadmap)을 보세요.

## 설치

Maven 좌표는 배포 namespace를 등록한 뒤 확정합니다. 그 전까지는 저장소를 함께 빌드하고 모듈에 의존합니다.

```kotlin
// settings.gradle.kts
include(":framekit-core", ":framekit-android", ":framekit-image", ":framekit-ui", ":framekit-ui-image")

// app/build.gradle.kts
dependencies {
    implementation(project(":framekit-ui-image"))
}
```

`framekit-ui-image` 하나만 추가하면 core·android·image·ui 모듈이 함께 들어옵니다. 이미지 전용 앱에는 Media3가 포함되지 않습니다.

요구 사항: minSdk 26, compileSdk 37, Java 17 target. 빌드 기준은 [docs/build-baseline.md](docs/build-baseline.md)에 있습니다.

## Quick Start

```kotlin
class MainActivity : ComponentActivity() {

    private val editor = registerForActivityResult(ImageEditorContract()) { result ->
        when (result) {
            is FrameKitResult.Success -> showImage(result.output.uri)
            FrameKitResult.Cancelled -> Unit
            is FrameKitResult.Failure -> showError(result.error.code)
        }
    }

    fun editPhoto() {
        // 시스템 Photo Picker를 먼저 띄운다. 이미 Uri가 있으면 EditorInput.UriSource(uri)를 넘긴다.
        editor.launch(ImageEditorRequest(input = EditorInput.Pick()))
    }
}
```

Compose에서는 `rememberLauncherForActivityResult(ImageEditorContract())`를 씁니다. `showImage`, `showError`는 호스트 앱이 구현합니다.

설정을 바꾸려면 request에 값을 넣습니다.

```kotlin
ImageEditorRequest(
    input = EditorInput.UriSource(uri),
    config = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP)),
    export = ImageExportConfig(format = ImageFormat.PNG, maxWidth = 2048),
    ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, accentArgb = 0xFF1E6BFF.toInt()),
)
```

모든 옵션의 기본값·단위·범위는 [docs/configuration.md](docs/configuration.md)에 정리했습니다.

## 결과 파일과 권한

- 결과는 `files/framekit/exports/`에 저장되고 SDK 전용 FileProvider(`<applicationId>.framekit.files`)의 `content://` Uri로 전달됩니다.
- 결과 파일은 **호스트 앱 소유**입니다. SDK는 성공한 결과를 자동으로 지우지 않습니다. 필요 없으면 `FrameKitOutputs.deleteOutput(context, uri)`를 호출하세요.
- 다른 앱에 공유할 때는 Intent에 `FLAG_GRANT_READ_URI_PERMISSION`과 `ClipData`를 함께 넣습니다.
- SDK manifest는 `INTERNET`, `READ_MEDIA_*`, 저장소 권한을 추가하지 않습니다. 원본 Uri의 읽기 권한은 호스트가 제공합니다.
- 기본 메타데이터 정책 `SAFE`는 촬영 시각과 촬영 설정만 남기고 위치·기기 일련번호·주석·원본 썸네일을 제거합니다.

자세한 내용은 [docs/integration.md](docs/integration.md)를 보세요.

## 구조

```
framekit-core       Kotlin JVM. 프로젝트 모델, 좌표·행렬, crop 계산, undo/redo, 검증
framekit-android    Uri/파일 source 등록, 결과 DTO, 오류 코드, AppFile 출력·FileProvider
framekit-image      EXIF·디코딩, RenderPlan, Canvas renderer, JPEG/PNG export
framekit-ui         Compose 공통 테마·컴포넌트·문구
framekit-ui-image   이미지 편집 화면, ImageEditorContract
app                 Showcase 앱
```

미리보기와 저장은 같은 `ImageRenderPlan`과 같은 renderer를 사용하므로 화면에서 본 결과와 저장된 결과가 일치합니다. 모듈 의존 규칙과 데이터 흐름은 [docs/architecture.md](docs/architecture.md)에 있습니다.

## Showcase

`app` 모듈은 SDK를 실제로 호출하는 예제 앱입니다.

| 예제 | 설정 |
| --- | --- |
| 사진 편집 | 기본 설정 |
| 회전만 | `enabledTools = {ROTATE}` |
| PNG 저장 | `format = PNG` |
| 제한 모드 | 자르기만, undo 끔, 최대 1080px·품질 85 |
| 브랜드 테마 | 라이트 테마, 파란 accent, 모서리 22dp |
| 영어 UI | `localeTag = "en"` |

결과 화면에서 출력 크기·MIME·파일 크기·경고를 확인하고 공유하거나 삭제할 수 있습니다.

```bash
./gradlew :app:installDebug
```

## 검증

```bash
./gradlew test lint :app:assembleDebug :app:assembleRelease
```

| 범위 | 내용 |
| --- | --- |
| JVM 단위 테스트 | undo/redo(D01~D07), 검증(V01), EXIF·crop·좌표 변환(G01~G04) |
| Robolectric | EXIF 1..8 디코딩(API 27/36 두 경로), renderer 결과 픽셀, preview/export 일치, export 실패·취소 정리(Q07~Q09), 편집 흐름(Q01·Q10), 세션 저장·복원(Q13·Q14) |
| 에뮬레이터 | API 36 폴더블 에뮬레이터에서 Picker → 자르기 → 수평 맞추기 → 저장 → 결과 확인 (debug 빌드) |

**아직 검증하지 않은 것**: 실기기(API 26/27 저사양, Galaxy S23 등), release 빌드의 기기 실행, 성능 수치, 기기에서 실제 프로세스를 종료한 뒤의 복원(자동 테스트로만 확인). 검증 전에는 지원한다고 표시하지 않습니다.

## Known Issues

- 프로세스 종료 후에는 확정한 편집만 돌아오고 undo 기록과 열려 있던 도구의 미적용 변경은 사라집니다(설계상 동작).
- `localeTag`를 쓰는 호스트가 App Bundle language split을 켜 두면 기기 언어가 아닌 문구 리소스가 빠질 수 있습니다. [integration 문서](docs/integration.md#언어)를 참고하세요.
- 확대·이동(pan/zoom) 제스처는 아직 없습니다.
- 출력 대상은 `OutputTarget.AppFile`만 지원합니다. MediaStore·SAF 문서 저장은 계획 중입니다.

전체 변경 이력은 [docs/release-notes.md](docs/release-notes.md)에 있습니다.

## Roadmap

| 버전 | 범위 |
| --- | --- |
| v0.1 | 이미지 자르기·회전·반전, JPEG/PNG, undo/redo, 기본 UI와 Activity Result 계약 |
| v0.2 | 보정 13종·필터 12종, 텍스트·스티커·그리기, 블러·모자이크, WEBP, 이미지 headless |
| v0.3 | 단일 영상 재생·trim·crop·회전·보정·속도·음량, MP4 저장 (Media3) |
| v0.4 | 여러 클립, 분할·순서 변경, 배경 음악, 시간 지정 텍스트·스티커 |
| v1.0 | 사용자 정의 필터·스티커·폰트, 이미지·영상 headless, Maven 배포, 문서 |

## 기여

빌드 기준, 커밋 규칙, 검증 방법은 [CONTRIBUTING.md](CONTRIBUTING.md)를 보세요.

## License

배포 라이선스는 Maven 배포 전에 확정합니다. 편집기 아이콘 일부는 Apache License 2.0인 [Material Icons](https://github.com/google/material-design-icons)의 path 데이터를 사용합니다([NOTICE](NOTICE)).
