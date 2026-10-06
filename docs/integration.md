# Integration

호스트 앱에서 이미지 편집기를 열고 결과를 받는 방법입니다.

## 의존성

```kotlin
dependencies {
    implementation(project(":framekit-ui-image"))
}
```

SDK manifest는 편집 Activity(`exported=false`)와 결과 전용 FileProvider만 merge합니다. 권한은 추가하지 않습니다.

## 편집기 열기

### Activity / Fragment

```kotlin
private val editor = registerForActivityResult(ImageEditorContract()) { result ->
    when (result) {
        is FrameKitResult.Success -> onEdited(result.output)
        FrameKitResult.Cancelled -> Unit
        is FrameKitResult.Failure -> onFailed(result.error)
    }
}
```

`registerForActivityResult`는 `onCreate` 이전(프로퍼티 초기화)에 호출해야 합니다.

### Compose

```kotlin
val editor = rememberLauncherForActivityResult(ImageEditorContract()) { result -> /* ... */ }
Button(onClick = { editor.launch(ImageEditorRequest(EditorInput.Pick())) }) { Text("Edit") }
```

## 입력

| 입력 | 사용 | 권한 |
| --- | --- | --- |
| `EditorInput.Pick()` | 시스템 Photo Picker를 먼저 띄움. 닫으면 `Cancelled` | Picker가 선택한 항목만 읽기 권한 부여 |
| `EditorInput.UriSource(uri)` | 호스트가 이미 가진 Uri | 호스트가 읽기 권한을 가지고 있어야 함 |
| `EditorInput.FileSource(path)` | 호스트 앱 내부 저장소의 파일 절대 경로 | 같은 프로세스이므로 별도 권한 없음 |

- 편집기는 호스트 프로세스에서 실행되므로 호스트가 읽을 수 있는 Uri는 편집기도 읽을 수 있습니다.
- 편집기는 Uri scheme만 보고 권한을 가정하지 않습니다. 열 수 없으면 `PERMISSION_DENIED`(권한 없음) 또는 `SOURCE_UNAVAILABLE`(삭제·이동)을 화면에 표시하고, 닫으면 `Failure`로 돌려줍니다.
- 원격 URL은 지원하지 않습니다. 다운로드를 마친 로컬 Uri를 넘기세요.
- Bitmap, Drawable, callback은 Intent로 넘길 수 없으므로 받지 않습니다.

## 결과

`FrameKitResult`는 한 번의 실행에 정확히 한 번 전달됩니다.

| 결과 | 언제 |
| --- | --- |
| `Success(EditedMedia)` | 저장이 끝나고 파일 검증까지 통과했을 때 |
| `Cancelled` | Picker를 닫았거나, 변경 없이 닫았거나, 변경을 버리고 닫았을 때 |
| `Failure(EditorError)` | 잘못된 request, 열 수 없는 원본 등 편집을 시작할 수 없을 때 |

저장 중 취소는 편집 화면으로 돌아갈 뿐 `Cancelled`를 보내지 않습니다. 저장 실패도 편집 화면에서 다시 시도할 수 있으므로 바로 `Failure`를 보내지 않습니다.

`EditedMedia`:

| 필드 | 설명 |
| --- | --- |
| `uri` | `content://<applicationId>.framekit.files/...` |
| `mediaType` | `IMAGE` |
| `width`, `height` | 실제 인코딩된 픽셀 크기 |
| `durationMs` | 이미지는 `null` |
| `mimeType` | `image/jpeg` 또는 `image/png` |
| `fileSize` | 바이트 |
| `warnings` | `COLOR_SPACE_CONVERTED_TO_SRGB`, `HDR_GAIN_MAP_DROPPED` |

### 오류 처리

`EditorError`는 `code`, `recoverable`, `suggestedAction`, `diagnosticId`를 가집니다. 번역된 문구는 들어 있지 않으므로 호스트가 코드별 문구를 정합니다. 내부 예외는 결과에 포함하지 않고, 기기 로그(`FrameKit` 태그)에 예외 종류만 남깁니다. 파일명이나 Uri는 기록하지 않습니다.

| 코드 | 대표 원인 | 권장 행동 |
| --- | --- | --- |
| `INVALID_CONFIGURATION` | request 값이 범위를 벗어남 | 개발자 수정 |
| `INVALID_SOURCE` | 이미지가 아니거나 형식 불일치 | 다른 파일 선택 |
| `PERMISSION_DENIED` | 읽기 권한 없음 | 다시 선택 |
| `SOURCE_UNAVAILABLE` | 삭제·이동·provider 응답 없음 | 다시 선택 |
| `UNSUPPORTED_FORMAT` | GIF, HEIF, animated WEBP 등 | 다른 파일 선택 |
| `DECODE_FAILED` | 손상된 파일, 0바이트 | 다른 파일 선택 |
| `RESULT_UNAVAILABLE` | `RESULT_OK`인데 결과가 없음 | 편집기 재실행 |

