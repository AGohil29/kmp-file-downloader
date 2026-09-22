plugins {
    id("kmp-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(libs.findLibrary("ktor-client-core").get())
        }

        androidMain.dependencies {
            implementation(libs.findLibrary("ktor-client-okhttp").get())
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.findLibrary("ktor-client-cio").get())
            }
        }

        iosMain.dependencies {
            implementation(libs.findLibrary("ktor-client-darwin").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("ktor-client-mock").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}