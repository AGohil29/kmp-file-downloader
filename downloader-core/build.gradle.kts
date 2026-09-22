plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-filesystem"))
        }
    }
}