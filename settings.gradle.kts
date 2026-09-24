pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-file-downloader"

// Core & Modular Libraries
include(":downloader-core")
include(":downloader-filesystem")
include(":downloader-storage")
include(":downloader-network")
include(":downloader-runtime")