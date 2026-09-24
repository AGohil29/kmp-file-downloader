plugins {
    id("kmp-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(project(":downloader-storage"))
            implementation(project(":downloader-network"))
            implementation(project(":downloader-filesystem"))
            implementation(libs.findLibrary("ktor-client-core").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
            implementation(libs.findLibrary("okio-fakefilesystem").get())
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
