plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // Android Target
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    // Desktop Target (JVM)
    jvm("desktop") {
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
            baseName = "SampleCommonUI"
            isStatic = true
            // Export core API models to Swift if needed
            export(project(":downloader-core"))
            export(project(":downloader-runtime"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":downloader-core"))
            api(project(":downloader-runtime"))
            implementation(project(":downloader-filesystem"))
            implementation(project(":downloader-storage"))
            implementation(project(":downloader-network"))

            // Okio & Coroutines
            api(libs.findLibrary("okio").get())
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())

            // Compose Multiplatform Base Kits
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }

        androidMain.dependencies {
            implementation(libs.findLibrary("androidx-core-ktx").get())
            // SQLDelight Android driver & Ktor OkHttp client
            implementation(libs.findLibrary("sqldelight-driver-android").get())
            implementation(libs.findLibrary("ktor-client-okhttp").get())
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // SQLDelight SQLite JDBC driver & Ktor CIO client
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
                implementation(libs.findLibrary("ktor-client-cio").get())
            }
        }

        iosMain.dependencies {
            // SQLDelight Native driver & Ktor Darwin client
            implementation(libs.findLibrary("sqldelight-driver-native").get())
            implementation(libs.findLibrary("ktor-client-darwin").get())
        }
    }
}

android {
    namespace = "com.arun.downloader.sample.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}