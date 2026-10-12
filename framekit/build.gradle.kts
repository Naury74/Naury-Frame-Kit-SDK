plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.naury.framekit"
    resourcePrefix = "framekit_"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            // Robolectric은 JDK 내부 API로 FileDescriptor를 다루는데, JDK 17+는 이를 기본으로 export하지 않는다.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
        }
    }
}

kotlin {
    explicitApi()
}

// 통합 artifact: 사진·영상 편집기를 모두 노출하고 media 종류에 따라 알맞은 편집기를 연다.
dependencies {
    api(project(":framekit-ui-image"))
    api(project(":framekit-ui-video"))
    api(project(":framekit-ui-motion"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    // 버전 없이 쓰는 Compose 의존성의 버전을 POM에서도 알 수 있게 BOM을 직접 선언한다(Maven Central 요구).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
