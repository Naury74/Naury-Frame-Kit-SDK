# FrameKit

Android 앱에 넣어 쓰는 **비파괴 이미지·영상 편집 SDK**입니다. 호스트 앱은 Uri를 넘기거나 내장 Photo Picker를 띄우고, FrameKit 편집 화면에서 편집한 뒤 결과 파일의 `content://` Uri와 메타데이터를 받습니다. 원본은 절대 덮어쓰지 않습니다.

> 현재 버전은 **v0.2 (개발 중)** 입니다. 사진 편집(자르기·회전·보정·필터·텍스트·이모지 스티커·그리기·모자이크)과 UI 없는 headless 저장이 동작합니다. 영상 편집은 v0.3에서 추가합니다. Maven 배포 전이므로 지금은 소스 모듈로 의존합니다.

호스트 앱은 SDK 라이브러리만 추가하면 됩니다. 편집 화면(Activity)은 SDK 안에 있고 manifest merge로 자동 등록되며, 호스트는 Activity Result 한 번으로 편집 화면을 띄우고 결과 파일을 돌려받습니다. 저장소의 `app` 모듈은 SDK를 확인·시연하기 위한 Showcase입니다.

## 지원 기능

| 기능 | 내용 | 상태 |
| --- | --- | --- |
| 자르기 | 자유·원본·1:1·4:3·3:4·16:9·9:16·3:2·2:3, 모서리·변 핸들, 이동 | v0.1 지원 |
| 회전·반전 | 90° 회전, 좌우·상하 반전, 수평 맞추기 ±45° | v0.1 지원 |
| 실행 취소 | 도구 한 번 사용 = 한 단계, 최대 50단계, redo | v0.1 지원 |
| 보정 | 밝기·노출·대비·하이라이트·그림자·채도·색온도·색조·선명도·페이드·비네트·그레인, 드래그 1회 = 1단계 | v0.2 지원 |
| 필터·템플릿 | 화사하게·부드럽게·러블리·드라마틱 템플릿과 클린·비비드·웜·쿨·필름 01~03·모노·페이드·빈티지·시네마, 강도 조절 | v0.2 지원 |
| 텍스트 | 폰트 6종·색·정렬·외곽선·배경·그림자, 한글·이모지, 이동·확대·회전 | v0.2 지원 |
| 스티커 | 표준 이모지 9개 분류(기기 이모지 폰트로 렌더) | v0.2 지원 |
| 그리기 | 펜·마커·형광펜·지우개(그리기만 지움), 색·굵기·불투명도 | v0.2 지원 |
| 가리기 | 모자이크·블러, 브러시·사각형·원 | v0.2 지원 |
| 배경 제거(누끼) | 피사체만 남기고 투명 배경, 기기 안에서 처리(선택 모듈 `framekit-segmentation`) | v0.2 지원 |
| 저장 | JPEG(품질 0..100)·PNG·WEBP(손실/무손실), 최대 16MP, 확대 없음, EXIF SAFE/NONE/ALL | v0.2 지원 |
| Headless | `ImageProcessor`로 UI 없이 편집·저장, `ExportHandle` 상태·취소 | v0.2 지원 |
| 큰 사진 | 250MP까지 열기, 자른 영역·띠 단위 디코딩으로 메모리 제한, 실패 시 앱 종료 없이 오류 반환 | v0.2 지원 |
| 폴드·태블릿 | 화면 크기·폴드 자세(탁상·책)에 맞춘 배치, 접고 펴도 편집 유지 | v0.2 지원 |
| 입력 | content Uri, 앱 내부 파일, 시스템 Photo Picker | v0.1 지원 |
| 결과 | Success/Cancelled/Failure 한 번만 반환, 오류 코드와 다음 행동 | v0.1 지원 |
| 세션 복원 | 프로세스가 종료돼도 확정한 편집 복원, 원본이 같은 이미지인지 확인 | v0.1 지원 |
| 테마·언어 | 다크(기본)/라이트/시스템, accent 색, 모서리, ko/en, localeTag | v0.1 지원 |

