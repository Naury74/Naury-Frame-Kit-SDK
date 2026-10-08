# FrameKit

Android 앱에 넣어 쓰는 **비파괴 이미지·영상 편집 SDK**입니다. 호스트 앱은 Uri를 넘기거나 내장 Photo Picker를 띄우고, FrameKit 편집 화면에서 편집한 뒤 결과 파일의 `content://` Uri와 메타데이터를 받습니다. 원본은 절대 덮어쓰지 않습니다.

> 현재 버전은 **1.0.0-alpha01** 입니다. 사진 편집(자르기·회전·보정·필터·텍스트·이모지 스티커·그리기·모자이크·배경 제거)과 여러 장 사진 편집·PDF 문서 저장, 카메라로 찍은 직후 편집, UI 없는 headless 저장, 여러 클립 영상 편집(나누기·순서 변경, 구간·자르기·회전·보정·필터·속도·소리·배경 음악·텍스트·스티커·모자이크, MP4 저장), 호스트 필터·스티커·폰트 카탈로그, 색 팔레트, 사진·영상 headless 처리가 동작합니다. Maven Central 배포 전이므로 지금은 `publishToMavenLocal` 또는 소스 모듈로 의존합니다.

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
| 여러 장 사진 | 최대 100장(기본 20)을 한 화면에서 한 장씩 편집, 쪽 추가·순서 변경·삭제, 사진마다 저장 | v1.0 지원 |
| PDF 문서 | 편집한 사진(여러 장이면 쪽 순서대로)을 PDF로 저장. A4·A5·Letter·Legal·사진 크기, 방향, 여백, dpi, 한 문서/사진마다. 문서·문서 흑백 필터 | v1.0 지원 |
| 문서 스캔 | 문서 사진을 열면 자동 감지·제안, 네 모서리 자동 감지와 끌기(돋보기), 반듯하게 펴기, 스캔 보정(그림자 제거·흰 배경·흑백), 인식한 글자 보기·복사, 원본으로 되돌리기 | v1.0 지원 |
| PDF 글자 검색(OCR) | 선택 모듈 `framekit-ocr`로 쪽마다 글자를 인식해 보이지 않는 글자 레이어 추가(검색·복사), 한국어·영어, 기기 안에서 처리 | v1.0 지원 |
| Headless | `ImageProcessor`로 UI 없이 편집·저장, `ExportHandle` 상태·취소 | v0.2 지원 |
| 큰 사진 | 250MP까지 열기, 자른 영역·띠 단위 디코딩으로 메모리 제한, 실패 시 앱 종료 없이 오류 반환 | v0.2 지원 |
| 폴드·태블릿 | 화면 크기·폴드 자세(탁상·책)에 맞춘 배치, 접고 펴도 편집 유지 | v0.2 지원 |
| 입력 | content Uri, 앱 내부 파일, 여러 원본, 시스템 Photo Picker(한 개·여러 개), 카메라 촬영·녹화 직후 | v1.0 지원 |
| 편집 화면 사용성 | 원본 비교 버튼, 사진·영상 두 손가락 확대·두 번 탭 확대, 자르기 영역 자동 맞춤, 가로 스크롤 끝 흐림, FrameKit 디자인 대화상자, 자르기 비율 직접 입력·자르기 중 회전, 뒤로 가기 시 적용 여부 확인, 저장 시 열린 도구 자동 적용, 접근성(화면 읽기·48dp·색 외 선택 표시) | v1.0 지원 |
| 결과 | Success/Cancelled/Failure 한 번만 반환, 오류 코드와 다음 행동 | v0.1 지원 |
| 세션 복원 | 프로세스가 종료돼도 확정한 편집 복원, 원본이 같은 이미지인지 확인 | v0.1 지원 |
| 테마·언어 | 다크(기본)/라이트/시스템, 프라이머리(브랜드) 색(`primaryArgb`, 위 글자색 자동 대비), 모서리, 13개 언어(한국어·영어·일본어·중국어(간체·번체)·베트남어·태국어·인도네시아어·러시아어·스페인어·포르투갈어(브라질)·프랑스어·독일어), localeTag | v1.0 지원 |

