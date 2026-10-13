# Release Notes

## 1.0.0-alpha04

### 바뀜

- 녹음 화면의 상단 바를 다른 편집 화면과 같은 모양으로 맞춤
- 밝은 테마에서 영상·음성 타임라인의 처음 앞·끝 뒤 영역이 회색 덩어리로 보이던 문제와 음성 조각 선택 표시가 잘 보이지 않던 문제를 고침

## 1.0.0-alpha03

### 바뀜

- 문서 감지가 더 잘 됨: 바탕과 밝기가 비슷한 종이, 그림자가 진 종이, 나무 책상·색 있는 천 위의 종이도 찾음. 둥글거나 손가락에 가린 모서리도 실제 종이 모서리에 맞춤
- PDF로 저장할 때는 문서 보정 도구가 감지 여부와 상관없이 맨 앞에 나와, 찾지 못한 문서도 모서리를 직접 맞춰 펼 수 있음
- 여러 쪽을 PDF로 묶을 때 "모든 쪽 스캔"으로 남은 쪽을 한 번에 펴고 같은 스캔 방식을 적용. 문서를 찾지 못한 쪽은 그대로 두고 알려 줌
- `DocumentDetector.detect`/`analyze`에 채도(`chroma`)를 넘길 수 있음(선택)
- 편집 화면 상단 바를 새로 디자인: 제목은 가운데, 닫기·뒤로는 원형 버튼, 저장은 강조색 캡슐 버튼. 상단 바 아래는 화면과 자연스럽게 이어짐. 공통 컴포넌트 `FrameKitNavigationBar`로 제공
- 사진 편집 화면에서 사진의 빈 곳을 한 번 누르면 상단 바·도구 등 조작부를 숨기고, 다시 누르면 보임
- 편집기가 원본을 고를 때 `framekit-picker`가 있으면 앱 안 갤러리를 씀(시스템 선택기를 계속 쓰려면 `EditorUiConfig(picker = PickerStyle.SYSTEM)`)

### 추가

- `framekit-picker`: 앱 안 사진·영상 갤러리. 앨범별·날짜별 보기, 고른 순서 번호, 크게 보기(확대·축소, 썸네일 줄), 개수·크기·영상 길이·형식 제한, 폴드·태블릿 화면 대응. `MediaPickerContract`로 단독 사용하거나 편집기에서 자동으로 사용. 호스트가 사진 읽기 권한을 선언하지 않았거나 거부하면 시스템 사진 선택기로 대신함
- `EditorUiConfig.picker`(`PickerStyle.AUTO`/`SYSTEM`)
- `FrameKitNavigationBar`, `Modifier.frameKitGlass`: 호스트 화면에서도 같은 상단 바·버튼 모양을 쓸 수 있음

## 1.0.0-alpha02

### 바뀜

- 영상 편집기 타임라인을 줄(영상·원본 소리·배경 음악·음성·텍스트·가리기)로 나눈 트랙 타임라인으로 바꿈. 고른 클립의 양 끝을 끌어 바로 자르고(구간 도구 대신), 무엇을 고르냐에 따라 아래 동작이 바뀜
- 배경 음악·음성을 한 줄에 여러 조각으로 넣고 나누기·옮기기·양 끝 자르기·삭제·반복·볼륨·페이드인/아웃(`AudioClip.timelineEndUs`, `startOffsetUs`, `fadeInUs`, `fadeOutUs`, `AudioEditing`). 움직이는 이미지 편집기도 같은 방식의 소리 줄
- 원본 소리 구간 음소거와 되살리기(`VideoClip.mutedRanges`, `TimeRanges`)
- 소리 줄에 파형 표시(`WaveformExtractor`)
- 도구 화면의 상단 바: 닫기(X) 대신 뒤로 가기 화살표와 도구 이름(`EditorTopBar(toolTitle = …)`). 뒤로 가기는 시스템 뒤로 가기와 같아 바뀐 내용이 있으면 적용할지 묻는다.
- 도구 하단 바를 `ToolActionBar`로 교체: 왼쪽 초기화, 오른쪽 적용(아이콘과 글자). 넓은 화면에서는 패널 맨 아래에 고정. `ApplyCancelBar`는 deprecated.
- 도구 줄(`ToolRail`)이 가로로 넘칠 때 가려진 도구가 있는 쪽에 둥근 화살표(누르면 넘김)와 넓은 흐림을 보이고, 처음 한 번 살짝 밀었다 되돌려 옆으로 넘길 수 있음을 알림(`HorizontalScrollHints`)
- 텍스트 글꼴 이름 "손글씨" → "필기체"(13개 언어). 저장되는 글꼴 id `handwriting`은 그대로.
- 배경 제거 모델을 분리 프로세스(`:framekit_segmentation`)에서 실행. 모델이 기기에서 동작하지 못하면(SIGILL 등) 앱은 살아 있고 도구를 숨긴다(`BackgroundRemover.isAvailable`).
- 글자 인식도 분리 프로세스(`:framekit_ocr`)에서 실행(`TextRecognizer.isAvailable`). 사진을 열 때 도는 문서 판별·PDF 저장이 앱을 종료시키지 않는다.

