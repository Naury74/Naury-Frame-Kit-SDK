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

// Maven Central 업로드 묶음을 만들 임시 저장소. 서명·체크섬이 붙은 배포 파일이 모듈별로 모인다.
val centralStaging = layout.buildDirectory.dir("central-staging")

subprojects {
    val summary = sdkModules[name] ?: return@subprojects
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
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

    // Maven Central은 javadoc jar를 요구한다. API 문서는 소스 jar와 README·docs로 제공하므로 안내 파일만 담은 jar를 붙인다.
    val javadocJar = tasks.register<Jar>("centralJavadocJar") {
        archiveClassifier.set("javadoc")
        from(rootProject.file("README.md"))
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
                    artifact(javadocJar)
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
                            developerConnection.set("scm:git:ssh://git@github.com/Naury74/Naury-Frame-Kit-SDK.git")
                        }
                        issueManagement {
                            system.set("GitHub")
                            url.set("https://github.com/Naury74/Naury-Frame-Kit-SDK/issues")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "CentralStaging"
                    url = uri(centralStaging)
                }
            }
        }
        configureSigning()
    }
}

/**
 * 서명 키가 있으면 배포 파일에 서명한다. 키는 저장소에 두지 않고 `~/.gradle/gradle.properties`나 환경 변수에서 읽는다.
 *
 * - `signingInMemoryKey` / `signingInMemoryKeyPassword`: ASCII armor 비밀 키와 비밀번호(CI에 알맞음).
 * - `signing.gnupg.keyName` (+ `signing.gnupg.passphrase`): 설치된 gpg로 서명(개인 컴퓨터에 알맞음).
 *
 * 키가 없으면 서명하지 않으므로 `publishToMavenLocal`은 그대로 동작한다. Central 묶음을 만들 때는 서명이 필수다.
 */
fun Project.configureSigning() {
    val inMemoryKey = providers.gradleProperty("signingInMemoryKey").orNull
    val useGpg = providers.gradleProperty("signing.gnupg.keyName").isPresent
    if (inMemoryKey == null && !useGpg) return
    extensions.configure<SigningExtension> {
        if (inMemoryKey != null) {
            useInMemoryPgpKeys(inMemoryKey, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
        } else {
            useGpgCmd()
        }
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}

// ---- Maven Central(Central Portal) 배포 ----

val sdkProjects = subprojects.filter { it.name in sdkModules }

/** 모든 SDK 모듈을 서명해 임시 저장소에 모으고 Central Portal 업로드용 zip을 만든다. */
val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Maven Central 업로드 묶음(build/central-bundle.zip)을 만든다."
    dependsOn(sdkProjects.map { "${it.path}:publishReleasePublicationToCentralStagingRepository" })
    doFirst {
        val root = centralStaging.get().asFile
        val unsigned = root.walkTopDown().filter { it.isFile && it.extension in setOf("aar", "jar", "pom", "module") }
            .filter { !java.io.File(it.path + ".asc").exists() }.toList()
        check(unsigned.isEmpty()) { "서명되지 않은 파일이 있습니다. docs/publishing.md의 서명 키 설정을 확인하세요: ${unsigned.take(3)}" }
    }
    from(centralStaging) { exclude("**/maven-metadata*") }
    archiveFileName.set("central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
    notCompatibleWithConfigurationCache("임시 저장소 내용을 실행 시점에 검사한다")
}

tasks.register("cleanCentralStaging") {
    group = "publishing"
    description = "이전 버전이 섞이지 않도록 Central 임시 저장소를 비운다."
    val dir = centralStaging
    doLast { dir.get().asFile.deleteRecursively() }
}

/**
 * 묶음을 Central Portal에 올린다. 기본은 검증 후 대기(USER_MANAGED)라 포털에서 내용을 확인하고 직접 Publish를 누른다.
 * 자격 증명은 Central Portal에서 만든 사용자 토큰(`centralPortalUsername` / `centralPortalPassword`)이다.
 * `-PcentralAutoPublish=true`를 주면 검증이 끝나는 대로 바로 공개한다(되돌릴 수 없다).
 */
tasks.register("publishToCentral") {
    group = "publishing"
    description = "build/central-bundle.zip을 Maven Central(Central Portal)에 업로드한다."
    dependsOn(centralBundle)
    notCompatibleWithConfigurationCache("업로드는 네트워크 작업이다")
    doLast {
        val user = providers.gradleProperty("centralPortalUsername").orNull
        val password = providers.gradleProperty("centralPortalPassword").orNull
        check(user != null && password != null) { "centralPortalUsername / centralPortalPassword가 없습니다. docs/publishing.md를 참고하세요." }
        val bundle = layout.buildDirectory.file("central-bundle.zip").get().asFile
        val auto = providers.gradleProperty("centralAutoPublish").orNull == "true"
        val token = java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val boundary = "----FrameKit${System.currentTimeMillis()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + bundle.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
        val name = java.net.URLEncoder.encode("${project.property("FRAMEKIT_GROUP")}:framekit:${project.property("FRAMEKIT_VERSION")}", "UTF-8")
        val type = if (auto) "AUTOMATIC" else "USER_MANAGED"
        val request = java.net.http.HttpRequest.newBuilder(
            uri("https://central.sonatype.com/api/v1/publisher/upload?publishingType=$type&name=$name"),
        )
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "업로드 실패(${response.statusCode()}): ${response.body()}" }
        logger.lifecycle("업로드 완료. 배포 ID: ${response.body()}")
        logger.lifecycle(if (auto) "검증이 끝나면 자동으로 공개됩니다." else "https://central.sonatype.com/publishing/deployments 에서 확인 후 Publish를 누르세요.")
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
