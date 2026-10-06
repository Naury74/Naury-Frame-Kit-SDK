plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.naury.framekit.image"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

dependencies {
    api(project(":framekit-android"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
