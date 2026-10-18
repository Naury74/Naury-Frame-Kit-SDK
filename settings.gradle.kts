pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // -PnaurylabDev=true일 때만 배포 전 개발 판(Kinetica 등)을 로컬 저장소에서 받는다. com.naurylab 묶음만 찾는다.
        if (providers.gradleProperty("naurylabDev").orNull == "true") {
            maven {
                url = uri("${System.getProperty("user.home")}/.naurylab/dev-repo")
                content { includeGroup("com.naurylab") }
            }
        }
    }
}

rootProject.name = "FrameKit"

include(":app")
include(":framekit-core", ":framekit-android")
include(":framekit-image", ":framekit-ui", ":framekit-ui-image")
include(":framekit-segmentation")
include(":framekit-ocr")
include(":framekit-video", ":framekit-ui-video")
include(":framekit-motion", ":framekit-ui-motion")
include(":framekit-ui-audio")
include(":framekit-picker")
include(":framekit-scanner")
include(":framekit")
