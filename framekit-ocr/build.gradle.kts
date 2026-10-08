plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.naury.framekit.ocr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":framekit-image"))
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.kotlinx.coroutines.android)
}
