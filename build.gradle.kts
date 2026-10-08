plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// SDK 모듈은 같은 좌표·POM으로 배포한다. 저장소의 app(Showcase)은 배포하지 않는다.
val sdkModules = mapOf(
    "framekit-core" to "편집 모델, 좌표·시간 계산, 실행 취소, 검증 (Kotlin JVM)",
    "framekit-android" to "원본 등록, 결과·오류 DTO, 출력 파일, 세션, 카탈로그",
    "framekit-image" to "사진 디코딩·렌더링·저장과 headless ImageProcessor",
    "framekit-video" to "Media3 기반 영상 미리보기·저장과 headless VideoProcessor",
    "framekit-ui" to "편집 화면 공통 Compose 테마·컴포넌트·도구 패널",
    "framekit-ui-image" to "사진 편집 화면과 ImageEditorContract",
    "framekit-ui-video" to "영상 편집 화면과 VideoEditorContract",
    "framekit" to "사진·영상 편집기를 고르는 FrameKitContract (통합)",
    "framekit-segmentation" to "ML Kit 배경 제거 (선택)",
    "framekit-ocr" to "ML Kit 글자 인식, PDF 글자 레이어 (선택)",
)

// 호스트 앱이 더 낮은 Kotlin(2.2 이상)으로 빌드해도 SDK를 읽을 수 있게 언어·API 버전과 표준 라이브러리 버전을
// 낮춰 둔다. 2.3 이후 API를 쓰면 SDK 빌드에서 바로 오류가 난다.
val minimumConsumerKotlin = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2

subprojects {
    val summary = sdkModules[name] ?: return@subprojects
    apply(plugin = "maven-publish")
    pluginManager.withPlugin("com.android.library") { configureConsumerKotlin() }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configureConsumerKotlin() }
    group = providers.gradleProperty("FRAMEKIT_GROUP").get()
    version = providers.gradleProperty("FRAMEKIT_VERSION").get()

    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
    }

    // AGP는 평가가 끝난 뒤에 컴포넌트를 만들므로, 컴포넌트가 생길 때 publication을 만든다.
    components.configureEach {
        val component = this
        val wanted = if (plugins.hasPlugin("com.android.library")) "release" else "java"
        if (component.name != wanted) return@configureEach
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("release") {
                    from(component)
                    artifactId = project.name
                    pom {
                        name.set(project.name)
                        description.set(summary)
                        url.set("https://github.com/Naury74/Naury-Frame-Kit-SDK")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("Naury74")
                                name.set("Naury74")
                            }
                        }
                        scm {
                            url.set("https://github.com/Naury74/Naury-Frame-Kit-SDK")
                            connection.set("scm:git:https://github.com/Naury74/Naury-Frame-Kit-SDK.git")
                        }
                    }
                }
            }
        }
    }
}

fun Project.configureConsumerKotlin() {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension>("kotlin") {
        coreLibrariesVersion = "2.2.0"
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            languageVersion.set(minimumConsumerKotlin)
            apiVersion.set(minimumConsumerKotlin)
        }
    }
}