영상 편집(Media3 1.11.1)

| 기능 | 내용 | 상태 |
| --- | --- | --- |
| 재생·탐색 | 미리보기 재생·일시정지, 가운데 재생 헤드 썸네일 타임라인(끌어 탐색, 두 손가락 확대 1초당 8~240dp) | v0.3/v0.4 지원 |
| 여러 클립 | 여러 영상을 한 번에 열거나 클립 추가(`maxClipCount`, 기본 10·최대 20), 재생 위치에서 나누기, 앞뒤로 옮기기, 삭제. 각각 실행 취소 한 단계 | v0.4 지원 |
| 화면 비율 | 첫 클립·9:16·16:9·1:1·4:5·3:4, 모양이 다른 클립은 맞추기(여백)·채우기(가장자리 자름) | v0.4 지원 |
| 배경 음악 | 오디오 파일 추가, 재생 위치에서 시작, 곡 시작 위치, 볼륨, 반복. 영상보다 길어지지 않음 | v0.4 지원 |
| 텍스트·스티커 | 사진과 같은 텍스트·이모지 스티커를 원하는 구간 동안 표시, 화면에서 옮기고 크기·각도 조절 | v0.4 지원 |
| 구간 자르기 | 시작·끝 손잡이, 최소 길이·최대 길이 제한(기본 1초~5분), 출력 1프레임 이내 정확도 | v0.3 지원 |
| 자르기·회전 | 사진과 같은 비율·핸들·90° 회전·반전·수평 맞추기 | v0.3 지원 |
| 보정·필터 | 사진과 같은 보정 값과 필터·템플릿(같은 GLSL로 같은 색), 선명도는 사진 전용 | v0.3 지원 |
| 속도 | 0.25·0.5·1·1.5·2·4배, 소리 높이 유지, 적용·취소 | v0.3 지원 |
| 소리 | 음소거(재생 줄 버튼), 볼륨 0~200% | v0.3 지원 |
| 모자이크·블러 | 사각형·원 영역을 지정한 시간 구간에만 적용, 화면에서 옮기고 크기 조절, 최대 8개 | v0.3 지원 |
| 저장 | MP4(H.264/AAC), 짧은 변 최대 1080px·30fps 기본, HDR은 SDR로 변환, 진행률·취소 | v0.3 지원 |
| 세션 복원 | 프로세스 종료 후 확정한 영상 편집 복원 | v0.3 지원 |
| 통합 진입점 | `FrameKitContract` 하나로 사진·영상 중 알맞은 편집기 실행, 사진 또는 영상 고르기 | v0.3 지원 |

SDK 확장(v1.0)

| 기능 | 내용 | 상태 |
| --- | --- | --- |
| 호스트 카탈로그 | 앱의 필터 값·스티커 이미지·폰트 파일을 `EditorCatalog`로 넘겨 편집기·저장·headless에 추가, 내장 항목 숨기기 | v1.0 지원 |
| 색·폰트 | `EditorPalette`로 배경·패널·글자 색 변경, 명암비 경고, 카탈로그 폰트를 편집기 문구에 사용 | v1.0 지원 |
| 영상 headless | `VideoProcessor`로 UI 없이 클립·음악·효과를 MP4로 저장 | v1.0 지원 |
| 배포 | Maven 좌표 `io.github.naury74:<모듈>`, sources jar, Kotlin 2.2 이상 호스트 지원 | v1.0 지원(로컬 배포 확인) |

