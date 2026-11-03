import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// jvm: Android 모듈용, iOS: FrameKitCore XCFramework.
kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcframework = XCFramework("FrameKitCore")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "FrameKitCore"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
            // 쓴 PDF를 실제 뷰어 구현으로 읽어 글자 레이어를 확인한다. 테스트에서만 쓴다.
            implementation(libs.pdfbox)
        }
    }
}

// `./gradlew test`에 core JVM 테스트를 포함한다.
tasks.register("test") {
    group = "verification"
    description = "JVM 대상 테스트를 실행한다."
    dependsOn("jvmTest")
}
