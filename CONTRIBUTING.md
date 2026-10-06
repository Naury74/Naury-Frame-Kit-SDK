# Contributing

## 빌드 기준

toolchain과 라이브러리 버전은 [docs/build-baseline.md](docs/build-baseline.md)에 고정되어 있습니다. JDK는 Android Studio에 포함된 JBR(25)을 사용합니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test lint :app:assembleDebug :app:assembleRelease
```

PR 전에 위 명령이 통과해야 하고, lint error는 0이어야 합니다. 같은 명령이 CI(`.github/workflows/ci.yml`)에서 실행됩니다.

## 작업 흐름

1. `main`에서 branch 생성: `feature/<slug>`, `fix/<slug>`, `chore/<slug>`
2. 작업 하나에 의도 하나. 여러 기능을 한 커밋에 묶지 않습니다.
3. 동작을 바꾸면 테스트와 문서(`docs/`, KDoc)를 같이 고칩니다.

## Commit

[Conventional Commits](https://www.conventionalcommits.org/)를 사용하고 제목은 한국어로 씁니다. scope는 모듈 이름입니다.

```text
feat(core): crop 행렬과 유효 영역 계산
fix(ui-image): 저장 완료 후 두 번째 저장 무시
build: toolchain과 version catalog 고정
docs: integration 문서에 공유 방법 추가
```

| scope | 모듈 |
| --- | --- |
| `core` | framekit-core |
| `android` | framekit-android |
| `image` | framekit-image |
| `ui` | framekit-ui |
| `ui-image` | framekit-ui-image |
| `app` | Showcase |

## 코드 규칙

- core에는 Android·Compose·Media3 타입을 넣지 않습니다.
- 모델에 `Uri`, `Bitmap`, Compose `Offset`, 화면 density를 저장하지 않습니다.
- 미리보기와 저장은 같은 `ImageRenderPlan`을 사용합니다. 한쪽에만 적용되는 보정을 두지 않습니다.
- undo 기록에 Bitmap을 넣지 않습니다.
- 효과가 없는 버튼, 성공을 가장하는 stub, 티켓 없는 TODO를 남기지 않습니다.
- 주석과 KDoc은 한국어로 씁니다. public API에는 단위·범위·실패 조건을 적고, 일반 주석은 코드가 설명하지 못하는 이유만 씁니다.
- `Utils`, `Manager`, `Helper`처럼 책임이 드러나지 않는 이름을 쓰지 않습니다.
- 필요 없는 network·database·DI·analytics 의존성을 추가하지 않습니다. 버전은 `gradle/libs.versions.toml`에서만 관리합니다.
- 로그에 Uri, 파일명, 사용자 콘텐츠를 남기지 않습니다.

## 테스트

| 대상 | 방법 |
| --- | --- |
| 모델·좌표·시간 계산 | JVM 단위 테스트 (`framekit-core`) |
| 디코딩·렌더·export | Robolectric native graphics |
| 편집 흐름 | ViewModel 테스트 |
| 실제 Picker·화면 | 에뮬레이터/실기기 수동 확인 |

기기에서 확인하지 않은 항목은 "검증 완료"로 적지 않습니다.

## 저장소에 넣지 않는 것

빌드 산출물, APK/AAB, keystore, 서명 정보, 로컬 설정(`local.properties`), 테스트·lint 리포트, IDE 설정.
