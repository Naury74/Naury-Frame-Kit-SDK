# Headless

`ImageProcessor`(사진)와 `VideoProcessor`(영상)는 편집 화면 없이 편집하고 저장합니다. 편집 화면과 같은 디코더·렌더러·저장 경로를 쓰므로, 같은 프로젝트는 같은 결과를 냅니다.

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

## PDF

여러 사진을 문서 하나(또는 사진마다 하나)로 저장합니다. 쪽 설정은 [PdfOptions](configuration.md#pdf)를 따릅니다.

```kotlin
val pages = uris.map { uri ->
    val source = processor.open(EditorInput.UriSource(uri))
    processor.newProject(source).copy(filter = FilterSelection("document", 1.0)) to source
}
val handle = processor.startPdfExport(
    pages,
    ImageExportConfig(format = ImageFormat.PDF, quality = 85, pdf = PdfOptions(pageSize = PdfPageSize.A4, marginMm = 8.0)),
    scope = lifecycleScope,
)
val result = handle.awaitResult()   // Success.output.pageCount == pages.size
```

- 쪽마다 하나씩 처리해 바로 파일에 쓰므로 쪽 수가 많아도 메모리 사용이 일정합니다.
- `framekit-ocr` 모듈이 있으면 글자 레이어를 자동으로 넣습니다. 다른 인식기를 쓰려면 `ImageProcessor(context, textRecognizer = myRecognizer)`로 `TextRecognizer` 구현을 넘깁니다.
- 비스듬한 문서는 `DocumentRectifier.detect(bitmap)`로 네 모서리를 찾고 `DocumentRectifier(...).rectify(info, quad, file)`로 펴서, 그 파일을 `open(EditorInput.FileSource(path))`로 열어 쪽으로 씁니다.
- 중간에 실패하거나 취소하면 그 호출에서 만든 PDF를 모두 지웁니다.

## 영상

```kotlin
val processor = VideoProcessor(context)
val video = processor.open(EditorInput.UriSource(videoUri))
val music = processor.openAudio(EditorInput.UriSource(songUri))      // 선택
val base = processor.newProject(video)                                // 원본 전체가 한 클립
val project = base.copy(
    timeline = base.timeline.copy(
        videoClips = base.timeline.videoClips.map { it.copy(sourceRange = TimeRangeUs(0, 5_000_000), speed = 2.0) },
        audioClips = listOf(AudioClip("m", music.metadata.id, TimeRangeUs(0, 3_000_000), timelineStartUs = 0, loop = true)),
    ),
)
val handle = processor.startExport(project, listOf(video), VideoExportConfig(maxShortSide = 720), scope = lifecycleScope, music = listOf(music))
val result = handle.awaitResult()
```

- 여러 클립은 `open`으로 연 원본을 모두 `sources`에 넘기고 `VideoClip`을 이어 붙입니다.
- Media3 Transformer가 main looper에서 실행되므로 앱 프로세스 안에서 호출합니다(WorkManager 등도 같은 프로세스).
- `ExportState.Running.progress`는 Media3가 추정할 수 있을 때 `0..1`입니다.
- 앱 시작 때 `deleteStalePartials()`로 이전에 중단된 임시 파일을 지울 수 있습니다.

## 카탈로그

호스트 필터·스티커·폰트를 쓰는 프로젝트는 처리기에도 같은 카탈로그를 넘깁니다: `ImageProcessor(context, catalog = catalog)`, `VideoProcessor(context, catalog = catalog)`. `catalog`를 생략(`null`)하면 이미 등록된 카탈로그(열려 있는 편집기가 등록한 것 포함)를 그대로 씁니다.

## 입력

| 함수 | 설명 |
| --- | --- |
| `open(EditorInput.UriSource)` | 읽기 권한이 있는 Uri |
| `open(EditorInput.FileSource)` | 앱 내부 파일 |
| `open(Bitmap)` | 메모리의 Bitmap. 내부 캐시에 PNG로 복사한 뒤 사용합니다. 크기에 비례한 시간·저장 공간이 들고, 호출 후 원본 Bitmap은 호출한 쪽이 계속 소유합니다(recycle 가능). |

`EditorInput.Pick`·`Capture`는 UI가 필요하고 `Multiple`은 원본마다 `open`을 불러야 하므로 `INVALID_CONFIGURATION`입니다.

## ExportHandle

| 멤버 | 설명 |
| --- | --- |
| `state: StateFlow<ExportState>` | `Idle → Preparing → Running → Finalizing → Completed`, 또는 `Cancelling → Cancelled`, `Failed` |
| `awaitResult()` | 끝날 때까지 기다린 뒤 `FrameKitResult`를 반환. 몇 번 불러도 같은 결과. 저장 실패(메모리 부족 포함)는 예외가 아니라 `Failure`로 반환 |
| `cancel()` | 취소 요청. 완료 후에는 아무 일도 하지 않음 |

- 작업은 `startExport` 호출 시 **한 번만** 시작됩니다. `state`를 여러 번 구독해도 다시 실행되지 않습니다.
- 작업 수명은 넘긴 `scope`를 따릅니다. scope가 취소되면 저장도 취소되고 미완성 파일은 삭제됩니다.
- 파일이 공개(publish)된 뒤 도착한 취소는 결과를 되돌리지 않습니다.
- 잘못된 프로젝트나 설정은 `startExport`가 즉시 `FrameKitException`(`INVALID_PROJECT`/`INVALID_CONFIGURATION`)을 던집니다.

## 정리

`close()`는 GPU 컨텍스트와 `open(Bitmap)`으로 만든 임시 파일을 정리합니다. 저장된 결과 파일은 지우지 않습니다. 필요 없으면 `deleteOutput(uri)`를 호출하세요.
