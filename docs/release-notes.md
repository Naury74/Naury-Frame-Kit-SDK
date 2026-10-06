# Release Notes

## 0.1.0 (개발 중)

첫 vertical slice: Photo Picker → 이미지 표시 → 자르기·회전 → undo → JPEG/PNG 저장 → 호스트 결과 수신.

### 추가

- core: 이미지 프로젝트 모델, 검증, snapshot undo/redo와 gesture transaction, crop 행렬·유효 영역 계산
- android: Uri/파일 source 등록과 권한 오류 구분, 결과·오류 DTO, AppFile 출력과 전용 FileProvider
- image: EXIF 1..8 디코딩, 공통 RenderPlan과 Canvas renderer, JPEG/PNG export, SAFE EXIF
- ui: 다크/라이트 테마, 공통 컴포넌트, ko/en 문구
- ui-image: `ImageEditorContract`, 자르기·회전 도구, 저장 진행·취소·재시도
- app: Showcase 홈, 예제, 결과 화면

### Known Issues

- 프로세스 종료 후 편집 내용 복원 없음(원본만 다시 엶)
- pan/zoom 제스처 없음
- 출력 대상은 AppFile만 지원
- 실기기·release 빌드 기기 실행 미검증
