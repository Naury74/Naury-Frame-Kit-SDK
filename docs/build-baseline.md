# Build Baseline

FrameKit을 빌드하고 검증하는 기준 toolchain이다. 아래 값을 바꾸는 작업은 기능 변경과 섞지 않고 별도 `build` commit으로 올린다.

확인일: 2026-10-06

## Toolchain

| 항목 | 값 | 비고 |
| --- | --- | --- |
| Gradle Wrapper | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle Daemon JVM | 25 | `gradle/gradle-daemon-jvm.properties`, Android Studio JBR 25.0.3로 확인 |
| Android Gradle Plugin | 9.4.1 | AGP built-in Kotlin 사용, `kotlin-android` plugin을 따로 적용하지 않음 |
| Kotlin | 2.4.20 | `kotlin-jvm`, Compose Compiler, Parcelize plugin을 같은 버전으로 맞춤 |
| Java target | 17 | 모든 모듈의 `sourceCompatibility`/`jvmTarget` |
| compileSdk | 37 | stable SDK Platform 37 |
| targetSdk | 37 | Showcase 앱에만 지정 |
| minSdk | 26 | 모든 Android 모듈 |

## 주요 라이브러리

| 라이브러리 | 버전 |
| --- | --- |
| Compose BOM | 2026.09.00 |
| androidx.activity | 1.13.0 |
| androidx.core | 1.19.1 |
| androidx.lifecycle | 2.11.0 |
| androidx.exifinterface | 1.4.2 |
| kotlinx.coroutines | 1.11.0 |

영상 단계(v0.3)에서 Media3 1.11.1을 추가한다. 모든 Media3 artifact는 같은 버전을 사용한다.

## 버전 정책

- 버전은 `gradle/libs.versions.toml`에서만 관리한다. 동적 버전(`+`, `latest.release`)은 쓰지 않는다.
- BOM은 Compose 라이브러리만 관리한다. AGP·Kotlin·Compose Compiler는 이 문서의 값으로 따로 고정한다.
- preview SDK와 alpha/beta 라이브러리는 기본 경로에 쓰지 않는다.

## 검증 명령

```bash
./gradlew test lint :app:assembleDebug :app:assembleRelease
```

CI(`.github/workflows/ci.yml`)도 같은 JDK와 명령을 사용한다.