### 추가

- 워터마크: 사진·영상 편집기 `WATERMARK` 도구(한 곳·반복·대각선·띠, 자리·크기·불투명도·색·기울기·촘촘함·상자·그림자, 무늬 그림 카드와 자리 고르기), 여러 장이면 모든 사진에 적용. 호스트 설정 `WatermarkConfig`(미리 채울 문구·추천 문구·열 때 바로 얹기·잠금), 모델 `Watermark`(`ImageProject.watermark`, `Timeline.watermark`), `WatermarkRenderer`, 세션 저장
- 음성 편집기 `AudioEditorContract`(`framekit-ui-audio`): 새로 녹음(탭·누르고 말하기, 잠시 멈춤·이어 녹음, 위로 밀어 고정·왼쪽으로 밀어 취소), 소리 파일 불러와 이어 붙이기, 재생 위치에 녹음 끼워 넣기·겹쳐 넣기, 나누기·구간 잘라 내기·구간만 남기기·양 끝 자르기·볼륨·페이드, 파형 타임라인, AAC(M4A) 저장, 프로세스 종료 후 이어 편집. 설정 `AudioEditorConfig`(녹음 방식·최대 길이·녹음 후 편집 여부·도구), `AudioExportConfig`
- `MediaKind.AUDIO`: `FrameKitContract`에서 `Capture(AUDIO)`(녹음)·`Pick(AUDIO)`(소리 파일 고르기), `audio/` MIME 원본은 음성 편집기로. 고르기 시트에 소리 편집(`EditorAction.EDIT_AUDIO`)
- `AudioTrackMixer.mixAudioOnly`(소리만 섞어 M4A로), `VoiceRecorder` 잠시 멈춤·이어 녹음
- 고르기 시트(`FrameKitRequest.chooser = EditorChooser.ASK`, `chooserActions`): 원본 미리보기와 함께 사진 편집·PDF·움직이는 이미지·영상 편집·편집 없이 보내기 중에서 선택. 기본은 이전처럼 자동(`AUTO`)
- 편집 없이 보내기 결과 `EditedMedia.isOriginal`
- 배경 음악과 음성 트랙(`AudioRole.MUSIC`/`VOICE`): 영상 편집기와 움직이는 이미지 편집기에서 음성 파일 불러오기·마이크 녹음(`VoiceRecorder`, `VoiceRecordDialog`, 호스트가 `RECORD_AUDIO`를 선언했을 때만), 트랙마다 볼륨·시작 위치·반복
- 움직이는 이미지 편집기에서 프로세스가 끝나도 편집 이어 가기, MP4 저장 시 소리 섞기(`AudioTrackMixer`)
- 움직이는 이미지 편집기 `MotionEditorContract`(`framekit-motion`, `framekit-ui-motion`): 사진으로 슬라이드쇼 만들기(카메라 움직임·전환·사진당 시간·부메랑·화면 비율), GIF·움직이는 WebP 편집(구간·속도·부메랑), 보정·필터, 움직이는 텍스트·스티커, MP4·GIF·움직이는 WebP 저장
- GIF·움직이는 WebP 인코더/디코더(네이티브 라이브러리 없음), 프레임 MP4 인코더, `MediaType.ANIMATED_IMAGE`
- `FrameKitContract`가 움직이는 GIF·WebP를 움직이는 이미지 편집기로 연다(`openAnimatedInMotionEditor`)
- 텍스트 꾸밈 세부 설정: 외곽선 색·두께, 배경 색·불투명도·모서리 둥글기·여백, 그림자 색·흐림·거리·불투명도
- 배경 제거 모델 내려받기 흐름: 이유·데이터 사용 안내(모바일 데이터면 강조), 진행률, 앱이 끝나도 이어받기, 다 받으면 바로 배경 제거(`BackgroundRemover.modelState`/`downloadModel`, `OnDeviceModelState`)
- 분리 프로세스 도구(`IsolatedWorkerService`, `IsolatedServiceClient`, `ProcessCrashGuard`, `PixelFiles`)

