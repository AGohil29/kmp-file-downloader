plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-filesystem"))
            implementation(project(":downloader-storage"))
            implementation(project(":downloader-network"))
        }
    }
}