계획된 기능(아직 **미지원**): 단일 영상 편집과 영상 모자이크(v0.3) · 다중 클립 타임라인(v0.4) · 사용자 정의 필터·스티커·폰트, Maven 배포(v1.0). 자세한 순서는 [Roadmap](#roadmap)을 보세요.

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

배경 제거가 필요하면 선택 모듈을 추가합니다. 추가하면 편집기에 "배경 제거" 도구가 자동으로 나타납니다.

```kotlin
implementation(project(":framekit-segmentation"))
```

이 모듈은 Google Play 서비스의 ML Kit을 쓰므로 Play 서비스가 없는 기기에서는 동작하지 않고, ML Kit 의존성이 `INTERNET`·`ACCESS_NETWORK_STATE` 권한을 추가합니다. 자세한 내용은 [integration](docs/integration.md#배경-제거-선택-모듈)을 보세요.

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

## UI 없이 처리 (headless)

편집 화면 없이 코드로 편집하고 저장할 수 있습니다. 편집 화면과 같은 렌더러를 쓰므로 결과가 같습니다.

```kotlin
ImageProcessor(context).use { processor ->
    val source = processor.open(EditorInput.UriSource(uri))
    val project = processor.newProject(source).copy(filter = FilterSelection("bright", 1.0))
    val handle = processor.startExport(project, source, ImageExportConfig(), scope = lifecycleScope)
    when (val result = handle.awaitResult()) {
        is FrameKitResult.Success -> showImage(result.output.uri)
        FrameKitResult.Cancelled -> Unit
        is FrameKitResult.Failure -> showError(result.error.code)
    }
}
```

자세한 내용은 [docs/headless.md](docs/headless.md)를 보세요.

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
framekit-image      디코딩, RenderPlan, OpenGL 색 보정(CPU 대체), 오버레이·가리기 렌더러, 저장, headless
framekit-ui         Compose 공통 테마·컴포넌트·문구
framekit-ui-image   이미지 편집 화면, ImageEditorContract
framekit-segmentation  (선택) ML Kit 배경 제거
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
| Robolectric | EXIF 1..8 디코딩(API 27/36), 영역·띠 디코딩, 렌더 픽셀, preview/export 일치, 오버레이 위치(G05)·지우개·형광펜, 모자이크 격자(Q06), 저장 실패·취소 정리(Q07~Q09), 편집 흐름(Q01·Q10), 세션 복원(Q13·Q14), headless |
| 실기기 계측 | Galaxy Z Fold7(Android 16): GL 색 보정이 CPU 기준과 일치(보정 12종·프리셋 전부, 평균 오차 ≤ 1/255) |
| 실기기 수동 | Galaxy Z Fold7 릴리스 빌드: 편집·저장, 프로세스 종료 후 복원, 108MP 사진 저장, 펼친 화면·태블릿 크기 전환, 필터·비네트 |

**아직 검증하지 않은 것**: API 26/27 저사양 기기, Galaxy S23 성능 기준, 텍스트·스티커·그리기·가리기의 실기기 수동 확인, 성능 수치. 검증 전에는 지원한다고 표시하지 않습니다.

## Known Issues

- 프로세스 종료 후에는 확정한 편집만 돌아오고 undo 기록과 열려 있던 도구의 미적용 변경은 사라집니다(설계상 동작).
- `localeTag`를 쓰는 호스트가 App Bundle language split을 켜 두면 기기 언어가 아닌 문구 리소스가 빠질 수 있습니다. [integration 문서](docs/integration.md#언어)를 참고하세요.
- 캔버스 확대·이동(pan/zoom) 제스처는 아직 없습니다.
- 그리기의 필압은 저장하지만 굵기 변화에는 아직 반영하지 않습니다.
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