### 고침

- 여러 장 편집의 쪽 목록 미리보기가 편집 내용을 반영하지 않던 문제

## 1.0.0-alpha01

### 추가

- 호스트 카탈로그 `EditorCatalog`: 필터 값·스티커 이미지·폰트 파일을 편집기·저장·headless에 추가, 내장 항목 숨기기
- `EditorUiConfig.palette`(`EditorPalette`, 명암비 경고)와 `uiFontId`, 프라이머리 색 `primaryArgb`
- 영상 headless `VideoProcessor`
- Showcase Playground, 호스트 카탈로그 예제
- Maven Central 배포(`com.naurylab`, sources jar)
- 진입점: `EditorInput.Multiple`(사진 여러 장·영상 여러 개), `Pick(kind, maxItems)`(여러 개 고르기), `Capture(kind)`(카메라 앱으로 찍거나 녹화한 뒤 편집)
- 여러 장 사진 편집: 쪽 목록에서 고르기·추가·순서 변경·삭제, 사진마다 저장 또는 PDF 한 개로 저장(`ImageEditorConfig.maxImageCount`)
- PDF 저장: `ImageFormat.PDF`와 `PdfOptions`(A4·A5·Letter·Legal·사진 크기, 방향, 여백, dpi, 한 문서/사진마다), headless `ImageProcessor.startPdfExport`
- 문서용 필터 `문서`·`문서 흑백`
- 문서 보정(스캔) 도구(`ImageTool.DOCUMENT`): 문서로 판별된 사진에서만 노출·감지 제안, 네 모서리 자동 감지, 모서리 끌기와 돋보기, 원근 보정, 스캔 보정(`ScanMode`: 컬러·흑백·선명한 흑백·원래 색, 그림자·조명 얼룩 제거), 인식한 글자 보기·복사, 원본으로 되돌리기(`DocumentDetector`, `DocumentQuad`, `DocumentRectifier`, `ScanEnhancer`)
- PDF 결과의 `EditedMedia.recognizedText`로 인식한 글자를 호스트에 전달
- PDF 글자 레이어(OCR): 선택 모듈 `framekit-ocr`(ML Kit 한국어·영어, 기기 안에서 처리), `TextRecognizer`, `PdfOptions.recognizeText`, 경고 `TEXT_RECOGNITION_SKIPPED`
- 보정·필터·수평 맞추기 조절을 눈금 다이얼(`DialSlider`)로, 보정 항목은 값 호가 있는 원형 버튼으로
- 결과 `FrameKitResult.Success.outputs`(여러 결과), `EditedMedia.pageCount`, `MediaType.DOCUMENT`, 오류 코드 `CAMERA_UNAVAILABLE`
- 편집 화면 사용성: 원본 비교 버튼, 그리기·가리기 중 두 손가락 확대·이동, 자르기 비율 직접 입력과 자르기 중 회전, 텍스트 불투명도·자간·행간, 속도 패널의 결과 길이, 영상 가리기 마스크 이동·크기 조절
- 뒤로 가기로 열린 도구의 변경을 잃지 않도록 적용·버리기를 물음. 저장을 누르면 열린 도구의 변경을 먼저 적용
- 가로 스크롤 줄(툴바·칩·색상·필터·보정·쪽 목록) 끝 흐림으로 화면 밖 항목 표시
- 사진·영상 캔버스 확대: 두 손가락(손가락 중심 기준), 확대 중 한 손가락 이동, 사진은 빈 곳 두 번 탭으로 확대·복귀
- 자르기·회전에서 손을 떼면 남은 영역이 화면을 채우도록 자동으로 확대해 맞춤(사진·영상)
- 대화상자·저장 진행·안내 메시지를 테마 색·모서리를 따르는 FrameKit 디자인으로 변경(`FrameKitDialog`, `FrameKitSnackbarHost`)
- 접근성: 슬라이더 이름 읽기, 선택 표시(체크)를 색 외에도 표시, 색 이름, 48dp 누름 영역, 스위치 줄 전체 토글
- Apache License 2.0 (`LICENSE`, `NOTICE`)
- 다국어: 한국어·영어·일본어·중국어(간체·번체)·베트남어·태국어·인도네시아어·러시아어·스페인어·포르투갈어(브라질)·프랑스어·독일어(13개 언어), Showcase Playground 언어 선택

