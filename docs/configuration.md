# Configuration

`ImageEditorRequest`, `VideoEditorRequest`, `FrameKitRequest`의 모든 옵션입니다. 잘못된 값은 기본값으로 바꾸지 않고 `INVALID_CONFIGURATION`으로 거부합니다. 실행 전에 `request.validate()`로 미리 확인할 수 있습니다.

```kotlin
ImageEditorRequest(
    input = EditorInput.Pick(),
    config = ImageEditorConfig(),
    export = ImageExportConfig(),
    ui = EditorUiConfig(),
    output = OutputTarget.AppFile,
)
```

## input: EditorInput

| 값 | 설명 |
| --- | --- |
| `UriSource(uri)` | 읽기 권한이 있는 Uri |
| `FileSource(absolutePath)` | 호스트 앱 내부 파일 |
| `Multiple(items)` | 여러 원본. 항목은 `UriSource`/`FileSource`만, 1개 이상 |
| `Pick(kind = MediaKind.IMAGE, maxItems = 1)` | 시스템 Photo Picker. 이미지 편집기는 `IMAGE`만 허용. `maxItems` 1..100 |
| `Capture(kind)` | 카메라 앱으로 촬영 후 편집. `IMAGE`·`VIDEO`만(`ANY` 불가) |

진입점별 예시와 카메라 권한 규칙은 [integration](integration.md#입력)을 보세요.

## config: ImageEditorConfig

| 옵션 | 기본값 | 범위·규칙 |
| --- | --- | --- |
| `enabledTools` | 전체 9종 | 빠진 도구는 숨기고 해당 편집 요청도 거부. 빈 집합이면 미리보기와 저장만 가능 |
| `allowUndo` | `true` | |
| `allowRedo` | `true` | `allowUndo = false`이면서 `true`이면 오류 |
| `maxImageCount` | `20` | 한 번에 편집하는 사진 수, 1..100. 2 이상이면 쪽 목록(추가·순서 변경·삭제)이 나타남 |

도구:

| 도구 | 포함 기능 |
| --- | --- |
| `CROP` | 비율 선택(자유, 원본, 1:1, 4:3, 3:4, 16:9, 9:16, 3:2, 2:3), 핸들 드래그, 이동 |
| `ROTATE` | 90° 회전, 좌우·상하 반전, 수평 맞추기 -45°..45° |
| `ADJUST` | 보정 12종(슬라이더 -100..100 또는 0..100) |
| `FILTER` | 템플릿·필터 프리셋과 강도 |
| `TEXT` | 텍스트 추가·편집 |
| `STICKER` | 표준 이모지 스티커 |
| `DRAW` | 펜·마커·형광펜·지우개 |
| `PRIVACY` | 모자이크·블러 |
| `CUTOUT` | 배경 제거. `framekit-segmentation`이 있을 때만 표시 |

자르기·회전·텍스트는 도구를 열고 적용할 때까지의 모든 변경이 undo 한 단계입니다. 보정·필터·스티커·그리기·가리기는 바로 반영되며, 슬라이더 드래그 1회·선택 1회·획 1개·마스크 1개가 각각 한 단계입니다. undo 기록은 최대 50단계이며 이미지(Bitmap)가 아닌 편집 값만 저장합니다.

## export: ImageExportConfig

| 옵션 | 기본값 | 단위·범위 |
| --- | --- | --- |
| `format` | `JPEG` | `JPEG`, `PNG`, `WEBP_LOSSY`, `WEBP_LOSSLESS`(API 30+), `PDF` |
| `quality` | `92` | JPEG·손실 WEBP 품질 0..100. PNG·무손실 WEBP에는 적용되지 않음 |
| `maxWidth` | `null` | 출력 폭 상한(px), 양수 |
| `maxHeight` | `null` | 출력 높이 상한(px), 양수 |
| `maxOutputPixels` | `16_000_000` | `width × height` 상한, 양수 |
| `metadataPolicy` | `SAFE` | `SAFE`, `NONE`, `ALL` |
| `jpegBackgroundArgb` | `0xFF000000` | JPEG에서 투명 영역을 채울 색 |
| `pdf` | `PdfOptions()` | `format = PDF`일 때 쪽 설정. 아래 표 |

### PDF

`format = ImageFormat.PDF`이면 편집한 사진을 PDF 문서로 저장합니다. 사진 한 장이면 1쪽, 여러 장이면 쪽 목록 순서대로 쪽이 됩니다. 결과 `EditedMedia`의 `mediaType`은 `DOCUMENT`, `mimeType`은 `application/pdf`, `pageCount`는 쪽 수입니다.

| `PdfOptions` | 기본값 | 단위·범위 |
| --- | --- | --- |
| `pageSize` | `A4` | `A4`, `A5`, `LETTER`, `LEGAL`, `FIT_IMAGE`(사진 크기에 맞춘 쪽) |
| `orientation` | `AUTO` | `AUTO`(사진이 가로로 길면 가로 쪽), `PORTRAIT`, `LANDSCAPE` |
| `marginMm` | `10.0` | 네 변 여백(mm), 0..50 |
| `dpi` | `200` | 쪽에 넣는 해상도, 72..600. 원본보다 키우지 않음 |
| `backgroundArgb` | 흰색 | 여백과 투명 영역 색 |
| `combinePages` | `true` | `false`면 사진마다 PDF 한 개(`Success.outputs`에 여러 개) |

- 쪽 이미지는 JPEG(`quality` 적용)로 넣습니다. 쪽마다 디코딩·인코딩한 뒤 바로 파일에 써서 쪽 수가 많아도 메모리가 늘지 않습니다.
- 문서 스캔에는 필터의 `문서`(밝고 선명하게)·`문서 흑백` 프리셋과 자르기를 함께 쓰면 좋습니다.
- 메타데이터 정책은 PDF에 적용되지 않습니다. PDF에는 EXIF를 쓰지 않고 제작 프로그램 이름만 기록합니다.

- 출력 크기는 자른 영역의 원본 픽셀 크기에서 시작해 상한에 맞게 비율을 유지하며 줄입니다. **확대하지 않습니다.**
- 기기 메모리 예산(앱 memory class 기준)을 넘으면 `INSUFFICIENT_MEMORY`로 실패하며, 사용자 동의 없이 해상도를 낮추지 않습니다.
- `SAFE`는 촬영 시각(DateTimeOriginal 등)과 카메라 설정(제조사, 모델, 노출, 조리개, ISO, 초점 거리, 플래시, 화이트밸런스)만 복사합니다. GPS, 소유자, 일련번호, 주석, 원본 썸네일은 복사하지 않고, orientation은 normal로, 크기는 새 값으로 기록합니다. PNG에는 메타데이터를 쓰지 않습니다.
- `NONE`은 메타데이터를 쓰지 않습니다.
- `ALL`은 사용자가 명시적으로 원할 때만 쓰세요. 위치·작성자·일련번호까지 복사합니다. 방향·크기·썸네일은 이 경우에도 새로 씁니다.
- 무손실 WEBP는 API 30 미만에서 손실 압축으로 바꾸지 않고 `UNSUPPORTED_FORMAT`을 반환합니다.

> 문서의 초기 설계에서는 `maxOutputPixels`를 편집기 설정에 두었지만, UI 없이 export하는 경우에도 같은 제한이 필요해 `ImageExportConfig`로 옮겼습니다.

## ui: EditorUiConfig

| 옵션 | 기본값 | 범위·규칙 |
| --- | --- | --- |
| `themeMode` | `DARK` | `DARK`, `LIGHT`, `SYSTEM` |
| `primaryArgb` | `null` (`#635BFF`) | 프라이머리(브랜드) 색, ARGB Int. 저장 버튼·선택된 칩과 도구·슬라이더·자르기 핸들·체크 표시에 쓰임 |
| `cornerRadiusDp` | `14` | 0..32 |
| `showExportProgress` | `true` | `false`이면 단계 대신 "저장하는 중"만 표시 |
| `enableHaptics` | `true` | 시스템 햅틱 설정은 항상 존중 |
| `localeTag` | `null` | `null`은 기기 언어. 빈 문자열은 오류 |
| `palette` | `null` | `EditorPalette`. 배경·패널·강조 패널·글자·보조 글자·캔버스 여백·프라이머리 색 위 글자 색(ARGB)을 바꿈. `null`인 항목은 테마 기본값 |
| `uiFontId` | `null` | 편집기 문구 폰트. `catalog.fonts`에 있는 id여야 함 |

기본 다크 팔레트: background `#0D0D0E`, surface `#171719`, raised `#242428`, foreground `#FFFFFF`, foregroundMuted `#A1A1AA`, primary `#635BFF`, onPrimary 자동(기본 색에서는 `#FFFFFF`).

### 프라이머리 색

호스트 앱의 브랜드 색을 그대로 넘기면 됩니다. 리소스 색은 `ContextCompat.getColor(context, R.color.brand)`로 ARGB를 얻어 넘깁니다.

```kotlin
FrameKitRequest(
    input = EditorInput.Pick(MediaKind.ANY),
    ui = EditorUiConfig(primaryArgb = ContextCompat.getColor(context, R.color.brand_primary)),
)
```

- 저장 버튼처럼 프라이머리 색을 배경으로 쓰는 곳의 글자색은 `palette.onPrimaryArgb`를 주지 않으면 흰색·검정 중 명암비가 높은 쪽을 자동으로 고릅니다. 노랑 같은 밝은 색도 글자가 보입니다. 같은 계산은 `EditorPalette.readableOn(argb)`로 쓸 수 있습니다.
- 다크·라이트 테마 모두 같은 프라이머리 색을 씁니다.

`EditorPalette.contrastWarnings(base, primaryArgb)`는 다음 조합을 알려 줍니다.

- 글자·배경 명암비가 WCAG 기준(본문 4.5:1, 보조 글자·프라이머리 색 위 글자 3:1)보다 낮은 조합
- 프라이머리 색이 패널 색과 너무 비슷해(1.5:1 미만) 선택 표시가 잘 안 보이는 경우

편집기는 경고가 있어도 그대로 쓰므로 호스트가 확인하세요. Showcase Playground가 고른 색에 대해 이 경고를 보여 줍니다.

## catalog: EditorCatalog

세 request(`ImageEditorRequest`, `VideoEditorRequest`, `FrameKitRequest`)와 headless 처리기(`ImageProcessor`, `VideoProcessor`)가 같은 카탈로그를 받습니다.

```kotlin
EditorCatalog(
    filters = listOf(CustomFilter("brand-warm", "Warm", temperature = 0.4, saturation = 0.1, fade = 0.05)),
    stickers = listOf(CustomSticker("logo", "Logo", CatalogFile.Asset("stickers/logo.png"))),
    fonts = listOf(CustomFont("brand", "Brand", CatalogFile.Asset("fonts/brand.ttf"))),
    showDefaultFilters = true,
    showDefaultStickers = true,
    showDefaultFonts = true,
)
```

| 항목 | 규칙 |
| --- | --- |
| id | `a-z 0-9 . _ -` 1..64자, 종류 안에서 중복 불가, 내장 필터·폰트 id와 겹치면 안 됨. 프로젝트·세션에는 id만 저장되므로 앱 버전이 바뀌어도 같은 항목을 가리켜야 함 |
| label | 화면에 그대로 표시. 빈 문자열 불가. 언어별 문구는 호스트가 고른 값으로 넘김 |
| 개수 | 필터·스티커 각 200개, 폰트 20개까지 |
| `CustomFilter` | 색온도·색조·밝기·대비·채도 `-1..1`, 어두운/밝은 영역 색 이동 RGB 각 `-0.2..0.2`, 페이드 `0..1`. 내장 필터와 같은 수식으로 사진·영상에 같은 색을 냄. 값을 바꾸면 `version`을 올림 |
| `CustomSticker` | PNG·WEBP 등 투명 이미지. 긴 변 512px로 줄여 읽고 이미지 비율대로 표시. 에셋 id는 `StickerCatalog.assetId(id)` (`sticker:<id>`) |
| `CustomFont` | TTF·OTF. 텍스트 도구 폰트 목록 끝에 붙음 |
| `CatalogFile` | `Asset(path)`(앱 assets), `LocalFile(absolutePath)`(앱 내부 파일), `UriFile(uri)`(호스트가 읽을 수 있는 `content://`·`android.resource://`) |
| `showDefault*` | `false`이면 내장 필터(원본은 유지)·이모지 스티커·내장 폰트(기본 산세리프만 유지)를 숨김 |

- 편집기는 시작할 때와 Activity가 다시 만들어질 때마다 카탈로그를 다시 등록합니다. 파일은 편집이 끝날 때까지 같은 위치에 있어야 합니다.
- 읽지 못한 스티커 이미지는 그리지 않고, 폰트는 기본 폰트로 대신합니다. 앱을 멈추지 않습니다.
- 카탈로그는 프로세스 전체에 하나만 등록됩니다. 서로 다른 카탈로그로 편집기 두 개를 동시에 띄우지 마세요.

## VideoEditorRequest

```kotlin
VideoEditorRequest(
    input = EditorInput.Pick(MediaKind.VIDEO),
    config = VideoEditorConfig(),
    export = VideoExportConfig(),
    ui = EditorUiConfig(),
    output = OutputTarget.AppFile,
)
```

`input`이 `Pick`·`Capture`이면 `kind`는 `VIDEO`여야 합니다. `Multiple`이면 고른 순서대로 클립을 이어 붙입니다.

### config: VideoEditorConfig

| 옵션 | 기본값 | 단위·범위·규칙 |
| --- | --- | --- |
| `enabledTools` | 전체 11종 | 빠진 도구는 숨김. 빈 집합이면 미리보기와 저장만 가능 |
| `allowUndo` / `allowRedo` | `true` | 이미지와 같은 규칙 |
| `minClipDurationUs` | `1_000_000` (1초) | 출력 시간 기준 최소 길이(µs). 100_000 이상 |
| `maxTimelineDurationUs` | `600_000_000` (10분) | 출력 시간 기준 전체 최대 길이(µs). `minClipDurationUs` 이상, 1시간 이하. 더 긴 원본은 앞부분만 남긴 채로 열림 |
| `maxClipCount` | `10` | 클립 수 상한, 1..20. 2 이상이면 타임라인 아래에 클립 추가·나누기·이동·삭제 버튼이 나타남. 1이면 숨김 |

| 도구 | 포함 기능 |
| --- | --- |
| `TRIM` | 시작·끝 손잡이로 남길 구간 선택 |
| `CROP` / `ROTATE` | 사진과 같은 비율·핸들, 90° 회전·반전·수평 맞추기 |
| `ADJUST` / `FILTER` | 사진과 같은 보정 값과 필터·템플릿. 선명도는 영상에 적용되지 않음 |
| `CANVAS` | 결과 화면 비율(첫 클립·9:16·16:9·1:1·4:5·3:4)과 맞추기·채우기 |
| `SPEED` | 0.25·0.5·1·1.5·2·4배, 적용·취소. 결과 길이가 제한을 벗어나는 배속은 선택되지 않음 |
| `AUDIO` | 고른 클립의 음소거·볼륨 0~200%, 배경 음악(추가·볼륨·반복·시작 위치·곡 시작 위치). 재생 줄의 음소거 버튼도 이 도구가 켜져 있을 때만 표시 |
| `TEXT` / `STICKER` | 재생 위치부터 3초 동안 보이는 텍스트·이모지 스티커. 시작·끝을 재생 위치로 옮길 수 있음 |
| `PRIVACY` | 사각형·원 모자이크·블러. 재생 위치부터 3초 동안 적용되고 시작·끝을 옮길 수 있음. 최대 8개 |

구간·자르기·회전·속도·텍스트는 도구를 열고 적용할 때까지가 undo 한 단계이고, 나머지는 선택 1회·슬라이더 드래그 1회·마스크 1개·스티커 1개·클립 나누기·이동·추가·삭제가 각각 한 단계입니다. 클립 단위 도구(구간·자르기·회전·보정·필터·속도·소리)는 타임라인에서 고른 클립을 고칩니다. 텍스트·스티커·마스크·배경 음악은 출력 시간에 붙어 있어 클립을 옮기거나 나눠도 그 시간에 남고, 결과가 짧아지면 끝을 넘는 부분이 잘립니다.

### export: VideoExportConfig

| 옵션 | 기본값 | 단위·범위 |
| --- | --- | --- |
| `maxShortSide` | `1080` | 출력의 짧은 변 상한(px), 144..2160. 원본보다 키우지 않음. 긴 변은 인코더 한도 때문에 4096px을 넘지 않게 함께 줄임 |
| `maxFrameRate` | `30` | 초당 프레임 상한, 1..60. 넘는 프레임은 버리고 `FRAME_RATE_REDUCED` 경고 |
| `allowFallback` | `true` | `false`이면 인코더가 다른 해상도·설정을 제안할 때 실패. `true`이면 `ENCODER_FALLBACK_APPLIED` 경고 |

출력은 MP4(H.264 영상, AAC 음성)입니다. HDR 원본은 SDR로 변환하고 `HDR_CONVERTED_TO_SDR` 경고를 붙입니다.

## FrameKitRequest

```kotlin
FrameKitRequest(
    input = EditorInput.Pick(MediaKind.ANY),
    image = ImageEditorConfig(),
    imageExport = ImageExportConfig(),
    video = VideoEditorConfig(),
    videoExport = VideoExportConfig(),
    ui = EditorUiConfig(),
    output = OutputTarget.AppFile,
)
```

열리는 편집기와 상관없이 다섯 설정을 모두 검증합니다. 사진이면 `image`·`imageExport`, 영상이면 `video`·`videoExport`가 쓰이고 `ui`·`output`은 공통입니다.

## output: OutputTarget

| 값 | 설명 |
| --- | --- |
| `AppFile` | `files/framekit/exports/`에 저장하고 FileProvider Uri 반환 (현재 유일한 대상) |