저장 단계 오류(`INSUFFICIENT_MEMORY`, `INSUFFICIENT_STORAGE`, `ENCODE_FAILED`, `OUTPUT_WRITE_FAILED`)는 편집 화면의 다시 시도 dialog로 처리됩니다.

## 결과 파일 관리

- 결과 파일은 호스트 소유이며 SDK가 자동으로 지우지 않습니다.
- 필요 없어진 결과는 `FrameKitOutputs.deleteOutput(context, uri)`로 지웁니다. FrameKit이 만든 파일이 아니면 `false`를 반환하고 아무것도 지우지 않습니다.
- 저장 도중 앱이 종료되어 남은 숨김 `.partial` 파일은 24시간이 지나면 다음 편집기 실행 때 정리됩니다.

### 다른 앱에 공유

```kotlin
val send = Intent(Intent.ACTION_SEND).apply {
    type = media.mimeType
    putExtra(Intent.EXTRA_STREAM, media.uri)
    clipData = ClipData.newRawUri(null, media.uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivity(Intent.createChooser(send, null))
```

FileProvider는 `files/framekit/exports/`만 노출합니다. 원본이나 세션 파일은 노출되지 않습니다.

## 언어

편집기 문구는 한국어와 영어를 제공합니다. `EditorUiConfig.localeTag`가 `null`이면 기기 언어를, `"ko"`/`"en"`이면 그 언어를 씁니다. 호스트의 언어 설정은 바뀌지 않습니다.

App Bundle로 배포하면서 `localeTag`를 쓰는 경우 언어별 split 때문에 기기 언어가 아닌 리소스가 설치되지 않을 수 있습니다. 이때는 language split을 끕니다.

```kotlin
android {
    bundle {
        language { enableSplit = false }
    }
}
```

## 화면 회전과 프로세스 종료

- 화면 회전·폴더블 접기/펴기: 편집 상태와 undo 기록이 그대로 유지됩니다.
- Picker가 열려 있는 동안 회전해도 Picker가 두 번 열리지 않습니다.
- 프로세스 종료 후 복귀: 마지막으로 **적용한** 편집을 복원하고 "이전 편집을 복원했습니다" 안내를 띄웁니다.
  - undo/redo 기록과 열려 있던 도구의 미적용 변경은 복원하지 않습니다. 원래 사진과 비교한 변경 여부는 유지되므로 닫을 때 확인을 묻습니다.
  - 원본을 다시 열어 형식·크기·EXIF 방향이 같은지 확인합니다. 다른 이미지로 바뀌었으면 복원하지 않고 처음부터 엽니다.
  - 원본에 접근할 수 없으면 오류 화면을 보여 주고, Picker로 연 경우 "다른 파일 선택"으로 다시 고를 수 있습니다. 같은 이미지를 고르면 그때 복원합니다.
  - 저장 중에 종료됐다면 파일은 만들어지지 않았으며, 다음 실행에서 다시 저장하라고 안내합니다.

### 세션 파일

| 위치 | 내용 |
| --- | --- |
| `files/framekit/sessions/<sessionId>/descriptor.json` | 원본 참조(Uri 또는 파일 경로), 원본 지문, SDK가 받은 권한 여부 |
| `files/framekit/sessions/<sessionId>/project.snapshot` | 마지막으로 적용한 편집 값(JSON) |

- `SavedStateHandle`에는 세션 id만 저장하고 편집 데이터나 이미지는 Bundle에 넣지 않습니다.
- 파일은 임시 이름으로 쓴 뒤 rename하므로 저장 도중 종료돼도 이전 snapshot이 남습니다.
- 편집기가 결과(성공·취소·실패)를 보내면 세션을 바로 지웁니다. 중단된 세션은 7일이 지나면 다음 편집기 실행 때 정리합니다.
- Picker로 고른 `content://` Uri는 가능하면 persistable 읽기 권한을 받아 두었다가 세션이 끝날 때 해제합니다. 호스트가 이미 persist한 권한은 받지도, 해제하지도 않습니다.

## R8

SDK 모듈은 reflection을 쓰지 않으므로 별도 keep 규칙이 필요 없습니다. Parcelable request/result는 AGP 기본 규칙으로 유지됩니다.
