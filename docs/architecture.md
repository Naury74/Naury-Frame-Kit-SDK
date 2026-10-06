# Architecture

## 모듈

```
                      app (Showcase)
                            │
                        framekit  (FrameKitContract)
                  ┌─────────┴─────────┐
          framekit-ui-image     framekit-ui-video
             │       └─────┬─────┘        │
             │         framekit-ui        │
             │             │        framekit-video (Media3)
             └──── framekit-image ◄───────┘
                           │
                    framekit-android
                           │
                     framekit-core  (Kotlin JVM)
```

| 모듈 | 종류 | 책임 |
| --- | --- | --- |
| `framekit-core` | Kotlin JVM | `ImageProject`, `GeometryEdit`, 좌표·행렬(`Affine2D`, `GeometryFrame`), crop 계산, `EditHistory`, 검증 |
| `framekit-android` | Android library | `EditorInput`, `FrameKitResult`, `EditorError`, `SessionSourceRegistry`, `AppFileOutputStore`, FileProvider |
| `framekit-image` | Android library | EXIF·디코딩, `ImageRenderPlan`, `CanvasGeometryRenderer`, `ImageExportCoordinator` |
| `framekit-ui` | Compose library | `FrameKitTheme`, 공통 컴포넌트, 오류 문구 |
| `framekit-ui-image` | Compose library | `ImageEditorContract`, 편집 Activity·ViewModel·도구 |
| `framekit-video` | Android library | Media3 어댑터: `VideoRenderPlan`, Composition 생성, 미리보기(`VideoPreviewEngine`), `VideoExportCoordinator`, 색·모자이크 GL 효과, 썸네일 |
| `framekit-ui-video` | Compose library | `VideoEditorContract`, 영상 편집 Activity·ViewModel·타임라인 |
| `framekit` | Android library | `FrameKitContract`, 원본 종류에 따라 편집기를 고르는 라우터 Activity |

의존 규칙:

- core는 Android, Compose, Media3 타입을 참조하지 않습니다. `Uri`, `Bitmap` 대신 `SourceId`를 씁니다.
- 엔진(image)은 UI를 참조하지 않습니다.
- 영상은 별도 `framekit-video`/`framekit-ui-video`로 두어 이미지 전용 앱이 Media3를 받지 않습니다. Media3 타입과 `@UnstableApi`는 `framekit-video` 안의 어댑터에만 있고 UI는 `VideoPreviewEngine` 같은 FrameKit 인터페이스만 씁니다.
- 자르기·회전·보정·필터·가리기 패널과 자르기 프레임은 `framekit-ui`에 있어 사진·영상 편집기가 함께 씁니다.
- SDK 모듈은 explicit API mode로 공개 범위를 명시하고, 리소스 이름에 `framekit_` prefix를 붙입니다.
- DI 프레임워크 없이 생성자 주입을 사용합니다.

## 편집 모델

`ImageProject`는 실행한 명령 목록이 아니라 **최종 편집 상태**입니다.

```kotlin
data class GeometryEdit(
    val quarterTurns: Int = 0,           // 0..3, 시계 방향 90°
    val straightenDegrees: Double = 0.0, // -45..45, 시계 방향
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: RectN = RectN.Full,        // G 공간 정규화 좌표
)
```

undo는 이 snapshot을 쌓습니다(`EditHistory`). 도구 사용은 `HistoryTransaction`의 `begin → update* → commit/cancel`이라 slider drag나 crop 조정이 아무리 많아도 한 단계입니다. 같은 내용 commit은 무시하고, `isDirty`는 undo 스택 길이가 아니라 처음 상태와의 비교로 판단합니다.

## 좌표계

| 공간 | 정의 |
| --- | --- |
| S | EXIF 방향을 적용한 원본(upright) 픽셀 |
| G | 90° 회전·수평 맞추기·반전 후 이미지를 감싸는 축 정렬 사각형. crop은 여기서 0..1로 저장 |
| C | 자른 뒤 출력 캔버스 0..1 (v0.2 overlay 기준) |
| V | 화면 viewport 픽셀. 모델에 저장하지 않음 |

column vector 기준 행렬:

```
M_sourceToOutput = scaleOutput × cropTranslate × flip × straighten × quarterTurn
```

디코더가 upright 픽셀을 주므로 EXIF 행렬은 디코딩 단계에서 한 번만 적용합니다. API 28+ `ImageDecoder`는 직접 적용하고, API 26/27 `BitmapFactory` 경로는 core의 `ExifOrientation` 행렬을 적용합니다.

수평 맞추기로 생기는 빈 모서리는 `CropBoundsCalculator`가 막습니다. crop은 항상 회전된 이미지 사각형 안에 있어야 하며, 비율을 유지한 최대 crop과 clamp는 닫힌 식과 이분 탐색으로 계산합니다. 반전 후 회전은 모델에서 반대 방향 회전이 되므로 `GeometryOperations`가 화면 기준 동작으로 변환합니다.

## 효과와 오버레이