### 변경

- 배포 모듈은 Kotlin 언어·API 2.2, `kotlin-stdlib` 2.2.0 의존으로 빌드(호스트 Kotlin 2.2 이상 지원)
- `StickerToolPanel`의 `onAdd`는 이모지 대신 에셋 id를 넘김. 편집기의 `addSticker`도 에셋 id를 받음
- 스티커 검증은 이모지와 등록한 이미지 스티커를 모두 허용(`StickerCatalog`)
- **이름 변경**: `EditorUiConfig.accentArgb` → `primaryArgb`, `EditorPalette.onAccentArgb` → `onPrimaryArgb`. 프라이머리 색 위 글자색은 지정하지 않으면 자동으로 흰색·검정 중 잘 보이는 쪽을 씀(`EditorPalette.readableOn`). `contrastWarnings`에 프라이머리 색·패널 색 비교 추가
- 기본값: `VideoEditorConfig.maxClipCount` 10, `maxTimelineDurationUs` 10분
- **API 변경**: `VideoPreviewEngine.attachSurface/detachSurface`가 `SurfaceHolder` 대신 `Surface`(와 크기)를 받음. 미리보기는 TextureView로 그림
- headless 처리기의 `catalog` 기본값이 `null`(등록된 카탈로그 유지)로 바뀜
- 좁은 화면에서 도구 패널 높이를 제한하고 패널만 스크롤, 텍스트 입력 중에는 영상 타임라인을 숨김
- 초기화 버튼은 되돌릴 기준이 있는 도구에만 표시, 영상의 일괄 삭제는 실행 취소 안내

### 수정·안정성

- 사각형·원 가리기를 그리기 시작할 때 앱이 종료되던 문제(크기 0인 사각형) 수정
- 배경 제거 모듈의 ML Kit 모델 미리 받기 meta-data를 SDK에서 빼고 호스트가 넣도록 변경(글자 인식 모듈과 merge 충돌 방지)

- 실행 중 GL 오류가 나면 CPU 색 보정으로 대체
- 원본이 편집 중 삭제되거나 권한이 끊기면 `SOURCE_UNAVAILABLE`, 저장 공간 부족으로 쓰기 실패하면 `INSUFFICIENT_STORAGE`
- 내보내기 중 메모리 부족을 `INSUFFICIENT_MEMORY` 실패로 반환
- 영상 출력 긴 변을 4096px 이하로 제한(길쭉한 자르기에서 인코더 실패 방지)
- 영상 메타데이터를 읽지 못하는 파일, 미리보기 구성 실패, 필터 썸네일 실패가 화면을 멈추지 않음
- 이어 붙인 영상·배경 음악도 persistable 권한을 얻어 프로세스 종료 후 복원
- 썸네일 추출기 자원 누수와 닫힌 뒤 사용 문제 수정
- 남은 촬영 파일·임시 내보내기 파일 정리

### Known Issues

- Maven Central 공개 배포는 아직 하지 않음(로컬 배포만 확인)
- Apple 실리콘 Mac의 arm64 에뮬레이터에서 ML Kit(배경 제거·글자 인식)이 SIGILL로 앱을 종료시킴. 실기기는 정상
- 카탈로그는 프로세스 전체에 하나만 등록됨

## 0.4.0

### 추가

- 여러 클립: 클립 추가(`VideoEditorConfig.maxClipCount`), 재생 위치에서 나누기, 순서 변경, 삭제
- 가운데 재생 헤드 타임라인: 끌어 탐색, 두 손가락 확대, 텍스트·스티커·마스크·음악 구간 표시
- 화면 비율(`VideoTool.CANVAS`)과 맞추기·채우기
- 배경 음악: 추가·볼륨·반복·시작 위치·곡 시작 위치, 영상보다 길어지지 않음
- 구간 텍스트·스티커(`VideoTool.TEXT`, `VideoTool.STICKER`)
- 여러 원본·음악·텍스트를 포함한 세션 복원

### 변경

