# Release Notes

## 0.2.0 (개발 중)

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