계획된 기능(아직 **미지원**): 장면 전환(crossfade)·PiP·HDR 유지·MediaStore/SAF 저장·Maven Central 공개 배포. 자세한 순서는 [Roadmap](#roadmap)을 보세요.

## 설치

필요한 범위에 맞춰 모듈 하나를 고릅니다. 하위 모듈은 Gradle 메타데이터로 함께 들어옵니다.

| 모듈 | 용도 |
| --- | --- |
| `framekit` | 사진·영상 편집 화면 모두, `FrameKitContract` |
| `framekit-ui-image` | 사진 편집 화면만. Media3가 포함되지 않음 |
| `framekit-ui-video` | 영상 편집 화면만 |
| `framekit-image` / `framekit-video` | UI 없는 처리(headless)와 엔진 |

Maven Central 공개 전에는 이 저장소에서 로컬 Maven에 올린 뒤 씁니다.

```bash
./gradlew publishToMavenLocal   # io.github.naury74:*:1.0.0-alpha01
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.naury74:framekit:1.0.0-alpha01")
}
```

저장소를 함께 빌드하려면 `include(":framekit-core", …)`로 모듈을 포함하고 `implementation(project(":framekit"))`를 씁니다. 호스트 앱은 Compose를 쓰지 않아도 되고 Kotlin 2.2 이상이면 됩니다. 별도 소비자 앱(Compose 없음, Kotlin 2.2, R8 release)에서 `mavenLocal` 산출물로 영상 편집·저장·결과 수신까지 확인했습니다.

배경 제거·PDF 글자 검색이 필요하면 선택 모듈을 추가합니다. 추가하면 편집기에 "배경 제거" 도구가 나타나고, PDF 저장에 글자 레이어가 들어갑니다.

```kotlin
implementation("io.github.naury74:framekit-segmentation:1.0.0-alpha01") // 배경 제거
implementation("io.github.naury74:framekit-ocr:1.0.0-alpha01")          // PDF 글자 검색(OCR)
```

두 모듈은 Google Play 서비스의 ML Kit을 쓰므로 Play 서비스가 없는 기기에서는 동작하지 않고, ML Kit 의존성이 `INTERNET`·`ACCESS_NETWORK_STATE` 권한을 추가합니다. 자세한 내용은 [integration](docs/integration.md#배경-제거-선택-모듈)을 보세요.

요구 사항: minSdk 26, compileSdk 37, Java 17 target. 빌드 기준은 [docs/build-baseline.md](docs/build-baseline.md)에 있습니다.

## Quick Start

```kotlin
class MainActivity : ComponentActivity() {

    private val editor = registerForActivityResult(FrameKitContract()) { result ->
        when (result) {
            // mediaType으로 사진(IMAGE)인지 영상(VIDEO)인지 구분한다.
            is FrameKitResult.Success -> showMedia(result.output.uri, result.output.mediaType)
            FrameKitResult.Cancelled -> Unit
            is FrameKitResult.Failure -> showError(result.error.code)
        }
    }

    fun edit() {
        // 시스템 Photo Picker에서 사진이나 영상을 고르면 알맞은 편집기가 열린다.
        // 이미 Uri가 있으면 EditorInput.UriSource(uri)를 넘긴다.
        editor.launch(FrameKitRequest(input = EditorInput.Pick(MediaKind.ANY)))
    }
}
```

Compose에서는 `rememberLauncherForActivityResult(FrameKitContract())`를 씁니다. 여러 결과(사진마다 저장)는 `result.outputs`에 모두 들어 있습니다.

입력만 바꾸면 여러 경우에 같은 진입점을 씁니다.

```kotlin
EditorInput.UriSource(uri)                                   // 사진 또는 영상 한 개
EditorInput.Multiple(uris.map(EditorInput::UriSource))       // 사진 여러 장, 또는 영상 여러 개(이어 붙임)
EditorInput.Pick(MediaKind.IMAGE, maxItems = 10)             // Photo Picker에서 최대 10장
EditorInput.Capture(MediaKind.IMAGE)                         // 카메라로 찍은 직후 편집 (VIDEO는 녹화)

// 여러 장을 A4 PDF 하나로
FrameKitRequest(
    input = EditorInput.Pick(MediaKind.IMAGE, maxItems = 20),
    imageExport = ImageExportConfig(format = ImageFormat.PDF, pdf = PdfOptions(pageSize = PdfPageSize.A4)),
)
```

카메라 권한 규칙과 진입점별 예시는 [integration](docs/integration.md#입력)에 있습니다. `showMedia`, `showError`는 호스트 앱이 구현합니다. 사진만 쓰는 앱은 `ImageEditorContract`/`ImageEditorRequest`, 영상만 쓰는 앱은 `VideoEditorContract`/`VideoEditorRequest`를 직접 써도 됩니다.

설정을 바꾸려면 request에 값을 넣습니다.

```kotlin
FrameKitRequest(
    input = EditorInput.UriSource(uri),
    image = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP)),
    imageExport = ImageExportConfig(format = ImageFormat.PNG, maxWidth = 2048),
    video = VideoEditorConfig(maxTimelineDurationUs = 15_000_000),   // 최대 15초
    videoExport = VideoExportConfig(maxShortSide = 720),
    ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, primaryArgb = 0xFF1E6BFF.toInt()),
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

영상은 `VideoProcessor`로 같은 방식입니다.

```kotlin
val processor = VideoProcessor(context)
val source = processor.open(EditorInput.UriSource(videoUri))
val project = processor.newProject(source)   // 원본 전체를 한 클립으로
val handle = processor.startExport(project, listOf(source), scope = lifecycleScope)
```

자세한 내용은 [docs/headless.md](docs/headless.md)를 보세요.

## 호스트 카탈로그와 테마

앱의 필터·스티커·폰트를 편집기에 더하고, 색과 폰트를 브랜드에 맞출 수 있습니다.

```kotlin
FrameKitRequest(
    input = EditorInput.Pick(MediaKind.ANY),
    catalog = EditorCatalog(
        filters = listOf(CustomFilter("brand-warm", "Warm", temperature = 0.4, saturation = 0.1)),
        stickers = listOf(CustomSticker("logo", "Logo", CatalogFile.Asset("stickers/logo.png"))),
        fonts = listOf(CustomFont("brand", "Brand", CatalogFile.Asset("fonts/brand.ttf"))),
    ),
    ui = EditorUiConfig(
        primaryArgb = 0xFF1E6BFF.toInt(),
        palette = EditorPalette(backgroundArgb = 0xFF101820.toInt()),
        uiFontId = "brand",
    ),
)
```

프로젝트에는 id만 저장되므로 id는 앱 버전이 바뀌어도 같은 항목을 가리켜야 합니다. 자세한 규칙은 [docs/configuration.md](docs/configuration.md#catalog-editorcatalog)를 보세요.

## 결과 파일과 권한

- 결과(사진·PDF·MP4)는 `files/framekit/exports/`에 저장되고 SDK 전용 FileProvider(`<applicationId>.framekit.files`)의 `content://` Uri로 전달됩니다.
- 결과 파일은 **호스트 앱 소유**입니다. SDK는 성공한 결과를 자동으로 지우지 않습니다. 필요 없으면 `FrameKitOutputs.deleteOutput(context, uri)`를 호출하세요.
- 다른 앱에 공유할 때는 Intent에 `FLAG_GRANT_READ_URI_PERMISSION`과 `ClipData`를 함께 넣습니다.
- SDK manifest는 `INTERNET`, `READ_MEDIA_*`, `CAMERA`, 저장소 권한을 추가하지 않습니다. 원본 Uri의 읽기 권한은 호스트가 제공합니다. 호스트가 `CAMERA`를 선언한 경우에만 `Capture` 때 권한을 요청합니다.
- 기본 메타데이터 정책 `SAFE`는 촬영 시각과 촬영 설정만 남기고 위치·기기 일련번호·주석·원본 썸네일을 제거합니다.

자세한 내용은 [docs/integration.md](docs/integration.md)를 보세요.

## 구조

```
framekit-core       Kotlin JVM. 프로젝트 모델, 좌표·행렬, crop 계산, undo/redo, 검증
framekit-android    Uri/파일 source 등록, 결과 DTO, 오류 코드, AppFile 출력·FileProvider
framekit-image      디코딩, RenderPlan, OpenGL 색 보정(CPU 대체), 오버레이·가리기 렌더러, 저장, headless
framekit-ui         Compose 공통 테마·컴포넌트·문구
framekit-ui-image   이미지 편집 화면, ImageEditorContract
framekit-video      Media3 어댑터: 미리보기 플레이어, Transformer 저장, 색·모자이크 GL 효과, 썸네일
framekit-ui-video   영상 편집 화면(타임라인), VideoEditorContract
framekit            통합 모듈: FrameKitContract가 원본 종류에 맞는 편집기를 연다
framekit-segmentation  (선택) ML Kit 배경 제거
framekit-ocr        (선택) ML Kit 글자 인식, PDF 글자 레이어
app                 Showcase 앱
```

미리보기와 저장은 같은 `ImageRenderPlan`과 같은 renderer를 사용하므로 화면에서 본 결과와 저장된 결과가 일치합니다. 영상도 미리보기와 저장이 같은 `VideoRenderPlan`에서 Media3 Composition을 만들고, 색 보정은 사진과 같은 GLSL을 씁니다. Media3 타입은 `framekit-video` 밖으로 나오지 않습니다. 모듈 의존 규칙과 데이터 흐름은 [docs/architecture.md](docs/architecture.md)에 있습니다.

## Showcase

`app` 모듈은 SDK를 실제로 호출하는 예제 앱입니다.

| 예제 | 설정 |
| --- | --- |
| Playground | 도구·실행 취소·테마·프라이머리 색·모서리·언어·저장 설정을 골라 실제 요청으로 실행. SDK가 거부하는 조합을 실행 전에 표시 |
| 사진 편집 | 기본 설정 |
| 영상 편집 | 최대 10개 클립, 모든 영상 도구, MP4 저장 |
| 사진 또는 영상 | `Pick(MediaKind.ANY)`, 고른 종류의 편집기가 열림 |
| 여러 장 사진 | 최대 10장을 골라 한 장씩 편집하고 사진마다 저장 |
| 사진을 PDF로 | 최대 20쪽, 문서 보정·문서 필터, 글자 검색이 되는 A4 PDF 하나로 저장 |
| 카메라로 찍기 / 녹화 | `Capture(IMAGE)` / `Capture(VIDEO)` |
| 사진·영상 여러 개 | `Pick(ANY, maxItems)`, 모두 사진이면 사진 편집기, 모두 영상이면 영상 편집기 |
| 짧은 클립 | 최대 15초·720p, 구간·속도·소리·필터만 |
| 영상 모자이크 | 가리기·구간 도구만 |
| 회전만 | `enabledTools = {ROTATE}` |
| PNG 저장 | `format = PNG` |
| 제한 모드 | 자르기만, undo 끔, 최대 1080px·품질 85 |
| 호스트 카탈로그 | 앱 assets의 스티커 이미지 2개와 필터 2개(Sunset, Ocean) 추가 |
| 브랜드 테마 | 라이트 테마, 파란 프라이머리 색, 모서리 22dp |
| 영어 UI | `localeTag = "en"` |

결과 화면에서 출력 크기·길이·MIME·파일 크기·경고를 확인하고 공유하거나 삭제할 수 있습니다.

```bash
./gradlew :app:installDebug
```

## 검증

```bash
./gradlew test lint :app:assembleDebug :app:assembleRelease publishToMavenLocal
```

| 범위 | 내용 |
| --- | --- |
| JVM 단위 테스트 | undo/redo(D01~D07), 검증(V01), EXIF·crop·좌표 변환(G01~G04) |
| Robolectric | EXIF 1..8 디코딩(API 27/36), 영역·띠 디코딩, 렌더 픽셀, preview/export 일치, 오버레이 위치(G05)·지우개·형광펜, 모자이크 격자(Q06), 저장 실패·취소 정리(Q07~Q09), 편집 흐름(Q01·Q10), 세션 복원(Q13·Q14), headless |
| 실기기 계측 | Galaxy Z Fold7(Android 16): GL 색 보정이 CPU 기준과 일치(보정 12종·프리셋 전부, 평균 오차 ≤ 1/255) |
| 실기기 계측(영상) | 실제 Media3 저장: 구간 정확도(1프레임 이내), 2배속 길이, 회전·반전 방향, 필터 색, 구간 모자이크, 취소 시 파일 없음, 미리보기 준비, 소리 있는·없는 클립 이어 붙이기, 나누기·순서 변경 길이, 반복 음악이 영상보다 길어지지 않음, 구간 텍스트, 정사각형 맞추기·채우기 |
| 실기기 수동 | Galaxy Z Fold7 릴리스 빌드: 편집·저장, 프로세스 종료 후 복원, 108MP 사진 저장, 펼친 화면·태블릿 크기 전환, 필터·비네트 |
| 에뮬레이터 수동 | 폴더블 에뮬레이터(Android 16): 영상 선택→구간 자르기→MP4 저장, 미리보기 모자이크, 넓은 화면 배치, 클립 추가·스티커·두 클립 저장, 호스트 스티커·필터, Playground 검증 표시 |
| 소비자 앱 | 별도 프로젝트(Compose 없음, Kotlin 2.2, R8 release)가 `mavenLocal`의 `framekit`으로 영상 편집·필터·저장 결과 수신 |

**아직 검증하지 않은 것**: API 26/27 저사양 기기, Galaxy S23 성능 기준, 텍스트·스티커·그리기·가리기와 영상 편집 화면의 실기기 수동 확인, 4K·HDR 영상, 성능 수치. 검증 전에는 지원한다고 표시하지 않습니다.

## Known Issues

- 프로세스 종료 후에는 확정한 편집만 돌아오고 undo 기록과 열려 있던 도구의 미적용 변경은 사라집니다(설계상 동작).
- `localeTag`를 쓰는 호스트가 App Bundle language split을 켜 두면 기기 언어가 아닌 문구 리소스가 빠질 수 있습니다. [integration 문서](docs/integration.md#언어)를 참고하세요.
- 그리기의 필압은 저장하지만 굵기 변화에는 아직 반영하지 않습니다.
- 영상 모자이크는 사각형·원만 지원합니다(브러시는 사진 전용). 영상 블러는 원형 샘플 평균이라 사진 블러와 모양이 조금 다릅니다.
- 영상 선명도(sharpness)는 미리보기·저장 모두 적용하지 않습니다.
- 배경 음악은 한 곡만 넣을 수 있습니다(모델은 여러 곡을 지원). 여러 소리를 섞을 때 리미터가 없어 볼륨을 크게 올리면 소리가 찌그러질 수 있습니다.
- 나중에 붙인 영상·음악은 persistable 권한을 주지 않는 provider라면 프로세스 종료 뒤 그 부분을 빼고 복원합니다.
- 사진과 영상을 한 번에 섞어 편집할 수는 없습니다(`UNSUPPORTED_OPERATION`).
- 출력 대상은 `OutputTarget.AppFile`만 지원합니다. MediaStore·SAF 문서 저장은 계획 중입니다.

전체 변경 이력은 [docs/release-notes.md](docs/release-notes.md)에 있습니다.

## Roadmap

| 버전 | 범위 |
| --- | --- |
| v0.1 | 이미지 자르기·회전·반전, JPEG/PNG, undo/redo, 기본 UI와 Activity Result 계약 |
| v0.2 | 보정 12종·필터와 템플릿, 텍스트·스티커·그리기, 블러·모자이크, 배경 제거, WEBP, 이미지 headless |
| v0.3 | 단일 영상 재생·구간·자르기·회전·보정·필터·속도·소리·구간 모자이크, MP4 저장 (Media3), 통합 FrameKitContract |
| v0.4 | 여러 클립, 나누기·순서 변경, 화면 비율, 배경 음악, 구간 텍스트·스티커 |
| v1.0 | 사용자 정의 필터·스티커·폰트, 색 팔레트·문구 폰트, 이미지·영상 headless, 여러 장 사진·PDF·카메라 진입점, 편집 화면 사용성·접근성, Playground, Maven 배포 설정, Apache 2.0, 문서 (1.0.0-alpha01) |

## 기여

빌드 기준, 커밋 규칙, 검증 방법은 [CONTRIBUTING.md](CONTRIBUTING.md)를 보세요.

## License

```
Copyright 2026 Naury74

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

전문은 [LICENSE](LICENSE)에 있습니다. 편집기 아이콘 일부는 같은 Apache License 2.0인 [Material Icons](https://github.com/google/material-design-icons)의 path 데이터를 사용합니다([NOTICE](NOTICE)).
