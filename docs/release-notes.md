# Release Notes

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

- 캔버스 확대·이동 제스처 없음
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
