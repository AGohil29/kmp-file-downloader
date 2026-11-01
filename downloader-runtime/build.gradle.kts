plugins {
    id("kmp-library")
    id("kmp-publishing")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "DownloaderRuntime"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":downloader-core"))
            api(project(":downloader-storage"))
            api(project(":downloader-network"))
            api(project(":downloader-filesystem"))
            api(libs.findLibrary("ktor-client-core").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
            implementation(libs.findLibrary("okio-fakefilesystem").get())
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.findLibrary("androidx-core-ktx").get())
                implementation(libs.findLibrary("androidx-work-runtime-ktx").get())
                implementation(libs.findLibrary("sqldelight-driver-android").get())
                implementation(libs.findLibrary("ktor-client-okhttp").get())
            }
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.findLibrary("sqldelight-driver-native").get())
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
                implementation(libs.findLibrary("ktor-client-cio").get())
                implementation(libs.findLibrary("ktor-client-okhttp").get())
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "com.arun.downloader.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
