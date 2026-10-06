# Architecture

## 모듈

```
                 app (Showcase)
                       │
               framekit-ui-image
                ┌──────┴──────┐
          framekit-ui    framekit-image
                └──────┬──────┘
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

의존 규칙:

- core는 Android, Compose, Media3 타입을 참조하지 않습니다. `Uri`, `Bitmap` 대신 `SourceId`를 씁니다.
- 엔진(image)은 UI를 참조하지 않습니다.
- 영상 모듈(v0.3)은 별도 `framekit-video`/`framekit-ui-video`로 추가해 이미지 전용 앱이 Media3를 받지 않게 합니다.
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

## 편집 화면

`ImageEditorActivity`는 request를 검증하고 `ImageEditorViewModel`을 만듭니다. ViewModel은 `Loading → Ready → (Exporting)` 상태와 결과를 가지며, 결과가 정해지면 Activity가 한 번만 `setResult` 후 종료합니다. Picker로 고른 Uri는 `SavedStateHandle`에 저장해 재생성 시 Picker를 다시 띄우지 않습니다.
