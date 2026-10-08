import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    // 쓴 PDF를 실제 뷰어 구현으로 읽어 글자 레이어를 확인한다. 테스트에서만 쓴다.
    testImplementation(libs.pdfbox)
}
