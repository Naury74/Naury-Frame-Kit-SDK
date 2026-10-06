# Headless

`ImageProcessor`는 편집 화면 없이 사진을 편집하고 저장합니다. 편집 화면과 같은 디코더·렌더러·저장 경로를 쓰므로, 같은 프로젝트는 같은 결과를 냅니다.

## 흐름

```kotlin
val processor = ImageProcessor(context)
val source = processor.open(EditorInput.UriSource(uri))          // 또는 open(bitmap)
val project = processor.newProject(source).copy(
    geometry = GeometryEdit(crop = RectN(0.1, 0.1, 0.9, 0.9)),
    adjustments = Adjustments(brightness = 0.1),
    filter = FilterSelection("bright", 0.8),
    overlays = listOf(ImageOverlay.Sticker("s1", EmojiCatalog.assetId("🎉"))),
)
val handle = processor.startExport(project, source, ImageExportConfig(format = ImageFormat.WEBP_LOSSY), scope = lifecycleScope)
handle.state.collect { state -> render(state) }   // 선택
val result = handle.awaitResult()
processor.close()
```

## 입력

| 함수 | 설명 |
| --- | --- |
| `open(EditorInput.UriSource)` | 읽기 권한이 있는 Uri |
| `open(EditorInput.FileSource)` | 앱 내부 파일 |
| `open(Bitmap)` | 메모리의 Bitmap. 내부 캐시에 PNG로 복사한 뒤 사용합니다. 크기에 비례한 시간·저장 공간이 들고, 호출 후 원본 Bitmap은 호출한 쪽이 계속 소유합니다(recycle 가능). |

`EditorInput.Pick`은 UI가 필요하므로 `INVALID_CONFIGURATION`입니다.

## ExportHandle

| 멤버 | 설명 |
| --- | --- |
| `state: StateFlow<ExportState>` | `Idle → Preparing → Running → Finalizing → Completed`, 또는 `Cancelling → Cancelled`, `Failed` |
| `awaitResult()` | 끝날 때까지 기다린 뒤 `FrameKitResult`를 반환. 몇 번 불러도 같은 결과. 저장 실패는 예외가 아니라 `Failure`로 반환 |
| `cancel()` | 취소 요청. 완료 후에는 아무 일도 하지 않음 |

- 작업은 `startExport` 호출 시 **한 번만** 시작됩니다. `state`를 여러 번 구독해도 다시 실행되지 않습니다.
- 작업 수명은 넘긴 `scope`를 따릅니다. scope가 취소되면 저장도 취소되고 미완성 파일은 삭제됩니다.
- 파일이 공개(publish)된 뒤 도착한 취소는 결과를 되돌리지 않습니다.
- 잘못된 프로젝트나 설정은 `startExport`가 즉시 `FrameKitException`(`INVALID_PROJECT`/`INVALID_CONFIGURATION`)을 던집니다.

## 정리

`close()`는 GPU 컨텍스트와 `open(Bitmap)`으로 만든 임시 파일을 정리합니다. 저장된 결과 파일은 지우지 않습니다. 필요 없으면 `deleteOutput(uri)`를 호출하세요.