- `MediaType.AUDIO` 추가(새 enum 값)
- `VideoTool`에 `CANVAS`, `TEXT`, `STICKER` 추가, 속도 도구는 적용·취소 방식
- `VideoExportCoordinator.export`에 `audioSources` 인자 추가(기본값 있음)
- 텍스트·스티커 제스처 계산을 `framekit-ui`의 `OverlayGestures`로 이동

## 0.3.0

### 추가

- 영상 편집 화면 `framekit-ui-video`(`VideoEditorContract`): 미리보기·재생·썸네일 타임라인, 구간 자르기, 자르기·회전, 보정·필터(사진과 같은 색), 속도 0.25~4배, 음소거·볼륨, 구간 모자이크·블러
- 영상 엔진 `framekit-video`(Media3 1.11.1): MP4(H.264/AAC) 저장, 진행률·취소, HDR→SDR, 프레임 상한, 인코더 대체 경고
- 통합 모듈 `framekit`(`FrameKitContract`): 원본 종류에 맞는 편집기 자동 선택, 사진 또는 영상 고르기(`MediaKind.ANY`)
- 영상 편집 세션 복원
- 넓은 화면에서 도구를 격자로 보여 주고 패널 위쪽부터 채우는 배치

### 변경

- 자르기·회전·보정·필터·텍스트·스티커·가리기 패널과 문자열을 `framekit-ui`로 이동(동작 변화 없음)
- `ExportWarning`에 `ENCODER_FALLBACK_APPLIED`, `FRAME_RATE_REDUCED`, `HDR_CONVERTED_TO_SDR` 추가(새 enum 값)
- `SourceFingerprint`에 `durationUs`, `SessionRecord`에 `videoSnapshot` 추가(기본값 있음)

### Known Issues

- 영상 편집 화면은 폴더블 에뮬레이터에서만 수동 확인했고 실기기 확인 전
- 영상 선명도 미적용, 영상 마스크는 사각형·원만 지원
- 다중 클립·배경 음악·영상 텍스트/스티커는 v0.4

## 0.2.0

### 추가

- 보정 12종과 필터·템플릿(화사하게 등) 15종, 강도 조절. OpenGL ES 3.0 렌더러와 같은 수식의 CPU 대체 경로
- 텍스트(폰트·색·정렬·외곽선·배경·그림자), 표준 이모지 스티커, 그리기(펜·마커·형광펜·지우개)
- 모자이크·블러 가리기(브러시·사각형·원)
- 배경 제거(누끼): 선택 모듈 `framekit-segmentation`(ML Kit, 기기 안 처리), 편집기·headless 모두 지원
- WEBP 손실/무손실 저장, 메타데이터 ALL 정책
- `ImageProcessor`·`ExportHandle`로 UI 없는 편집·저장
- 큰 사진 방어: 250MP 상한, 자른 영역·띠 단위 디코딩
- 폴드·태블릿 레이아웃(화면 크기·폴드 자세)

### 변경

- `EditorErrorCode.SOURCE_TOO_LARGE` 추가(새 enum 값: `when` 분기 확인 필요)
- `ImageTool`에 `ADJUST`, `FILTER`, `TEXT`, `STICKER`, `DRAW`, `PRIVACY`, `CUTOUT` 추가

### Known Issues

- 캔버스 확대·이동 제스처 없음(1.0.0-alpha01에서 추가)
- 그리기 필압은 굵기에 반영하지 않음

## 0.1.0

첫 버전: Photo Picker → 이미지 표시 → 자르기·회전 → undo → JPEG/PNG 저장 → 호스트 결과 수신.

### 추가

- core: 이미지 프로젝트 모델, 검증, snapshot undo/redo와 gesture transaction, crop 행렬·유효 영역 계산
- android: Uri/파일 source 등록과 권한 오류 구분, 결과·오류 DTO, AppFile 출력과 전용 FileProvider
- image: EXIF 1..8 디코딩, 공통 RenderPlan과 Canvas renderer, JPEG/PNG export, SAFE EXIF
- ui: 다크/라이트 테마, 공통 컴포넌트, ko/en 문구
- ui-image: `ImageEditorContract`, 자르기·회전 도구, 저장 진행·취소·재시도
- 세션 복원: 프로세스 종료 후 마지막으로 적용한 편집 복원, 원본 지문 확인, 중단된 저장 안내, 7일 지난 세션 정리

### Known Issues

- 프로세스 종료 후 undo 기록과 미적용 도구 변경은 복원하지 않음(설계상 동작)
- pan/zoom 제스처 없음
- 출력 대상은 AppFile만 지원