| 단계 | 구현 | 기준 공간 |
| --- | --- | --- |
| 배경 제거 | `SubjectCutout` 마스크(자산)를 원본에 DST_IN 합성, 구현은 선택 모듈의 `BackgroundRemover` | 원본(S) |
| geometry | `CanvasGeometryRenderer` | 원본(S) → 출력 |
| 보정·필터 | `ColorEffectSpec` → `GlColorEffectRenderer`(ES 3.0) / `CpuColorEffectRenderer` | 출력 캔버스 |
| 가리기 | `PrivacyRenderer` | 출력 캔버스(C), 보정 결과에만 적용 |
| 그리기·텍스트·스티커 | `OverlayRenderer` | 출력 캔버스(C), 목록 순서가 z-order |

- `ColorEffectSpec`이 보정 순서·색공간·상수를 고정합니다. GL 셰이더와 CPU 기준 구현은 같은 상수와 정수 해시를 쓰고, 실기기 계측 테스트로 두 결과를 비교합니다.
- GL 컨텍스트는 전용 스레드 하나가 소유하고, 미리보기·썸네일·저장이 같은 렌더러를 공유합니다.
- 오버레이 크기는 모두 캔버스 비율이라 해상도와 무관하고, 같은 레이아웃 계산을 미리보기·저장·터치 판정이 함께 씁니다.

## 렌더링과 저장

```
ImageProject ─┐
SourceMetadata┴─► ImageRenderPlanFactory ─► ImageRenderPlan ─► CanvasGeometryRenderer
                                                                  ├─ 화면: viewport 행렬 추가
                                                                  └─ 저장: 출력 Bitmap
```

미리보기와 저장이 같은 plan과 같은 `draw` 호출을 쓰므로 둘 사이에 별도 보정 값이 없습니다. 선택 테두리·격자·핸들은 UI가 그 위에 그리며 plan에 포함되지 않습니다.

`ImageExportCoordinator` 순서:

1. config·project 검증, 출력 크기 계산
2. 메모리·저장 공간 사전 확인
3. 출력에 필요한 만큼만 sample 디코딩 → render
4. 숨김 `.partial`에 encode → header로 크기·형식 검증 → SAFE EXIF
5. 같은 폴더에서 rename(publish) → FileProvider Uri

실패하거나 취소되면 `.partial`을 지웁니다. publish가 끝난 뒤 도착한 취소는 결과를 되돌리지 않습니다.

## 영상

`VideoProject`는 클립 목록(원본 구간·속도·효과·소리)과 출력 시간 기준의 구간 마스크를 가진 최종 상태입니다. 시간은 `Long` µs이고 구간은 `[start, end)`입니다.

```
VideoProject ─► VideoPlanFactory ─► VideoRenderPlan ─► Media3CompositionFactory ─► Composition
                                                          ├─ 미리보기: CompositionPlayer (짧은 변 720px)
                                                          └─ 저장: Transformer → .partial → 검증 → publish
```

- 클립마다 clipping(구간) → 회전·반전(ScaleAndRotate) → Crop → Presentation(캔버스 크기) → 색 GL 효과 순서이고, 구간 마스크는 Composition 전체 효과로 출력 캔버스에 적용합니다.
- 색 GL 효과는 사진 렌더러와 같은 GLSL(`ColorEffectShaders.VIDEO`)을 써서 같은 값이면 같은 색이 됩니다.
- 편집 화면은 슬라이더를 움직이는 동안 미리보기를 매번 다시 준비하지 않고 120ms 모았다가 반영합니다. 자르기·회전 중에는 자르기 전 전체 프레임을 보여 주고 그 위에 자르기 프레임을 그립니다.
- 저장은 Transformer를 main looper에서 실행하고, 취소하면 Transformer를 멈춘 뒤 `.partial`을 지웁니다. 결과 파일의 영상 트랙 길이와 회전을 반영한 크기를 확인한 뒤 publish합니다.

## 세션 복원

`EditorSessionStore`(framekit-android)가 세션 파일을 관리하고, 편집 화면의 `ImageSessionRecorder`·`VideoSessionRecorder`가 화면과 세션을 연결합니다. 영상 snapshot은 클립의 원본을 세션 원본 목록의 위치로 저장하고, 지문에 영상 길이를 포함합니다.

```
도구 적용·undo·redo ─► history.current 변경 ─► 300ms 묶음 ─► project.snapshot (임시 파일 → rename)
프로세스 재시작 ─► SavedStateHandle의 세션 id ─► descriptor로 원본 재등록 ─► 지문 비교 ─► EditHistory.restore(baseline, snapshot)
```

- snapshot에는 `SourceId`를 저장하지 않습니다. `SourceId`는 등록할 때마다 새로 만들어지므로 복원 시 새 id를 붙입니다.
- `EditHistory.restore`는 undo 기록 없이 현재 상태만 되살리지만 baseline은 원래 사진으로 두어 `isDirty`가 유지됩니다.
- 저장(export)을 시작할 때 snapshot에 진행 중 표시를 남겨, 저장 중 종료를 다음 실행에서 알립니다.

## 편집 화면

`ImageEditorActivity`는 request를 검증하고 `ImageEditorViewModel`을 만듭니다. ViewModel은 `Loading → Ready → (Exporting)` 상태와 결과를 가지며, 결과가 정해지면 Activity가 한 번만 `setResult` 후 종료합니다. Picker로 고른 Uri와 세션 id는 `SavedStateHandle`에 저장해 재생성 시 Picker를 다시 띄우지 않습니다.
