# Release Notes

## 1.0.0-alpha01

### 추가

- 호스트 카탈로그 `EditorCatalog`: 필터 값·스티커 이미지·폰트 파일을 편집기·저장·headless에 추가, 내장 항목 숨기기
- `EditorUiConfig.palette`(`EditorPalette`, 명암비 경고)와 `uiFontId`, 프라이머리 색 `primaryArgb`
- 영상 headless `VideoProcessor`
- Showcase Playground, 호스트 카탈로그 예제
- Maven 배포 설정(`io.github.naury74`, sources jar), 별도 소비자 앱에서 R8 release 확인
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

## 0.4.0 (개발 중)

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

## 0.3.0 (개발 중)

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
- 폴드·태블릿 레이아웃(화면 크기·폴드 자세), CI 수정

### 변경

- `EditorErrorCode.SOURCE_TOO_LARGE` 추가(새 enum 값: `when` 분기 확인 필요)
- `ImageTool`에 `ADJUST`, `FILTER`, `TEXT`, `STICKER`, `DRAW`, `PRIVACY`, `CUTOUT` 추가

### Known Issues

- 캔버스 확대·이동 제스처 없음(1.0.0-alpha01에서 추가)
- 그리기 필압은 굵기에 반영하지 않음
- 텍스트·스티커·그리기·가리기의 실기기 수동 확인 전

## 0.1.0

첫 vertical slice: Photo Picker → 이미지 표시 → 자르기·회전 → undo → JPEG/PNG 저장 → 호스트 결과 수신.

### 추가

- core: 이미지 프로젝트 모델, 검증, snapshot undo/redo와 gesture transaction, crop 행렬·유효 영역 계산
- android: Uri/파일 source 등록과 권한 오류 구분, 결과·오류 DTO, AppFile 출력과 전용 FileProvider
- image: EXIF 1..8 디코딩, 공통 RenderPlan과 Canvas renderer, JPEG/PNG export, SAFE EXIF
- ui: 다크/라이트 테마, 공통 컴포넌트, ko/en 문구
- ui-image: `ImageEditorContract`, 자르기·회전 도구, 저장 진행·취소·재시도
- app: Showcase 홈, 예제, 결과 화면
- 세션 복원: 프로세스 종료 후 마지막으로 적용한 편집 복원, 원본 지문 확인, 중단된 저장 안내, 7일 지난 세션 정리

### Known Issues

- 프로세스 종료 후 undo 기록과 미적용 도구 변경은 복원하지 않음(설계상 동작)
- 기기에서 실제 프로세스 종료 후 복원은 아직 수동 확인 전(자동 테스트로 확인)
- pan/zoom 제스처 없음
- 출력 대상은 AppFile만 지원
- 실기기·release 빌드 기기 실행 미검증
