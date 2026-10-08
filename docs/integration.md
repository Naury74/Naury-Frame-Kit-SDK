# Integration

호스트 앱에서 사진·영상 편집기를 열고 결과를 받는 방법입니다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.naury74:framekit:1.0.0-alpha01")             // 사진·영상 모두
    // implementation("io.github.naury74:framekit-ui-image:1.0.0-alpha01") // 사진만 (Media3 없음)
    // implementation("io.github.naury74:framekit-ui-video:1.0.0-alpha01") // 영상만
}
```

Maven Central 공개 전에는 이 저장소에서 `./gradlew publishToMavenLocal`을 실행하고 호스트의 저장소 목록에 `mavenLocal()`을 넣습니다.

- 호스트는 Kotlin 2.2 이상이면 됩니다. SDK는 Kotlin 2.4로 빌드하지만 언어·API 버전과 `kotlin-stdlib` 의존을 2.2로 맞춰 배포합니다.
- 호스트가 Compose를 쓰지 않아도 됩니다. 편집 화면의 Compose 의존은 SDK가 가져옵니다.
- 이미지 전용 앱은 `framekit-ui-image`만 쓰면 Media3가 들어오지 않습니다.

SDK manifest는 편집 Activity(`exported=false`), 통합 모듈의 투명 라우터 Activity, 결과 전용 FileProvider만 merge합니다. 권한은 추가하지 않습니다.

## 편집기 열기

| 계약 | request | 여는 편집기 |
| --- | --- | --- |
| `FrameKitContract` | `FrameKitRequest` | 원본의 MIME 형식(없으면 확장자)으로 사진·영상 편집기를 고름 |
| `ImageEditorContract` | `ImageEditorRequest` | 사진 편집기 |
| `VideoEditorContract` | `VideoEditorRequest` | 영상 편집기 |

세 계약 모두 결과는 `FrameKitResult`이고 `Success`의 `EditedMedia.mediaType`이 `IMAGE` 또는 `VIDEO`입니다. 사진도 영상도 아닌 원본은 `UNSUPPORTED_FORMAT`으로 실패합니다.

### Activity / Fragment

```kotlin
private val editor = registerForActivityResult(FrameKitContract()) { result ->
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
val editor = rememberLauncherForActivityResult(FrameKitContract()) { result -> /* ... */ }
Button(onClick = { editor.launch(FrameKitRequest(EditorInput.Pick(MediaKind.ANY))) }) { Text("Edit") }
```

## 입력

| 입력 | 사용 | 권한 |
| --- | --- | --- |
| `EditorInput.UriSource(uri)` | 호스트가 이미 가진 Uri 한 개 | 호스트가 읽기 권한을 가지고 있어야 함 |
| `EditorInput.FileSource(path)` | 호스트 앱 내부 저장소의 파일 절대 경로 | 같은 프로세스이므로 별도 권한 없음 |
| `EditorInput.Multiple(items)` | 이미 가진 사진 여러 장 또는 영상 여러 개. 각 항목은 `UriSource`/`FileSource` | 각 Uri의 읽기 권한 |
| `EditorInput.Pick(kind, maxItems = 1)` | 시스템 Photo Picker를 먼저 띄움. `IMAGE`·`VIDEO`·`ANY`(통합 계약만). `maxItems`가 2 이상이면 여러 개 선택. 닫으면 `Cancelled` | Picker가 선택한 항목만 읽기 권한 부여 |
| `EditorInput.Capture(kind)` | 기기 카메라 앱으로 사진을 찍거나 영상을 녹화한 뒤 바로 편집. `IMAGE`·`VIDEO` | 아래 [카메라](#카메라) 참고 |

### 진입점 조합

| 하고 싶은 일 | request |
| --- | --- |
| 사진 한 장 편집 | `FrameKitRequest(EditorInput.UriSource(uri))` |
| 영상 한 개 편집 | `FrameKitRequest(EditorInput.UriSource(videoUri))` — MIME으로 영상 편집기가 열림 |
| 사진 여러 장을 한 번에 편집 | `EditorInput.Multiple(uris.map(EditorInput::UriSource))` |
| 영상 여러 개를 이어 붙여 편집 | `EditorInput.Multiple(videoUris.map(EditorInput::UriSource))` |
| 사용자가 사진이나 영상을 직접 고름 | `EditorInput.Pick(MediaKind.ANY)` |
| 사진 최대 10장을 고르게 함 | `EditorInput.Pick(MediaKind.IMAGE, maxItems = 10)` |
| 방금 찍은 사진 편집 | `EditorInput.Capture(MediaKind.IMAGE)`, 또는 호스트가 찍은 파일을 `UriSource`/`FileSource`로 |
| 방금 녹화한 영상 편집 | `EditorInput.Capture(MediaKind.VIDEO)` |
| 여러 장을 PDF 한 개로 | 위 입력 + `imageExport = ImageExportConfig(format = ImageFormat.PDF)` |

- 여러 개를 넘기면 사진 편집기는 아래쪽 쪽 목록에서 한 장씩 골라 편집하고, 영상 편집기는 고른 순서대로 클립을 이어 붙입니다.
- 개수 상한은 `ImageEditorConfig.maxImageCount`(기본 20, 최대 100), `VideoEditorConfig.maxClipCount`(기본 10, 최대 20)입니다. `Multiple`이 상한보다 많으면 앞에서부터 상한까지만 엽니다. `Pick`의 `maxItems`도 편집기 상한으로 줄어듭니다.
- 통합 계약에서 사진과 영상을 섞어 넘기거나 `Pick(ANY)`로 섞어 고르면 `UNSUPPORTED_OPERATION`으로 실패합니다. 한 번에 한 종류만 편집합니다.
- 편집기는 호스트 프로세스에서 실행되므로 호스트가 읽을 수 있는 Uri는 편집기도 읽을 수 있습니다.
- 편집기는 Uri scheme만 보고 권한을 가정하지 않습니다. 열 수 없으면 `PERMISSION_DENIED`(권한 없음) 또는 `SOURCE_UNAVAILABLE`(삭제·이동)을 화면에 표시하고, 닫으면 `Failure`로 돌려줍니다.
- 원격 URL은 지원하지 않습니다. 다운로드를 마친 로컬 Uri를 넘기세요.
- Bitmap, Drawable, callback은 Intent로 넘길 수 없으므로 받지 않습니다.

### 카메라

`EditorInput.Capture`는 기기의 카메라 앱(`ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE`)을 띄웁니다. SDK가 카메라를 직접 다루지 않으므로 별도 카메라 라이브러리가 필요 없습니다.

- 촬영 파일은 `cache/framekit/captures/`에 만들고 SDK FileProvider로 카메라 앱에 쓰기 권한을 줍니다. 편집이 끝나면(성공·취소·실패) 지우고, 남은 파일은 다음 실행 때 정리합니다.
- **호스트 manifest에 `CAMERA` 권한이 선언되어 있으면** Android는 카메라 앱 호출에도 그 권한을 요구합니다. 이때 SDK가 실행 중에 권한을 요청하고, 거부하면 `PERMISSION_DENIED`로 실패합니다. 권한을 선언하지 않은 앱은 요청 없이 바로 카메라가 열립니다.
- 카메라 앱이 없는 기기(일부 태블릿·에뮬레이터)는 `CAMERA_UNAVAILABLE`로 실패합니다.
- 촬영을 취소하면 `Cancelled`입니다.
- 회전·재생성 중에도 카메라를 두 번 띄우지 않으며, 프로세스가 종료돼도 찍은 파일 경로를 기억해 이어서 엽니다.

## 결과

`FrameKitResult`는 한 번의 실행에 정확히 한 번 전달됩니다.

| 결과 | 언제 |
| --- | --- |
| `Success(output, outputs)` | 저장이 끝나고 파일 검증까지 통과했을 때. `outputs`는 만들어진 모든 결과(여러 장을 사진마다 저장하면 여러 개), `output`은 첫 번째 결과 |
| `Cancelled` | Picker를 닫았거나, 변경 없이 닫았거나, 변경을 버리고 닫았을 때 |
| `Failure(EditorError)` | 잘못된 request, 열 수 없는 원본 등 편집을 시작할 수 없을 때 |

저장 중 취소는 편집 화면으로 돌아갈 뿐 `Cancelled`를 보내지 않습니다. 저장 실패도 편집 화면에서 다시 시도할 수 있으므로 바로 `Failure`를 보내지 않습니다.

`EditedMedia`:

| 필드 | 설명 |
| --- | --- |
| `uri` | `content://<applicationId>.framekit.files/...` |
| `mediaType` | `IMAGE`, `VIDEO`, `DOCUMENT`(PDF) |
| `width`, `height` | 실제 인코딩된 픽셀 크기 |
| `durationMs` | 영상 길이. 사진·PDF는 `null` |
| `mimeType` | `image/jpeg`, `image/png`, `image/webp`, `application/pdf`, `video/mp4` |
| `pageCount` | PDF의 쪽 수. 그 밖에는 `null` |
| `recognizedText` | PDF 글자 레이어의 글자(`framekit-ocr`이 있을 때). 줄은 줄바꿈, 쪽은 빈 줄로 구분하며 10만 자에서 자름 |
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
| `UNSUPPORTED_OPERATION` | 사진·영상을 섞어 넘김 등 | 입력 수정 |
| `CAMERA_UNAVAILABLE` | 카메라 앱이 없음 | 사진 선택으로 대체 |
| `RESULT_UNAVAILABLE` | `RESULT_OK`인데 결과가 없음 | 편집기 재실행 |

저장 단계 오류(`INSUFFICIENT_MEMORY`, `INSUFFICIENT_STORAGE`, `ENCODE_FAILED`, `OUTPUT_WRITE_FAILED`)는 편집 화면의 다시 시도 dialog로 처리됩니다. 편집 도중 원본이 삭제되거나 권한이 끊기면 `SOURCE_UNAVAILABLE`, 저장 공간이 모자라 쓰기에 실패하면 `INSUFFICIENT_STORAGE`로 구분합니다. 여러 장을 저장하다 한 장이라도 실패하면 그 호출에서 만든 파일을 모두 지우고 실패로 돌려주므로 일부만 남지 않습니다.

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

편집기 문구는 13개 언어를 제공합니다: 한국어·영어·일본어·중국어(간체·번체)·베트남어·태국어·인도네시아어·러시아어·스페인어·포르투갈어(브라질)·프랑스어·독일어. 그 밖의 언어는 영어로 보입니다.

`EditorUiConfig.localeTag`가 `null`이면 기기 언어를, BCP 47 태그(`"ko"`, `"en"`, `"ja"`, `"zh-CN"`, `"zh-TW"`, `"vi"`, `"th"`, `"id"`, `"ru"`, `"es"`, `"pt-BR"`, `"fr"`, `"de"`)면 그 언어를 씁니다. 호스트의 언어 설정은 바뀌지 않습니다.

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
- Picker로 고른 `content://` Uri는 가능하면 persistable 읽기 권한을 받아 두었다가 세션이 끝날 때 해제합니다. 편집 중에 붙인 영상·배경 음악도 같습니다. 호스트가 이미 persist한 권한은 받지도, 해제하지도 않습니다.

## 배경 제거 선택 모듈

```kotlin
implementation(project(":framekit-segmentation"))
```

- 추가하면 manifest meta-data로 편집기가 구현을 찾아 "배경 제거" 도구를 보여 줍니다. 모듈이 없으면 도구는 숨겨지고 `ImageProcessor.canRemoveBackground`는 `false`입니다.
- 피사체 분할은 Google Play 서비스의 ML Kit으로 **기기 안에서** 수행됩니다. 사진을 서버로 보내지 않습니다.
- 모델은 앱 설치 시 Play 서비스가 내려받습니다. 아직 받지 못했으면 "잠시 후 다시 시도" 안내가 나타나고, headless에서는 `UNSUPPORTED_OPERATION`이 반환됩니다.
- Play 서비스가 없는 기기(일부 중국향 기기 등)에서는 사용할 수 없습니다.
- ML Kit 의존성이 `INTERNET`, `ACCESS_NETWORK_STATE` 권한을 merge합니다. 앱 정책상 제거해야 한다면 호스트 manifest에서 직접 제거할 수 있지만, Play 서비스 동작은 직접 확인해야 합니다.

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

- JPEG로 저장하면 제거된 배경은 `jpegBackgroundArgb` 색으로 채워집니다. 투명하게 남기려면 PNG나 WEBP를 쓰세요.

## 글자 인식(OCR) 선택 모듈

```kotlin
implementation("io.github.naury74:framekit-ocr:1.0.0-alpha01")
```

- 추가하면 PDF로 저장할 때 쪽마다 글자를 인식해 **보이지 않는 글자 레이어**를 겹칩니다. PDF 뷰어에서 검색·선택·복사가 되고, 이미지는 그대로 보입니다. `PdfOptions.recognizeText = false`로 끌 수 있습니다.
- ML Kit 한국어 모델을 쓰며 영어·숫자도 함께 인식합니다. **기기 안에서** 처리하고 사진을 서버로 보내지 않습니다.
- 모델은 첫 사용 때 Play 서비스가 내려받습니다. 받는 중에 저장하면 그 쪽은 이미지만 들어가고 결과에 `TEXT_RECOGNITION_SKIPPED` 경고가 붙습니다.
- Play 서비스가 없는 기기에서는 사용할 수 없고, ML Kit 의존성이 `INTERNET`, `ACCESS_NETWORK_STATE` 권한을 merge합니다.

### ML Kit 모델 미리 받기

배경 제거·글자 인식 모델을 앱 설치 때 받아 두려면 호스트 manifest에 쓰는 모듈만 적습니다. 두 모듈 모두 쓰면 쉼표로 함께 적습니다. SDK는 이 값을 넣지 않습니다(두 모듈이 같은 키를 써서 merge가 충돌하기 때문).

```xml
<application>
    <meta-data
        android:name="com.google.mlkit.vision.DEPENDENCIES"
        android:value="subject_segment,ocr_korean" />
</application>
```

### 알려진 문제: Apple 실리콘 Mac의 arm64 에뮬레이터

일부 arm64 에뮬레이터(Apple 실리콘 호스트)에서는 ML Kit 모델의 네이티브 코드가 `SIGILL`로 앱을 종료시킵니다. 배경 제거를 실행하거나 OCR이 켜진 PDF를 저장할 때 생기며, 실기기(Galaxy Z Fold7)에서는 정상 동작을 확인했습니다. 에뮬레이터에서 PDF를 확인할 때는 `PdfOptions(recognizeText = false)`를 쓰세요.

## R8

SDK 모듈은 reflection을 쓰지 않으므로 별도 keep 규칙이 필요 없습니다. Parcelable request/result는 AGP 기본 규칙으로 유지됩니다. 별도 소비자 앱의 R8 release 빌드에서 영상 편집기(Media3)·필터·저장·결과 수신을 확인했습니다.
