# Configuration

`ImageEditorRequest`의 모든 옵션입니다. 잘못된 값은 기본값으로 바꾸지 않고 `INVALID_CONFIGURATION`으로 거부합니다. 실행 전에 `request.validate()`로 미리 확인할 수 있습니다.

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
| `Pick(kind = MediaKind.IMAGE)` | 시스템 Photo Picker. 이미지 편집기는 `IMAGE`만 허용 |
| `UriSource(uri)` | 읽기 권한이 있는 Uri |
| `FileSource(absolutePath)` | 호스트 앱 내부 파일 |

## config: ImageEditorConfig

| 옵션 | 기본값 | 범위·규칙 |
| --- | --- | --- |
| `enabledTools` | `{CROP, ROTATE}` | 빠진 도구는 숨기고 해당 편집 요청도 거부. 빈 집합이면 미리보기와 저장만 가능 |
| `allowUndo` | `true` | |
| `allowRedo` | `true` | `allowUndo = false`이면서 `true`이면 오류 |

도구:

| 도구 | 포함 기능 |
| --- | --- |
| `CROP` | 비율 선택(자유, 원본, 1:1, 4:3, 3:4, 16:9, 9:16, 3:2, 2:3), 핸들 드래그, 이동 |
| `ROTATE` | 90° 회전, 좌우·상하 반전, 수평 맞추기 -45°..45° |

도구 하나를 열고 적용할 때까지의 모든 변경은 undo 한 단계입니다. undo 기록은 최대 50단계이며 이미지(Bitmap)가 아닌 편집 값만 저장합니다.

## export: ImageExportConfig

| 옵션 | 기본값 | 단위·범위 |
| --- | --- | --- |
| `format` | `JPEG` | `JPEG`, `PNG` |
| `quality` | `92` | JPEG 품질 0..100. PNG에는 적용되지 않음 |
| `maxWidth` | `null` | 출력 폭 상한(px), 양수 |
| `maxHeight` | `null` | 출력 높이 상한(px), 양수 |
| `maxOutputPixels` | `16_000_000` | `width × height` 상한, 양수 |
| `metadataPolicy` | `SAFE` | `SAFE`, `NONE` |
| `jpegBackgroundArgb` | `0xFF000000` | JPEG에서 투명 영역을 채울 색 |

- 출력 크기는 자른 영역의 원본 픽셀 크기에서 시작해 상한에 맞게 비율을 유지하며 줄입니다. **확대하지 않습니다.**
- 기기 메모리 예산(앱 memory class 기준)을 넘으면 `INSUFFICIENT_MEMORY`로 실패하며, 사용자 동의 없이 해상도를 낮추지 않습니다.
- `SAFE`는 촬영 시각(DateTimeOriginal 등)과 카메라 설정(제조사, 모델, 노출, 조리개, ISO, 초점 거리, 플래시, 화이트밸런스)만 복사합니다. GPS, 소유자, 일련번호, 주석, 원본 썸네일은 복사하지 않고, orientation은 normal로, 크기는 새 값으로 기록합니다. PNG에는 메타데이터를 쓰지 않습니다.
- `NONE`은 메타데이터를 쓰지 않습니다.

> 문서의 초기 설계에서는 `maxOutputPixels`를 편집기 설정에 두었지만, UI 없이 export하는 경우에도 같은 제한이 필요해 `ImageExportConfig`로 옮겼습니다.

## ui: EditorUiConfig

| 옵션 | 기본값 | 범위·규칙 |
| --- | --- | --- |
| `themeMode` | `DARK` | `DARK`, `LIGHT`, `SYSTEM` |
| `accentArgb` | `null` (`#635BFF`) | ARGB Int |
| `cornerRadiusDp` | `14` | 0..32 |
| `showExportProgress` | `true` | `false`이면 단계 대신 "저장하는 중"만 표시 |
| `enableHaptics` | `true` | 시스템 햅틱 설정은 항상 존중 |
| `localeTag` | `null` | `null`은 기기 언어. 빈 문자열은 오류 |

기본 다크 팔레트: background `#0D0D0E`, surface `#171719`, raised `#242428`, foreground `#FFFFFF`, accent `#635BFF`. accent를 바꿀 때는 흰 글자와의 대비를 확인하세요. 저장 버튼 글자는 항상 흰색입니다.

## output: OutputTarget

| 값 | 설명 |
| --- | --- |
| `AppFile` | `files/framekit/exports/`에 저장하고 FileProvider Uri 반환 (현재 유일한 대상) |
